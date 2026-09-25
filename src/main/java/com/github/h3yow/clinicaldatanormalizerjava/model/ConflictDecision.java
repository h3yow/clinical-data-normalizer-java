package com.github.h3yow.clinicaldatanormalizerjava.model;

/**
 * 同一患者同一时刻同一指标多来源冲突时的取舍记录（可追溯）。
 */
public record ConflictDecision(String conflictKey, Observation winner, Observation loser,
                               String rule) {
}
