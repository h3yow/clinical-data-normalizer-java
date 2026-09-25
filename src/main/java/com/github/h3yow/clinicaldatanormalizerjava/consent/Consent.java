package com.github.h3yow.clinicaldatanormalizerjava.consent;

import java.time.Instant;
import java.util.Set;

/**
 * 授权：某患者在某时间范围内允许导出的指标代码集合。
 */
public record Consent(String patientId, Set<String> allowedCodes, Instant expiresAt) {

    public boolean covers(String patient, String code, Instant now) {
        return patientId.equals(patient)
                && (allowedCodes.contains("*") || allowedCodes.contains(code))
                && now.isBefore(expiresAt);
    }
}
