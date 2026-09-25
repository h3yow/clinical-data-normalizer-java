package com.github.h3yow.clinicaldatanormalizerjava.consent;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最小必要披露：导出前逐指标校验授权。
 * - 无任何授权记录      -> NO_CONSENT
 * - 授权已过期          -> EXPIRED
 * - 请求的指标超出范围  -> OUT_OF_SCOPE
 */
@Service
public class ConsentService {

    private final Map<String, Consent> consents = new ConcurrentHashMap<>();

    public void register(Consent consent) {
        consents.put(consent.patientId(), consent);
    }

    public void requireCovered(String patientId, Set<String> codes, Instant now) {
        Consent consent = consents.get(patientId);
        if (consent == null) {
            throw new ConsentException(ConsentException.Kind.NO_CONSENT,
                    "no consent registered for patient " + patientId);
        }
        if (!now.isBefore(consent.expiresAt())) {
            throw new ConsentException(ConsentException.Kind.EXPIRED,
                    "consent for patient " + patientId + " expired at " + consent.expiresAt());
        }
        if (!consent.allowedCodes().contains("*")) {
            for (String code : codes) {
                if (!consent.allowedCodes().contains(code)) {
                    throw new ConsentException(ConsentException.Kind.OUT_OF_SCOPE,
                            "code " + code + " not covered by consent for patient " + patientId);
                }
            }
        }
    }
}
