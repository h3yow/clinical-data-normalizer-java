package com.github.h3yow.clinicaldatanormalizerjava.tests;

import com.github.h3yow.clinicaldatanormalizerjava.model.ConflictDecision;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import com.github.h3yow.clinicaldatanormalizerjava.store.ObservationStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObservationStoreTest {

    private static final Logger log = LoggerFactory.getLogger(ObservationStoreTest.class);
    private static final Instant T = Instant.parse("2024-01-01T12:00:00Z");

    private static Observation obs(String patient, String code, String value,
                                   String source, String msgId, String obxSet) {
        return new Observation(patient, code, new BigDecimal(value), "mmol/L", T,
                source, msgId, msgId + "#OBX-" + obxSet);
    }

    @Test
    void duplicateDeliveryIsIdempotent() {
        ObservationStore store = new ObservationStore();
        Observation o = obs("P1", "2345-7", "5.5", "LAB", "M1", "1");
        store.merge(List.of(o));
        // 同一消息重复投递 + 失败重试
        List<ConflictDecision> d2 = store.merge(List.of(o));
        List<ConflictDecision> d3 = store.merge(List.of(o, o));

        List<Observation> result = store.observationsOf("P1");
        log.info("after duplicate deliveries: size={} (expect 1), extra conflicts={}",
                result.size(), d2.size() + d3.size());
        assertEquals(1, result.size());
        assertTrue(d2.isEmpty() && d3.isEmpty());
        assertEquals(1, store.version());
    }

    @Test
    void conflictResolutionIsDeterministicRegardlessOfOrder() {
        // 同一患者同一时刻同一指标，两个来源给出不同值
        Observation fromLab = obs("P1", "2345-7", "5.5", "LAB", "M1", "1");
        Observation fromDevice = obs("P1", "2345-7", "6.1", "DEVICE", "M2", "1");

        ObservationStore s1 = new ObservationStore();
        s1.merge(List.of(fromLab));
        List<ConflictDecision> d1 = s1.merge(List.of(fromDevice));

        ObservationStore s2 = new ObservationStore();
        s2.merge(List.of(fromDevice));
        List<ConflictDecision> d2 = s2.merge(List.of(fromLab));

        Observation w1 = s1.observationsOf("P1").get(0);
        Observation w2 = s2.observationsOf("P1").get(0);
        log.info("order LAB->DEVICE winner={} rule={}", w1.observationKey(), d1.get(0).rule());
        log.info("order DEVICE->LAB winner={} rule={}", w2.observationKey(), d2.get(0).rule());

        // LAB 优先级(30) > DEVICE(10)，两种顺序下胜者必须一致
        assertEquals(w1.observationKey(), w2.observationKey());
        assertEquals("M1#OBX-1", w1.observationKey());
        assertEquals(1, s1.observationsOf("P1").size());
        assertEquals(1, s2.observationsOf("P1").size());
        // 冲突可追溯：裁决记录含胜负双方与规则
        assertEquals(1, d1.size());
        assertEquals("SOURCE_PRIORITY(LAB=30 > 10)", d1.get(0).rule());
        assertEquals("M2#OBX-1", d1.get(0).loser().observationKey());
    }

    @Test
    void samePriorityTieBreakByObservationKey() {
        Observation a = obs("P1", "2345-7", "5.5", "LAB", "M1", "1");
        Observation b = obs("P1", "2345-7", "5.6", "LAB", "M9", "1");

        ObservationStore s1 = new ObservationStore();
        s1.merge(List.of(a, b));
        ObservationStore s2 = new ObservationStore();
        s2.merge(List.of(b, a));

        String w1 = s1.observationsOf("P1").get(0).observationKey();
        String w2 = s2.observationsOf("P1").get(0).observationKey();
        log.info("tie-break winners: {} vs {} (rule={})",
                w1, w2, s1.conflictLog().get(0).rule());
        assertEquals(w1, w2);
        assertEquals("M9#OBX-1", w1); // 字典序大者胜
        assertTrue(s1.conflictLog().get(0).rule().startsWith("TIE_BREAK_BY_OBSERVATION_KEY"));
    }

    @Test
    void outOfOrderAndRetryConvergeToSameSet() {
        Observation o1 = obs("P1", "2345-7", "5.5", "LAB", "M1", "1");
        Observation o2 = new Observation("P1", "8480-6", new BigDecimal("120"), "mm[Hg]",
                Instant.parse("2024-01-01T11:00:00Z"), "HIS", "M2", "M2#OBX-1");
        Observation o3 = obs("P1", "2345-7", "5.5", "LAB", "M3", "1"); // 与 o1 同键不同消息? 同时刻同指标
        // o3 与 o1 冲突键相同但 observationKey 不同 -> 触发裁决

        ObservationStore s1 = new ObservationStore();
        s1.merge(List.of(o1, o2));
        s1.merge(List.of(o3));
        s1.merge(List.of(o1)); // 重试

        ObservationStore s2 = new ObservationStore();
        s2.merge(List.of(o3));
        s2.merge(List.of(o2, o1, o1)); // 乱序 + 重复

        List<Observation> r1 = s1.observationsOf("P1");
        List<Observation> r2 = s2.observationsOf("P1");
        log.info("converged set sizes: {} vs {}", r1.size(), r2.size());
        r1.forEach(o -> log.info("s1 final: {} = {}", o.observationKey(), o.value()));
        r2.forEach(o -> log.info("s2 final: {} = {}", o.observationKey(), o.value()));
        assertEquals(r1.size(), r2.size());
        for (int i = 0; i < r1.size(); i++) {
            assertEquals(r1.get(i).observationKey(), r2.get(i).observationKey());
        }
    }
}
