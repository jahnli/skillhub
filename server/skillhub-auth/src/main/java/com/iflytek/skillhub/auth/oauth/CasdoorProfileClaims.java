package com.iflytek.skillhub.auth.oauth;

import com.iflytek.skillhub.domain.user.EmployeeProfile;
import java.util.Map;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** The employee claims released for the authenticated user by the configured Casdoor app. */
record CasdoorProfileClaims(String displayName, String avatarUrl, EmployeeProfile employeeProfile) {
    static CasdoorProfileClaims from(OidcUser user) {
        Map<String, Object> userInfo = user.getUserInfo() == null ? Map.of() : user.getUserInfo().getClaims();
        Map<String, Object> idToken = user.getIdToken().getClaims();
        return new CasdoorProfileClaims(
                read(userInfo, idToken, 128, "displayName", "name"),
                read(userInfo, idToken, 512, "picture", "avatar", "avatar_url"),
                new EmployeeProfile(
                        read(userInfo, idToken, 256, "company", "properties.company", "affiliation"),
                        read(userInfo, idToken, 256, "department", "properties.department"),
                        read(userInfo, idToken, 128, "employee_number", "employeeNumber", "properties.employeeNumber", "properties.no"),
                        read(userInfo, idToken, 256, "job_title", "jobTitle", "properties.jobTitle", "title"),
                        read(userInfo, idToken, 64, "phone_number", "phone")));
    }

    private static String read(Map<String, Object> userInfo, Map<String, Object> idToken,
                               int maxLength, String... keys) {
        String current = read(userInfo, maxLength, keys);
        return current == null ? read(idToken, maxLength, keys) : current;
    }

    private static String read(Map<String, Object> claims, int maxLength, String... keys) {
        for (String key : keys) {
            Object value = claims.get(key);
            if (key.startsWith("properties.") && claims.get("properties") instanceof Map<?, ?> properties) {
                value = properties.get(key.substring("properties.".length()));
            }
            if (value instanceof String text && text.strip().length() <= maxLength && text.indexOf(0) < 0) {
                return text.strip();
            }
        }
        return null;
    }
}
