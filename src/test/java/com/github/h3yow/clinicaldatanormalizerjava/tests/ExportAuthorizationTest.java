package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.consent.Consent;
import com.github.h3yow.clinicaldatanormalizerjava.consent.ConsentException;
import com.github.h3yow.clinicaldatanormalizerjava.consent.ConsentService;
import com.github.h3yow.clinicaldatanormalizerjava.export.DeidExporter;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportResult;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExportAuthorizationTest {

    private static final Logger log = LoggerFactory.getLogger(ExportAuthorizationTest.class);
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");

    private final ConsentService consentService = new ConsentService();
    private final DeidExporter exporter = new DeidExporter("test-key");

    private static Observation obs(String patient, String code, String value) {
        return new Observation(patient, code, new BigDecimal(value), "mmol/L",
                Instant.parse("2024-01-01T12:34:56Z"), "LAB", "M1", "M1#OBX-1");
    }

    @Test
    void rejectsWhenNoConsent() {
        ConsentException e = assertThrows(ConsentException.class,
                () -> consentService.requireCovered("P1", Set.of("2345-7"), NOW));
        log.info("export denied: kind={} detail={}", e.getKind(), e.getMessage());
        assertEquals(ConsentException.Kind.NO_CONSENT, e.getKind());
    }

    @Test
    void rejectsExpiredConsent() {
        consentService.register(new Consent("P1", Set.of("2345-7"),
                NOW.minus(1, ChronoUnit.DAYS)));
        ConsentException e = assertThrows(ConsentException.class,
                () -> consentService.requireCovered("P1", Set.of("2345-7"), NOW));
        log.info("export denied: kind={} detail={}", e.getKind(), e.getMessage());
        assertEquals(ConsentException.Kind.EXPIRED, e.getKind());
    }

    @Test
    void rejectsOutOfScope() {
        consentService.register(new Consent("P1", Set.of("2345-7"),
                NOW.plus(1, ChronoUnit.DAYS)));
        ConsentException e = assertThrows(ConsentException.class,
                () -> consentService.requireCovered("P1", Set.of("2345-7", "8480-6"), NOW));
        log.info("export denied: kind={} detail={}", e.getKind(), e.getMessage());
        assertEquals(ConsentException.Kind.OUT_OF_SCOPE, e.getKind());
    }

    @Test
    void exportIsDeidentifiedAndDigestReproducible() {
        List<Observation> data = List.of(
                obs("P1", "2345-7", "5.5"),
                obs("P1", "8480-6", "120"));
        String scope = "P1|2345-7,8480-6";

        ExportResult r1 = exporter.export(data, scope, 7);
        List<Observation> reversed = new java.util.ArrayList<>(data);
        java.util.Collections.reverse(reversed);
        ExportResult r2 = exporter.export(reversed, scope, 7);
        ExportResult r3 = exporter.export(data, scope, 8); // 不同数据版本

        r1.records().forEach(rec -> log.info("deid record: {}", rec));
        log.info("digest(v7)={} digest(v7,reordered)={} digest(v8)={}",
                r1.digest(), r2.digest(), r3.digest());

        // 同一数据版本 + 同一授权范围 -> 摘要可复现，且与输入顺序无关
        assertEquals(r1.digest(), r2.digest());
        // 数据版本变化 -> 摘要变化
        assertNotEquals(r1.digest(), r3.digest());
        // 不含直接标识符：原始患者ID、消息ID、来源均不得出现
        for (String rec : r1.records()) {
            assertFalse(rec.contains("P1|"), "record leaks patient id: " + rec);
            assertFalse(rec.contains("M1"), "record leaks message id: " + rec);
            assertFalse(rec.contains("LAB"), "record leaks source: " + rec);
            assertFalse(rec.contains("12:34"), "record leaks precise timestamp: " + rec);
        }
        // 时间已泛化为日期
        assertTrue(r1.records().get(0).endsWith("|2024-01-01"));
    }
}
