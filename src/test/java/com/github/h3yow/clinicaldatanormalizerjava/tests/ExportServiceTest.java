package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthErrorReason;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationException;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationGrant;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationService;
import com.github.h3yow.clinicaldatanormalizerjava.export.DeidentifiedObservation;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportBundle;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportService;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionService;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.CodeCatalog;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.ObservationNormalizer;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.UnitCatalog;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 去标识化与授权测试：最小必要披露、过期/越权分类拒绝、同版本同范围摘要可复现。
 */
class ExportServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ExportServiceTest.class);

    private String vitalsMessage(String controlId, String patientId, String value, String unit, String time) {
        return "MSH|^~\\&|HIS_A|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId + "|P|2.5.1\r"
                + "PID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||" + value + "|" + unit + "|||||F|||" + time + "\r";
    }

    private String glucoseMessage(String controlId, String patientId) {
        return "MSH|^~\\&|LAB_X|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId + "|P|2.5.1\r"
                + "PID|||" + patientId + "\rOBR|1||B-1|2345-7\r"
                + "OBX|1|NM|2345-7^^LN||100|mg/dL|||||F|||20260920100000+0800\r";
    }

    private record Harness(IngestionService ingest, AuthorizationService auth, ExportService exporter) {
    }

    private Harness harnessWithPatientData() {
        IngestionService ingest = new IngestionService(
                new ObservationNormalizer(new CodeCatalog(), new UnitCatalog()));
        AuthorizationService auth = new AuthorizationService();
        auth.seedDemoGrants();
        ExportService exporter = new ExportService(ingest, auth, "unit-test-salt");
        ingest.ingest(vitalsMessage("MSG-E1", "P-0001", "72", "bpm", "20260920100000+0800").getBytes(StandardCharsets.UTF_8));
        ingest.ingest(glucoseMessage("MSG-E2", "P-0001").getBytes(StandardCharsets.UTF_8));
        return new Harness(ingest, auth, exporter);
    }

    private AuthorizationException expectAuthFailure(Harness h, String grantId, String patientId,
                                                      Set<String> cats, AuthErrorReason expected) {
        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> h.exporter().export(grantId, patientId, cats));
        assertEquals(expected, ex.getReason());
        log.info("[授权拒绝] grant={} patient={} cats={} -> {} —— {}", grantId, patientId, cats,
                ex.getReason(), ex.getMessage());
        return ex;
    }

    @Test
    void exportIsDeidentifiedAndScoped() {
        Harness h = harnessWithPatientData();
        ExportBundle bundle = h.exporter().export("grant-all-demo", "P-0001",
                Set.of("vital_signs", "laboratory"));

        assertTrue(bundle.pseudonym().startsWith("PSEUDO-"), "患者 ID 必须替换为伪名");
        assertFalse(bundle.pseudonym().contains("P-0001"), "伪名不得包含原患者标识");
        assertEquals(2, bundle.observations().size());
        for (DeidentifiedObservation o : bundle.observations()) {
            // 记录层面也不得含有直接标识：DeidentifiedObservation 根本没有 patient/source/controlId 字段
            assertEquals(2026, o.effectiveYear(), "时间必须降精度到年份");
        }
        log.info("[去标识化导出] pseudonym={} version={} rows={} —— 判定: 无患者ID/来源/控制号，时间降到年，仅含授权大类",
                bundle.pseudonym(), bundle.dataVersion(), bundle.observations().size());
        for (DeidentifiedObservation o : bundle.observations()) {
            log.info("  行: category={} code={} value={} unit={} year={}",
                    o.category(), o.code(), o.value(), o.unit(), o.effectiveYear());
        }
    }

    @Test
    void exportIsLimitedToRequestedCategories() {
        Harness h = harnessWithPatientData();
        ExportBundle vitalsOnly = h.exporter().export("grant-vitals-demo", "P-0001",
                Set.of("vital_signs"));
        assertEquals(1, vitalsOnly.observations().size());
        assertEquals("8867-4", vitalsOnly.observations().get(0).code());
        assertEquals(List.of("vital_signs"), vitalsOnly.scopes());
        log.info("[最小必要] 仅申请 vital_signs 时只返回 {} 行 —— 判定: 不返回未申请的 laboratory",
                vitalsOnly.observations().size());
    }

    @Test
    void rejectsMissingExpiredOutOfScopeGrants() {
        Harness h = harnessWithPatientData();
        expectAuthFailure(h, "no-such-grant", "P-0001", Set.of("vital_signs"),
                AuthErrorReason.GRANT_NOT_FOUND);
        expectAuthFailure(h, "grant-expired-demo", "P-0001", Set.of("vital_signs"),
                AuthErrorReason.GRANT_EXPIRED);
        expectAuthFailure(h, "grant-all-demo", "P-9999", Set.of("vital_signs"),
                AuthErrorReason.PATIENT_OUT_OF_SCOPE);
        // grant-vitals-demo 只有 vital_signs，申请 laboratory 必须拒绝
        expectAuthFailure(h, "grant-vitals-demo", "P-0001", Set.of("laboratory"),
                AuthErrorReason.CATEGORY_OUT_OF_SCOPE);
        // 超出范围的申请即使部分在范围内也整体拒绝
        expectAuthFailure(h, "grant-vitals-demo", "P-0001",
                Set.of("vital_signs", "laboratory"), AuthErrorReason.CATEGORY_OUT_OF_SCOPE);
    }

    @Test
    void digestIsReproducibleForSameVersionAndScope() {
        Harness h = harnessWithPatientData();
        ExportBundle a = h.exporter().export("grant-all-demo", "P-0001",
                Set.of("laboratory", "vital_signs")); // 故意乱序传入
        ExportBundle b = h.exporter().export("grant-all-demo", "P-0001",
                Set.of("vital_signs", "laboratory"));

        assertEquals(a.digest(), b.digest(), "大类传入顺序不同不得影响摘要");
        assertEquals(a.observations(), b.observations());
        assertNotNull(a.digest());
        assertEquals(64, a.digest().length(), "摘要为 SHA-256 十六进制");

        // 同患者同范围，新增数据改变版本后摘要必须变化
        h.ingest().ingest(vitalsMessage("MSG-E3", "P-0001", "75", "bpm",
                "20260921100000+0800").getBytes(StandardCharsets.UTF_8));
        ExportBundle c = h.exporter().export("grant-all-demo", "P-0001",
                Set.of("vital_signs", "laboratory"));
        assertNotEquals(a.dataVersion(), c.dataVersion());
        assertNotEquals(a.digest(), c.digest(), "数据版本变化后摘要必须改变");

        // 不同范围摘要也不同
        ExportBundle d = h.exporter().export("grant-vitals-demo", "P-0001", Set.of("vital_signs"));
        assertNotEquals(c.digest(), d.digest(), "授权范围不同摘要不同");

        log.info("[可复现摘要] 同版本同范围两次导出 digest={} 一致；版本 {}->{} 后 digest 改变；范围缩小后再次改变 —— 判定: 摘要确定且可追溯",
                a.digest().substring(0, 16), a.dataVersion(), c.dataVersion());
    }

    @Test
    void grantExpiryBoundaryIsExclusive() {
        AuthorizationService auth = new AuthorizationService();
        auth.seedDemoGrants();
        Instant expiry = Instant.parse("2026-09-25T00:00:00Z");
        auth.register(new AuthorizationGrant("g-exact", Set.of("P"), Set.of("vital_signs"), expiry));
        assertDoesNotThrow(() ->
                auth.validate("g-exact", "P", Set.of("vital_signs"), expiry.minus(1, ChronoUnit.SECONDS)));
        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> auth.validate("g-exact", "P", Set.of("vital_signs"), expiry));
        assertEquals(AuthErrorReason.GRANT_EXPIRED, ex.getReason());
        log.info("[过期边界] expiresAt 时刻本身即视为过期 —— 判定: 严格晚于当前时间才有效");
    }
}
