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
import org.springframework.web.util.UriComponentsBuilder;

/** Casdoor browser logout and shared revocation of Casdoor-authenticated web sessions. */
@Component
public class CasdoorSessionLogout {
    public static final RequestMatcher BACK_CHANNEL = new AntPathRequestMatcher(
            "/api/v1/auth/casdoor/back-channel-logout", "POST");
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
    private final String postLogoutRedirectUri;

    public CasdoorSessionLogout(ClientRegistrationRepository registrations, StringRedisTemplate redis, CasdoorRegistration casdoor,
            @Value("${spring.session.redis.namespace:skillhub:session}") String sessionNamespace,
            @Value("${spring.session.timeout:${server.servlet.session.timeout:8h}}") String sessionTimeout,
            @Value("${skillhub.auth.casdoor.post-logout-redirect-uri:}") String postLogoutRedirectUri) {
        this.redis = redis;
        this.registration = casdoor.id().isBlank() ? null : registrations.findByRegistrationId(casdoor.id());
        this.decoder = registration == null ? null : createDecoder(registration);
        this.keyPrefix = registration == null ? null : sessionNamespace + ":casdoor:{"
                + encodeKey(registration.getProviderDetails().getIssuerUri()) + ":"
                + encodeKey(registration.getClientId()) + "}:";
        // A session sees the marker on its next request, or expires from inactivity first.
        this.markerLifetime = DurationStyle.detectAndParse(sessionTimeout).plus(TOKEN_MAX_AGE).plus(CLOCK_SKEW);
        this.postLogoutRedirectUri = postLogoutRedirectUri;
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
        if (session == null || !(session.getAttribute(SESSION_TOKEN) instanceof OidcIdToken token)
                || !matchesRegistration(token.getClaimAsString("iss"), token.getAudience())) {
            return;
        }
        Object endpoint = registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
        if (!(endpoint instanceof String url) || !StringUtils.hasText(url)) {
            return;
        }
        var builder = UriComponentsBuilder.fromUriString(url)
                .queryParam("id_token_hint", "{hint}");
        if (StringUtils.hasText(postLogoutRedirectUri)) {
            builder.queryParam("post_logout_redirect_uri", "{returnUri}");
        }
        request.setAttribute(BROWSER_REDIRECT, builder.encode().buildAndExpand(Map.of(
                "hint", token.getTokenValue(), "returnUri", postLogoutRedirectUri)).toUriString());
    }

    public OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
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
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }

    private void receiveLogout(HttpServletRequest request, HttpServletResponse response) {
        if (decoder == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        String[] values = request.getParameterValues("logout_token");
        if (values == null || values.length != 1 || !StringUtils.hasText(values[0])) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        Jwt token;
        try {
            token = decoder.decode(values[0]);
        } catch (JwtException | IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String sid = token.getClaimAsString("sid");
        String subject = token.getSubject();
        Instant now = Instant.now();
        if (!matchesRegistration(token.getClaimAsString("iss"), token.getAudience())
                || !validLogoutClaims(token, now)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String key = StringUtils.hasText(sid) ? key("sid", sid) : key("sub", subject);
        // The signed cutoff is monotonic, so retries and replays cannot revoke later logins.
        redis.execute(RECORD_LOGOUT, List.of(key), Long.toString(token.getIssuedAt().getEpochSecond()),
                Long.toString(markerLifetime.toSeconds()));
        response.setStatus(HttpServletResponse.SC_OK);
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
