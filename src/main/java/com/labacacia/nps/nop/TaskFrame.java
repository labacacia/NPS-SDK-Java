// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskFrame implements NpsFrame {

    private final String             taskId;
    private final Map<String,Object> dag;
    private final Integer            timeoutMs;      // nullable
    private final String             callbackUrl;    // nullable
    private final Map<String,Object> context;        // nullable
    private final String             priority;       // nullable
    private final Integer            depth;          // nullable

    public TaskFrame(String taskId, Map<String,Object> dag, Integer timeoutMs,
                     String callbackUrl, Map<String,Object> context,
                     String priority, Integer depth) {
        this.taskId      = taskId;
        this.dag         = dag;
        this.timeoutMs   = timeoutMs;
        this.callbackUrl = callbackUrl;
        this.context     = context;
        this.priority    = priority;
        this.depth       = depth;
    }

    public TaskFrame(String taskId, Map<String,Object> dag) {
        this(taskId, dag, null, null, null, null, null);
    }

    @Override public FrameType    frameType()    { return FrameType.TASK; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String taskId()           { return taskId; }
    public Map<String,Object> dag()  { return dag; }
    public Integer timeoutMs()       { return timeoutMs; }
    public String callbackUrl()      { return callbackUrl; }
    public Map<String,Object> context() { return context; }
    public String priority()         { return priority; }
    public Integer depth()           { return depth; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id",      taskId);
        m.put("dag",          dag);
        m.put("timeout_ms",   timeoutMs);
        m.put("callback_url", callbackUrl);
        m.put("context",      context);
        m.put("priority",     priority);
        m.put("depth",        depth);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TaskFrame fromDict(Map<String, Object> d) {
        Object tm = d.get("timeout_ms"), dep = d.get("depth");
        return new TaskFrame(
            (String) d.get("task_id"),
            (Map<String,Object>) d.get("dag"),
            tm instanceof Number n ? n.intValue() : null,
            (String) d.get("callback_url"),
            (Map<String,Object>) d.get("context"),
            (String) d.get("priority"),
            dep instanceof Number n ? n.intValue() : null
        );
    }
}
