package com.github.h3yow.clinicaldatanormalizerjava.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 归一化后的统一观测模型。
 *
 * @param patientId    患者内部标识（归一化自 PID-3）
 * @param code         统一代码（LOINC）
 * @param value        数值（已换算到规范单位）
 * @param unit         规范 UCUM 单位
 * @param effectiveTime 观测时间
 * @param sourceId     来源系统（MSH-3）
 * @param messageId    消息控制 ID（MSH-10），幂等键的一部分
 * @param observationKey 观测级幂等键：messageId + OBX set id
 */
public record Observation(String patientId, String code, BigDecimal value, String unit,
                          Instant effectiveTime, String sourceId, String messageId,
                          String observationKey) {
}
