// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ActionFrame implements NpsFrame {

    private final String             actionId;
    private final Map<String,Object> params;         // nullable
    private final Boolean            async_;         // nullable
    private final String             idempotencyKey; // nullable
    private final Integer            timeoutMs;      // nullable
    private final String             callbackUrl;    // nullable (serialized as "callback_url")
    /** Priority hint: {@code "low"}, {@code "normal"}, or {@code "high"}. */
    private final String             priority;       // nullable (serialized as "priority")
    private final String             requestId;      // nullable (serialized as "request_id")

    public ActionFrame(String actionId, Map<String,Object> params, Boolean async_,
                       String idempotencyKey, Integer timeoutMs,
                       String callbackUrl, String priority, String requestId) {
        this.actionId       = actionId;
        this.params         = params;
        this.async_         = async_;
        this.idempotencyKey = idempotencyKey;
        this.timeoutMs      = timeoutMs;
        this.callbackUrl    = callbackUrl;
        this.priority       = priority;
        this.requestId      = requestId;
    }

    /** Convenience constructor — original 5-arg form (callbackUrl/priority/requestId = null). */
    public ActionFrame(String actionId, Map<String,Object> params, Boolean async_,
                       String idempotencyKey, Integer timeoutMs) {
        this(actionId, params, async_, idempotencyKey, timeoutMs, null, null, null);
    }

    public ActionFrame(String actionId) { this(actionId, null, null, null, null, null, null, null); }

    @Override public FrameType    frameType()    { return FrameType.ACTION; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String actionId()          { return actionId; }
    public Map<String,Object> params(){ return params; }
    public Boolean async_()           { return async_; }
    public String idempotencyKey()    { return idempotencyKey; }
    public Integer timeoutMs()        { return timeoutMs; }
    public String callbackUrl()       { return callbackUrl; }
    public String priority()          { return priority; }
    public String requestId()         { return requestId; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action_id",       actionId);
        m.put("action",          actionId);
        m.put("params",          params);
        m.put("async",           async_ != null ? async_ : false);
        m.put("idempotency_key", idempotencyKey);
        m.put("timeout_ms",      timeoutMs);
        m.put("callback_url",    callbackUrl);
        m.put("priority",        priority);
        m.put("request_id",      requestId);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ActionFrame fromDict(Map<String, Object> d) {
        Object tm = d.get("timeout_ms");
        Object action = d.containsKey("action_id") ? d.get("action_id") : d.get("action");
        return new ActionFrame(
            (String) action,
            (Map<String,Object>) d.get("params"),
            (Boolean) d.get("async"),
            (String) d.get("idempotency_key"),
            tm instanceof Number n ? n.intValue() : null,
            (String) d.get("callback_url"),
            (String) d.get("priority"),
            (String) d.get("request_id")
        );
    }
}
