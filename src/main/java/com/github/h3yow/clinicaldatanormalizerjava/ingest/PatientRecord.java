package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个患者的纵向合并状态（内存存储，进程内可运行）。
 *
 * <p>三类索引保证幂等与顺序无关：
 * <ul>
 *   <li>{@code processedMessages}：已接受/已见过的消息控制号 → 内容指纹集合。
 *       同一消息重复投递（乱序、重试）直接判定 DUPLICATE，不产生重复观测。</li>
 *   <li>{@code byFingerprint}：内容指纹 → 观测。同内容观测天然去重；
 *       被更正/被撤回的历史值保留在内容库中供审计，但不再属于当前有效集合。</li>
 *   <li>{@code slotWinner}：冲突槽位（患者|指标|时刻）→ 当前有效指纹。
 *       当前有效集合 = 全部槽位获胜者；同一槽位任意时刻至多一条有效观测。</li>
 * </ul>
 *
 * <p>版本历史：每当当前有效集合真正变化一次（新增/更正/撤回），
 * 由 {@link #commitVersion} 产生一个单调递增、不可变的 {@link VersionSnapshot}。
 * 重复投递、内容去重、与现状一致的重放都不会产生新版本，版本号也绝不回退。
 */
public final class PatientRecord {

    private final String patientId;
    private final Map<String, List<String>> processedMessages = new LinkedHashMap<>();
    private final Map<String, Observation> byFingerprint = new LinkedHashMap<>();
    private final Map<String, String> slotWinner = new LinkedHashMap<>();
    private final List<ConflictDecision> conflictHistory = new ArrayList<>();
    private final List<AmendmentDecision> amendmentHistory = new ArrayList<>();
    private final List<VersionSnapshot> versions = new ArrayList<>();

    public PatientRecord(String patientId) {
        this.patientId = patientId;
    }

    public String patientId() {
        return patientId;
    }

    /** 当前数据版本号 = 已产生的版本快照数（0 表示尚无任何有效观测）。 */
    public long version() {
        return versions.size();
    }

    public boolean hasProcessed(String messageControlId) {
        return processedMessages.containsKey(messageControlId);
    }

    /** 记录消息已处理，并登记其携带的全部观测指纹。 */
    public void markProcessed(String messageControlId, List<String> fingerprints) {
        processedMessages.put(messageControlId, List.copyOf(fingerprints));
    }

    public Map<String, List<String>> processedMessages() {
        return processedMessages;
    }

    public Observation observation(String fingerprint) {
        return byFingerprint.get(fingerprint);
    }

    /** 内容库中的全部观测（含被更正/被撤回的历史值，仅供内部审计，不是当前有效集合）。 */
    public List<Observation> observations() {
        return List.copyOf(byFingerprint.values());
    }

    public String slotWinner(String slotKey) {
        return slotWinner.get(slotKey);
    }

    public Map<String, String> slotWinners() {
        return slotWinner;
    }

    /**
     * 当前有效观测集合：全部槽位获胜者，按槽位键排序（确定性，与投递顺序无关）。
     * 被更正掉或被撤回的值不在其中。
     */
    public List<Observation> effectiveObservations() {
        return slotWinner.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> byFingerprint.get(e.getValue()))
                .toList();
    }

    /** 新增观测到内容库；内容指纹已存在时返回 false（不重复计数）。 */
    public boolean addObservation(Observation obs) {
        if (byFingerprint.containsKey(obs.fingerprint())) {
            return false;
        }
        byFingerprint.put(obs.fingerprint(), obs);
        return true;
    }

    /** 设置冲突槽位的获胜指纹；与现状一致时不产生任何变化。 */
    public void setSlotWinner(String slotKey, String fingerprint) {
        String current = slotWinner.get(slotKey);
        if (fingerprint.equals(current)) {
            return;
        }
        slotWinner.put(slotKey, fingerprint);
    }

    /** 撤回槽位的当前有效观测；返回被撤下的指纹，槽位本就为空时返回 null。 */
    public String clearSlotWinner(String slotKey) {
        return slotWinner.remove(slotKey);
    }

    public void recordConflict(ConflictDecision decision) {
        conflictHistory.add(decision);
    }

    public List<ConflictDecision> conflictHistory() {
        return List.copyOf(conflictHistory);
    }

    public void recordAmendment(AmendmentDecision decision) {
        amendmentHistory.add(decision);
    }

    public List<AmendmentDecision> amendmentHistory() {
        return List.copyOf(amendmentHistory);
    }

    /**
     * 当前有效集合发生真实变化后提交一个新版本。
     * 快照在此刻固定，之后的投递不会改变它。
     */
    public VersionSnapshot commitVersion(Instant changedAt, List<VersionChange> changes) {
        VersionSnapshot snapshot = new VersionSnapshot(
                versions.size() + 1L, changedAt, changes, effectiveObservations());
        versions.add(snapshot);
        return snapshot;
    }

    /** 按版本号取历史快照；版本不存在（&lt;1 或 &gt;当前版本）返回 null。 */
    public VersionSnapshot snapshot(long version) {
        if (version < 1 || version > versions.size()) {
            return null;
        }
        return versions.get((int) version - 1);
    }

    /** 全部历史版本（按版本号升序，不可变）。 */
    public List<VersionSnapshot> versions() {
        return List.copyOf(versions);
    }
}
