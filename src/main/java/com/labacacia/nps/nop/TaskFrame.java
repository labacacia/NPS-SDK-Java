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
    private final Integer            timeoutMs;          // nullable
    private final String             callbackUrl;        // nullable
    private final Map<String,Object> context;            // nullable
    private final String             priority;           // nullable
    private final Integer            depth;              // nullable
    private final String             compensationPolicy; // default "none"
    private final int                resultTtlSeconds;   // NOP v0.7; default 3600

    public TaskFrame(String taskId, Map<String,Object> dag, Integer timeoutMs,
                     String callbackUrl, Map<String,Object> context,
                     String priority, Integer depth) {
        this(taskId, dag, timeoutMs, callbackUrl, context, priority, depth, "none", 3600);
    }

    public TaskFrame(String taskId, Map<String,Object> dag, Integer timeoutMs,
                     String callbackUrl, Map<String,Object> context,
                     String priority, Integer depth, String compensationPolicy) {
        this(taskId, dag, timeoutMs, callbackUrl, context, priority, depth, compensationPolicy, 3600);
    }

    public TaskFrame(String taskId, Map<String,Object> dag, Integer timeoutMs,
                     String callbackUrl, Map<String,Object> context,
                     String priority, Integer depth, String compensationPolicy,
                     int resultTtlSeconds) {
        this.taskId              = taskId;
        this.dag                 = dag;
        this.timeoutMs           = timeoutMs;
        this.callbackUrl         = callbackUrl;
        this.context             = context;
        this.priority            = priority;
        this.depth               = depth;
        this.compensationPolicy  = compensationPolicy != null ? compensationPolicy : "none";
        this.resultTtlSeconds    = resultTtlSeconds > 0 ? resultTtlSeconds : 3600;
    }

    public TaskFrame(String taskId, Map<String,Object> dag) {
        this(taskId, dag, null, null, null, null, null, "none", 3600);
    }

    @Override public FrameType    frameType()    { return FrameType.TASK; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String taskId()               { return taskId; }
    public Map<String,Object> dag()      { return dag; }
    public Integer timeoutMs()           { return timeoutMs; }
    public String callbackUrl()          { return callbackUrl; }
    public Map<String,Object> context()  { return context; }
    public String priority()             { return priority; }
    public Integer depth()               { return depth; }
    public String compensationPolicy()   { return compensationPolicy; }
    public int    resultTtlSeconds()     { return resultTtlSeconds; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id",             taskId);
        m.put("dag",                 dag);
        m.put("timeout_ms",          timeoutMs);
        m.put("callback_url",        callbackUrl);
        m.put("context",             context);
        m.put("priority",            priority);
        m.put("depth",               depth);
        m.put("compensation_policy", compensationPolicy);
        m.put("result_ttl_seconds",  resultTtlSeconds);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TaskFrame fromDict(Map<String, Object> d) {
        Object tm = d.get("timeout_ms"), dep = d.get("depth");
        String cp = d.get("compensation_policy") instanceof String s ? s : "none";
        Object ttl = d.get("result_ttl_seconds");
        int resultTtl = ttl instanceof Number n ? n.intValue() : 3600;
        return new TaskFrame(
            (String) d.get("task_id"),
            (Map<String,Object>) d.get("dag"),
            tm instanceof Number n ? n.intValue() : null,
            (String) d.get("callback_url"),
            (Map<String,Object>) d.get("context"),
            (String) d.get("priority"),
            dep instanceof Number n ? n.intValue() : null,
            cp,
            resultTtl
        );
    }
}
