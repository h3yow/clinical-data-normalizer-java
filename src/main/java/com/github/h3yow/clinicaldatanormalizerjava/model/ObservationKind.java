package com.github.h3yow.clinicaldatanormalizerjava.model;

/**
 * 观测事件类别（取自 OBX-11 结果状态）。
 *
 * <ul>
 *   <li>{@link #FINAL}：普通新增（OBX-11 = F）。</li>
 *   <li>{@link #CORRECTION}：结果更正（OBX-11 = C），替换同槽位当前有效值；
 *       槽位没有任何普通新增目标时不生效（登记为待生效，不当成普通新增）。</li>
 *   <li>{@link #WITHDRAWAL}：结果撤回（OBX-11 = D），把该槽位从当前有效集合移除；
 *       撤回对槽位是终态声明。</li>
 * </ul>
 */
public enum ObservationKind {
    FINAL,
    CORRECTION,
    WITHDRAWAL
}
