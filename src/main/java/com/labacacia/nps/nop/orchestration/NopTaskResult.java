// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.nop.NopErrorCodes;
import com.labacacia.nps.nop.TaskState;

import java.util.Map;

/**
 * Final result returned by {@link INopOrchestrator#execute} (NPS-5 §5).
 *
 * @param taskId           Task identifier from the original task definition.
 * @param finalState       Terminal state: {@code COMPLETED}, {@code FAILED}, or {@code CANCELLED}.
 * @param aggregatedResult Aggregated result from all terminal nodes; null on failure or all-skipped.
 * @param errorCode        Error code when {@code finalState} is not {@code COMPLETED}.
 * @param errorMessage     Human-readable error description.
 * @param nodeResults      Per-node results keyed by DAG node ID (only successful nodes).
 * @param compensation     Saga compensation outcome, or null when none was attempted.
 */
public record NopTaskResult(
    String taskId,
    TaskState finalState,
    JsonNode aggregatedResult,
    String errorCode,
    String errorMessage,
    Map<String, JsonNode> nodeResults,
    SagaCompensationResult compensation) {

    public static NopTaskResult success(
        String taskId,
        JsonNode aggregatedResult,
        Map<String, JsonNode> nodeResults,
        SagaCompensationResult compensation) {
        return new NopTaskResult(taskId, TaskState.COMPLETED, aggregatedResult,
            null, null, nodeResults, compensation);
    }

    public static NopTaskResult failure(
        String taskId,
        String errorCode,
        String errorMessage,
        SagaCompensationResult compensation) {
        return new NopTaskResult(taskId, TaskState.FAILED, null,
            errorCode, errorMessage, Map.of(), compensation);
    }

    public static NopTaskResult failure(String taskId, String errorCode, String errorMessage) {
        return failure(taskId, errorCode, errorMessage, null);
    }

    public static NopTaskResult cancelled(String taskId, String reason) {
        return new NopTaskResult(taskId, TaskState.CANCELLED, null,
            NopErrorCodes.NOP_TASK_CANCELLED, reason, Map.of(), null);
    }
}
