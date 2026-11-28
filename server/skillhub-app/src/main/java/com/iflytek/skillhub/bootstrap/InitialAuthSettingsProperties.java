package com.iflytek.skillhub.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "skillhub.auth.initial-settings")
public class InitialAuthSettingsProperties {
    private boolean passwordLoginEnabled = true;
    private String roleGrantsJson = "[]";

    public boolean isPasswordLoginEnabled() { return passwordLoginEnabled; }
    public void setPasswordLoginEnabled(boolean enabled) { this.passwordLoginEnabled = enabled; }
    public String getRoleGrantsJson() { return roleGrantsJson; }
    public void setRoleGrantsJson(String roleGrantsJson) { this.roleGrantsJson = roleGrantsJson; }
}
