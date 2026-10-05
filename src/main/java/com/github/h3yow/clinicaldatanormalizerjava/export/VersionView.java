package com.github.h3yow.clinicaldatanormalizerjava.export;

import com.github.h3yow.clinicaldatanormalizerjava.ingest.VersionChange;

import java.time.Instant;
import java.util.List;

/**
 * 版本历史的对外视图：版本号、变化时间、相对上一版的变化。
 * 不含患者标识、来源系统、消息控制号。
 */
public record VersionView(
        long version,
        Instant changedAt,
        List<VersionChange> changes
) {
    public VersionView {
        changes = List.copyOf(changes);
    }
}
