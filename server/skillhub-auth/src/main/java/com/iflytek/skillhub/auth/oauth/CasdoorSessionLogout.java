package com.iflytek.skillhub.auth.oauth;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/** Casdoor browser logout and shared revocation of Casdoor-authenticated web sessions. */
@Component
public class CasdoorSessionLogout {
    private static final Logger log = LoggerFactory.getLogger(CasdoorSessionLogout.class);
    public static final RequestMatcher BACK_CHANNEL = new AntPathRequestMatcher(
            "/api/v1/auth/back-channel-logout", "POST");
    public static final String BROWSER_REDIRECT = CasdoorSessionLogout.class.getName() + ".redirect";
    static final String SESSION_TOKEN = CasdoorSessionLogout.class.getName() + ".idToken";
    static final String LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout";
    private static final Duration TOKEN_MAX_AGE = Duration.ofMinutes(2);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final RedisScript<Long> RECORD_LOGOUT = RedisScript.of("""
            local cutoff = redis.call('GET', KEYS[1])
            if not cutoff or tonumber(cutoff) < tonumber(ARGV[1]) then
                redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ClientRegistration registration;
    private final JwtDecoder decoder;
    private final String keyPrefix;
    private final Duration markerLifetime;
    public static final String LOGIN_CALLBACK = "/api/v1/auth/login/callback";
    private static final String LOGOUT_CALLBACK = "/api/v1/auth/logout/callback";
    private static final RequestMatcher LOGOUT_RETURN = new AntPathRequestMatcher(LOGOUT_CALLBACK, "GET");

    public CasdoorSessionLogout(ClientRegistrationRepository registrations, StringRedisTemplate redis, CasdoorRegistration casdoor,
            @Value("${spring.session.redis.namespace:skillhub:session}") String sessionNamespace,
            @Value("${spring.session.timeout:${server.servlet.session.timeout:8h}}") String sessionTimeout) {
        this.redis = redis;
        this.registration = casdoor.id().isBlank() ? null : registrations.findByRegistrationId(casdoor.id());
        this.decoder = registration == null ? null : createDecoder(registration);
        this.keyPrefix = registration == null ? null : sessionNamespace + ":casdoor:{"
                + encodeKey(registration.getProviderDetails().getIssuerUri()) + ":"
                + encodeKey(registration.getClientId()) + "}:";
        // A session sees the marker on its next request, or expires from inactivity first.
        this.markerLifetime = DurationStyle.detectAndParse(sessionTimeout).plus(TOKEN_MAX_AGE).plus(CLOCK_SKEW);
    }

    static void rememberToken(HttpServletRequest request, OidcIdToken token) {
        request.getSession().setAttribute(SESSION_TOKEN, token);
    }

    public void prepareBrowserLogout(HttpServletRequest request, Authentication authentication) {
        if (registration == null || !"true".equals(request.getParameter("federated"))) {
            return;
        }
        if (authentication == null || !(authentication.getPrincipal() instanceof PlatformPrincipal principal)
                || !registration.getRegistrationId().equals(principal.oauthProvider())) {
            return;
        }
        var session = request.getSession(false);
        Object savedToken = session == null ? null : session.getAttribute(SESSION_TOKEN);
        Object endpoint = registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
        if (!(endpoint instanceof String url) || !StringUtils.hasText(url)) {
            return;
        }
        var builder = UriComponentsBuilder.fromUriString(url);
        var parameters = new java.util.HashMap<String, String>();
        if (savedToken instanceof OidcIdToken token) {
            if (!matchesRegistration(token.getClaimAsString("iss"), token.getAudience())) {
                return;
            }
            builder.queryParam("id_token_hint", "{hint}");
            parameters.put("hint", token.getTokenValue());
        } else {
            // Sessions created before ID tokens were retained can still end the browser's SSO session.
            builder.queryParam("client_id", "{client}");
            parameters.put("client", registration.getClientId());
        }
        builder.queryParam("post_logout_redirect_uri", "{returnUri}");
        parameters.put("returnUri", logoutRedirectUri(request));
        request.setAttribute(BROWSER_REDIRECT, builder.encode().buildAndExpand(parameters).toUriString());
    }

    private String logoutRedirectUri(HttpServletRequest request) {
        var base = ServletUriComponentsBuilder.fromContextPath(request).build();
        String loginUrl = UriComponentsBuilder.fromUriString(registration.getRedirectUri()).buildAndExpand(Map.of(
                "registrationId", registration.getRegistrationId(), "action", "login",
                "baseUrl", base.toUriString(), "baseScheme", base.getScheme(), "baseHost", base.getHost(),
                "basePort", base.getPort() < 0 ? "" : ":" + base.getPort(), "basePath", request.getContextPath())).toUriString();
        var login = UriComponentsBuilder.fromUriString(loginUrl);
        String path = login.build().getPath();
        String loginSuffix = path.endsWith(LOGIN_CALLBACK) ? LOGIN_CALLBACK : "/login/oauth2/code/" + registration.getRegistrationId();
        String prefix = path.endsWith(loginSuffix) ? path.substring(0, path.length() - loginSuffix.length()) : request.getContextPath();
        return login.replacePath(prefix + LOGOUT_CALLBACK)
                .replaceQuery(null).fragment(null).build(true).toUriString();
    }

    public OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                if (registration != null && LOGOUT_RETURN.matches(request)) {
                    response.setStatus(HttpServletResponse.SC_FOUND);
                    response.setHeader("Location", request.getContextPath() + "/?signedOut=1");
                    return;
                }
                if (BACK_CHANNEL.matches(request)) {
                    receiveLogout(request, response);
                    return;
                }
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (decoder != null && authentication != null
                        && authentication.getPrincipal() instanceof PlatformPrincipal principal
                        && registration.getRegistrationId().equals(principal.oauthProvider())) {
                    var session = request.getSession(false);
                    if (session != null && session.getAttribute(SESSION_TOKEN) instanceof OidcIdToken token
                            && isRevoked(token)) {
                        session.invalidate();
                        SecurityContextHolder.clearContext();
                        log.info("Casdoor session invalidated requestId={}", MDC.get("requestId"));
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }

    private void receiveLogout(HttpServletRequest request, HttpServletResponse response) {
        String requestId = MDC.get("requestId");
        log.info("Casdoor back-channel logout received requestId={}", requestId);
        if (decoder == null) {
            rejectLogout(response, HttpServletResponse.SC_NOT_FOUND, "registration_unavailable");
            return;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            rejectLogout(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "unsupported_content_type");
            return;
        }
        String[] values = request.getParameterValues("logout_token");
        if (values == null || values.length != 1 || !StringUtils.hasText(values[0])) {
            rejectLogout(response, HttpServletResponse.SC_BAD_REQUEST, "expected_one_nonempty_logout_token");
            return;
        }
        Jwt token;
        try {
            token = decoder.decode(values[0]);
        } catch (JwtException | IllegalArgumentException e) {
            rejectLogout(response, HttpServletResponse.SC_BAD_REQUEST, "jwt_validation_failed");
            return;
        }
        String sid = token.getClaimAsString("sid");
        String subject = token.getSubject();
        Instant now = Instant.now();
        if (!matchesRegistration(token.getClaimAsString("iss"), token.getAudience())) {
            rejectLogout(response, HttpServletResponse.SC_BAD_REQUEST, "registration_mismatch");
            return;
        }
        if (!validLogoutClaims(token, now)) {
            rejectLogout(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_logout_claims");
            return;
        }
        boolean hasSid = StringUtils.hasText(sid);
        String scope = hasSid ? "sid" : "sub";
        String key = key(scope, hasSid ? sid : subject);
        // The signed cutoff is monotonic, so retries and replays cannot revoke later logins.
        try {
            redis.execute(RECORD_LOGOUT, List.of(key), Long.toString(token.getIssuedAt().getEpochSecond()),
                    Long.toString(markerLifetime.toSeconds()));
        } catch (DataAccessException e) {
            log.error("Casdoor back-channel logout failed reason=redis_write_failed requestId={}", requestId);
            throw e;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        log.info("Casdoor back-channel logout accepted status=200 scope={} requestId={}", scope, requestId);
    }

    private static void rejectLogout(HttpServletResponse response, int status, String reason) {
        response.setStatus(status);
        log.warn("Casdoor back-channel logout rejected status={} reason={} requestId={}", status, reason, MDC.get("requestId"));
    }

    static boolean validLogoutClaims(Jwt token, Instant now) {
        Instant issuedAt = token.getIssuedAt();
        return issuedAt != null && !issuedAt.isBefore(now.minus(TOKEN_MAX_AGE))
                && !issuedAt.isAfter(now.plus(CLOCK_SKEW))
                && (token.getExpiresAt() == null || token.getExpiresAt().isAfter(now))
                && StringUtils.hasText(token.getId()) && !token.hasClaim("nonce")
                && (StringUtils.hasText(token.getSubject()) || StringUtils.hasText(token.getClaimAsString("sid")))
                && token.getClaim("events") instanceof Map<?, ?> events
                && events.get(LOGOUT_EVENT) instanceof Map<?, ?>;
    }

    private boolean isRevoked(OidcIdToken token) {
        if (!matchesRegistration(token.getClaimAsString("iss"), token.getAudience())) {
            return true;
        }
        List<String> keys = new ArrayList<>(2);
        keys.add(key("sub", token.getSubject()));
        String sid = token.getClaimAsString("sid");
        if (StringUtils.hasText(sid)) {
            keys.add(key("sid", sid));
        }
        List<String> cutoffs = redis.opsForValue().multiGet(keys);
        long issuedAt = token.getIssuedAt().getEpochSecond();
        return cutoffs != null && cutoffs.stream().anyMatch(cutoff ->
                cutoff != null && issuedAt <= Long.parseLong(cutoff));
    }

    private boolean matchesRegistration(String issuer, List<String> audience) {
        return registration.getProviderDetails().getIssuerUri().equals(issuer)
                && audience != null && audience.contains(registration.getClientId());
    }

    private String key(String claim, String value) {
        return keyPrefix + claim + ":" + encodeKey(value);
    }

    private static String encodeKey(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static JwtDecoder createDecoder(ClientRegistration registration) {
        var provider = registration.getProviderDetails();
        var decoder = NimbusJwtDecoder.withJwkSetUri(provider.getJwkSetUri())
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("logout+jwt"), null)))
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(provider.getIssuerUri()));
        return decoder;
    }
}
