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
    private final Map<String,Object> inputs;         // nullable
    private final Map<String,Object> params;         // nullable
    private final String             idempotencyKey; // nullable

    public DelegateFrame(String taskId, String subtaskId, String action, String agentNid,
                         Map<String,Object> inputs, Map<String,Object> params,
                         String idempotencyKey) {
        this.taskId         = taskId;
        this.subtaskId      = subtaskId;
        this.action         = action;
        this.agentNid       = agentNid;
        this.inputs         = inputs;
        this.params         = params;
        this.idempotencyKey = idempotencyKey;
    }

    @Override public FrameType    frameType()    { return FrameType.DELEGATE; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String taskId()           { return taskId; }
    public String subtaskId()        { return subtaskId; }
    public String action()           { return action; }
    public String agentNid()         { return agentNid; }
    public Map<String,Object> inputs(){ return inputs; }
    public Map<String,Object> params(){ return params; }
    public String idempotencyKey()   { return idempotencyKey; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id",         taskId);
        m.put("subtask_id",      subtaskId);
        m.put("action",          action);
        m.put("agent_nid",       agentNid);
        m.put("inputs",          inputs);
        m.put("params",          params);
        m.put("idempotency_key", idempotencyKey);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DelegateFrame fromDict(Map<String, Object> d) {
        return new DelegateFrame(
            (String) d.get("task_id"),
            (String) d.get("subtask_id"),
            (String) d.get("action"),
            (String) d.get("agent_nid"),
            (Map<String,Object>) d.get("inputs"),
            (Map<String,Object>) d.get("params"),
            (String) d.get("idempotency_key")
        );
    }
}
