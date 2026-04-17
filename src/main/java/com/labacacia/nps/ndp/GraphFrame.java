// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GraphFrame implements NpsFrame {

    private final int                       seq;
    private final boolean                   initialSync;
    private final List<Map<String,Object>>  nodes; // nullable
    private final List<Map<String,Object>>  patch; // nullable

    public GraphFrame(int seq, boolean initialSync,
                      List<Map<String,Object>> nodes, List<Map<String,Object>> patch) {
        this.seq         = seq;
        this.initialSync = initialSync;
        this.nodes       = nodes;
        this.patch       = patch;
    }

    @Override public FrameType    frameType()    { return FrameType.GRAPH; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public int    seq()         { return seq; }
    public boolean initialSync(){ return initialSync; }
    public List<Map<String,Object>> nodes() { return nodes; }
    public List<Map<String,Object>> patch() { return patch; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq",          seq);
        m.put("initial_sync", initialSync);
        m.put("nodes",        nodes);
        m.put("patch",        patch);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static GraphFrame fromDict(Map<String, Object> d) {
        return new GraphFrame(
            ((Number) d.get("seq")).intValue(),
            (Boolean) d.get("initial_sync"),
            (List<Map<String,Object>>) d.get("nodes"),
            (List<Map<String,Object>>) d.get("patch")
        );
    }
}
