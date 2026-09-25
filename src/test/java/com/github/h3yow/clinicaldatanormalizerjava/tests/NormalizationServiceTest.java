package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7ParseException;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Parser;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.RejectionReason;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.CodeMapper;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.NormalizationService;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.UnitNormalizer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NormalizationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(NormalizationServiceTest.class);

    private final Hl7Parser parser = new Hl7Parser();
    private final NormalizationService service =
            new NormalizationService(new CodeMapper(), new UnitNormalizer());

    private static String msg(String obx) {
        return "MSH|^~\\&|LAB|L|HIS|H|20240101120000||ORU^R01|MSG100|P|2.5\r"
                + "PID|1||P123^^^HIS^MR||Doe^John\r" + obx;
    }

    @Test
    void normalizesAndConvertsUnits() {
        Hl7Message m = parser.parse(msg(
                "OBX|1|NM|GLU^Glucose^L|1|99|mg/dL|||||F|||20240101113000\r"
                        + "OBX|2|NM|SBP^Systolic^L|1|120|mm[Hg]|||||F|||20240101120000\r"
                        + "OBX|3|NM|TEMP^Temp^L|1|98.6|[degF]|||||F|||20240101120000"));
        List<Observation> obs = service.normalize(m);
        assertEquals(3, obs.size());
        for (Observation o : obs) {
            log.info("normalized: key={} patient={} code={} value={} unit={} time={} src={}",
                    o.observationKey(), o.patientId(), o.code(),
                    o.value().toPlainString(), o.unit(), o.effectiveTime(), o.sourceId());
        }
        Observation glu = obs.get(0);
        assertEquals("2345-7", glu.code());
        assertEquals("mmol/L", glu.unit());
        // 99 mg/dL / 18.016 = 5.4951154... -> 6 位小数 HALF_UP
        assertEquals(0, glu.value().compareTo(new BigDecimal("5.495115")));
        assertEquals("MSG100#OBX-1", glu.observationKey());

        assertEquals("8480-6", obs.get(1).code());
        assertEquals(0, obs.get(1).value().compareTo(new BigDecimal("120")));

        // 98.6 F -> 37 C
        assertEquals("Cel", obs.get(2).unit());
        assertEquals(0, obs.get(2).value().compareTo(new BigDecimal("37")));
    }

    @Test
    void rejectsUnknownUnit() {
        Hl7Message m = parser.parse(msg(
                "OBX|1|NM|GLU^Glucose^L|1|5.5|furlong/fortnight|||||F|||20240101113000"));
        Hl7ParseException e = assertThrows(Hl7ParseException.class, () -> service.normalize(m));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.UNKNOWN_UNIT, e.getReason());
    }

    @Test
    void rejectsNonNumericValue() {
        Hl7Message m = parser.parse(msg(
                "OBX|1|NM|GLU^Glucose^L|1|five-point-five|mmol/L|||||F|||20240101113000"));
        Hl7ParseException e = assertThrows(Hl7ParseException.class, () -> service.normalize(m));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.INVALID_VALUE, e.getReason());
    }

    @Test
    void rejectsUnsupportedValueType() {
        Hl7Message m = parser.parse(msg(
                "OBX|1|ST|GLU^Glucose^L|1|5.5|mmol/L|||||F|||20240101113000"));
        Hl7ParseException e = assertThrows(Hl7ParseException.class, () -> service.normalize(m));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.INVALID_VALUE, e.getReason());
    }

    @Test
    void rejectsUnmappableCode() {
        Hl7Message m = parser.parse(msg(
                "OBX|1|NM|ZZZ^Unknown^L|1|5.5|mmol/L|||||F|||20240101113000"));
        Hl7ParseException e = assertThrows(Hl7ParseException.class, () -> service.normalize(m));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.UNMAPPABLE_CODE, e.getReason());
    }

    @Test
    void rejectsMissingRequiredFields() {
        // 缺 OBX-14 时间
        Hl7Message m = parser.parse(msg("OBX|1|NM|GLU^Glucose^L|1|5.5|mmol/L"));
        Hl7ParseException e = assertThrows(Hl7ParseException.class, () -> service.normalize(m));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.MALFORMED_STRUCTURE, e.getReason());
    }

    @Test
    void rejectsUnsupportedMessageType() {
        Hl7Message m = parser.parse(
                "MSH|^~\\&|LAB|L|HIS|H|20240101120000||ADT^A01|MSG101|P|2.5\r"
                        + "PID|1||P123^^^HIS^MR");
        Hl7ParseException e = assertThrows(Hl7ParseException.class, () -> service.normalize(m));
        log.info("rejected reason={} detail={}", e.getReason(), e.getMessage());
        assertEquals(RejectionReason.UNSUPPORTED_MESSAGE, e.getReason());
    }
}
