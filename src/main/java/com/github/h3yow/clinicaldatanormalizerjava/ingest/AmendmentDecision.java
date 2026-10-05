package com.github.h3yow.clinicaldatanormalizerjava.ingest;

/**
 * 一条更正/撤回声明的处置记录（可追溯，随投递响应返回并记入患者历史）。
 *
 * @param slotKey            目标槽位：患者|统一编码|生效时间
 * @param type               声明类型：更正 / 撤回
 * @param outcome            处置结果：已应用 / 更正值与当前一致（无需变更）
 * @param previousFingerprint 被替换/被撤回观测的内容指纹
 * @param newFingerprint     更正后新有效观测的内容指纹（撤回时为 null）
 * @param reason             处置依据描述
 */
public record AmendmentDecision(
        String slotKey,
        AmendmentType type,
        AmendmentOutcome outcome,
        String previousFingerprint,
        String newFingerprint,
        String reason
) {
    public enum AmendmentType {
        CORRECTION, WITHDRAWAL
    }

    public enum AmendmentOutcome {
        /** 已应用：当前有效集合发生变化 */
        APPLIED,
        /** 更正值与当前有效值一致：接受但不产生版本变化 */
        ALREADY_CURRENT
    }
}
