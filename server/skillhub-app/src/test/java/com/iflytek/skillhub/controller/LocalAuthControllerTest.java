package com.iflytek.skillhub.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iflytek.skillhub.auth.exception.AuthFlowException;
import com.iflytek.skillhub.auth.local.LocalAuthService;
import com.iflytek.skillhub.auth.local.LocalCredentialRepository;
import com.iflytek.skillhub.auth.local.PasswordResetService;
import com.iflytek.skillhub.auth.settings.LocalAuthSettings;
import com.iflytek.skillhub.auth.settings.LocalAuthSettingsService;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.security.AuthFailureThrottleService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocalAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LocalAuthService localAuthService;

    @MockBean
    private NamespaceMemberRepository namespaceMemberRepository;

    @MockBean
    private SkillHubMetrics skillHubMetrics;

    @MockBean
    private AuthFailureThrottleService authFailureThrottleService;

    @MockBean
    private PasswordResetService passwordResetService;

    @MockBean
    private LocalCredentialRepository localCredentialRepository;

    @MockBean
    private LocalAuthSettingsService localAuthSettingsService;

    @org.junit.jupiter.api.BeforeEach
    void localAuthEnabled() {
        given(localAuthSettingsService.current()).willReturn(new LocalAuthSettings(1L, true, 0L, null));
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void publicCapabilitiesReflectPasswordLoginWithoutExposingRetiredOrInternalFields(
            boolean passwordLogin) throws Exception {
        given(localAuthSettingsService.current()).willReturn(
                new LocalAuthSettings(1L, passwordLogin, 7L, null));

        mockMvc.perform(get("/api/v1/auth/local/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordLoginEnabled").value(passwordLogin))
                .andExpect(jsonPath("$.data.selfRegistrationEnabled").doesNotExist())
                .andExpect(jsonPath("$.data.registrationAvailable").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist());
    }


    @Test
    void openApiDoesNotExposeSelfRegistration() throws Exception {
        byte[] bytes = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var document = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
        org.assertj.core.api.Assertions.assertThat(document.path("paths").size()).isGreaterThan(200);
        org.assertj.core.api.Assertions.assertThat(document.path("paths")
                .has("/api/v1/auth/local/register")).isFalse();
        var schemas = document.path("components").path("schemas");
        org.assertj.core.api.Assertions.assertThat(schemas.has("LocalRegisterRequest")).isFalse();
        for (String name : List.of("LocalAuthCapabilitiesResponse", "SystemAuthSettingsResponse",
                "SystemAuthSettingsUpdateRequest")) {
            org.assertj.core.api.Assertions.assertThat(schemas.path(name).path("properties")
                    .has("selfRegistrationEnabled")).as(name).isFalse();
        }
        String output = System.getProperty("skillhub.test.openapi-output");
        if (output != null && !output.isBlank()) {
            java.nio.file.Files.write(java.nio.file.Path.of(output), bytes);
        }
    }

    @Test
    void removedRegistrationEndpointCannotCreateAnAccountOrSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/local/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"bob","password":"Abcd123!","email":"bob@example.com"}
                    """))
                .andExpect(status().isNotFound())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getRequest().getSession(false)).isNull());
        org.mockito.Mockito.verifyNoInteractions(localAuthService, localCredentialRepository);
        verify(skillHubMetrics, never()).incrementUserRegister();
    }

    @Test
    void login_returnsCurrentUserEnvelope() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal(
            "usr_1",
            "alice",
            "alice@example.com",
            "",
            "local",
            Set.of("SUPER_ADMIN")
        );
        given(localAuthService.login("alice", "Abcd123!")).willReturn(principal);
        given(localCredentialRepository.existsByUserId("usr_1")).willReturn(true);

        mockMvc.perform(post("/api/v1/auth/local/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"alice","password":"Abcd123!"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.userId").value("usr_1"))
            .andExpect(jsonPath("$.data.oauthProvider").value("local"))
            .andExpect(jsonPath("$.data.canChangePassword").value(true));
        verify(skillHubMetrics).recordLocalLogin(true);
        verify(skillHubMetrics, never()).recordLocalLogin(false);
        verify(authFailureThrottleService).resetIdentifier("local", "alice");
    }

    @Test
    void login_failure_recordsFailureMetric() throws Exception {
        given(localAuthService.login("alice", "wrong"))
            .willThrow(new AuthFlowException(HttpStatus.UNAUTHORIZED, "error.auth.local.invalidCredentials"));

        mockMvc.perform(post("/api/v1/auth/local/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"username":"alice","password":"wrong"}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
        verify(authFailureThrottleService).recordFailure("local", "alice", "127.0.0.1");
        verify(skillHubMetrics).recordLocalLogin(false);
        verify(skillHubMetrics, never()).recordLocalLogin(true);
    }

    @Test
    void changePassword_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/auth/local/change-password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"old","newPassword":"Newpass123!"}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void changePassword_withAuthentication_returnsUpdated() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal(
            "usr_3",
            "carol",
            "carol@example.com",
            "",
            "local",
            Set.of("SUPER_ADMIN")
        );
        var auth = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );

        mockMvc.perform(post("/api/v1/auth/local/change-password")
                .with(authentication(auth))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"old","newPassword":"Newpass123!"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void changePassword_withAuthentication_withInvalidCsrf_returnsForbidden() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal(
            "usr_3",
            "carol",
            "carol@example.com",
            "",
            "local",
            Set.of("SUPER_ADMIN")
        );
        var auth = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );

        mockMvc.perform(post("/api/v1/auth/local/change-password")
                .with(authentication(auth))
                .with(csrf().useInvalidToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"old","newPassword":"Newpass123!"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void changePassword_withSessionCookieAndBearerHeaderWithoutCsrf_isRejected() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal(
            "usr_3",
            "carol",
            "carol@example.com",
            "",
            "local",
            Set.of("SUPER_ADMIN")
        );
        var auth = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );

        mockMvc.perform(post("/api/v1/auth/local/change-password")
                .with(authentication(auth))
                .header("Authorization", "Bearer invalid-token")
                .cookie(new Cookie("SESSION", "browser-session"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"old","newPassword":"Newpass123!"}
                    """))
            .andExpect(status().isUnauthorized());

        verify(localAuthService, never()).changePassword("usr_3", "old", "Newpass123!");
    }

    @Test
    void requestPasswordReset_returnsGenericSuccessEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/auth/local/password-reset/request")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"alice@example.com"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(passwordResetService).requestPasswordReset("alice@example.com");
    }

    @Test
    void requestPasswordReset_rejectsInvalidEmailFormat() throws Exception {
        mockMvc.perform(post("/api/v1/auth/local/password-reset/request")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"alice"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(passwordResetService, never()).requestPasswordReset("alice");
    }

    @Test
    void confirmPasswordReset_returnsUpdatedEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/auth/local/password-reset/confirm")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"alice@example.com","code":"123456","newPassword":"Abcd123!"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        verify(passwordResetService).confirmPasswordReset("alice@example.com", "123456", "Abcd123!");
    }

    @Test
    void confirmPasswordReset_rejectsInvalidEmailFormat() throws Exception {
        mockMvc.perform(post("/api/v1/auth/local/password-reset/confirm")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"alice","code":"123456","newPassword":"Abcd123!"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(passwordResetService, never()).confirmPasswordReset("alice", "123456", "Abcd123!");
    }

}
