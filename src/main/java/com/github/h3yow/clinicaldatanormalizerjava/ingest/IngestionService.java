package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7ParseException;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Parser;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.ParseErrorReason;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.NormalizationException;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.ObservationNormalizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息投递入口：解析 → 归一化 → 患者纵向合并。
 *
 * <p>幂等与顺序保证：
 * <ul>
 *   <li>消息级：以 MSH-10 消息控制号去重。控制号已处理过（无论当时成功或冲突如何）
 *       的投递直接返回 DUPLICATE，不写任何状态，可安全重试、乱序、重复投递。</li>
 *   <li>内容级：观测以患者|指标|时刻|值|单位的内容指纹去重，相同观测永不重复落库。</li>
 *   <li>顺序无关：冲突裁决只依赖数据内容与固定优先级，不依赖投递先后。
 *       先按来源系统优先级比较（HIS_A &gt; LAB_X &gt; OTHER，默认最低），
 *       平手时比较消息控制号，再平手比较观测指纹，规则全序且可复现。</li>
 * </ul>
 * 单条消息失败只影响该消息（异常前不落任何状态），批内其它消息照常处理。
 */
@Service
public class IngestionService {

    /** 来源系统权威性优先级，数值越大越优先；未列出的来源为 0。 */
    private static final Map<String, Integer> SOURCE_PRIORITY = Map.of(
            "HIS_A", 30,
            "LAB_X", 20,
            "OTHER", 10
    );

    private final Hl7Parser parser = new Hl7Parser();
    private final ObservationNormalizer normalizer;
    private final Map<String, PatientRecord> patients = new LinkedHashMap<>();

    public IngestionService(ObservationNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public synchronized List<IngestionOutcome> ingest(byte[] payload) {
        List<Hl7Message> messages;
        try {
            messages = parser.parseBatch(payload);
        } catch (Hl7ParseException e) {
            // 整个批量包无法拆出可信的消息边界时，整体拒绝
            return List.of(IngestionOutcome.rejected(null, "PARSE",
                    e.getReason().name(), e.getMessage()));
        }
        List<IngestionOutcome> outcomes = new ArrayList<>();
        for (Hl7Message message : messages) {
            outcomes.add(ingestOne(message));
        }
        return outcomes;
    }

    private IngestionOutcome ingestOne(Hl7Message message) {
        String controlId = null;
        var msh = message.segment("MSH");
        if (msh != null) {
            controlId = msh.get(10);
        }
        List<Observation> observations;
        try {
            observations = normalizer.normalize(message);
        } catch (NormalizationException e) {
            return IngestionOutcome.rejected(controlId, "NORMALIZE",
                    e.getReason().name(), e.getMessage());
        }

        String patientId = observations.get(0).patientId();
        PatientRecord record = patients.computeIfAbsent(patientId, PatientRecord::new);

        if (controlId != null && record.hasProcessed(controlId)) {
            return IngestionOutcome.duplicate(controlId);
        }

        // 按冲突槽位归组；组内先用确定性规则挑出最终获胜者，再统一落状态，
        // 使处理结果与消息内 OBX 顺序、投递顺序均无关。
        Map<String, List<Observation>> bySlot = new LinkedHashMap<>();
        for (Observation obs : observations) {
            bySlot.computeIfAbsent(obs.slotKey(), k -> new ArrayList<>()).add(obs);
        }

        List<ConflictDecision> decisions = new ArrayList<>();
        List<Observation> toAdd = new ArrayList<>();
        Map<String, Observation> newWinnerBySlot = new LinkedHashMap<>();

        for (Map.Entry<String, List<Observation>> entry : bySlot.entrySet()) {
            List<Observation> incoming = entry.getValue();
            String slotKey = entry.getKey();
            Observation incomingWinner = incoming.stream().max(this::compareForWinner).orElseThrow();

            String currentFingerprint = record.slotWinner(slotKey);
            Observation currentWinner = currentFingerprint == null ? null : record.observation(currentFingerprint);
            Observation winner = incomingWinner;
            Observation loser = null;
            String reason;

            if (currentWinner == null) {
                reason = "槽位无既有观测，直接采用 " + incomingWinner.sourceSystem()
                        + "/" + incomingWinner.messageControlId();
            } else if (Double.compare(currentWinner.value(), incomingWinner.value()) == 0) {
                // 数值一致：非冲突，保持既有获胜者不变（不同指纹只进内容库，槽位不变化、版本不递增）
                continue;
            } else {
                if (compareForWinner(currentWinner, incomingWinner) >= 0) {
                    winner = currentWinner;
                    loser = incomingWinner;
                    reason = "既有观测保留：来源优先级 "
                            + priorityOf(currentWinner.sourceSystem()) + " vs "
                            + priorityOf(incomingWinner.sourceSystem())
                            + "（平手再按消息控制号、指纹比较）";
                } else {
                    winner = incomingWinner;
                    loser = currentWinner;
                    reason = "新观测覆盖：来源优先级 "
                            + priorityOf(incomingWinner.sourceSystem()) + " vs "
                            + priorityOf(currentWinner.sourceSystem())
                            + "（平手再按消息控制号、指纹比较）";
                }
            }

            // 同槽位内部仅对"数值不一致"的来源记录冲突裁决；
            // 值相同的重复观测只做内容指纹去重，不算冲突，避免噪声裁决。
            for (Observation other : incoming) {
                if (other.fingerprint().equals(incomingWinner.fingerprint())) {
                    continue;
                }
                if (Double.compare(other.value(), incomingWinner.value()) == 0) {
                    continue;
                }
                decisions.add(new ConflictDecision(
                        slotKey,
                        incomingWinner.fingerprint(), incomingWinner.messageControlId(),
                        incomingWinner.sourceSystem(),
                        other.fingerprint(), other.messageControlId(), other.sourceSystem(),
                        "批内同槽位比较：来源优先级 "
                                + priorityOf(incomingWinner.sourceSystem()) + " vs "
                                + priorityOf(other.sourceSystem())
                                + "（平手按消息控制号、指纹比较）"));
            }
            // 与既有槽位获胜者：仅当数值不同才构成冲突；值相同则保持现状即可。
            if (loser != null && Double.compare(loser.value(), winner.value()) == 0) {
                loser = null;
            }
            if (loser != null) {
                decisions.add(new ConflictDecision(
                        slotKey,
                        winner.fingerprint(), winner.messageControlId(), winner.sourceSystem(),
                        loser.fingerprint(), loser.messageControlId(), loser.sourceSystem(),
                        reason));
            }
            newWinnerBySlot.put(slotKey, winner);
            toAdd.add(winner);
        }

        // 落状态：先登记观测，再登记槽位与消息，保证失败前无任何写入（本段不抛检异常）
        int accepted = 0;
        for (Observation obs : toAdd) {
            if (record.addObservation(obs)) {
                accepted++;
            }
        }
        for (Map.Entry<String, Observation> e : newWinnerBySlot.entrySet()) {
            record.setSlotWinner(e.getKey(), e.getValue().fingerprint());
        }
        for (ConflictDecision d : decisions) {
            record.recordConflict(d);
        }
        record.markProcessed(controlId, observations.stream().map(Observation::fingerprint).toList());

        return IngestionOutcome.accepted(controlId, accepted, decisions);
    }

    /**
     * 全序比较：来源优先级（高者胜）→ 消息控制号字典序（大者胜）→ 观测指纹（大者胜）。
     * 仅依赖内容，与投递顺序无关。
     */
    private int compareForWinner(Observation a, Observation b) {
        return Comparator
                .comparingInt((Observation o) -> priorityOf(o.sourceSystem()))
                .thenComparing(Observation::messageControlId)
                .thenComparing(Observation::fingerprint)
                .compare(a, b);
    }

    private int priorityOf(String sourceSystem) {
        return SOURCE_PRIORITY.getOrDefault(sourceSystem, 0);
    }

    // ---- 供导出/查询使用的只读接口 ----

    public synchronized PatientRecord getPatient(String patientId) {
        return patients.get(patientId);
    }

    public synchronized List<String> patientIds() {
        return List.copyOf(patients.keySet());
    }
}
