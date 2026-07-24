// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.labacacia.nps.nop.TaskState;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent record of a running or completed NOP task.
 */
public final class NopTaskRecord {

    private final String taskId;
    private final NopTask task;
    private final Instant startedAt;

    private volatile TaskState state;
    private volatile Instant completedAt;

    /** Per-node subtask records, keyed by DAG node ID. */
    private final Map<String, NopSubtaskRecord> subtasks = new ConcurrentHashMap<>();

    public NopTaskRecord(String taskId, NopTask task, TaskState state, Instant startedAt) {
        this.taskId = taskId;
        this.task = task;
        this.state = state;
        this.startedAt = startedAt;
    }

    public String taskId()          { return taskId; }
    public NopTask task()           { return task; }
    public Instant startedAt()      { return startedAt; }
    public TaskState state()        { return state; }
    public void state(TaskState s)  { this.state = s; }
    public Instant completedAt()    { return completedAt; }
    public void completedAt(Instant t) { this.completedAt = t; }
    public Map<String, NopSubtaskRecord> subtasks() { return subtasks; }
}
