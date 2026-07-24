// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.nop.TaskState;
import com.labacacia.nps.nop.orchestration.INopTaskStore;
import com.labacacia.nps.nop.orchestration.NopSubtaskRecord;
import com.labacacia.nps.nop.orchestration.NopTaskRecord;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Volatile, in-memory implementation of {@link INopTaskStore}. Suitable for testing and
 * single-process deployments. Not durable across restarts.
 */
public final class InMemoryNopTaskStore implements INopTaskStore {

    private final ConcurrentHashMap<String, NopTaskRecord> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(NopTaskRecord record) {
        if (tasks.putIfAbsent(record.taskId(), record) != null) {
            throw new IllegalStateException("Task already exists: " + record.taskId());
        }
    }

    @Override
    public NopTaskRecord get(String taskId) {
        return tasks.get(taskId);
    }

    @Override
    public void updateState(String taskId, TaskState state) {
        NopTaskRecord rec = tasks.get(taskId);
        if (rec != null) rec.state(state);
    }

    @Override
    public void updateSubtask(
        String taskId,
        String nodeId,
        String subtaskId,
        TaskState state,
        JsonNode result,
        String errorCode,
        String errorMsg,
        int attempt) {

        NopTaskRecord rec = tasks.get(taskId);
        if (rec == null) return;

        NopSubtaskRecord sub = rec.subtasks().computeIfAbsent(
            nodeId, k -> new NopSubtaskRecord(nodeId, subtaskId));

        synchronized (sub) {
            sub.state(state);
            sub.attemptCount(attempt);
            if (result != null)    sub.result(result);
            if (errorCode != null) sub.errorCode(errorCode);
            if (errorMsg != null)  sub.errorMessage(errorMsg);
        }
    }
}
