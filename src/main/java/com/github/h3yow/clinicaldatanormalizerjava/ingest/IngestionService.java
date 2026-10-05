package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Message;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7ParseException;
import com.github.h3yow.clinicaldatanormalizerjava.hl7.Hl7Parser;
import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;
import com.github.h3yow.clinicaldatanormalizerjava.model.ObservationKind;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.NormalizationException;
import com.github.h3yow.clinicaldatanormalizerjava.normalize.ObservationNormalizer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息投递入口：解析 → 归一化 → 患者纵向合并（含结果更正与撤回）。
 *
 * <p>幂等与顺序保证：
 * <ul>
 *   <li>消息级：以 MSH-10 消息控制号去重。控制号已处理过（无论当时成功或冲突如何）
 *       的投递直接返回 DUPLICATE，不写任何状态，可安全重试、乱序、重复投递。</li>
 *   <li>内容级：观测以患者|指标|时刻|类别|值|单位的内容指纹去重，相同观测永不重复落库。</li>
 *   <li>顺序无关：冲突裁决只依赖数据内容与固定优先级，不依赖投递先后。
 *       先按来源系统优先级比较（HIS_A &gt; LAB_X &gt; OTHER，默认最低），
 *       平手时比较消息控制号，再平手比较观测指纹，规则全序且可复现。</li>
 * </ul>
 *
 * <p>更正与撤回（OBX-11 = C / D）与冲突裁决的一致取舍规则：
 * <ul>
 *   <li>更正/撤回针对的是"槽位"（患者|指标|时刻）本身，而不是某一条来源观测，
 *       因此不参与来源优先级比较：命中即替换/移除当前有效值。</li>
 *   <li>更正/撤回要求槽位当前存在有效观测，否则整条消息以 MERGE 阶段
 *       {@link MergeErrorReason} 分类拒绝（不落任何状态、不登记控制号，可重投）。
 *       绝不把无目标的更正/撤回悄悄当成普通新增。</li>
 *   <li>同一消息同一槽位出现混合声明时，按 撤回 &gt; 更正 &gt; 最终结果 取舍，
 *       与 OBX 段顺序无关。</li>
 *   <li>被更正/被撤回的值保留在内容库供审计，但不再属于当前有效集合，
 *       不会出现在当前导出中。</li>
 * </ul>
 *
 * <p>版本：当前有效集合每真实变化一次提交一个单调递增的版本快照；
 * 重复投递、内容去重、与现状一致的重放均不产生新版本。
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
        PatientRecord record = patients.get(patientId);

        if (controlId != null && record != null && record.hasProcessed(controlId)) {
            return IngestionOutcome.duplicate(controlId);
        }

        // 按冲突槽位归组；组内先用确定性规则挑出最终获胜者，再统一落状态，
        // 使处理结果与消息内 OBX 顺序、投递顺序均无关。
        Map<String, List<Observation>> bySlot = new LinkedHashMap<>();
        for (Observation obs : observations) {
            bySlot.computeIfAbsent(obs.slotKey(), k -> new ArrayList<>()).add(obs);
        }

        // ---- 预检：所有更正/撤回必须存在当前有效目标，否则整条消息拒绝 ----
        // （不落任何状态、不登记控制号、不创建患者记录；来源在目标到达后重投即可收敛）
        for (Map.Entry<String, List<Observation>> entry : bySlot.entrySet()) {
            ObservationKind kind = effectiveKind(entry.getValue());
            if (kind == ObservationKind.FINAL) {
                continue;
            }
            if (record == null || record.slotWinner(entry.getKey()) == null) {
                MergeErrorReason reason = kind == ObservationKind.CORRECTION
                        ? MergeErrorReason.CORRECTION_TARGET_NOT_FOUND
                        : MergeErrorReason.WITHDRAWAL_TARGET_NOT_FOUND;
                return IngestionOutcome.rejected(controlId, "MERGE", reason.name(),
                        "槽位 " + entry.getKey() + " 当前没有有效观测，无法"
                                + (kind == ObservationKind.CORRECTION ? "更正" : "撤回")
                                + "；不作为普通新增处理");
            }
        }

        // 预检全部通过后才创建患者记录，保证被拒绝的消息不留任何痕迹
        record = patients.computeIfAbsent(patientId, PatientRecord::new);

        List<ConflictDecision> decisions = new ArrayList<>();
        List<AmendmentDecision> amendments = new ArrayList<>();
        List<VersionChange> changes = new ArrayList<>();
        int accepted = 0;

        for (Map.Entry<String, List<Observation>> entry : bySlot.entrySet()) {
            String slotKey = entry.getKey();
            List<Observation> incoming = entry.getValue();
            ObservationKind kind = effectiveKind(incoming);

            String currentFingerprint = record.slotWinner(slotKey);
            Observation currentWinner = currentFingerprint == null
                    ? null : record.observation(currentFingerprint);

            switch (kind) {
                case WITHDRAWAL -> {
                    // 预检已保证 currentWinner != null
                    record.clearSlotWinner(slotKey);
                    changes.add(new VersionChange(VersionChange.ChangeType.WITHDRAWN, slotKey,
                            currentWinner.category(), currentWinner.code(), currentWinner.displayName(),
                            currentWinner.effectiveTime(), currentFingerprint, null));
                    amendments.add(new AmendmentDecision(slotKey,
                            AmendmentDecision.AmendmentType.WITHDRAWAL,
                            AmendmentDecision.AmendmentOutcome.APPLIED,
                            currentFingerprint, null,
                            "撤回声明命中槽位当前有效观测，已从当前有效集合移除（历史值保留在内容库）"));
                }
                case CORRECTION -> {
                    Observation correction = incoming.stream()
                            .filter(o -> o.kind() == ObservationKind.CORRECTION)
                            .max(this::compareForWinner).orElseThrow();
                    if (Double.compare(correction.value(), currentWinner.value()) == 0) {
                        // 更正值与当前有效值一致：接受但不改变有效集合、不产生版本
                        amendments.add(new AmendmentDecision(slotKey,
                                AmendmentDecision.AmendmentType.CORRECTION,
                                AmendmentDecision.AmendmentOutcome.ALREADY_CURRENT,
                                currentFingerprint, currentFingerprint,
                                "更正值与当前有效值一致，接受但不产生版本变化"));
                    } else {
                        if (record.addObservation(correction)) {
                            accepted++;
                        }
                        record.setSlotWinner(slotKey, correction.fingerprint());
                        changes.add(new VersionChange(VersionChange.ChangeType.CORRECTED, slotKey,
                                correction.category(), correction.code(), correction.displayName(),
                                correction.effectiveTime(), currentFingerprint, correction.fingerprint()));
                        amendments.add(new AmendmentDecision(slotKey,
                                AmendmentDecision.AmendmentType.CORRECTION,
                                AmendmentDecision.AmendmentOutcome.APPLIED,
                                currentFingerprint, correction.fingerprint(),
                                "更正声明替换槽位当前有效值（更正针对槽位本身，不参与来源优先级比较）"));
                    }
                }
                case FINAL -> {
                    accepted += applyFinalSlot(record, slotKey, incoming, currentWinner,
                            currentFingerprint, decisions, changes);
                }
            }
        }

        for (ConflictDecision d : decisions) {
            record.recordConflict(d);
        }
        for (AmendmentDecision a : amendments) {
            record.recordAmendment(a);
        }
        // 当前有效集合真实变化才提交新版本；版本号单调递增、不回退
        if (!changes.isEmpty()) {
            record.commitVersion(Instant.now(), changes);
        }
        record.markProcessed(controlId, observations.stream().map(Observation::fingerprint).toList());

        return IngestionOutcome.accepted(controlId, accepted, decisions, amendments);
    }

    /**
     * 处理纯最终结果（OBX-11=F）槽位：与既有有效值做确定性冲突裁决。
     *
     * @return 本次实际新增到内容库的观测数
     */
    private int applyFinalSlot(PatientRecord record, String slotKey, List<Observation> incoming,
                               Observation currentWinner, String currentFingerprint,
                               List<ConflictDecision> decisions, List<VersionChange> changes) {
        Observation incomingWinner = incoming.stream().max(this::compareForWinner).orElseThrow();

        if (currentWinner == null) {
            // 槽位无既有观测，直接采用
            int accepted = record.addObservation(incomingWinner) ? 1 : 0;
            record.setSlotWinner(slotKey, incomingWinner.fingerprint());
            changes.add(new VersionChange(VersionChange.ChangeType.ADDED, slotKey,
                    incomingWinner.category(), incomingWinner.code(), incomingWinner.displayName(),
                    incomingWinner.effectiveTime(), null, incomingWinner.fingerprint()));
            return accepted;
        }
        if (Double.compare(currentWinner.value(), incomingWinner.value()) == 0) {
            // 数值一致：非冲突，保持既有获胜者不变（不递增版本、不产生噪声裁决）
            return 0;
        }

        Observation winner;
        Observation loser;
        String reason;
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
        decisions.add(new ConflictDecision(
                slotKey,
                winner.fingerprint(), winner.messageControlId(), winner.sourceSystem(),
                loser.fingerprint(), loser.messageControlId(), loser.sourceSystem(),
                reason));

        if (!winner.fingerprint().equals(currentFingerprint)) {
            int accepted = record.addObservation(winner) ? 1 : 0;
            record.setSlotWinner(slotKey, winner.fingerprint());
            changes.add(new VersionChange(VersionChange.ChangeType.CORRECTED, slotKey,
                    winner.category(), winner.code(), winner.displayName(),
                    winner.effectiveTime(), currentFingerprint, winner.fingerprint()));
            return accepted;
        }
        return 0;
    }

    /**
     * 同一消息同一槽位的混合声明取舍：撤回 &gt; 更正 &gt; 最终结果。
     * 与 OBX 段顺序无关，保证确定性。
     */
    private ObservationKind effectiveKind(List<Observation> slotObservations) {
        ObservationKind result = ObservationKind.FINAL;
        for (Observation o : slotObservations) {
            switch (o.kind()) {
                case WITHDRAWAL -> {
                    return ObservationKind.WITHDRAWAL;
                }
                case CORRECTION -> result = ObservationKind.CORRECTION;
                case FINAL -> {
                }
            }
        }
        return result;
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
