// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ResolveFrame implements NpsFrame {

    private final String             target;
    private final String             requesterNid; // nullable
    private final Map<String,Object> resolved;     // nullable

    public ResolveFrame(String target, String requesterNid, Map<String,Object> resolved) {
        this.target       = target;
        this.requesterNid = requesterNid;
        this.resolved     = resolved;
    }

    public ResolveFrame(String target) { this(target, null, null); }

    @Override public FrameType    frameType()    { return FrameType.RESOLVE; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String target()               { return target; }
    public String requesterNid()         { return requesterNid; }
    public Map<String,Object> resolved() { return resolved; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target",        target);
        m.put("requester_nid", requesterNid);
        m.put("resolved",      resolved);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ResolveFrame fromDict(Map<String, Object> d) {
        return new ResolveFrame(
            (String) d.get("target"),
            (String) d.get("requester_nid"),
            (Map<String,Object>) d.get("resolved")
        );
    }
}
