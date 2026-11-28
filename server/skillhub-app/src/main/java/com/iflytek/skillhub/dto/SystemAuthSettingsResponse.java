package com.iflytek.skillhub.dto;

import java.time.Instant;

public record SystemAuthSettingsResponse(
        boolean passwordLoginEnabled,
        long version,
        Instant updatedAt
) {}
