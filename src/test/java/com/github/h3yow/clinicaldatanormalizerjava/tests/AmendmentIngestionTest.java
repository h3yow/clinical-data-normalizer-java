package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.ingest.AmendmentDecision;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionOutcome;
import com.github.h3yow.clinicaldatanormalizerjava.ingest.IngestionService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结果更正与撤回测试：替换/移除当前有效值、无目标的可区分拒绝、
 * 跨批次乱序 + 重试收敛、重复投递不产生重复版本、与冲突裁决的一致取舍。
 */
class AmendmentIngestionTest {

    private static final Logger log = LoggerFactory.getLogger(AmendmentIngestionTest.class);
    private static final String TIME = "20260920100000+0800";

    private IngestionService newService() {
        return new IngestionService(new ObservationNormalizer(new CodeCatalog(), new UnitCatalog()));
    }

    /** 普通最终结果（OBX-11=F）。 */
    private String finalMsg(String controlId, String source, String patientId, String value) {
        return "MSH|^~\\&|" + source + "|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId
                + "|P|2.5.1\rPID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||" + value + "|bpm|||||F|||" + TIME + "\r";
    }

    /** 更正声明（OBX-11=C）。 */
    private String correctionMsg(String controlId, String source, String patientId, String value) {
        return "MSH|^~\\&|" + source + "|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId
                + "|P|2.5.1\rPID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||" + value + "|bpm|||||C|||" + TIME + "\r";
    }

    /** 撤回声明（OBX-11=D，无需 OBX-5/6）。 */
    private String withdrawalMsg(String controlId, String source, String patientId) {
        return "MSH|^~\\&|" + source + "|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId
                + "|P|2.5.1\rPID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||||||||D|||" + TIME + "\r";
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private List<Observation> effective(IngestionService svc, String patientId) {
        return svc.getPatient(patientId).effectiveObservations();
    }

    @Test
    void correctionReplacesCurrentEffectiveValue() {
        IngestionService svc = newService();
        svc.ingest(bytes(finalMsg("MSG-A1", "HIS_A", "P-0001", "72")));
        IngestionOutcome outcome = svc.ingest(bytes(correctionMsg("MSG-A2", "HIS_A", "P-0001", "90"))).get(0);

        assertEquals(IngestionOutcome.Status.ACCEPTED, outcome.status());
        assertEquals(1, outcome.amendmentDecisions().size());
        AmendmentDecision d = outcome.amendmentDecisions().get(0);
        assertEquals(AmendmentDecision.AmendmentType.CORRECTION, d.type());
        assertEquals(AmendmentDecision.AmendmentOutcome.APPLIED, d.outcome());

        List<Observation> eff = effective(svc, "P-0001");
        assertEquals(1, eff.size(), "同一槽位只能保留一条有效观测");
        assertEquals(90.0, eff.get(0).value(), 1e-9, "更正值替换当前有效值");
        assertEquals(2, svc.getPatient("P-0001").version(), "新增+更正各产生一个版本");
        log.info("[结果更正] slot={} 旧指纹={} -> 新指纹={} 当前有效值=90 —— 判定依据: {}",
                d.slotKey(), d.previousFingerprint().substring(0, 8),
                d.newFingerprint().substring(0, 8), d.reason());
    }

    @Test
    void correctionDoesNotCompeteOnSourcePriority() {
        IngestionService svc = newService();
        // 当前有效值来自高优先级来源 HIS_A；低优先级来源 LAB_X 的更正仍然生效，
        // 因为更正针对槽位本身，不是竞争性观测
        svc.ingest(bytes(finalMsg("MSG-P1", "HIS_A", "P-0002", "72")));
        IngestionOutcome outcome = svc.ingest(bytes(correctionMsg("MSG-P2", "LAB_X", "P-0002", "66"))).get(0);

        assertEquals(IngestionOutcome.Status.ACCEPTED, outcome.status());
        assertEquals(66.0, effective(svc, "P-0002").get(0).value(), 1e-9);
        log.info("[更正取舍] LAB_X 的更正覆盖 HIS_A 的当前值 -> 66 —— 判定依据: 更正针对槽位本身，不参与来源优先级比较");
    }

    @Test
    void correctionWithSameValueIsAcceptedWithoutVersionBump() {
        IngestionService svc = newService();
        svc.ingest(bytes(finalMsg("MSG-S1", "HIS_A", "P-0003", "72")));
        IngestionOutcome outcome = svc.ingest(bytes(correctionMsg("MSG-S2", "HIS_A", "P-0003", "72"))).get(0);

        assertEquals(IngestionOutcome.Status.ACCEPTED, outcome.status());
        assertEquals(AmendmentDecision.AmendmentOutcome.ALREADY_CURRENT,
                outcome.amendmentDecisions().get(0).outcome());
        assertEquals(1, svc.getPatient("P-0003").version(), "更正值与当前一致不产生新版本");
        log.info("[更正幂等] 更正值=当前值 -> ALREADY_CURRENT，版本保持 {} —— 判定依据: 有效集合未变化",
                svc.getPatient("P-0003").version());
    }

    @Test
    void withdrawalRemovesObservationFromEffectiveSet() {
        IngestionService svc = newService();
        svc.ingest(bytes(finalMsg("MSG-W1", "HIS_A", "P-0004", "72")));
        IngestionOutcome outcome = svc.ingest(bytes(withdrawalMsg("MSG-W2", "HIS_A", "P-0004"))).get(0);

        assertEquals(IngestionOutcome.Status.ACCEPTED, outcome.status());
        assertEquals(AmendmentDecision.AmendmentType.WITHDRAWAL,
                outcome.amendmentDecisions().get(0).type());
        assertTrue(effective(svc, "P-0004").isEmpty(), "撤回后当前有效集合为空");
        assertEquals(2, svc.getPatient("P-0004").version());
        // 历史值仍保留在内容库供审计，但不在有效集合中
        assertEquals(1, svc.getPatient("P-0004").observations().size());
        log.info("[结果撤回] 槽位有效观测已移除，版本=2，内容库保留历史值供审计 —— 判定依据: {}",
                outcome.amendmentDecisions().get(0).reason());
    }

    @Test
    void correctionWithoutTargetIsRejectedDistinguishablyAndRetryable() {
        IngestionService svc = newService();
        IngestionOutcome rejected = svc.ingest(bytes(correctionMsg("MSG-C1", "HIS_A", "P-0005", "90"))).get(0);

        assertEquals(IngestionOutcome.Status.REJECTED, rejected.status());
        assertEquals("MERGE", rejected.errorStage());
        assertEquals("CORRECTION_TARGET_NOT_FOUND", rejected.errorReason());
        assertNull(svc.getPatient("P-0005"), "拒绝的消息不落任何状态（患者记录都不创建）");

        // 目标到达后重投同一控制号：不是 DUPLICATE，正常应用
        svc.ingest(bytes(finalMsg("MSG-C0", "HIS_A", "P-0005", "72")));
        IngestionOutcome retry = svc.ingest(bytes(correctionMsg("MSG-C1", "HIS_A", "P-0005", "90"))).get(0);
        assertEquals(IngestionOutcome.Status.ACCEPTED, retry.status());
        assertEquals(90.0, effective(svc, "P-0005").get(0).value(), 1e-9);
        log.info("[无目标更正] 首次 -> REJECTED/MERGE/CORRECTION_TARGET_NOT_FOUND（可区分、不静默新增）；"
                + "目标到达后重投 -> {}，当前有效值=90 —— 判定依据: 拒绝前无状态写入，可安全重试", retry.status());
    }

    @Test
    void withdrawalWithoutTargetIsRejectedDistinguishably() {
        IngestionService svc = newService();
        IngestionOutcome noTarget = svc.ingest(bytes(withdrawalMsg("MSG-D1", "HIS_A", "P-0006"))).get(0);
        assertEquals(IngestionOutcome.Status.REJECTED, noTarget.status());
        assertEquals("MERGE", noTarget.errorStage());
        assertEquals("WITHDRAWAL_TARGET_NOT_FOUND", noTarget.errorReason());

        // 已撤回的槽位再次撤回（不同控制号）：同样可区分拒绝
        svc.ingest(bytes(finalMsg("MSG-D2", "HIS_A", "P-0006", "72")));
        svc.ingest(bytes(withdrawalMsg("MSG-D3", "HIS_A", "P-0006")));
        IngestionOutcome again = svc.ingest(bytes(withdrawalMsg("MSG-D4", "HIS_A", "P-0006"))).get(0);
        assertEquals(IngestionOutcome.Status.REJECTED, again.status());
        assertEquals("WITHDRAWAL_TARGET_NOT_FOUND", again.errorReason());
        log.info("[无目标撤回] 空槽位/已撤回槽位的撤回 -> REJECTED/MERGE/WITHDRAWAL_TARGET_NOT_FOUND "
                + "—— 判定依据: 当前有效集合中无可撤回目标，不静默忽略");
    }

    @Test
    void crossBatchOutOfOrderWithRetryConvergesToSameFinalSet() {
        String f = finalMsg("MSG-O1", "HIS_A", "P-0007", "72");
        String c = correctionMsg("MSG-O2", "HIS_A", "P-0007", "90");
        String d = withdrawalMsg("MSG-O3", "HIS_A", "P-0007");

        // 顺序一：F -> C -> D
        IngestionService svcA = newService();
        svcA.ingest(bytes(f));
        svcA.ingest(bytes(c));
        svcA.ingest(bytes(d));

        // 顺序二：D -> C -> F（D、C 先被拒，目标到达后重投收敛）
        IngestionService svcB = newService();
        assertEquals("WITHDRAWAL_TARGET_NOT_FOUND", svcB.ingest(bytes(d)).get(0).errorReason());
        assertEquals("CORRECTION_TARGET_NOT_FOUND", svcB.ingest(bytes(c)).get(0).errorReason());
        svcB.ingest(bytes(f));
        svcB.ingest(bytes(c)); // 重投更正
        svcB.ingest(bytes(d)); // 重投撤回

        assertEquals(effective(svcA, "P-0007"), effective(svcB, "P-0007"),
                "乱序 + 重试后最终有效集合必须一致");
        assertTrue(effective(svcB, "P-0007").isEmpty());
        assertEquals(svcA.getPatient("P-0007").version(), svcB.getPatient("P-0007").version(),
                "版本序列一致（新增->更正->撤回各一版）");
        log.info("[乱序收敛] F/C/D 两种投递顺序，经拒绝+重投后有效集合与版本数一致（v={}）—— 判定依据: "
                + "无目标拒绝不落状态，重投后按相同规则应用", svcA.getPatient("P-0007").version());
    }

    @Test
    void duplicateDeliveryOfAmendmentsCreatesNoExtraVersions() {
        IngestionService svc = newService();
        String f = finalMsg("MSG-Q1", "HIS_A", "P-0008", "72");
        String c = correctionMsg("MSG-Q2", "HIS_A", "P-0008", "90");
        String d = withdrawalMsg("MSG-Q3", "HIS_A", "P-0008");
        svc.ingest(bytes(f));
        svc.ingest(bytes(c));
        svc.ingest(bytes(d));

        assertEquals(IngestionOutcome.Status.DUPLICATE, svc.ingest(bytes(c)).get(0).status());
        assertEquals(IngestionOutcome.Status.DUPLICATE, svc.ingest(bytes(d)).get(0).status());
        assertEquals(IngestionOutcome.Status.DUPLICATE, svc.ingest(bytes(f)).get(0).status());
        assertEquals(3, svc.getPatient("P-0008").version(), "重复投递不得产生重复版本，版本号不回退");
        log.info("[重投幂等] 更正/撤回重复投递均 DUPLICATE，版本={} 不变 —— 判定依据: MSH-10 消息级去重",
                svc.getPatient("P-0008").version());
    }

    @Test
    void withdrawalWinsOverCorrectionInSameMessage() {
        IngestionService svc = newService();
        svc.ingest(bytes(finalMsg("MSG-M1", "HIS_A", "P-0009", "72")));
        // 同一消息同一槽位：一条更正 + 一条撤回 -> 撤回优先（与段顺序无关）
        String mixed = "MSH|^~\\&|HIS_A|FAC||FAC|20260920100000+0800||ORU^R01|MSG-M2|P|2.5.1\r"
                + "PID|||P-0009\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||90|bpm|||||C|||" + TIME + "\r"
                + "OBX|2|NM|8867-4^^LN||||||||D|||" + TIME + "\r";
        IngestionOutcome outcome = svc.ingest(bytes(mixed)).get(0);
        assertEquals(IngestionOutcome.Status.ACCEPTED, outcome.status());
        assertTrue(effective(svc, "P-0009").isEmpty(), "同消息同槽位撤回优先于更正");
        log.info("[混合声明] 同消息同槽位 C+D -> 撤回生效 —— 判定依据: 撤回 > 更正 > 最终结果，与段顺序无关");
    }

    @Test
    void finalAfterWithdrawalIsFreshAddition() {
        IngestionService svc = newService();
        svc.ingest(bytes(finalMsg("MSG-R1", "HIS_A", "P-0010", "72")));
        svc.ingest(bytes(withdrawalMsg("MSG-R2", "HIS_A", "P-0010")));
        IngestionOutcome readd = svc.ingest(bytes(finalMsg("MSG-R3", "LAB_X", "P-0010", "80"))).get(0);

        assertEquals(IngestionOutcome.Status.ACCEPTED, readd.status());
        assertEquals(80.0, effective(svc, "P-0010").get(0).value(), 1e-9);
        assertEquals(3, svc.getPatient("P-0010").version(), "撤回后新最终结果视为新增，产生新版本");
        log.info("[撤回后新增] 撤回槽位收到新最终结果 -> 作为新增加入有效集合，版本=3 —— 判定依据: 槽位已空，按普通新增处理");
    }
}
