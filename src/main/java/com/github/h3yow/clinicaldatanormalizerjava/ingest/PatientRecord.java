package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;

import java.util.ArrayList;
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
 *   <li>{@code byFingerprint}：内容指纹 → 观测。同内容观测天然去重。</li>
 *   <li>{@code slotWinner}：冲突槽位（患者|指标|时刻）→ 当前获胜指纹。</li>
 * </ul>
 * 每当患者的最终观测集合发生变化，{@code version} 单调递增，供导出版本化使用。
 */
public final class PatientRecord {

    private final String patientId;
    private final Map<String, List<String>> processedMessages = new LinkedHashMap<>();
    private final Map<String, Observation> byFingerprint = new LinkedHashMap<>();
    private final Map<String, String> slotWinner = new LinkedHashMap<>();
    private final List<ConflictDecision> conflictHistory = new ArrayList<>();
    private long version = 0;

    public PatientRecord(String patientId) {
        this.patientId = patientId;
    }

    public String patientId() {
        return patientId;
    }

    public long version() {
        return version;
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

    public List<Observation> observations() {
        return List.copyOf(byFingerprint.values());
    }

    public String slotWinner(String slotKey) {
        return slotWinner.get(slotKey);
    }

    public Map<String, String> slotWinners() {
        return slotWinner;
    }

    /** 新增一条观测；内容指纹已存在时返回 false（不重复计数、不递增版本）。 */
    public boolean addObservation(Observation obs) {
        if (byFingerprint.containsKey(obs.fingerprint())) {
            return false;
        }
        byFingerprint.put(obs.fingerprint(), obs);
        version++;
        return true;
    }

    /** 设置冲突槽位的获胜指纹；与现状一致时不产生版本变化。 */
    public void setSlotWinner(String slotKey, String fingerprint) {
        String current = slotWinner.get(slotKey);
        if (fingerprint.equals(current)) {
            return;
        }
        slotWinner.put(slotKey, fingerprint);
        version++;
    }

    public void recordConflict(ConflictDecision decision) {
        conflictHistory.add(decision);
    }

    public List<ConflictDecision> conflictHistory() {
        return List.copyOf(conflictHistory);
    }
}
