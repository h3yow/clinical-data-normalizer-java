package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionOutcome;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionService;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.MergeDecision;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.PatientRecord;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.CodeCatalog;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.ObservationNormalizer;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.UnitCatalog;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结果更正与撤回测试：更正替换有效值、撤回移出有效集合、
 * 无目标时的可区分判定、与冲突/重复投递一致的取舍规则、乱序收敛。
 */
class CorrectionWithdrawalTest {

    private static final Logger log = LoggerFactory.getLogger(CorrectionWithdrawalTest.class);
    private static final String T1 = "20260920100000+0800";

    private IngestionService newService() {
        return new IngestionService(new ObservationNormalizer(new CodeCatalog(), new UnitCatalog()));
    }

    private String addMessage(String controlId, String source, String patientId, String value, String time) {
        return "MSH|^~\\&|" + source + "|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId
                + "|P|2.5.1\rPID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||" + value + "|bpm|||||F|||" + time + "\r";
    }

    private String correctionMessage(String controlId, String source, String patientId, String value, String time) {
        return "MSH|^~\\&|" + source + "|FAC||FAC|20260920110000+0800||ORU^R01|" + controlId
                + "|P|2.5.1\rPID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||" + value + "|bpm|||||C|||" + time + "\r";
    }

    private String withdrawalMessage(String controlId, String source, String patientId, String time) {
        return "MSH|^~\\&|" + source + "|FAC||FAC|20260920120000+0800||ORU^R01|" + controlId
                + "|P|2.5.1\rPID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1||8867-4^^LN||||||||D|||" + time + "\r";
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Set<String> effectiveFingerprints(IngestionService svc, String patientId) {
        PatientRecord record = svc.getPatient(patientId);
        Set<String> fps = new TreeSet<>();
        for (Observation o : record.effectiveObservations()) {
            fps.add(o.fingerprint());
        }
        return fps;
    }

    private void logDecisions(String tag, IngestionOutcome outcome) {
        for (MergeDecision d : outcome.mergeDecisions()) {
            log.info("[{}] {} slot={} 消息={} 原因码={} —— {}", tag, d.kind(), d.slotKey(),
                    d.messageControlId(), d.reasonCode(), d.reason());
        }
    }

    @Test
    void correctionReplacesCurrentEffectiveValue() {
        IngestionService svc = newService();
        svc.ingest(bytes(addMessage("MSG-A1", "HIS_A", "P-C1", "72", T1)));
        IngestionOutcome outcome = svc.ingest(bytes(correctionMessage("MSG-C1", "HIS_A", "P-C1", "75", T1))).get(0);

        assertEquals(IngestionOutcome.Status.ACCEPTED, outcome.status());
        assertEquals(1, outcome.mergeDecisions().size());
        MergeDecision d = outcome.mergeDecisions().get(0);
        assertEquals(MergeDecision.Kind.CORRECTION_APPLIED, d.kind());
        logDecisions("更正判定", outcome);

        PatientRecord record = svc.getPatient("P-C1");
        List<Observation> effective = record.effectiveObservations();
        assertEquals(1, effective.size(), "同一患者+指标+时刻只能保留一条有效观测");
        assertEquals(75.0, effective.get(0).value(), 1e-9, "更正值替换当前有效值");
        assertEquals(2, record.version(), "有效集合每次真实变化产生一个新版本");
        log.info("[更正生效] 有效值 72 -> 75，版本 {} —— 判定: 被更正掉的值不再出现在当前有效集合", record.version());
    }

    @Test
    void correctionWithoutTargetIsDeferredAndNeverTreatedAsPlainAdd() {
        IngestionService svc = newService();
        IngestionOutcome outcome = svc.ingest(bytes(correctionMessage("MSG-C2", "HIS_A", "P-C2", "75", T1))).get(0);

        assertEquals(IngestionOutcome.Status.DEFERRED, outcome.status(), "无可更正目标必须可区分");
        assertEquals(1, outcome.mergeDecisions().size());
        MergeDecision d = outcome.mergeDecisions().get(0);
        assertEquals(MergeDecision.Kind.CORRECTION_DEFERRED, d.kind());
        assertEquals("CORRECTION_TARGET_NOT_FOUND", d.reasonCode());
        logDecisions("无目标更正", outcome);

        PatientRecord record = svc.getPatient("P-C2");
        assertTrue(record.effectiveObservations().isEmpty(),
                "无目标更正不得进入当前有效集合（不能悄悄当成普通新增）");
        assertEquals(0, record.version(), "未产生有效变化不得产生版本");

        // 目标到达后，待生效更正按确定性规则生效
        svc.ingest(bytes(addMessage("MSG-A2", "HIS_A", "P-C2", "72", T1)));
        List<Observation> effective = record.effectiveObservations();
        assertEquals(1, effective.size());
        assertEquals(75.0, effective.get(0).value(), 1e-9, "目标到达后待生效更正替换有效值");
        log.info("[待生效更正] 目标到达后有效值={} 版本={} —— 判定: 更正优先于普通新增，与到达顺序无关",
                effective.get(0).value(), record.version());
    }

    @Test
    void correctionAndAddConvergeRegardlessOfOrder() {
        Consumer<IngestionService> addThenCorrect = svc -> {
            svc.ingest(bytes(addMessage("MSG-A3", "HIS_A", "P-C3", "72", T1)));
            svc.ingest(bytes(correctionMessage("MSG-C3", "HIS_A", "P-C3", "75", T1)));
        };
        Consumer<IngestionService> correctThenAdd = svc -> {
            svc.ingest(bytes(correctionMessage("MSG-C3", "HIS_A", "P-C3", "75", T1)));
            svc.ingest(bytes(addMessage("MSG-A3", "HIS_A", "P-C3", "72", T1)));
        };
        IngestionService s1 = newService();
        addThenCorrect.accept(s1);
        IngestionService s2 = newService();
        correctThenAdd.accept(s2);

        assertEquals(effectiveFingerprints(s1, "P-C3"), effectiveFingerprints(s2, "P-C3"),
                "更正与新增乱序到达，最终有效集合必须一致");
        assertEquals(1, effectiveFingerprints(s1, "P-C3").size());
        log.info("[乱序收敛] 先增后更 vs 先更后增，最终有效集合一致 —— 判定: 槽位有效值是事件集合的纯函数");
    }

    @Test
    void withdrawalRemovesSlotFromEffectiveSet() {
        IngestionService svc = newService();
        svc.ingest(bytes(addMessage("MSG-A4", "HIS_A", "P-C4", "72", T1)));
        IngestionOutcome outcome = svc.ingest(bytes(withdrawalMessage("MSG-W4", "HIS_A", "P-C4", T1))).get(0);

        assertEquals(IngestionOutcome.Status.ACCEPTED, outcome.status());
        MergeDecision d = outcome.mergeDecisions().get(0);
        assertEquals(MergeDecision.Kind.WITHDRAWAL_APPLIED, d.kind());
        logDecisions("撤回判定", outcome);

        PatientRecord record = svc.getPatient("P-C4");
        assertTrue(record.effectiveObservations().isEmpty(), "撤回后槽位移出当前有效集合");
        assertEquals(2, record.version(), "新增与撤回各产生一个版本");
        assertEquals("WITHDRAWN", record.versions().get(1).changes().get(0).kind().name());
        log.info("[撤回生效] 版本 1(ADDED) -> 2(WITHDRAWN)，有效集合为空 —— 判定: 被撤回的值不再出现在当前导出");
    }

    @Test
    void withdrawalWithoutTargetIsDeferredAndSuppressesLaterAdd() {
        IngestionService svc = newService();
        IngestionOutcome deferred = svc.ingest(bytes(withdrawalMessage("MSG-W5", "HIS_A", "P-C5", T1))).get(0);
        assertEquals(IngestionOutcome.Status.DEFERRED, deferred.status());
        assertEquals("WITHDRAWAL_TARGET_NOT_FOUND", deferred.mergeDecisions().get(0).reasonCode());
        logDecisions("无目标撤回", deferred);

        IngestionOutcome addOutcome = svc.ingest(bytes(addMessage("MSG-A5", "HIS_A", "P-C5", "72", T1))).get(0);
        assertEquals(IngestionOutcome.Status.ACCEPTED, addOutcome.status());
        assertTrue(svc.getPatient("P-C5").effectiveObservations().isEmpty(),
                "已登记撤回的槽位，后续普通新增不得使其复活");
        assertTrue(addOutcome.mergeDecisions().stream()
                .anyMatch(d -> d.kind() == MergeDecision.Kind.ADD_SUPERSEDED_BY_WITHDRAWAL));
        logDecisions("新增被撤回压制", addOutcome);

        // 反向顺序（先增后撤）最终同样为空：顺序无关
        IngestionService s2 = newService();
        s2.ingest(bytes(addMessage("MSG-A5", "HIS_A", "P-C5", "72", T1)));
        s2.ingest(bytes(withdrawalMessage("MSG-W5", "HIS_A", "P-C5", T1)));
        assertEquals(effectiveFingerprints(svc, "P-C5"), effectiveFingerprints(s2, "P-C5"));
        log.info("[撤回终态] 先撤后增 vs 先增后撤，最终有效集合均为空 —— 判定: 撤回是槽位终态声明");
    }

    @Test
    void correctionBeatsHigherPrioritySourceAdd() {
        IngestionService svc = newService();
        // 高优先级来源的普通新增 vs 低优先级来源的更正：显式更正意图优先
        svc.ingest(bytes(addMessage("MSG-A6", "HIS_A", "P-C6", "90", T1)));
        svc.ingest(bytes(correctionMessage("MSG-C6", "LAB_X", "P-C6", "75", T1)));
        List<Observation> effective = svc.getPatient("P-C6").effectiveObservations();
        assertEquals(75.0, effective.get(0).value(), 1e-9,
                "更正优先于任何普通新增（即使新增来自更高优先级来源）");
        log.info("[取舍规则] HIS_A(优先级30)新增90 vs LAB_X(优先级20)更正75 -> 有效值={} —— 判定: 撤回>更正>新增的固定层级",
                effective.get(0).value());
    }

    @Test
    void duplicateCorrectionDeliveryIsIdempotentAndAddsNoVersion() {
        IngestionService svc = newService();
        svc.ingest(bytes(addMessage("MSG-A7", "HIS_A", "P-C7", "72", T1)));
        svc.ingest(bytes(correctionMessage("MSG-C7", "HIS_A", "P-C7", "75", T1)));
        long versionAfterCorrection = svc.getPatient("P-C7").version();

        IngestionOutcome replay = svc.ingest(bytes(correctionMessage("MSG-C7", "HIS_A", "P-C7", "75", T1))).get(0);
        assertEquals(IngestionOutcome.Status.DUPLICATE, replay.status());
        assertEquals(versionAfterCorrection, svc.getPatient("P-C7").version(),
                "重复投递不得产生重复版本");
        assertEquals(1, svc.getPatient("P-C7").effectiveObservations().size());
        log.info("[更正重放] 更正消息重复投递 -> DUPLICATE，版本保持 {} —— 判定: MSH-10 消息级幂等",
                versionAfterCorrection);
    }

    @Test
    void crossBatchReorderAndRedeliveryConverge() {
        // 同一批消息：两个时刻的新增 + 时刻1的更正 + 时刻1的撤回（撤回晚于更正，终态）
        String add1 = addMessage("MSG-B1", "HIS_A", "P-C8", "72", T1);
        String add2 = addMessage("MSG-B2", "LAB_X", "P-C8", "80", "20260921100000+0800");
        String correct1 = correctionMessage("MSG-B3", "HIS_A", "P-C8", "75", T1);
        String withdraw1 = withdrawalMessage("MSG-B4", "HIS_A", "P-C8", T1);

        // 场景一：顺序投递
        IngestionService s1 = newService();
        for (String m : List.of(add1, add2, correct1, withdraw1)) {
            s1.ingest(bytes(m));
        }
        // 场景二：拆成不同批次 + 乱序 + 重复投递 + 失败后重投
        IngestionService s2 = newService();
        s2.ingest(bytes(correct1));                       // 更正先到：DEFERRED
        s2.ingest(bytes(withdraw1 + add2));               // 撤回先到：DEFERRED；add2 正常
        s2.ingest(bytes(add1));                           // add1 到达：更正生效但槽位已撤回
        s2.ingest(bytes(add1));                           // 重复投递：DUPLICATE
        s2.ingest(bytes(correct1 + withdraw1));           // 重投：DUPLICATE
        s2.ingest(bytes(add2));                           // 重投：DUPLICATE

        assertEquals(effectiveFingerprints(s1, "P-C8"), effectiveFingerprints(s2, "P-C8"),
                "拆批/乱序/重投/重试后最终有效集合必须一致");
        // 时刻1被撤回（终态），时刻2保留 80
        PatientRecord r = s2.getPatient("P-C8");
        assertEquals(1, r.effectiveObservations().size());
        assertEquals(80.0, r.effectiveObservations().get(0).value(), 1e-9);
        log.info("[跨批收敛] 顺序投递 vs 拆批乱序+重投，最终有效集合一致（仅时刻2=80）—— 判定: 事件集合纯函数 + 消息级幂等");
    }

    @Test
    void withdrawnSlotIsTerminal() {
        IngestionService svc = newService();
        svc.ingest(bytes(addMessage("MSG-A9", "HIS_A", "P-C9", "72", T1)));
        svc.ingest(bytes(withdrawalMessage("MSG-W9", "HIS_A", "P-C9", T1)));
        // 撤回后同一槽位再次普通新增：不得复活
        svc.ingest(bytes(addMessage("MSG-A9B", "HIS_A", "P-C9", "73", T1)));
        assertTrue(svc.getPatient("P-C9").effectiveObservations().isEmpty(),
                "撤回是槽位终态：同槽位后续普通新增不得复活");
        log.info("[撤回终态] 撤回后同槽位新增被压制 —— 判定: 如需重新上报应使用新的观测时刻");
    }
}
