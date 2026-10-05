package com.github.h3yow.clinicaldatanormalizerjava.normalize;

/**
 * 语义归一化阶段的错误原因。与解析阶段（HL7 词法/结构）分开归类，
 * 每条被拒绝的消息都能定位到唯一原因，不以默认值兜底。
 */
public enum NormalizationErrorReason {
    /** 不是 ORU^R01 消息（本服务仅接受该事件类型） */
    UNSUPPORTED_MESSAGE_TYPE,
    /** 缺少 MSH 段 */
    MISSING_MSH,
    /** MSH-9 消息类型不完整（缺 component 1/2） */
    MALFORMED_MESSAGE_TYPE,
    /** MSH-10 消息控制号缺失 */
    MISSING_MESSAGE_CONTROL_ID,
    /** MSH-7 消息时间缺失或非法（作为最终回退时间） */
    MISSING_OR_INVALID_MESSAGE_TIME,
    /** MSH-3 发送应用缺失（来源不可追溯） */
    MISSING_SENDING_APPLICATION,
    /** 缺少 PID 段 */
    MISSING_PID,
    /** PID-3 患者标识缺失（所有重复均为空） */
    MISSING_PATIENT_ID,
    /** 缺少 OBR 段（观测结果必须挂在医嘱下） */
    MISSING_OBR,
    /** 消息中没有任何 OBX 段 */
    MISSING_OBX,
    /** OBX-2 观测值类型缺失或非 NM（本服务只做数值归一） */
    UNSUPPORTED_OBSERVATION_VALUE_TYPE,
    /** OBX-3 观测标识符缺失 */
    MISSING_OBSERVATION_CODE,
    /** OBX-3 编码不在编码目录中（未知来源指标） */
    UNKNOWN_OBSERVATION_CODE,
    /** OBX-5 观测值缺失或空白 */
    MISSING_OBSERVATION_VALUE,
    /** OBX-5 不是有限实数（NaN/Infinity/不可解析均拒绝） */
    NON_NUMERIC_OBSERVATION_VALUE,
    /** OBX-6 单位缺失 */
    MISSING_UNIT,
    /** OBX-6 单位无法映射到 UCUM（单位目录无收录） */
    UNKNOWN_UNIT,
    /** 同一 OBX 的各重复之间单位不一致 */
    INCONSISTENT_UNIT_ACROSS_REPETITIONS,
    /** OBX-11 观测结果状态缺失或不是 F/C/D（Final/Corrected/Delete） */
    INVALID_OBSERVATION_STATUS,
    /** OBX-14/OBR-7/MSH-7 时间格式非法（须为 HL7 DTM） */
    INVALID_DATETIME,
    /** 数值超过 double 可表达范围 */
    VALUE_OUT_OF_RANGE
}
