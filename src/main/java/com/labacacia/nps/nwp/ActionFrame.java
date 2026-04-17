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

    public ActionFrame(String actionId, Map<String,Object> params, Boolean async_,
                       String idempotencyKey, Integer timeoutMs) {
        this.actionId       = actionId;
        this.params         = params;
        this.async_         = async_;
        this.idempotencyKey = idempotencyKey;
        this.timeoutMs      = timeoutMs;
    }

    public ActionFrame(String actionId) { this(actionId, null, null, null, null); }

    @Override public FrameType    frameType()    { return FrameType.ACTION; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String actionId()          { return actionId; }
    public Map<String,Object> params(){ return params; }
    public Boolean async_()           { return async_; }
    public String idempotencyKey()    { return idempotencyKey; }
    public Integer timeoutMs()        { return timeoutMs; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action_id",       actionId);
        m.put("params",          params);
        m.put("async",           async_ != null ? async_ : false);
        m.put("idempotency_key", idempotencyKey);
        m.put("timeout_ms",      timeoutMs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ActionFrame fromDict(Map<String, Object> d) {
        Object tm = d.get("timeout_ms");
        return new ActionFrame(
            (String) d.get("action_id"),
            (Map<String,Object>) d.get("params"),
            (Boolean) d.get("async"),
            (String) d.get("idempotency_key"),
            tm instanceof Number n ? n.intValue() : null
        );
    }
}
