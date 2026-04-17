// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import java.util.Map;

/** Wraps the raw JSON status dict returned by the NOP gateway. */
public final class NopTaskStatus {

    private final Map<String, Object> raw;

    public NopTaskStatus(Map<String, Object> raw) { this.raw = raw; }

    public String    taskId()           { return (String) raw.get("task_id"); }
    public TaskState state()            { return TaskState.fromValue((String) raw.get("state")); }
    public boolean   isTerminal()       { return state().isTerminal(); }
    public Object    aggregatedResult() { return raw.get("aggregated_result"); }

    public String errorCode() {
        Object v = raw.get("error_code");
        return v instanceof String s ? s : null;
    }

    public String errorMessage() {
        Object v = raw.get("error_message");
        return v instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> nodeResults() {
        Object v = raw.get("node_results");
        return v instanceof Map<?,?> m ? (Map<String, Object>) m : Map.of();
    }

    public Map<String, Object> raw() { return raw; }

    @Override public String toString() {
        return "NopTaskStatus(taskId=" + taskId() + ", state=" + raw.get("state") + ")";
    }
}
