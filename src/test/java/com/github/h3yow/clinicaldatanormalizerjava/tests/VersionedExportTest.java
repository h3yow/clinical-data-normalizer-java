package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthErrorReason;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationException;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationService;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportBundle;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportService;
import com.github.h3yow.clinicaldatanormalizerjava.export.VersionChangeView;
import com.github.h3yow.clinicaldatanormalizerjava.export.VersionInfo;
import com.github.h3yow.clinicaldatanormalizerjava.export.VersionNotFoundException;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionService;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.CodeCatalog;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.ObservationNormalizer;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.UnitCatalog;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本历史与按版本追溯导出测试：版本元数据、历史版本不可变且可复现、
 * 不存在版本的可区分拒绝、历史导出仍走授权与去标识化边界。
 */
class VersionedExportTest {

    private static final Logger log = LoggerFactory.getLogger(VersionedExportTest.class);
    private static final String TIME = "20260920100000+0800";

    private String msg(String controlId, String patientId, String value, String status) {
        return "MSH|^~\\&|HIS_A|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId + "|P|2.5.1\r"
                + "PID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||" + value + "|bpm|||||" + status + "|||" + TIME + "\r";
    }

    private String withdrawalMsg(String controlId, String patientId) {
        return "MSH|^~\\&|HIS_A|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId + "|P|2.5.1\r"
                + "PID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||||||||D|||" + TIME + "\r";
    }

    private String glucoseMsg(String controlId, String patientId) {
        return "MSH|^~\\&|LAB_X|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId + "|P|2.5.1\r"
                + "PID|||" + patientId + "\rOBR|1||B-1|2345-7\r"
                + "OBX|1|NM|2345-7^^LN||100|mg/dL|||||F|||" + TIME + "\r";
    }

    private record Harness(IngestionService ingest, AuthorizationService auth, ExportService exporter) {
    }

    private Harness harness() {
        IngestionService ingest = new IngestionService(
                new ObservationNormalizer(new CodeCatalog(), new UnitCatalog()));
        AuthorizationService auth = new AuthorizationService();
        auth.seedDemoGrants();
        return new Harness(ingest, auth, new ExportService(ingest, auth, "unit-test-salt"));
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 四个版本：v1 新增心率72，v2 更正为90，v3 新增血糖，v4 撤回心率。 */
    private Harness harnessWithFourVersions() {
        Harness h = harness();
        h.ingest().ingest(bytes(msg("MSG-V1", "P-0001", "72", "F")));
        h.ingest().ingest(bytes(msg("MSG-V2", "P-0001", "90", "C")));
        h.ingest().ingest(bytes(glucoseMsg("MSG-V3", "P-0001")));
        h.ingest().ingest(bytes(withdrawalMsg("MSG-V4", "P-0001")));
        return h;
    }

    @Test
    void versionHistoryRecordsWhatChanged() {
        Harness h = harnessWithFourVersions();
        List<VersionInfo> versions = h.exporter().listVersions("grant-all-demo", "P-0001");

        assertEquals(4, versions.size());
        assertEquals(1, versions.get(0).version());
        assertEquals("ADDED", versions.get(0).changes().get(0).changeType());
        assertEquals("CORRECTED", versions.get(1).changes().get(0).changeType());
        assertEquals("ADDED", versions.get(2).changes().get(0).changeType());
        assertEquals("WITHDRAWN", versions.get(3).changes().get(0).changeType());
        assertNotNull(versions.get(0).changedAt(), "版本必须带变化时间");

        VersionChangeView corrected = versions.get(1).changes().get(0);
        assertEquals(72.0, corrected.oldObservation().value(), 1e-9);
        assertEquals(90.0, corrected.newObservation().value(), 1e-9);
        assertEquals(2026, corrected.newObservation().effectiveYear(), "版本元数据时间同样降精度到年");
        for (VersionInfo v : versions) {
            log.info("[版本历史] v{} at={} 变更={}", v.version(), v.changedAt(),
                    v.changes().stream().map(VersionChangeView::changeType).toList());
        }
        log.info("[判定依据] 当前有效集合每真实变化一次产生一个版本；元数据只含去标识化字段"
                + "（无患者ID/来源/控制号/指纹）");
    }

    @Test
    void historicalExportIsImmutableAndReproducible() {
        Harness h = harness();
        h.ingest().ingest(bytes(msg("MSG-H1", "P-0001", "72", "F")));
        ExportBundle v1First = h.exporter().export("grant-all-demo", "P-0001",
                Set.of("vital_signs"), 1L);
        assertEquals(72.0, v1First.observations().get(0).value(), 1e-9);

        // 之后继续投递：更正 + 撤回 + 新指标，历史版本导出必须不受影响
        h.ingest().ingest(bytes(msg("MSG-H2", "P-0001", "90", "C")));
        h.ingest().ingest(bytes(glucoseMsg("MSG-H3", "P-0001")));
        h.ingest().ingest(bytes(withdrawalMsg("MSG-H4", "P-0001")));

        ExportBundle v1Again = h.exporter().export("grant-all-demo", "P-0001",
                Set.of("vital_signs"), 1L);
        assertEquals(v1First.digest(), v1Again.digest(), "同一历史版本任意时刻导出摘要必须一致");
        assertEquals(v1First.observations(), v1Again.observations());
        assertEquals(1, v1Again.dataVersion());

        ExportBundle v2 = h.exporter().export("grant-all-demo", "P-0001", Set.of("vital_signs"), 2L);
        assertEquals(90.0, v2.observations().get(0).value(), 1e-9, "v2 是更正后的值");
        assertNotEquals(v1Again.digest(), v2.digest());

        ExportBundle v4 = h.exporter().export("grant-all-demo", "P-0001",
                Set.of("vital_signs", "laboratory"), 4L);
        assertEquals(1, v4.observations().size(), "v4 中心率已撤回，只剩血糖");
        assertEquals("2345-7", v4.observations().get(0).code());

        ExportBundle current = h.exporter().export("grant-all-demo", "P-0001",
                Set.of("vital_signs", "laboratory"));
        assertEquals(v4.digest(), current.digest(), "当前版本即最新历史版本，导出一致");
        log.info("[历史复盘] v1 digest={} 在后续 3 次投递后复导一致；v2=更正后90；v4=撤回后仅剩血糖 "
                + "—— 判定依据: 版本快照在产生时固定，后续投递不可变", v1First.digest().substring(0, 12));
    }

    @Test
    void nonexistentVersionIsRejectedDistinguishably() {
        Harness h = harnessWithFourVersions(); // P-0001 共 4 个版本
        for (long bad : new long[]{0L, -1L, 5L, 99L}) {
            VersionNotFoundException ex = assertThrows(VersionNotFoundException.class,
                    () -> h.exporter().export("grant-all-demo", "P-0001", Set.of("vital_signs"), bad));
            assertEquals("VERSION_NOT_FOUND", ex.getReason());
            log.info("[版本拒绝] version={} -> {} —— {}", bad, ex.getReason(), ex.getMessage());
        }
        // 不属于该患者范围的版本：P-0002 只有 1 个版本，请求 v4 同样拒绝
        h.ingest().ingest(bytes(msg("MSG-P2", "P-0002", "70", "F")));
        assertThrows(VersionNotFoundException.class,
                () -> h.exporter().export("grant-all-demo", "P-0002", Set.of("vital_signs"), 4L));
        // 与授权失败可区分：版本不存在不是 403 授权问题
        AuthorizationException authEx = assertThrows(AuthorizationException.class,
                () -> h.exporter().export("grant-vitals-demo", "P-0001", Set.of("laboratory"), 1L));
        assertEquals(AuthErrorReason.CATEGORY_OUT_OF_SCOPE, authEx.getReason());
        log.info("[可区分拒绝] 版本不存在 -> VERSION_NOT_FOUND(404)；越权 -> CATEGORY_OUT_OF_SCOPE(403)，"
                + "两者原因通道不同");
    }

    @Test
    void historicalExportStillEnforcesAuthorization() {
        Harness h = harnessWithFourVersions();
        AuthorizationException expired = assertThrows(AuthorizationException.class,
                () -> h.exporter().export("grant-expired-demo", "P-0001", Set.of("vital_signs"), 1L));
        assertEquals(AuthErrorReason.GRANT_EXPIRED, expired.getReason());
        AuthorizationException outOfScope = assertThrows(AuthorizationException.class,
                () -> h.exporter().export("grant-all-demo", "P-9999", Set.of("vital_signs"), 1L));
        assertEquals(AuthErrorReason.PATIENT_OUT_OF_SCOPE, outOfScope.getReason());
        log.info("[历史导出授权] 过期授权/越患者授权请求历史版本 -> {}/{}，与当前导出同一套校验",
                expired.getReason(), outOfScope.getReason());
    }

    @Test
    void versionListingIsScopedToGrantCategories() {
        Harness h = harnessWithFourVersions();
        // vitals-only 授权：版本清单中实验室指标的变更痕迹被过滤（最小必要）
        List<VersionInfo> versions = h.exporter().listVersions("grant-vitals-demo", "P-0001");
        assertEquals(4, versions.size(), "版本序列本身可见（不含越范围内容）");
        assertTrue(versions.get(2).changes().isEmpty(), "v3 是血糖新增，vitals 授权下不暴露其变更");
        assertEquals("CORRECTED", versions.get(1).changes().get(0).changeType());
        log.info("[版本清单最小必要] vitals 授权下 v3(血糖) 变更被过滤 —— 判定依据: 变更视图按授权大类过滤");
    }

    @Test
    void redeliveryDoesNotChangeVersionsOrDigest() {
        Harness h = harness();
        String m1 = msg("MSG-R1", "P-0001", "72", "F");
        String m2 = msg("MSG-R2", "P-0001", "90", "C");
        h.ingest().ingest(bytes(m1));
        h.ingest().ingest(bytes(m2));
        ExportBundle before = h.exporter().export("grant-all-demo", "P-0001", Set.of("vital_signs"));

        // 反复重投（含失败重试语义）：版本与导出摘要都不得变化
        h.ingest().ingest(bytes(m1));
        h.ingest().ingest(bytes(m2));
        h.ingest().ingest(bytes(m1));
        ExportBundle after = h.exporter().export("grant-all-demo", "P-0001", Set.of("vital_signs"));

        assertEquals(before.dataVersion(), after.dataVersion(), "重复投递不得产生重复版本");
        assertEquals(before.digest(), after.digest(), "重复投递后导出摘要不变");
        assertEquals(2, h.exporter().listVersions("grant-all-demo", "P-0001").size());
        log.info("[重投可复现] 3 次重投后版本={} digest={} 不变 —— 判定依据: 消息级去重不产生状态变化",
                after.dataVersion(), after.digest().substring(0, 12));
    }
}
