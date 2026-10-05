package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import java.time.Instant;

/**
 * 一次版本变化中单个槽位（患者|指标|时刻）发生的变更。
 * 仅供服务内部追溯；对外暴露前必须经过去标识化（去掉患者标识、来源、控制号、指纹）。
 *
 * @param type            变更类型：新增 / 更正（含冲突覆盖）/ 撤回
 * @param slotKey         槽位键：患者|统一编码|生效时间
 * @param category        指标大类
 * @param code            统一编码（LOINC）
 * @param displayName     统一展示名
 * @param effectiveTime   观测生效时间
 * @param oldFingerprint  被替换/被撤回观测的内容指纹（ADDED 时为 null）
 * @param newFingerprint  新有效观测的内容指纹（WITHDRAWN 时为 null）
 */
public record VersionChange(
        ChangeType type,
        String slotKey,
        String category,
        String code,
        String displayName,
        Instant effectiveTime,
        String oldFingerprint,
        String newFingerprint
) {
    public enum ChangeType {
        /** 槽位首次出现有效观测 */
        ADDED,
        /** 槽位的当前有效值被替换（结果更正或冲突裁决覆盖） */
        CORRECTED,
        /** 槽位的有效观测被撤回 */
        WITHDRAWN
    }
}
