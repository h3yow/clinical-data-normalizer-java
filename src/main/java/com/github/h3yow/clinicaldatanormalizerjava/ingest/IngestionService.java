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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 消息投递入口：解析 → 归一化 → 患者纵向合并。
 *
 * <p>幂等与顺序保证：
 * <ul>
 *   <li>消息级：以 MSH-10 消息控制号去重。控制号已处理过（无论当时结果如何）
 *       的投递直接返回 DUPLICATE，不写任何状态，可安全重试、乱序、重复投递。</li>
 *   <li>内容级：观测事件以患者|指标|时刻|值|单位的内容指纹去重，相同事件永不重复登记。</li>
 *   <li>顺序无关：槽位（患者|指标|时刻）有效值是槽位内全部已登记事件的纯函数——
 *       撤回（终态）&gt; 更正 &gt; 普通新增；同类内部按来源优先级 → 消息控制号 →
 *       内容指纹的全序裁决。规则只依赖内容，与拆分批次、投递先后、重放次数均无关。</li>
 * </ul>
 *
 * <p>更正/撤回取舍规则（与冲突裁决、重复投递一致、不互相打架）：
 * <ul>
 *   <li>更正（OBX-11=C）替换槽位当前有效值；槽位没有任何已登记的普通新增（F）时，
 *       更正不生效——登记为待生效并返回可区分的 CORRECTION_TARGET_NOT_FOUND 判定，
 *       绝不悄悄当成普通新增；目标到达后按同一确定性规则生效。</li>
 *   <li>撤回（OBX-11=D）把槽位移出当前有效集合，且对槽位是终态：
 *       之后该槽位的普通新增与更正都不会使其复活。</li>
 *   <li>患者的当前有效观测集合每真正变化一次产生一个单调递增的版本；
 *       重复投递、内容重放、无目标更正/撤回的登记都不产生新版本。</li>
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
        PatientRecord record = patients.computeIfAbsent(patientId,
                id -> new PatientRecord(id, this::compareForWinner));

        if (controlId != null && record.hasProcessed(controlId)) {
            return IngestionOutcome.duplicate(controlId);
        }

        // 按冲突槽位归组；组内先登记事件，再用确定性规则重算有效值，
        // 使处理结果与消息内 OBX 顺序、投递顺序均无关。
        Map<String, List<Observation>> bySlot = new LinkedHashMap<>();
        for (Observation obs : observations) {
            bySlot.computeIfAbsent(obs.slotKey(), k -> new ArrayList<>()).add(obs);
        }

        List<ConflictDecision> decisions = new ArrayList<>();
        List<MergeDecision> mergeDecisions = new ArrayList<>();
        List<VersionChange> versionChanges = new ArrayList<>();
        Set<String> newlyRegistered = new LinkedHashSet<>();
        int accepted = 0;
        boolean anyDeferred = false;

        for (Map.Entry<String, List<Observation>> entry : bySlot.entrySet()) {
            String slotKey = entry.getKey();
            List<Observation> incoming = entry.getValue();

            // ---- 普通新增之间的冲突裁决（保持既有规则与判定文案）----
            String oldEffective = record.slotWinner(slotKey);
            List<Observation> incomingAdds = incoming.stream()
                    .filter(o -> o.kind() == ObservationKind.FINAL).toList();
            collectConflictDecisions(record, slotKey, oldEffective, incomingAdds, decisions);

            // ---- 登记全部事件（内容指纹幂等）----
            for (Observation obs : incoming) {
                if (record.registerEvent(obs)) {
                    newlyRegistered.add(obs.fingerprint());
                    accepted++;
                }
            }

            // ---- 更正/撤回判定（目标存在性在登记本消息事件之后判定）----
            PatientRecord.SlotState slot = record.slot(slotKey);
            for (Observation obs : incoming) {
                if (obs.kind() == ObservationKind.CORRECTION) {
                    if (slot.adds().isEmpty()) {
                        anyDeferred = true;
                        mergeDecisions.add(new MergeDecision(
                                MergeDecision.Kind.CORRECTION_DEFERRED, slotKey,
                                obs.messageControlId(), obs.sourceSystem(),
                                "CORRECTION_TARGET_NOT_FOUND",
                                "槽位没有可更正的普通新增观测：更正不生效、不当作普通新增，"
                                        + "已登记为待生效，目标到达后按确定性规则适用"));
                    } else {
                        mergeDecisions.add(new MergeDecision(
                                MergeDecision.Kind.CORRECTION_APPLIED, slotKey,
                                obs.messageControlId(), obs.sourceSystem(),
                                "APPLIED",
                                "槽位存在普通新增目标：更正参与有效值裁决（更正优先于普通新增）"));
                    }
                } else if (obs.kind() == ObservationKind.WITHDRAWAL) {
                    if (slot.adds().isEmpty()) {
                        anyDeferred = true;
                        mergeDecisions.add(new MergeDecision(
                                MergeDecision.Kind.WITHDRAWAL_DEFERRED, slotKey,
                                obs.messageControlId(), obs.sourceSystem(),
                                "WITHDRAWAL_TARGET_NOT_FOUND",
                                "槽位没有可撤回的普通新增观测：撤回暂不生效，"
                                        + "已登记为待生效，目标到达后即撤回（撤回为槽位终态）"));
                    } else {
                        mergeDecisions.add(new MergeDecision(
                                MergeDecision.Kind.WITHDRAWAL_APPLIED, slotKey,
                                obs.messageControlId(), obs.sourceSystem(),
                                "APPLIED",
                                "槽位存在可撤回目标：撤回生效，槽位移出当前有效集合（终态）"));
                    }
                }
            }

            // ---- 重算槽位有效值；变化则记入版本 ----
            record.recomputeSlotEffective(slotKey);
            String newEffective = record.slotWinner(slotKey);
            if (!Objects.equals(oldEffective, newEffective)) {
                versionChanges.add(toVersionChange(record, oldEffective, newEffective));
            }

            // ---- 普通新增被更正/撤回压制的判定（仅针对本次新登记的新增）----
            for (Observation add : incomingAdds) {
                if (!newlyRegistered.contains(add.fingerprint())) {
                    continue;
                }
                if (newEffective == null && !slot.withdrawals().isEmpty()) {
                    mergeDecisions.add(new MergeDecision(
                            MergeDecision.Kind.ADD_SUPERSEDED_BY_WITHDRAWAL, slotKey,
                            add.messageControlId(), add.sourceSystem(),
                            "SLOT_WITHDRAWN",
                            "槽位存在撤回事件（终态）：普通新增不进入当前有效集合"));
                } else if (newEffective != null && slot.corrections().contains(newEffective)
                        && !newEffective.equals(add.fingerprint())) {
                    mergeDecisions.add(new MergeDecision(
                            MergeDecision.Kind.ADD_SUPERSEDED_BY_CORRECTION, slotKey,
                            add.messageControlId(), add.sourceSystem(),
                            "CORRECTION_TAKES_PRECEDENCE",
                            "槽位存在更正事件：更正优先于普通新增，该新增不成为有效值"));
                }
            }
        }

        // 有效集合真正变化才产生新版本；重复投递/内容重放/待生效登记都不产生版本
        if (!versionChanges.isEmpty()) {
            record.commitVersion(versionChanges, Instant.now());
        }
        for (ConflictDecision d : decisions) {
            record.recordConflict(d);
        }
        for (MergeDecision d : mergeDecisions) {
            record.recordMergeDecision(d);
        }
        record.markProcessed(controlId, observations.stream().map(Observation::fingerprint).toList());

        boolean hasFinal = observations.stream().anyMatch(o -> o.kind() == ObservationKind.FINAL);
        if (!hasFinal && anyDeferred && versionChanges.isEmpty()) {
            // 整条消息只有未找到目标的更正/撤回：状态可区分，绝不当作普通新增
            return IngestionOutcome.deferred(controlId, accepted, mergeDecisions);
        }
        return IngestionOutcome.accepted(controlId, accepted, decisions, mergeDecisions);
    }

    /**
     * 普通新增之间的冲突裁决：批内同槽位先选出获胜者，再与既有有效值比较。
     * 仅当数值不一致时记录裁决；既有有效值是更正/撤回事件时不构成新增间冲突
     * （更正/撤回与普通新增的取舍由 MergeDecision 表达）。
     */
    private void collectConflictDecisions(PatientRecord record, String slotKey, String oldEffective,
                                          List<Observation> incomingAdds, List<ConflictDecision> decisions) {
        if (incomingAdds.isEmpty()) {
            return;
        }
        Observation incomingWinner = incomingAdds.stream().max(this::compareForWinner).orElseThrow();
        for (Observation other : incomingAdds) {
            if (other.fingerprint().equals(incomingWinner.fingerprint())) {
                continue;
            }
            if (Double.compare(other.value(), incomingWinner.value()) == 0) {
                continue; // 数值一致：非冲突，只做内容指纹去重
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
        Observation current = oldEffective == null ? null : record.observation(oldEffective);
        if (current == null || current.kind() != ObservationKind.FINAL) {
            return;
        }
        if (Double.compare(current.value(), incomingWinner.value()) == 0) {
            return; // 数值一致：保持既有获胜者，不产生噪声裁决
        }
        if (compareForWinner(current, incomingWinner) >= 0) {
            decisions.add(new ConflictDecision(
                    slotKey,
                    current.fingerprint(), current.messageControlId(), current.sourceSystem(),
                    incomingWinner.fingerprint(), incomingWinner.messageControlId(),
                    incomingWinner.sourceSystem(),
                    "既有观测保留：来源优先级 "
                            + priorityOf(current.sourceSystem()) + " vs "
                            + priorityOf(incomingWinner.sourceSystem())
                            + "（平手再按消息控制号、指纹比较）"));
        } else {
            decisions.add(new ConflictDecision(
                    slotKey,
                    incomingWinner.fingerprint(), incomingWinner.messageControlId(),
                    incomingWinner.sourceSystem(),
                    current.fingerprint(), current.messageControlId(), current.sourceSystem(),
                    "新观测覆盖：来源优先级 "
                            + priorityOf(incomingWinner.sourceSystem()) + " vs "
                            + priorityOf(current.sourceSystem())
                            + "（平手再按消息控制号、指纹比较）"));
        }
    }

    private VersionChange toVersionChange(PatientRecord record, String oldEffective, String newEffective) {
        if (oldEffective == null) {
            Observation now = record.observation(newEffective);
            return new VersionChange(VersionChange.Kind.ADDED, now.category(), now.code());
        }
        if (newEffective == null) {
            Observation prev = record.observation(oldEffective);
            return new VersionChange(VersionChange.Kind.WITHDRAWN, prev.category(), prev.code());
        }
        Observation now = record.observation(newEffective);
        if (now.kind() == ObservationKind.CORRECTION) {
            return new VersionChange(VersionChange.Kind.CORRECTED, now.category(), now.code());
        }
        return new VersionChange(VersionChange.Kind.REPLACED, now.category(), now.code());
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
