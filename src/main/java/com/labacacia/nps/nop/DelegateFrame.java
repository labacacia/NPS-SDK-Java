// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DelegateFrame implements NpsFrame {

    private final String             taskId;
    private final String             subtaskId;
    private final String             action;
    private final String             agentNid;
    private final Map<String,Object> inputs;              // nullable
    private final Map<String,Object> params;              // nullable
    private final String             idempotencyKey;      // nullable
    private final String             targetClusterAnchor; // nullable

    public DelegateFrame(String taskId, String subtaskId, String action, String agentNid,
                         Map<String,Object> inputs, Map<String,Object> params,
                         String idempotencyKey) {
        this(taskId, subtaskId, action, agentNid, inputs, params, idempotencyKey, null);
    }

    public DelegateFrame(String taskId, String subtaskId, String action, String agentNid,
                         Map<String,Object> inputs, Map<String,Object> params,
                         String idempotencyKey, String targetClusterAnchor) {
        this.taskId              = taskId;
        this.subtaskId           = subtaskId;
        this.action              = action;
        this.agentNid            = agentNid;
        this.inputs              = inputs;
        this.params              = params;
        this.idempotencyKey      = idempotencyKey;
        this.targetClusterAnchor = targetClusterAnchor;
    }

    @Override public FrameType    frameType()    { return FrameType.DELEGATE; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String taskId()               { return taskId; }
    public String subtaskId()            { return subtaskId; }
    public String action()               { return action; }
    public String agentNid()             { return agentNid; }
    public Map<String,Object> inputs()   { return inputs; }
    public Map<String,Object> params()   { return params; }
    public String idempotencyKey()       { return idempotencyKey; }
    public String targetClusterAnchor()  { return targetClusterAnchor; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parent_task_id",        taskId);
        m.put("subtask_id",            subtaskId);
        m.put("action",                action);
        m.put("target_agent_nid",      agentNid);
        m.put("inputs",                inputs);
        m.put("params",                params);
        m.put("idempotency_key",       idempotencyKey);
        m.put("target_cluster_anchor", targetClusterAnchor);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DelegateFrame fromDict(Map<String, Object> d) {
        return new DelegateFrame(
            (String) d.getOrDefault("parent_task_id", d.get("task_id")),
            (String) d.get("subtask_id"),
            (String) d.get("action"),
            (String) d.getOrDefault("target_agent_nid", d.get("agent_nid")),
            (Map<String,Object>) d.get("inputs"),
            (Map<String,Object>) d.get("params"),
            (String) d.get("idempotency_key"),
            (String) d.get("target_cluster_anchor")
        );
    }
}
