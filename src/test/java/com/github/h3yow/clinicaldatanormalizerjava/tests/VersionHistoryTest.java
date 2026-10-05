package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthErrorReason;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationException;
import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationService;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportBundle;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportErrorReason;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportException;
import com.github.h3yow.clinicaldatanormalizerjava.export.ExportService;
import com.github.h3yow.clinicaldatanormalizerjava.export.VersionView;
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
 * 版本历史与按版本追溯导出测试：版本单调递增、变化可追溯、
 * 历史版本导出可复现且不受后续数据影响、不存在版本可区分拒绝。
 */
class VersionHistoryTest {

    private static final Logger log = LoggerFactory.getLogger(VersionHistoryTest.class);
    private static final String T1 = "20260920100000+0800";
    private static final String T2 = "20260921100000+0800";

    private String msg(String controlId, String source, String patient, String value,
                       String status, String time) {
        String obx = status.equals("D")
                ? "OBX|1||8867-4^^LN||||||||D|||" + time
                : "OBX|1|NM|8867-4^^LN||" + value + "|bpm|||||" + status + "|||" + time;
        return "MSH|^~\\&|" + source + "|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId
                + "|P|2.5.1\rPID|||" + patient + "\rOBR|1||B-1|8867-4\r" + obx + "\r";
    }

    private record Harness(IngestionService ingest, AuthorizationService auth, ExportService exporter) {
    }

    private Harness newHarness() {
        IngestionService ingest = new IngestionService(
                new ObservationNormalizer(new CodeCatalog(), new UnitCatalog()));
        AuthorizationService auth = new AuthorizationService();
        auth.seedDemoGrants();
        return new Harness(ingest, auth, new ExportService(ingest, auth, "version-test-salt"));
    }

    private void feed(Harness h, String controlId, String patient, String value, String status, String time) {
        h.ingest().ingest(msg(controlId, "HIS_A", patient, value, status, time)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void versionsTrackEffectiveChangesWithTraceableDiffs() {
        Harness h = newHarness();
        feed(h, "MSG-V1", "P-0001", "72", "F", T1);          // v1 ADDED
        feed(h, "MSG-V2", "P-0001", "75", "C", T1);          // v2 CORRECTED
        feed(h, "MSG-V3", "P-0001", "80", "F", T2);          // v3 ADDED（新时刻）
        feed(h, "MSG-V4", "P-0001", null, "D", T1);          // v4 WITHDRAWN
        feed(h, "MSG-V4", "P-0001", null, "D", T1);          // 重复投递：DUPLICATE，无新版本

        List<VersionView> versions = h.exporter().listVersions("grant-all-demo", "P-0001");
        assertEquals(4, versions.size(), "重复投递不得产生重复版本");
        assertEquals(1, versions.get(0).version());
        assertEquals("ADDED", versions.get(0).changes().get(0).kind().name());
        assertEquals("CORRECTED", versions.get(1).changes().get(0).kind().name());
        assertEquals("ADDED", versions.get(2).changes().get(0).kind().name());
        assertEquals("WITHDRAWN", versions.get(3).changes().get(0).kind().name());
        for (VersionView v : versions) {
            assertNotNull(v.changedAt(), "每个版本必须有变化时间");
            log.info("[版本历史] v{} at={} 变化={} —— 判定: 每版相对上一版的变化可追溯",
                    v.version(), v.changedAt(), v.changes());
        }
        // 版本号单调递增不回退
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(versions.get(i).version() > versions.get(i - 1).version());
        }
    }

    @Test
    void historicalVersionExportIsFrozenAndReproducible() {
        Harness h = newHarness();
        feed(h, "MSG-H1", "P-0001", "72", "F", T1);          // v1: 72
        ExportBundle whenCurrent = h.exporter().export("grant-all-demo", "P-0001", Set.of("vital_signs"));
        assertEquals(1, whenCurrent.dataVersion());

        feed(h, "MSG-H2", "P-0001", "75", "C", T1);          // v2: 75
        feed(h, "MSG-H3", "P-0001", "80", "F", T2);          // v3: 75 + 80

        // 复盘 v1：必须是当时的去标识化结果与摘要，后续投递不影响
        ExportBundle v1 = h.exporter().exportVersion("grant-all-demo", "P-0001",
                Set.of("vital_signs"), "1");
        assertEquals(1, v1.dataVersion());
        assertEquals(1, v1.observations().size());
        assertEquals(72.0, v1.observations().get(0).value(), 1e-9);
        assertEquals(whenCurrent.digest(), v1.digest(),
                "历史版本导出摘要必须与该版本当时导出完全一致（可复现）");

        // 再投新数据后重复复盘 v1：结果与摘要仍不变
        feed(h, "MSG-H4", "P-0001", "99", "F", "20260922100000+0800");
        ExportBundle v1Again = h.exporter().exportVersion("grant-all-demo", "P-0001",
                Set.of("vital_signs"), "1");
        assertEquals(v1.digest(), v1Again.digest());
        assertEquals(v1.observations(), v1Again.observations());

        // 当前版本导出反映最新有效集合
        ExportBundle current = h.exporter().export("grant-all-demo", "P-0001", Set.of("vital_signs"));
        assertEquals(4, current.dataVersion());
        assertNotEquals(v1.digest(), current.digest());
        log.info("[按版本复盘] v1 digest={} 三次导出一致；当前已到 v{} —— 判定: 历史快照不可变，新数据不影响复盘",
                v1.digest().substring(0, 16), current.dataVersion());
    }

    @Test
    void historicalExportStaysInsideDeidentificationBoundary() {
        Harness h = newHarness();
        feed(h, "MSG-D1", "P-0001", "72", "F", T1);
        feed(h, "MSG-D2", "P-0001", "75", "C", T1);
        ExportBundle v1 = h.exporter().exportVersion("grant-all-demo", "P-0001",
                Set.of("vital_signs"), "1");
        assertTrue(v1.pseudonym().startsWith("PSEUDO-"));
        assertFalse(v1.pseudonym().contains("P-0001"));
        assertEquals(2026, v1.observations().get(0).effectiveYear(), "时间降精度到年份");
        // 记录结构本身不含患者标识/来源/控制号字段（编译期保证），此处验证伪名与精度
        log.info("[历史导出去标识化] pseudonym={} year={} —— 判定: 历史版本同样不带出标识符",
                v1.pseudonym(), v1.observations().get(0).effectiveYear());
    }

    @Test
    void missingOrForeignVersionIsRejectedDistinguishably() {
        Harness h = newHarness();
        feed(h, "MSG-M1", "P-0001", "72", "F", T1);   // P-0001 只有 v1
        feed(h, "MSG-M2", "P-0002", "80", "F", T1);
        feed(h, "MSG-M3", "P-0002", "81", "F", T2);   // P-0002 有 v1,v2

        // 超出当前范围的版本号
        ExportException tooLarge = assertThrows(ExportException.class,
                () -> h.exporter().exportVersion("grant-all-demo", "P-0001", Set.of("vital_signs"), "2"));
        assertEquals(ExportErrorReason.VERSION_NOT_FOUND, tooLarge.getReason());

        // 属于其他患者的版本号（P-0002 的 v2 对 P-0001 不存在）
        ExportException foreign = assertThrows(ExportException.class,
                () -> h.exporter().exportVersion("grant-all-demo", "P-0001", Set.of("vital_signs"), "2"));
        assertEquals(ExportErrorReason.VERSION_NOT_FOUND, foreign.getReason());

        // 非法版本号格式
        ExportException badFormat = assertThrows(ExportException.class,
                () -> h.exporter().exportVersion("grant-all-demo", "P-0001", Set.of("vital_signs"), "abc"));
        assertEquals(ExportErrorReason.INVALID_VERSION, badFormat.getReason());
        ExportException zero = assertThrows(ExportException.class,
                () -> h.exporter().exportVersion("grant-all-demo", "P-0001", Set.of("vital_signs"), "0"));
        assertEquals(ExportErrorReason.INVALID_VERSION, zero.getReason());

        // 授权之外的患者：授权校验先于版本判定
        AuthorizationException auth = assertThrows(AuthorizationException.class,
                () -> h.exporter().exportVersion("grant-vitals-demo", "P-0002", Set.of("vital_signs"), "1"));
        assertEquals(AuthErrorReason.PATIENT_OUT_OF_SCOPE, auth.getReason());
        log.info("[版本拒绝] 超范围版本/他人版本 -> VERSION_NOT_FOUND；非法格式 -> INVALID_VERSION；"
                + "越权患者 -> PATIENT_OUT_OF_SCOPE —— 判定: 三类拒绝可区分");
    }

    @Test
    void historicalExportEnforcesSameAuthorization() {
        Harness h = newHarness();
        feed(h, "MSG-A1", "P-0001", "72", "F", T1);
        // 过期授权：历史版本导出同样拒绝
        AuthorizationException expired = assertThrows(AuthorizationException.class,
                () -> h.exporter().exportVersion("grant-expired-demo", "P-0001", Set.of("vital_signs"), "1"));
        assertEquals(AuthErrorReason.GRANT_EXPIRED, expired.getReason());
        // 大类越权：历史版本导出同样拒绝
        AuthorizationException category = assertThrows(AuthorizationException.class,
                () -> h.exporter().exportVersion("grant-vitals-demo", "P-0001", Set.of("laboratory"), "1"));
        assertEquals(AuthErrorReason.CATEGORY_OUT_OF_SCOPE, category.getReason());
        // 版本历史查询也走授权
        AuthorizationException listAuth = assertThrows(AuthorizationException.class,
                () -> h.exporter().listVersions("grant-expired-demo", "P-0001"));
        assertEquals(AuthErrorReason.GRANT_EXPIRED, listAuth.getReason());
        log.info("[历史导出授权] 过期/越大类/版本历史查询均按既有授权链拒绝 —— 判定: 与当前导出同一套校验");
    }
}
