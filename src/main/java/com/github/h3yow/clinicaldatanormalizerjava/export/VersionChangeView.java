package com.github.h3yow.clinicaldatanormalizerjava.export;

/**
 * 版本变更的去标识化视图：只描述"变了什么"，不含患者标识、来源系统、
 * 消息控制号或内容指纹；时间精度与导出一致（年份）。
 *
 * @param changeType     ADDED / CORRECTED / WITHDRAWN
 * @param oldObservation 被替换/被撤回观测的去标识化形态（ADDED 时为 null）
 * @param newObservation 新有效观测的去标识化形态（WITHDRAWN 时为 null）
 */
public record VersionChangeView(
        String changeType,
        DeidentifiedObservation oldObservation,
        DeidentifiedObservation newObservation
) {
}
