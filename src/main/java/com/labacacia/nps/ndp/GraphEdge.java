// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import java.util.LinkedHashMap;
import java.util.Map;

/** A directed edge between two nodes in a {@link GraphFrame} (§3.3 format). */
public final class GraphEdge {

    private final String  fromNid;    // mandatory
    private final String  toNid;      // mandatory
    private final Integer latencyMs;  // nullable
    private final String  protocol;   // nullable

    public GraphEdge(String fromNid, String toNid, Integer latencyMs, String protocol) {
        this.fromNid   = fromNid;
        this.toNid     = toNid;
        this.latencyMs = latencyMs;
        this.protocol  = protocol;
    }

    public GraphEdge(String fromNid, String toNid) {
        this(fromNid, toNid, null, null);
    }

    public String  fromNid()   { return fromNid; }
    public String  toNid()     { return toNid; }
    public Integer latencyMs() { return latencyMs; }
    public String  protocol()  { return protocol; }

    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from_nid",   fromNid);
        m.put("to_nid",     toNid);
        m.put("latency_ms", latencyMs);
        m.put("protocol",   protocol);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static GraphEdge fromDict(Map<String, Object> d) {
        Object lat = d.get("latency_ms");
        return new GraphEdge(
            (String) d.get("from_nid"),
            (String) d.get("to_nid"),
            lat instanceof Number n ? n.intValue() : null,
            (String) d.get("protocol")
        );
    }
}
