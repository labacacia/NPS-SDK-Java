// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.nop.TaskState;

/**
 * State and result for a single DAG node (subtask).
 */
public final class NopSubtaskRecord {

    private final String nodeId;
    private final String subtaskId;

    private volatile TaskState state;
    private volatile JsonNode result;
    private volatile String errorCode;
    private volatile String errorMessage;
    private volatile int attemptCount;

    public NopSubtaskRecord(String nodeId, String subtaskId) {
        this.nodeId = nodeId;
        this.subtaskId = subtaskId;
    }

    public String nodeId()            { return nodeId; }
    public String subtaskId()         { return subtaskId; }
    public TaskState state()          { return state; }
    public void state(TaskState s)    { this.state = s; }
    public JsonNode result()          { return result; }
    public void result(JsonNode r)    { this.result = r; }
    public String errorCode()         { return errorCode; }
    public void errorCode(String c)   { this.errorCode = c; }
    public String errorMessage()      { return errorMessage; }
    public void errorMessage(String m){ this.errorMessage = m; }
    public int attemptCount()         { return attemptCount; }
    public void attemptCount(int a)   { this.attemptCount = a; }
}
