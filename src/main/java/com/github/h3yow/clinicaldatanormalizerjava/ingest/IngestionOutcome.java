package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import java.util.List;

/**
 * 批量投递中一条消息的处理结果。
 *
 * @param status                ACCEPTED / REJECTED / DUPLICATE
 * @param messageControlId      消息控制号（解析到 MSH-10 时存在，否则为 null）
 * @param acceptedCount         本次实际新增的观测数（内容库去重后）
 * @param conflictDecisions     本次触发的冲突裁决（可追溯）
 * @param amendmentDecisions    本次触发的更正/撤回处置（可追溯）
 * @param errorStage            PARSE / NORMALIZE / MERGE / null
 * @param errorReason           可区分的错误原因枚举名（成功时为 null）
 * @param errorMessage          错误详情
 */
public record IngestionOutcome(
        Status status,
        String messageControlId,
        int acceptedCount,
        List<ConflictDecision> conflictDecisions,
        List<AmendmentDecision> amendmentDecisions,
        String errorStage,
        String errorReason,
        String errorMessage
) {

    public enum Status {
        ACCEPTED, REJECTED, DUPLICATE
    }

    public static IngestionOutcome accepted(String controlId, int acceptedCount,
                                            List<ConflictDecision> decisions,
                                            List<AmendmentDecision> amendments) {
        return new IngestionOutcome(Status.ACCEPTED, controlId, acceptedCount,
                List.copyOf(decisions), List.copyOf(amendments), null, null, null);
    }

    public static IngestionOutcome duplicate(String controlId) {
        return new IngestionOutcome(Status.DUPLICATE, controlId, 0, List.of(), List.of(),
                null, null, null);
    }

    public static IngestionOutcome rejected(String controlId, String stage, String reason, String message) {
        return new IngestionOutcome(Status.REJECTED, controlId, 0, List.of(), List.of(),
                stage, reason, message);
    }
}
