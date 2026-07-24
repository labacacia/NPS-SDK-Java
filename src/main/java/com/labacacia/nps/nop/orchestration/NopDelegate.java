// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.nop.models.TaskContext;

/**
 * Sub-task delegation request handed to {@link INopWorkerClient#delegate} (NPS-5 §3.2).
 *
 * <p>Engine-facing analog of the .NET {@code DelegateFrame}. It carries the resolved
 * parameters (post {@code input_mapping}) and delegation metadata for a single DAG node.
 *
 * @param parentTaskId  Parent task ID.
 * @param subtaskId     Sub-task unique identifier (UUID v4).
 * @param nodeId        Corresponding DAG node ID.
 * @param targetAgentNid Target Worker Agent NID.
 * @param action        Operation URL ({@code nwp://}) or special action.
 * @param params        Operation parameters (post input_mapping resolution).
 * @param deadlineAt    Sub-task deadline (ISO 8601 UTC).
 * @param idempotencyKey Idempotency key for safe retries.
 * @param priority      Inherited from the task priority.
 * @param context       Transparent context (inherited from the task).
 * @param delegateDepth Delegation chain depth at which this frame is dispatched (1 = first level).
 */
public record NopDelegate(
    String parentTaskId,
    String subtaskId,
    String nodeId,
    String targetAgentNid,
    String action,
    JsonNode params,
    String deadlineAt,
    String idempotencyKey,
    String priority,
    TaskContext context,
    int delegateDepth) {
}
