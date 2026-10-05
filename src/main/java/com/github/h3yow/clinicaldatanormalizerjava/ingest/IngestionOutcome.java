package com.github.h3yow.clinicaldatanormalizerjava.ingest;

import java.util.List;

/**
 * 批量投递中一条消息的处理结果。
 *
 * @param status                ACCEPTED / REJECTED / DUPLICATE / DEFERRED
 * @param messageControlId      消息控制号（解析到 MSH-10 时存在，否则为 null）
 * @param acceptedCount         本次实际登记的观测事件数（去重后）
 * @param conflictDecisions     本次触发的冲突裁决（可追溯）
 * @param mergeDecisions        本次触发的更正/撤回/压制判定（可追溯）
 * @param errorStage            PARSE / NORMALIZE / null
 * @param errorReason           可区分的错误原因枚举名（成功时为 null）
 * @param errorMessage          错误详情
 */
public record IngestionOutcome(
        Status status,
        String messageControlId,
        int acceptedCount,
        List<ConflictDecision> conflictDecisions,
        List<MergeDecision> mergeDecisions,
        String errorStage,
        String errorReason,
        String errorMessage
) {

    public enum Status {
        ACCEPTED, REJECTED, DUPLICATE,
        /** 消息内全部更正/撤回意图均未找到目标：事件已登记待生效，未当作普通新增 */
        DEFERRED
    }

    public static IngestionOutcome accepted(String controlId, int acceptedCount,
                                            List<ConflictDecision> decisions) {
        return accepted(controlId, acceptedCount, decisions, List.of());
    }

    public static IngestionOutcome accepted(String controlId, int acceptedCount,
                                            List<ConflictDecision> decisions,
                                            List<MergeDecision> mergeDecisions) {
        return new IngestionOutcome(Status.ACCEPTED, controlId, acceptedCount,
                List.copyOf(decisions), List.copyOf(mergeDecisions), null, null, null);
    }

    public static IngestionOutcome deferred(String controlId, int acceptedCount,
                                            List<MergeDecision> mergeDecisions) {
        return new IngestionOutcome(Status.DEFERRED, controlId, acceptedCount,
                List.of(), List.copyOf(mergeDecisions), null, null, null);
    }

    public static IngestionOutcome duplicate(String controlId) {
        return new IngestionOutcome(Status.DUPLICATE, controlId, 0,
                List.of(), List.of(), null, null, null);
    }

    public static IngestionOutcome rejected(String controlId, String stage, String reason, String message) {
        return new IngestionOutcome(Status.REJECTED, controlId, 0,
                List.of(), List.of(), stage, reason, message);
    }
}
