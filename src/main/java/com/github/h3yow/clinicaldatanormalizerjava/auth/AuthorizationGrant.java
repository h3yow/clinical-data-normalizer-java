package com.github.h3yow.clinicaldatanormalizerjava.auth;

import java.util.Set;

public record AuthorizationGrant(
        String grantId,
        Set<String> patientIds,
        Set<String> categories,
        java.time.Instant expiresAt
) {
}
