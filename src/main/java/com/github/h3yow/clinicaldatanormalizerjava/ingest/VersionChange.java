package com.github.h3yow.clinicaldatanormalizerjava.ingest;

/**
 * 一个版本相对上一版的变化项。只含指标层面信息，
 * 不含患者标识、来源系统、消息控制号，可直接暴露给授权调用方。
 *
 * @param kind     变化类别
 * @param category 指标大类
 * @param code     统一编码（LOINC）
 */
public record VersionChange(Kind kind, String category, String code) {

    public enum Kind {
        /** 槽位首次出现有效观测 */
        ADDED,
        /** 有效值被更正事件替换 */
        CORRECTED,
        /** 有效值被更高优先级的普通新增替换（冲突裁决） */
        REPLACED,
        /** 槽位被撤回，移出当前有效集合 */
        WITHDRAWN
    }
}
