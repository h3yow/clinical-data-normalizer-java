package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个患者的纵向合并状态（内存存储，进程内可运行）。
 *
 * <p>事件溯源式槽位模型，保证幂等与顺序无关：
 * <ul>
 *   <li>{@code processedMessages}：已处理的消息控制号。重复投递（乱序、重试）
 *       直接判定 DUPLICATE，不产生重复观测、不产生重复版本。</li>
 *   <li>{@code byFingerprint}：内容指纹 → 观测事件（含更正/撤回事件）。同内容事件天然去重。</li>
 *   <li>{@code slots}：冲突槽位（患者|指标|时刻）→ 槽位内全部事件。
 *       槽位有效值是事件集合的纯函数：撤回（终态）&gt; 更正 &gt; 普通新增，
 *       同类内部按注入的全序比较器（来源优先级 → 控制号 → 指纹）裁决，
 *       与事件到达顺序完全无关。</li>
 * </ul>
 *
 * <p>版本历史：当前有效观测集合每真正变化一次，就固化一个 {@link VersionSnapshot}
 * （版本号单调递增、不回退）。历史快照不可变，供按版本追溯导出。
 */
public final class PatientRecord {

    private final String patientId;
    private final Comparator<Observation> winnerOrder;
    private final Map<String, List<String>> processedMessages = new LinkedHashMap<>();
    private final Map<String, Observation> byFingerprint = new LinkedHashMap<>();
    private final Map<String, SlotState> slots = new LinkedHashMap<>();
    private final List<ConflictDecision> conflictHistory = new ArrayList<>();
    private final List<MergeDecision> mergeHistory = new ArrayList<>();
    private final List<VersionSnapshot> versions = new ArrayList<>();

    /**
     * @param winnerOrder 同类事件的全序比较器（大者获胜），必须只依赖事件内容
     */
    public PatientRecord(String patientId, Comparator<Observation> winnerOrder) {
        this.patientId = patientId;
        this.winnerOrder = winnerOrder;
    }

    /** 槽位状态：登记该槽位见过的全部事件（按类别分集），并缓存当前有效指纹。 */
    public static final class SlotState {
        private final Set<String> adds = new LinkedHashSet<>();
        private final Set<String> corrections = new LinkedHashSet<>();
        private final Set<String> withdrawals = new LinkedHashSet<>();
        private String currentEffective;

        public Set<String> adds() {
            return adds;
        }

        public Set<String> corrections() {
            return corrections;
        }

        public Set<String> withdrawals() {
            return withdrawals;
        }

        /** 当前有效观测指纹；null 表示槽位无有效观测（从未生效或已撤回）。 */
        public String currentEffective() {
            return currentEffective;
        }
    }

    public String patientId() {
        return patientId;
    }

    /** 当前版本号 = 有效集合发生过的真实变化次数（单调递增，不回退）。 */
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

    /** 全部已登记事件（含被裁决掉的、被更正/撤回的历史事件）。 */
    public List<Observation> observations() {
        return List.copyOf(byFingerprint.values());
    }

    /** 当前有效观测集合（每个槽位至多一条；被更正掉/被撤回的不在其中）。 */
    public List<Observation> effectiveObservations() {
        List<Observation> result = new ArrayList<>();
        for (SlotState slot : slots.values()) {
            if (slot.currentEffective != null) {
                Observation obs = byFingerprint.get(slot.currentEffective);
                if (obs != null) {
                    result.add(obs);
                }
            }
        }
        return List.copyOf(result);
    }

    public String slotWinner(String slotKey) {
        SlotState slot = slots.get(slotKey);
        return slot == null ? null : slot.currentEffective;
    }

    /** 槽位 → 当前有效指纹（仅含有效槽位），供既有查询/测试使用。 */
    public Map<String, String> slotWinners() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, SlotState> e : slots.entrySet()) {
            if (e.getValue().currentEffective != null) {
                result.put(e.getKey(), e.getValue().currentEffective);
            }
        }
        return result;
    }

    /** 取槽位状态（不存在返回 null，不创建）。 */
    public SlotState slot(String slotKey) {
        return slots.get(slotKey);
    }

    /**
     * 登记一条观测事件。内容指纹已存在时不重复登记（幂等）。
     *
     * @return true 表示本次为新事件
     */
    public boolean registerEvent(Observation obs) {
        SlotState slot = slots.computeIfAbsent(obs.slotKey(), k -> new SlotState());
        switch (obs.kind()) {
            case FINAL -> slot.adds.add(obs.fingerprint());
            case CORRECTION -> slot.corrections.add(obs.fingerprint());
            case WITHDRAWAL -> slot.withdrawals.add(obs.fingerprint());
        }
        if (byFingerprint.containsKey(obs.fingerprint())) {
            return false;
        }
        byFingerprint.put(obs.fingerprint(), obs);
        return true;
    }

    /**
     * 重算槽位有效指纹并落缓存。有效值是槽位事件集合的纯函数：
     * 撤回（终态）&gt; 更正 &gt; 普通新增；更正在没有普通新增目标时不生效。
     *
     * @return 重算前的有效指纹（null 表示此前无有效观测）
     */
    public String recomputeSlotEffective(String slotKey) {
        SlotState slot = slots.get(slotKey);
        String previous = slot.currentEffective;
        slot.currentEffective = computeEffective(slot);
        return previous;
    }

    private String computeEffective(SlotState slot) {
        if (!slot.withdrawals.isEmpty()) {
            return null; // 撤回是槽位终态
        }
        if (slot.adds.isEmpty()) {
            return null; // 更正没有目标时不生效（待生效），绝不当作普通新增
        }
        if (!slot.corrections.isEmpty()) {
            return maxFingerprint(slot.corrections);
        }
        return maxFingerprint(slot.adds);
    }

    private String maxFingerprint(Set<String> fingerprints) {
        return fingerprints.stream()
                .map(byFingerprint::get)
                .max(winnerOrder)
                .map(Observation::fingerprint)
                .orElseThrow();
    }

    /**
     * 有效集合发生变化后固化一个新版本快照。
     * 快照包含变化清单与该版本完整有效指纹集合，不可变。
     */
    public void commitVersion(List<VersionChange> changes, Instant changedAt) {
        List<String> effective = slots.values().stream()
                .filter(s -> s.currentEffective != null)
                .map(s -> s.currentEffective)
                .sorted()
                .toList();
        versions.add(new VersionSnapshot(versions.size() + 1L, changedAt, changes, effective));
    }

    /** 全部历史版本（按版本号升序）。 */
    public List<VersionSnapshot> versions() {
        return List.copyOf(versions);
    }

    /** 取指定版本快照；不存在（含超出当前范围）返回 null。 */
    public VersionSnapshot versionSnapshot(long version) {
        if (version < 1 || version > versions.size()) {
            return null;
        }
        return versions.get((int) version - 1);
    }

    public void recordConflict(ConflictDecision decision) {
        conflictHistory.add(decision);
    }

    public List<ConflictDecision> conflictHistory() {
        return List.copyOf(conflictHistory);
    }

    public void recordMergeDecision(MergeDecision decision) {
        mergeHistory.add(decision);
    }

    public List<MergeDecision> mergeHistory() {
        return List.copyOf(mergeHistory);
    }
}
