package com.iflytek.skillhub.auth.oauth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Selects the OIDC registration that represents the trusted Casdoor application. */
@Component
public record CasdoorRegistration(String id) {
    @Autowired
    public CasdoorRegistration(Environment environment) {
        this("oidc".equals(environment.getProperty("spring.security.oauth2.client.registration.oidc.provider"))
                ? "oidc" : "casdoor");
    }

    public boolean matches(String registrationId) {
        return !id.isBlank() && id.equals(registrationId);
    }
}
