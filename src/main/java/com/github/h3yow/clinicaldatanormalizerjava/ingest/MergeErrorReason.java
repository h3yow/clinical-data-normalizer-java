package com.github.h3yow.clinicaldatanormalizerjava.ingest;

/**
 * 合并阶段（更正/撤回与当前有效集合比对）的可区分拒绝原因。
 * 该阶段失败的消息不写任何状态、不登记消息控制号，来源修正顺序后可安全重投。
 */
public enum MergeErrorReason {
    /** 更正声明找不到可更正的目标（该槽位当前没有有效观测） */
    CORRECTION_TARGET_NOT_FOUND,
    /** 撤回声明找不到可撤回的目标（该槽位当前没有有效观测） */
    WITHDRAWAL_TARGET_NOT_FOUND
}
