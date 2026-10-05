package com.github.h3yow.clinicaldatanormalizerjava.ingest;

/**
 * 更正/撤回/被压制新增在槽位合并时的判定记录（每次取舍都可追溯）。
 *
 * @param kind              判定类别
 * @param slotKey           冲突槽位：患者|统一编码|生效时间
 * @param messageControlId  触发该判定的消息控制号
 * @param sourceSystem      触发该判定的来源系统
 * @param reasonCode        机器可区分的原因码（如 CORRECTION_TARGET_NOT_FOUND）
 * @param reason            人类可读的判定依据
 */
public record MergeDecision(
        Kind kind,
        String slotKey,
        String messageControlId,
        String sourceSystem,
        String reasonCode,
        String reason
) {
    public enum Kind {
        /** 更正已生效：替换槽位当前有效值 */
        CORRECTION_APPLIED,
        /** 更正未找到可更正目标：已登记为待生效，绝不当作普通新增 */
        CORRECTION_DEFERRED,
        /** 撤回已生效：槽位从当前有效集合移除 */
        WITHDRAWAL_APPLIED,
        /** 撤回未找到可撤回目标：已登记为待生效 */
        WITHDRAWAL_DEFERRED,
        /** 普通新增被既有更正事件压制（更正优先于新增） */
        ADD_SUPERSEDED_BY_CORRECTION,
        /** 普通新增被既有撤回事件压制（撤回为槽位终态） */
        ADD_SUPERSEDED_BY_WITHDRAWAL
    }
}
