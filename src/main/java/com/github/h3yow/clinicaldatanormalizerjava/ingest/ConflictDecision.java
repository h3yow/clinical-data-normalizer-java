package com.github.h3yow.clinicaldatanormalizerjava.ingest;

/**
 * 同一时刻同一指标存在多个来源时的裁决记录（冲突绝不静默覆盖）。
 *
 * @param slotKey                 冲突槽位：患者|统一编码|生效时间
 * @param winnerFingerprint       被保留观测的内容指纹
 * @param winnerMessageControlId  获胜消息控制号
 * @param winnerSourceSystem      获胜来源系统
 * @param loserFingerprint        被舍弃观测的内容指纹
 * @param loserMessageControlId   失败消息控制号
 * @param loserSourceSystem       失败来源系统
 * @param reason                  裁决依据描述（含来源优先级与确定性 tie-breaker）
 */
public record ConflictDecision(
        String slotKey,
        String winnerFingerprint,
        String winnerMessageControlId,
        String winnerSourceSystem,
        String loserFingerprint,
        String loserMessageControlId,
        String loserSourceSystem,
        String reason
) {
}
