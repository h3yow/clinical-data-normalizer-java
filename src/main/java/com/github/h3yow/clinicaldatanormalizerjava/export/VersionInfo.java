package com.github.h3yow.clinicaldatanormalizerjava.export;

import java.time.Instant;
import java.util.List;

/**
 * 一个历史版本的元数据（去标识化）：版本号、变化时间、相对上一版的变更清单。
 *
 * @param version   版本号（从 1 开始单调递增）
 * @param changedAt 该版本产生的时间
 * @param changes   相对上一版本的变更（新增/更正/撤回），已去标识化
 */
public record VersionInfo(
        long version,
        Instant changedAt,
        List<VersionChangeView> changes
) {
    public VersionInfo {
        changes = List.copyOf(changes);
    }
}
