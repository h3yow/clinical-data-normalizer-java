package com.github.h3yow.clinicaldatanormalizerjava.store;

import com.github.h3yow.clinicaldatanormalizerjava.model.ConflictDecision;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 患者纵向观测存储。
 *
 * 幂等与乱序无关的保证：
 * - 观测级幂等键 observationKey = 消息控制ID + OBX set id。
 *   同一消息重复投递/失败重试时，键相同且内容相同 -> 直接忽略（无重复观测）；
 * - 冲突键 conflictKey = 患者 + LOINC 代码 + 观测时刻。同一冲突键下只允许
 *   一条观测胜出，裁决规则是全序的，因此与投递顺序无关：
 *     1) 来源优先级高者胜（SOURCE_PRIORITY，未知来源为 0）；
 *     2) 优先级相同，observationKey 字典序大者胜（打破对称、保证确定）；
 * - 每次裁决都记录 ConflictDecision（含胜/负双方与规则），不静默覆盖；
 * - version 仅在存储内容真实变化时递增，用于导出摘要的可复现性。
 */
@Repository
public class ObservationStore {

    /** 来源系统优先级，数值越大越可信（可在配置中扩展） */
    private static final Map<String, Integer> SOURCE_PRIORITY = Map.of(
            "LAB", 30,
            "HIS", 20,
            "DEVICE", 10
    );

    private final Map<String, Observation> byConflictKey = new ConcurrentHashMap<>();
    private final List<ConflictDecision> conflictLog =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile long version = 0;

    public static String conflictKeyOf(Observation o) {
        return o.patientId() + "|" + o.code() + "|" + o.effectiveTime();
    }

    /** 幂等合并一批观测；返回本次新产生的冲突裁决记录 */
    public synchronized List<ConflictDecision> merge(List<Observation> observations) {
        List<ConflictDecision> decisions = new ArrayList<>();
        // 批内先按键排序，保证批处理本身也与顺序无关
        List<Observation> sorted = observations.stream()
                .sorted(Comparator.comparing(Observation::observationKey))
                .toList();
        for (Observation incoming : sorted) {
            String key = conflictKeyOf(incoming);
            Observation existing = byConflictKey.get(key);
            if (existing == null) {
                byConflictKey.put(key, incoming);
                version++;
                continue;
            }
            if (sameContent(existing, incoming)) {
                continue; // 重复投递：幂等忽略
            }
            Observation winner = pick(existing, incoming);
            Observation loser = winner == existing ? incoming : existing;
            String rule = ruleOf(existing, incoming, winner);
            decisions.add(new ConflictDecision(key, winner, loser, rule));
            if (winner != existing) {
                byConflictKey.put(key, incoming);
                version++;
            }
        }
        conflictLog.addAll(decisions);
        return decisions;
    }

    private static Observation pick(Observation a, Observation b) {
        int pa = SOURCE_PRIORITY.getOrDefault(a.sourceId(), 0);
        int pb = SOURCE_PRIORITY.getOrDefault(b.sourceId(), 0);
        if (pa != pb) {
            return pa > pb ? a : b;
        }
        return a.observationKey().compareTo(b.observationKey()) >= 0 ? a : b;
    }

    private static String ruleOf(Observation a, Observation b, Observation winner) {
        int pa = SOURCE_PRIORITY.getOrDefault(a.sourceId(), 0);
        int pb = SOURCE_PRIORITY.getOrDefault(b.sourceId(), 0);
        if (pa != pb) {
            return "SOURCE_PRIORITY(" + winner.sourceId() + "=" + Math.max(pa, pb)
                    + " > " + Math.min(pa, pb) + ")";
        }
        return "TIE_BREAK_BY_OBSERVATION_KEY(winner=" + winner.observationKey() + ")";
    }

    private static boolean sameContent(Observation a, Observation b) {
        return a.observationKey().equals(b.observationKey())
                && a.value().compareTo(b.value()) == 0
                && a.unit().equals(b.unit())
                && a.sourceId().equals(b.sourceId());
    }

    public List<Observation> observationsOf(String patientId) {
        return byConflictKey.values().stream()
                .filter(o -> o.patientId().equals(patientId))
                .sorted(Comparator.comparing(Observation::code)
                        .thenComparing(Observation::effectiveTime))
                .toList();
    }

    public List<ConflictDecision> conflictLog() {
        synchronized (conflictLog) {
            return List.copyOf(conflictLog);
        }
    }

    public long version() {
        return version;
    }
}
