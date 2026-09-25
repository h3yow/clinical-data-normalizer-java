package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Parser;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.CodeCatalog;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.NormalizationErrorReason;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.NormalizationException;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.ObservationNormalizer;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.UnitCatalog;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 归一化层测试：单位换算、重复值、非法数值/单位/结构的分类拒绝，
 * 日志中打印归一化结果与判定依据。
 */
class ObservationNormalizerTest {

    private static final Logger log = LoggerFactory.getLogger(ObservationNormalizerTest.class);

    private final Hl7Parser parser = new Hl7Parser();
    private final ObservationNormalizer normalizer =
            new ObservationNormalizer(new CodeCatalog(), new UnitCatalog());

    private String base(String obx) {
        return "MSH|^~\\&|HIS_A|FAC||FAC|20260920100000+0800||ORU^R01|MSG-N1|P|2.5.1|||||||UTF-8\r"
                + "PID|||P-0001||Doe^John\rOBR|1||B-1|8867-4|||20260920100000+0800\r" + obx + "\r";
    }

    private List<Observation> norm(String text) {
        Hl7Message msg = parser.parseBatch(text).get(0);
        return normalizer.normalize(msg);
    }

    private void assertReason(NormalizationErrorReason reason, String text) {
        Hl7Message msg = parser.parseBatch(text).get(0);
        NormalizationException ex = assertThrows(NormalizationException.class,
                () -> normalizer.normalize(msg));
        assertEquals(reason, ex.getReason());
        log.info("[语义拒绝] 原因={} —— {} —— 判定依据: 不使用默认值兜底，按可区分原因拒绝",
                ex.getReason(), ex.getMessage());
    }

    @Test
    void normalizesWithUnitConversionAndRepeatedValues() {
        String text = base("OBX|1|NM|2345-7^Glucose^LN||1000~500|mg/L|||||F|||20260920100000+0800");
        List<Observation> obs = norm(text);
        assertEquals(2, obs.size(), "OBX-5 的每个重复各产生一条观测");
        assertEquals(100.0, obs.get(0).value(), 1e-9, "mg/L -> mg/dL 除以 10");
        assertEquals(50.0, obs.get(1).value(), 1e-9);
        assertEquals("mg/dL", obs.get(0).unit());
        assertEquals("laboratory", obs.get(0).category());
        for (Observation o : obs) {
            log.info("[归一化结果] patient={} code={}({}) value={} unit={} time={} source={} msg={} fp={}",
                    o.patientId(), o.code(), o.displayName(), o.value(), o.unit(), o.effectiveTime(),
                    o.sourceSystem(), o.messageControlId(), o.fingerprint().substring(0, 12));
        }
        log.info("[判定依据] 来源单位 mg/L 命中单位目录 factor=0.1；两个重复值分别归一；指纹内容一致才可幂等去重");
    }

    @Test
    void convertsTemperaturePressureAndMmol() {
        // 华氏度 98.6 -> 37 C
        Observation temp = norm(base(
                "OBX|1|NM|8310-5^Temp^LN||98.6|degF|||||F|||20260920100000+0800")).get(0);
        assertEquals(37.0, temp.value(), 1e-6, "98.6 degF -> 37 Cel");
        assertEquals("Cel", temp.unit());

        // 10 kPa -> 75.006 mmHg
        Observation bp = norm(base(
                "OBX|1|NM|8480-6^SBP^LN||10|kPa|||||F|||20260920100000+0800")).get(0);
        assertEquals(75.00616827, bp.value(), 1e-6);
        assertEquals("mm[Hg]", bp.unit());

        // 5.0 mmol/L 葡萄糖 -> 90.078 mg/dL
        Observation glucose = norm(base(
                "OBX|1|NM|2345-7^Glucose^LN||5.0|mmol/L|||||F|||20260920100000+0800")).get(0);
        assertEquals(90.078, glucose.value(), 1e-6);
        log.info("[单位换算] 98.6degF={}Cel, 10kPa={}mmHg, 5.0mmol/L={}mg/dL —— 判定: 仿射/倍率换算正确",
                temp.value(), bp.value(), glucose.value());
    }

    @Test
    void mapsLocalSourceCodeToLoinc() {
        String text = "MSH|^~\\&|HIS_A|FAC||FAC|20260920100000+0800||ORU^R01|MSG-LC|P|2.5.1\r"
                + "PID|||P-9\rOBR|1||B|HR\rOBX|1|NM|HR^^LOCAL||72|bpm|||||F|||20260920100000+0800\r";
        Observation o = norm(text).get(0);
        assertEquals("8867-4", o.code(), "HIS_A 的本地码 HR 归一到 LOINC 8867-4");
        assertEquals("Heart rate", o.displayName());
        log.info("[编码映射] HIS_A/LOCAL:HR -> LOINC {}({}) —— 判定依据: 来源特定映射优先", o.code(), o.displayName());
    }

    @Test
    void rejectsEachErrorReasonDistinguishably() {
        assertReason(NormalizationErrorReason.UNSUPPORTED_MESSAGE_TYPE, base(
                "OBX|1|NM|8867-4||72|bpm|||||F").replace("ORU^R01", "ADT^A01"));
        assertReason(NormalizationErrorReason.MISSING_MESSAGE_CONTROL_ID, base(
                "OBX|1|NM|8867-4||72|bpm|||||F").replace("MSG-N1", ""));
        assertReason(NormalizationErrorReason.MISSING_PATIENT_ID,
                base("OBX|1|NM|8867-4||72|bpm|||||F").replace("PID|||P-0001|", "PID|||"));
        assertReason(NormalizationErrorReason.MISSING_OBR,
                base("OBX|1|NM|8867-4||72|bpm|||||F").replace("OBR|1||B-1|8867-4|||20260920100000+0800\r", ""));
        assertReason(NormalizationErrorReason.MISSING_OBX,
                base("OBX|1|NM|8867-4||72|bpm|||||F").replace("OBX|1|NM|8867-4||72|bpm|||||F\r", ""));
        assertReason(NormalizationErrorReason.UNSUPPORTED_OBSERVATION_VALUE_TYPE,
                base("OBX|1|ST|8867-4||72|bpm|||||F"));
        assertReason(NormalizationErrorReason.UNKNOWN_OBSERVATION_CODE,
                base("OBX|1|NM|99999-9^X^LN||72|bpm|||||F"));
        assertReason(NormalizationErrorReason.MISSING_OBSERVATION_VALUE,
                base("OBX|1|NM|8867-4|||bpm|||||F"));
        assertReason(NormalizationErrorReason.NON_NUMERIC_OBSERVATION_VALUE,
                base("OBX|1|NM|8867-4||abc|bpm|||||F"));
        assertReason(NormalizationErrorReason.NON_NUMERIC_OBSERVATION_VALUE,
                base("OBX|1|NM|8867-4||NaN|bpm|||||F"));
        assertReason(NormalizationErrorReason.MISSING_UNIT,
                base("OBX|1|NM|8867-4||72||||||F"));
        assertReason(NormalizationErrorReason.UNKNOWN_UNIT,
                base("OBX|1|NM|8867-4||72|furlongs/fortnight|||||F"));
        assertReason(NormalizationErrorReason.INVALID_OBSERVATION_STATUS,
                base("OBX|1|NM|8867-4||72|bpm||||X"));
        assertReason(NormalizationErrorReason.INVALID_DATETIME,
                base("OBX|1|NM|8867-4||72|bpm|||||F|||2026-09-20"));
        assertReason(NormalizationErrorReason.VALUE_OUT_OF_RANGE,
                base("OBX|1|NM|8867-4||1E400|bpm|||||F"));
        assertReason(NormalizationErrorReason.MISSING_SENDING_APPLICATION,
                base("OBX|1|NM|8867-4||72|bpm|||||F")
                        .replace("MSH|^~\\&|HIS_A|FAC|", "MSH|^~\\&||FAC|"));
    }

    @Test
    void fallsBackEffectiveTimeToObrThenMsh() {
        String noObx14 = "MSH|^~\\&|HIS_A|||FAC|20260918090000+0000||ORU^R01|MSG-FT|P|2.5.1\r"
                + "PID|||P-2\rOBR|1||B|8867-4|||20260919103000+0000\rOBX|1|NM|8867-4||70|bpm|||||F\r";
        Observation o = norm(noObx14).get(0);
        assertEquals("2026-09-19T10:30:00Z", o.effectiveTime().toString(), "OBX-14 缺省回退 OBR-7");

        String noObxObr = noObx14.replace("OBR|1||B|8867-4|||20260919103000+0000", "OBR|1||B|8867-4");
        Observation o2 = norm(noObxObr).get(0);
        assertEquals("2026-09-18T09:00:00Z", o2.effectiveTime().toString(), "OBR-7 也缺省时回退 MSH-7");
        log.info("[时间回退] OBX-14->OBR-7->MSH-7 依次回退 —— 判定: 生效时间确定且不使用猜测值");
    }
}
