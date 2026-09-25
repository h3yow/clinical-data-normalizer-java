package com.github.h3yow.clinicaldatanormalizerjava.hl7;

/**
 * 可区分的消息拒绝原因。任何解析/归一化失败都必须归类到其中之一，
 * 不允许用默认值兜底。
 */
public enum RejectionReason {
    /** 结构不完整：缺少必需段/字段、分隔符声明非法等 */
    MALFORMED_STRUCTURE,
    /** 计量单位无法识别或不在 UCUM 白名单内 */
    UNKNOWN_UNIT,
    /** 数值非法：无法解析为数值、结果类型不支持等 */
    INVALID_VALUE,
    /** 观测代码无法映射到目标代码系统 */
    UNMAPPABLE_CODE,
    /** 消息类型/事件不支持 */
    UNSUPPORTED_MESSAGE
}
