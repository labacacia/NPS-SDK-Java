// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.nop.TaskState;

/**
 * Persistence abstraction for NOP task and subtask state (NPS-5 §5).
 */
public interface INopTaskStore {

    /** Persists a new task record. Throws if the task ID already exists. */
    void save(NopTaskRecord record);

    /** Returns the task record, or {@code null} if not found. */
    NopTaskRecord get(String taskId);

    /** Updates the overall task state. */
    void updateState(String taskId, TaskState state);

    /** Creates or updates a subtask record within the task. */
    void updateSubtask(
        String taskId,
        String nodeId,
        String subtaskId,
        TaskState state,
        JsonNode result,
        String errorCode,
        String errorMsg,
        int attempt);
}
