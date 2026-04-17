// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnchorFrame implements NpsFrame {

    private final String            anchorId;
    private final Map<String, Object> schema;
    private final int               ttl;

    public AnchorFrame(String anchorId, Map<String, Object> schema, int ttl) {
        this.anchorId = anchorId;
        this.schema   = schema;
        this.ttl      = ttl;
    }

    public AnchorFrame(String anchorId, Map<String, Object> schema) {
        this(anchorId, schema, 3600);
    }

    @Override public FrameType    frameType()    { return FrameType.ANCHOR; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String              anchorId() { return anchorId; }
    public Map<String, Object> schema()   { return schema;   }
    public int                 ttl()      { return ttl;       }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchor_id", anchorId);
        m.put("schema",    schema);
        m.put("ttl",       ttl);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static AnchorFrame fromDict(Map<String, Object> d) {
        Object ttlRaw = d.get("ttl");
        int ttl = ttlRaw instanceof Number n ? n.intValue() : 3600;
        return new AnchorFrame(
            (String) d.get("anchor_id"),
            (Map<String, Object>) d.get("schema"),
            ttl
        );
    }
}
