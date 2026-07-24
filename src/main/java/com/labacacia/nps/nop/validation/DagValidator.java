// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.validation;

import com.labacacia.nps.nop.NopConstants;
import com.labacacia.nps.nop.NopErrorCodes;
import com.labacacia.nps.nop.models.DagEdge;
import com.labacacia.nps.nop.models.TaskDag;
import com.labacacia.nps.nop.models.TaskDagNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a {@link TaskDag} against NPS-5 §3.1.1 rules:
 * acyclicity, node count limit, start/end node presence, edge consistency,
 * and condition expression length. Uses Kahn's algorithm for topological sort.
 */
public final class DagValidator {
    private DagValidator() {}

    /**
     * Validates the given DAG and returns a topological ordering on success.
     */
    public static DagValidationResult validate(TaskDag dag) {
        List<TaskDagNode> nodes = dag.nodes() != null ? dag.nodes() : List.of();
        List<DagEdge> edges = dag.edges() != null ? dag.edges() : List.of();

        if (nodes.isEmpty()) {
            return DagValidationResult.failure(
                NopErrorCodes.NOP_TASK_DAG_INVALID,
                "DAG must contain at least one node.");
        }

        if (nodes.size() > NopConstants.MAX_DAG_NODES) {
            return DagValidationResult.failure(
                NopErrorCodes.NOP_TASK_DAG_TOO_LARGE,
                "DAG contains " + nodes.size() + " nodes, exceeding the maximum of "
                    + NopConstants.MAX_DAG_NODES + ".");
        }

        Set<String> nodeIds = new HashSet<>(nodes.size());
        for (TaskDagNode node : nodes) {
            if (!nodeIds.add(node.id())) {
                return DagValidationResult.failure(
                    NopErrorCodes.NOP_TASK_DAG_INVALID,
                    "Duplicate node ID: '" + node.id() + "'.");
            }
        }

        // Adjacency + in-degree over all node IDs
        Map<String, List<String>> adjacency = new HashMap<>(nodeIds.size());
        Map<String, Integer> inDegree = new HashMap<>(nodeIds.size());
        for (String id : nodeIds) {
            adjacency.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }

        for (DagEdge edge : edges) {
            if (!nodeIds.contains(edge.from())) {
                return DagValidationResult.failure(
                    NopErrorCodes.NOP_TASK_DAG_INVALID,
                    "Edge references unknown source node: '" + edge.from() + "'.");
            }
            if (!nodeIds.contains(edge.to())) {
                return DagValidationResult.failure(
                    NopErrorCodes.NOP_TASK_DAG_INVALID,
                    "Edge references unknown target node: '" + edge.to() + "'.");
            }
            adjacency.get(edge.from()).add(edge.to());
            inDegree.merge(edge.to(), 1, Integer::sum);
        }

        // Validate input_from references are consistent with the node set
        for (TaskDagNode node : nodes) {
            if (node.inputFrom() == null || node.inputFrom().isEmpty()) continue;
            for (String upstream : node.inputFrom()) {
                if (!nodeIds.contains(upstream)) {
                    return DagValidationResult.failure(
                        NopErrorCodes.NOP_TASK_DAG_INVALID,
                        "Node '" + node.id() + "' references unknown upstream node '"
                            + upstream + "' in input_from.");
                }
            }
        }

        // Must have at least one start node (no incoming edges)
        boolean hasStart = inDegree.values().stream().anyMatch(d -> d == 0);
        if (!hasStart) {
            return DagValidationResult.failure(
                NopErrorCodes.NOP_TASK_DAG_INVALID,
                "DAG must have at least one start node (no incoming edges).");
        }

        // Must have at least one end node (no outgoing edges)
        boolean hasEnd = adjacency.values().stream().anyMatch(List::isEmpty);
        if (!hasEnd) {
            return DagValidationResult.failure(
                NopErrorCodes.NOP_TASK_DAG_INVALID,
                "DAG must have at least one end node (no outgoing edges).");
        }

        // Validate condition expression lengths
        for (TaskDagNode node : nodes) {
            if (node.condition() != null && node.condition().length() > NopConstants.MAX_CONDITION_LENGTH) {
                return DagValidationResult.failure(
                    NopErrorCodes.NOP_CONDITION_EVAL_ERROR,
                    "Node '" + node.id() + "' condition expression exceeds "
                        + NopConstants.MAX_CONDITION_LENGTH + " characters.");
            }
        }

        // Kahn's algorithm for topological sort + cycle detection
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> remaining = new HashMap<>(inDegree);
        for (Map.Entry<String, Integer> e : remaining.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        List<String> sorted = new ArrayList<>(nodeIds.size());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            for (String neighbor : adjacency.get(current)) {
                int d = remaining.merge(neighbor, -1, Integer::sum);
                if (d == 0) queue.add(neighbor);
            }
        }

        if (sorted.size() != nodeIds.size()) {
            return DagValidationResult.failure(
                NopErrorCodes.NOP_TASK_DAG_CYCLE,
                "DAG contains a cycle.");
        }

        return DagValidationResult.success(sorted);
    }
}
