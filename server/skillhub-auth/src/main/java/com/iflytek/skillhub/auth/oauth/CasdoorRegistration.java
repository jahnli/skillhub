package com.iflytek.skillhub.auth.oauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Selects the OIDC registration that represents the trusted Casdoor application. */
@Component
public record CasdoorRegistration(String id) {
    public CasdoorRegistration(@Value("${skillhub.auth.casdoor.registration-id:casdoor}") String id) {
        this.id = id;
    }

    public boolean matches(String registrationId) {
        return !id.isBlank() && id.equals(registrationId);
    }
}
