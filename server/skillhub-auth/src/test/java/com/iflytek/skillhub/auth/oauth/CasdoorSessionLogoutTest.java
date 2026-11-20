package com.iflytek.skillhub.auth.oauth;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CasdoorSessionLogoutTest {
    private static final String ISSUER = "https://casdoor.example.com";
    private static final String CLIENT = "skillhub";
    private static final Instant NOW = Instant.now();

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ClientRegistration registration = ClientRegistration.withRegistrationId("casdoor")
            .clientId(CLIENT)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri(ISSUER + "/login/oauth/authorize")
            .tokenUri(ISSUER + "/api/login/oauth/access_token")
            .issuerUri(ISSUER)
            .jwkSetUri(ISSUER + "/.well-known/jwks")
            .scope("openid")
            .providerConfigurationMetadata(Map.of("end_session_endpoint", ISSUER + "/api/logout"))
            .build();
    private final InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);
    private final CasdoorSessionLogout logout = new CasdoorSessionLogout(registrations, redis, new CasdoorRegistration("casdoor"), "test:session", "8h",
            "https://skills.example.com/skillhub/?signedOut=1");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void silentAuthorizationAddsPromptAndPkceAndBindsState() {
        var resolver = new SkillHubOAuth2AuthorizationRequestResolver(registrations, mock(OAuthLoginFlowService.class));
        var request = new MockHttpServletRequest("GET", "/oauth2/authorization/casdoor");
        request.setServletPath("/oauth2/authorization/casdoor");
        request.addParameter("silent", "true");

        var authorization = resolver.resolve(request);
        assertThat(authorization.getAuthorizationRequestUri()).contains("prompt=none", "code_challenge_method=S256", "nonce=");
        assertThat(request.getSession().getAttribute(SkillHubOAuth2AuthorizationRequestResolver.SILENT_STATE))
                .isEqualTo(authorization.getState());

        request.removeParameter("silent");
        assertThat(resolver.resolve(request).getAuthorizationRequestUri()).doesNotContain("prompt=");
    }

    @Test
    void acceptsCasdoorSubjectOnlyTokenAndRejectsNonceOrStaleIssueTime() {
        assertThat(CasdoorSessionLogout.validLogoutClaims(logoutClaims(NOW).build(), NOW)).isTrue();
        assertThat(CasdoorSessionLogout.validLogoutClaims(logoutClaims(NOW).claim("nonce", "nonce").build(), NOW)).isFalse();
        assertThat(CasdoorSessionLogout.validLogoutClaims(logoutClaims(NOW.minusSeconds(121)).build(), NOW)).isFalse();
    }

    @Test
    void browserLogoutBuildsCasdoorRedirectBeforeSessionInvalidation() {
        var request = new MockHttpServletRequest();
        var authentication = authenticate(request, "casdoor", NOW);
        request.addParameter("federated", "true");

        logout.prepareBrowserLogout(request, authentication);

        assertThat(request.getAttribute(CasdoorSessionLogout.BROWSER_REDIRECT)).asString()
                .startsWith(ISSUER + "/api/logout?id_token_hint=id-token")
                .contains("post_logout_redirect_uri=https%3A%2F%2Fskills.example.com%2Fskillhub%2F%3FsignedOut%3D1");
    }

    @Test
    void rejectsInvalidLogoutTokenBeforeWritingRevocation() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/casdoor/back-channel-logout");
        request.setServletPath(request.getRequestURI());
        request.setContentType("application/x-www-form-urlencoded");
        request.addParameter("logout_token", "not-a-jwt");
        var response = new MockHttpServletResponse();

        logout.filter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(redis);
    }

    @Test
    void recordsRevocationForSignedCasdoorNoticeThroughDeploymentPrefix() throws Exception {
        var signingKey = new RSAKeyGenerator(2048).keyID("casdoor").generate();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var body = exchange.getResponseBody()) {
                body.write(jwks);
            }
        });
        server.start();
        try {
            var casdoor = ClientRegistration.withClientRegistration(registration)
                    .jwkSetUri("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks").build();
            var receiver = new CasdoorSessionLogout(new InMemoryClientRegistrationRepository(casdoor), redis, new CasdoorRegistration("casdoor"),
                    "test:session", "8h", "https://skills.example.com/skills/?signedOut=1");
            var notice = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID("casdoor").type(new JOSEObjectType("logout+jwt")).build(), new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(CLIENT).subject("user").jwtID("logout-id").issueTime(Date.from(NOW))
                    .claim("events", Map.of(CasdoorSessionLogout.LOGOUT_EVENT, Map.of())).build());
            notice.sign(new RSASSASigner(signingKey));
            var request = new MockHttpServletRequest("POST", "/skills/api/v1/auth/casdoor/back-channel-logout");
            request.setContextPath("/skills");
            request.setServletPath("/api/v1/auth/casdoor/back-channel-logout");
            request.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
            request.addParameter("logout_token", notice.serialize());
            var response = new MockHttpServletResponse();

            receiver.filter().doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(200);
            verify(redis).execute(any(RedisScript.class), anyList(), eq(Long.toString(NOW.getEpochSecond())),
                    eq("28950"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void revocationInvalidatesOldCasdoorSessionButAllowsNewLoginAndLocalSession() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(Arrays.asList(null, Long.toString(NOW.getEpochSecond())));

        var oldRequest = new MockHttpServletRequest();
        authenticate(oldRequest, "casdoor", NOW.minusSeconds(5));
        var oldSession = (MockHttpSession) oldRequest.getSession(false);
        logout.filter().doFilter(oldRequest, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(oldSession.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        var newRequest = new MockHttpServletRequest();
        authenticate(newRequest, "casdoor", NOW.plusSeconds(1));
        logout.filter().doFilter(newRequest, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(newRequest.getSession(false)).isNotNull();

        var localRequest = new MockHttpServletRequest();
        authenticate(localRequest, "local", NOW.minusSeconds(5));
        logout.filter().doFilter(localRequest, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(localRequest.getSession(false)).isNotNull();
    }

    private static Jwt.Builder logoutClaims(Instant issuedAt) {
        return Jwt.withTokenValue("logout-token").header("alg", "RS256").issuer(ISSUER).audience(List.of(CLIENT))
                .subject("user").claim("jti", "logout-id").issuedAt(issuedAt)
                .claim("events", Map.of(CasdoorSessionLogout.LOGOUT_EVENT, Map.of()));
    }

    private static UsernamePasswordAuthenticationToken authenticate(MockHttpServletRequest request, String provider,
                                                                   Instant issuedAt) {
        var principal = new PlatformPrincipal("user", "User", null, null, provider, Set.of());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        CasdoorSessionLogout.rememberToken(request, new OidcIdToken("id-token", issuedAt, issuedAt.plusSeconds(60),
                Map.of("iss", ISSUER, "aud", List.of(CLIENT), "sub", "casdoor-user", "sid", "casdoor-session")));
        return authentication;
    }
}
