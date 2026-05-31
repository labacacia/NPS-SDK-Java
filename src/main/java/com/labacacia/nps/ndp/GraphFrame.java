// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * NDP Graph frame (§5 format, alpha.11).
 *
 * <p>Replaces the previous seq/initial_sync/nodes/patch representation with a
 * stable graph snapshot keyed by {@code graphId}.
 */
public final class GraphFrame implements NpsFrame {

    private final String               graphId;   // mandatory
    private final List<GraphNode>      nodes;     // mandatory
    private final List<GraphEdge>      edges;     // mandatory
    private final int                  ttl;       // mandatory
    private final Map<String,Object>   metadata;  // nullable

    public GraphFrame(String graphId, List<GraphNode> nodes, List<GraphEdge> edges,
                      int ttl, Map<String,Object> metadata) {
        this.graphId  = graphId;
        this.nodes    = nodes;
        this.edges    = edges;
        this.ttl      = ttl;
        this.metadata = metadata;
    }

    public GraphFrame(String graphId, List<GraphNode> nodes, List<GraphEdge> edges, int ttl) {
        this(graphId, nodes, edges, ttl, null);
    }

    @Override public FrameType    frameType()     { return FrameType.GRAPH; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String graphId()             { return graphId; }
    public List<GraphNode> nodes()      { return nodes; }
    public List<GraphEdge> edges()      { return edges; }
    public int ttl()                    { return ttl; }
    public Map<String,Object> metadata(){ return metadata; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("graph_id",  graphId);
        m.put("nodes",     nodes  != null ? nodes.stream().map(GraphNode::toDict).collect(Collectors.toList()) : null);
        m.put("edges",     edges  != null ? edges.stream().map(GraphEdge::toDict).collect(Collectors.toList()) : null);
        m.put("ttl",       ttl);
        m.put("metadata",  metadata);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static GraphFrame fromDict(Map<String, Object> d) {
        List<Map<String,Object>> rawNodes = (List<Map<String,Object>>) d.get("nodes");
        List<Map<String,Object>> rawEdges = (List<Map<String,Object>>) d.get("edges");
        List<GraphNode> nodes = rawNodes != null
            ? rawNodes.stream().map(GraphNode::fromDict).collect(Collectors.toList()) : null;
        List<GraphEdge> edges = rawEdges != null
            ? rawEdges.stream().map(GraphEdge::fromDict).collect(Collectors.toList()) : null;
        Object ttlRaw = d.get("ttl");
        return new GraphFrame(
            (String) d.get("graph_id"),
            nodes,
            edges,
            ttlRaw instanceof Number n ? n.intValue() : 0,
            (Map<String,Object>) d.get("metadata")
        );
    }
}
