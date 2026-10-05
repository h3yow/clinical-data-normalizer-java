package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import java.time.Instant;
import java.util.List;

/**
 * 患者当前有效观测集合的一次不可变快照。
 * 每当有效集合真正变化就产生一个新版本；历史版本用于按版本追溯导出，
 * 后续投递再多新数据也不影响已固化快照。
 *
 * @param version              版本号（单调递增，从 1 开始，不回退）
 * @param changedAt            该版本产生时间
 * @param changes              相对上一版的变化清单
 * @param effectiveFingerprints 该版本当前有效观测的内容指纹集合（排序后固化）
 */
public record VersionSnapshot(
        long version,
        Instant changedAt,
        List<VersionChange> changes,
        List<String> effectiveFingerprints
) {
    public VersionSnapshot {
        changes = List.copyOf(changes);
        effectiveFingerprints = List.copyOf(effectiveFingerprints);
    }
}
