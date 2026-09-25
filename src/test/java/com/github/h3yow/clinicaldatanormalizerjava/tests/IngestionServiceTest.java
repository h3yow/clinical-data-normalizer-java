package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.ingest.ConflictDecision;
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
 * 纵向合并测试：重复投递、乱序到达、失败重试、冲突裁决。
 */
class IngestionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(IngestionServiceTest.class);

    private IngestionService newService() {
        return new IngestionService(new ObservationNormalizer(new CodeCatalog(), new UnitCatalog()));
    }

    /** 构造一条单观测 ORU^R01。 */
    private String message(String controlId, String source, String patientId,
                           String value, String unit, String effectiveTime) {
        return "MSH|^~\\&|" + source + "|FAC||FAC|20260920100000+0800||ORU^R01|" + controlId
                + "|P|2.5.1\rPID|||" + patientId + "\rOBR|1||B-1|8867-4\r"
                + "OBX|1|NM|8867-4^^LN||" + value + "|" + unit + "|||||F|||" + effectiveTime + "\r";
    }

    private byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void duplicateDeliveryIsIdempotent() {
        IngestionService svc = newService();
        String msg = message("MSG-D1", "HIS_A", "P-0001", "72", "bpm", "20260920100000+0800");

        IngestionOutcome first = svc.ingest(bytes(msg)).get(0);
        IngestionOutcome second = svc.ingest(bytes(msg)).get(0);
        IngestionOutcome third = svc.ingest(bytes(msg)).get(0);

        assertEquals(IngestionOutcome.Status.ACCEPTED, first.status());
        assertEquals(1, first.acceptedCount());
        assertEquals(IngestionOutcome.Status.DUPLICATE, second.status());
        assertEquals(IngestionOutcome.Status.DUPLICATE, third.status());

        PatientRecord record = svc.getPatient("P-0001");
        assertEquals(1, record.observations().size(), "重放不得产生重复观测");
        log.info("[幂等重放] 同一消息投递 3 次：{} -> {} -> {}，最终观测数={} —— 判定依据: MSH-10 消息级去重 + 内容指纹",
                first.status(), second.status(), third.status(), record.observations().size());
    }

    @Test
    void outOfOrderDeliveryProducesSameFinalSet() {
        String m1 = message("MSG-LOW", "LAB_X", "P-0002", "70", "bpm", "20260920100000+0800");
        String m2 = message("MSG-HIGH", "HIS_A", "P-0002", "90", "bpm", "20260920100000+0800");

        List<Observation> orderAB = finalWinner(svc -> {
            svc.ingest(bytes(m1));
            svc.ingest(bytes(m2));
        });
        List<Observation> orderBA = finalWinner(svc -> {
            svc.ingest(bytes(m2));
            svc.ingest(bytes(m1));
        });

        assertEquals(1, orderAB.size());
        assertEquals(1, orderBA.size());
        assertEquals(orderAB.get(0).value(), orderBA.get(0).value(), 1e-9, "乱序到达结果必须一致");
        assertEquals(90.0, orderAB.get(0).value(), 1e-9, "高优先级来源 HIS_A 获胜");
        assertEquals("MSG-HIGH", orderAB.get(0).messageControlId());
        log.info("[乱序无关] 先低后高 vs 先高后低，最终获胜值均为 {} bpm（来源 HIS_A/MSG-HIGH）—— 判定依据: 来源优先级 {}>{}",
                orderAB.get(0).value(), "HIS_A", "LAB_X");
    }

    private List<Observation> finalWinner(java.util.function.Consumer<IngestionService> script) {
        IngestionService svc = newService();
        script.accept(svc);
        PatientRecord record = svc.getPatient("P-0002");
        return record.slotWinners().values().stream()
                .map(record::observation).toList();
    }

    @Test
    void sameContentFromDifferentMessagesDedupesByFingerprint() {
        // 不同控制号但内容完全相同（同患者/指标/时刻/值/单位）
        String a = message("MSG-C1", "HIS_A", "P-0003", "72", "bpm", "20260920100000+0800");
        String b = message("MSG-C2", "HIS_A", "P-0003", "72", "bpm", "20260920100000+0800");
        IngestionService svc = newService();
        IngestionOutcome o1 = svc.ingest(bytes(a)).get(0);
        IngestionOutcome o2 = svc.ingest(bytes(b)).get(0);
        assertEquals(IngestionOutcome.Status.ACCEPTED, o1.status());
        assertEquals(IngestionOutcome.Status.ACCEPTED, o2.status(), "不同消息不判 DUPLICATE");
        assertEquals(1, svc.getPatient("P-0003").observations().size(), "同内容观测按指纹去重为一条");
        log.info("[内容去重] 不同消息携带相同观测：第二次 acceptedCount={}，最终观测数=1 —— 判定依据: 内容指纹一致",
                o2.acceptedCount());
    }

    @Test
    void conflictIsRecordedAndNotSilentlyOverwritten() {
        IngestionService svc = newService();
        svc.ingest(bytes(message("MSG-W1", "LAB_X", "P-0004", "70", "bpm", "20260920100000+0800")));
        IngestionOutcome outcome = svc.ingest(
                bytes(message("MSG-W2", "HIS_A", "P-0004", "90", "bpm", "20260920100000+0800"))).get(0);

        assertEquals(1, outcome.conflictDecisions().size(), "覆盖必须留下裁决记录");
        ConflictDecision d = outcome.conflictDecisions().get(0);
        assertEquals("MSG-W2", d.winnerMessageControlId());
        assertEquals("MSG-W1", d.loserMessageControlId());
        assertTrue(d.reason().contains("来源优先级"), "裁决依据必须可追溯: " + d.reason());
        log.info("[冲突裁决] slot={} 胜者={}/{} 败者={}/{} 依据='{}' —— 判定: 冲突不静默覆盖",
                d.slotKey(), d.winnerSourceSystem(), d.winnerMessageControlId(),
                d.loserSourceSystem(), d.loserMessageControlId(), d.reason());
    }

    @Test
    void malformedAndSemanticallyInvalidMessagesAreRejectedWithReasons() {
        IngestionService svc = newService();

        // 解析失败：非 MSH 起始
        List<IngestionOutcome> parseFail = svc.ingest(bytes("XXX|junk\r"));
        assertEquals(IngestionOutcome.Status.REJECTED, parseFail.get(0).status());
        assertEquals("PARSE", parseFail.get(0).errorStage());
        assertEquals("UNEXPECTED_FRAME_START", parseFail.get(0).errorReason());

        // 归一化失败：未知单位
        List<IngestionOutcome> normFail = svc.ingest(
                bytes(message("MSG-BAD", "HIS_A", "P-0005", "72", "parsec", "20260920100000+0800")));
        assertEquals(IngestionOutcome.Status.REJECTED, normFail.get(0).status());
        assertEquals("NORMALIZE", normFail.get(0).errorStage());
        assertEquals("UNKNOWN_UNIT", normFail.get(0).errorReason());

        // 拒绝后重试一条合法消息，应正常入库
        List<IngestionOutcome> retry = svc.ingest(
                bytes(message("MSG-BAD", "HIS_A", "P-0005", "72", "bpm", "20260920100000+0800")));
        assertEquals(IngestionOutcome.Status.ACCEPTED, retry.get(0).status());
        assertEquals(1, svc.getPatient("P-0005").observations().size());
        log.info("[失败重试] PARSE:{} / NORMALIZE:{} 均被分类拒绝且不落库；同控制号修正后重投 -> {} —— 判定依据: 异常前无状态写入",
                parseFail.get(0).errorReason(), normFail.get(0).errorReason(), retry.get(0).status());
    }
}
