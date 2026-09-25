package com.github.h3yow.clinicaldatanormalizerjava.model;

import java.time.Instant;

/**
 * 归一化后的患者观测（统一模型）。
 *
 * @param patientId         归一到统一命名域后的患者标识（PID-3.1）
 * @param category          指标大类（由编码目录给出，如 vital_signs/laboratory）
 * @param code              统一编码（LOINC，如 8867-4）
 * @param displayName       统一展示名（如 Heart rate）
 * @param value             以规范单位表示的数值
 * @param unit              规范单位（UCUM，如 bpm、mg/dL）
 * @param effectiveTime     观测生效时间（OBX-14，缺省回退 OBR-7/MSH-7）
 * @param sourceSystem      来源系统（MSH-3.1）
 * @param messageControlId  来源消息控制号（MSH-10），用于追溯
 * @param observationId     来源消息内观测序号（OBX 段序号），用于追溯
 * @param fingerprint       内容指纹：对"患者+指标+生效时间+值+单位"做确定性哈希，
 *                          同内容重放得到相同指纹，实现幂等去重
 */
public record Observation(
        String patientId,
        String category,
        String code,
        String displayName,
        double value,
        String unit,
        Instant effectiveTime,
        String sourceSystem,
        String messageControlId,
        int observationId,
        String fingerprint
) {
    /** 冲突裁决键：同一患者、同一指标、同一观测时刻。 */
    public String slotKey() {
        return patientId + '|' + code + '|' + effectiveTime;
    }
}
