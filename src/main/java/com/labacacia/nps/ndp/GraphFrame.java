// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * NDP Graph frame (§3.3 format, alpha.11).
 *
 * <p>Replaces the previous seq/initial_sync/nodes/patch representation with a
 * stable graph snapshot keyed by {@code graphId}.
 */
public final class GraphFrame implements NpsFrame {

    private static final int MAX_GRAPH_NODES = 256;
    private static final int MAX_GRAPH_EDGES = 1024;

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
        validate();
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

    public void validate() {
        if (nodes == null || edges == null) {
            throw new IllegalArgumentException(NdpErrorCodes.NDP_GRAPH_INVALID + ": nodes and edges are required");
        }
        if (nodes.size() > MAX_GRAPH_NODES) {
            throw new IllegalArgumentException(
                NdpErrorCodes.NDP_GRAPH_TOO_LARGE + ": nodes length exceeds " + MAX_GRAPH_NODES);
        }
        if (edges.size() > MAX_GRAPH_EDGES) {
            throw new IllegalArgumentException(
                NdpErrorCodes.NDP_GRAPH_TOO_LARGE + ": edges length exceeds " + MAX_GRAPH_EDGES);
        }

        Set<String> nodeIds = new HashSet<>();
        for (GraphNode node : nodes) {
            if (node.nid() == null || node.nid().isEmpty()) {
                throw new IllegalArgumentException(NdpErrorCodes.NDP_GRAPH_INVALID + ": graph nodes require nid");
            }
            nodeIds.add(node.nid());
        }
        for (GraphEdge edge : edges) {
            if (edge.fromNid() == null || edge.fromNid().isEmpty() ||
                edge.toNid() == null || edge.toNid().isEmpty()) {
                throw new IllegalArgumentException(
                    NdpErrorCodes.NDP_GRAPH_INVALID + ": graph edges require from_nid and to_nid");
            }
            if (edge.fromNid().equals(edge.toNid())) {
                throw new IllegalArgumentException(
                    NdpErrorCodes.NDP_GRAPH_INVALID + ": graph self-edges are forbidden");
            }
            if (!nodeIds.contains(edge.fromNid()) || !nodeIds.contains(edge.toNid())) {
                throw new IllegalArgumentException(
                    NdpErrorCodes.NDP_GRAPH_INVALID + ": graph edge endpoints must appear in nodes");
            }
        }
    }

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
