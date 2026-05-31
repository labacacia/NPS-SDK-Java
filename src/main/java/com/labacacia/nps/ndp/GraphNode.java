// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A node entry in a {@link GraphFrame} (§5 format). */
public final class GraphNode {

    private final String       nid;
    private final String       clusterAnchor; // nullable
    private final List<String> nodeRoles;     // nullable

    public GraphNode(String nid, String clusterAnchor, List<String> nodeRoles) {
        this.nid           = nid;
        this.clusterAnchor = clusterAnchor;
        this.nodeRoles     = nodeRoles;
    }

    public GraphNode(String nid) {
        this(nid, null, null);
    }

    public String nid()              { return nid; }
    public String clusterAnchor()    { return clusterAnchor; }
    public List<String> nodeRoles()  { return nodeRoles; }

    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nid",            nid);
        m.put("cluster_anchor", clusterAnchor);
        m.put("node_roles",     nodeRoles);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static GraphNode fromDict(Map<String, Object> d) {
        return new GraphNode(
            (String) d.get("nid"),
            (String) d.get("cluster_anchor"),
            (List<String>) d.get("node_roles")
        );
    }
}
