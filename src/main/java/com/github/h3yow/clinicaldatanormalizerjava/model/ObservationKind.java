package com.github.h3yow.clinicaldatanormalizerjava.model;

/**
 * 观测的语义类别（来自 OBX-11 观测结果状态）：
 * <ul>
 *   <li>{@link #FINAL}：OBX-11 = F，普通最终结果，参与多来源冲突裁决；</li>
 *   <li>{@link #CORRECTION}：OBX-11 = C，结果更正，替换同患者/同指标/同时刻的当前有效值；</li>
 *   <li>{@link #WITHDRAWAL}：OBX-11 = D，结果撤回，把该时刻观测从当前有效集合中移除。</li>
 * </ul>
 */
public enum ObservationKind {
    FINAL,
    CORRECTION,
    WITHDRAWAL
}
