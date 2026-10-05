package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import com.github.h3yow.clinicaldatanormalizerjava.model.Observation;

import java.time.Instant;
import java.util.List;

/**
 * 患者当前有效观测集合的一个历史版本（不可变快照）。
 * 快照在版本产生时固定，之后投递任何新数据都不会改变它，
 * 因此按版本导出的结果稳定可复现。
 *
 * @param version      版本号（从 1 开始单调递增，不回退）
 * @param changedAt    该版本产生的时间
 * @param changes      相对上一版本的变更清单（新增/更正/撤回）
 * @param effectiveSet 该版本对应的当前有效观测集合（按槽位键排序，确定性）
 */
public record VersionSnapshot(
        long version,
        Instant changedAt,
        List<VersionChange> changes,
        List<Observation> effectiveSet
) {
    public VersionSnapshot {
        changes = List.copyOf(changes);
        effectiveSet = List.copyOf(effectiveSet);
    }
}
