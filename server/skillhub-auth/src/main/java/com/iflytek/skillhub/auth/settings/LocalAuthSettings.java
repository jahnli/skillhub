package com.iflytek.skillhub.auth.settings;

import java.time.Instant;

public record LocalAuthSettings(
        long id,
        boolean passwordLoginEnabled,
        long version,
        Instant updatedAt
) {}
