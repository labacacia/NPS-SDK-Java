// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.models;

import java.util.List;

/**
 * DAG (Directed Acyclic Graph) definition for a task (NPS-5 §3.1.1).
 *
 * @param nodes DAG vertices — each represents a sub-task to execute.
 * @param edges Directed edges defining execution order and data flow.
 */
public record TaskDag(List<TaskDagNode> nodes, List<DagEdge> edges) {
}
