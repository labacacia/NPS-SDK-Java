// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.models;

/**
 * A directed edge in a task DAG (NPS-5 §3.1.1).
 *
 * @param from Source node ID.
 * @param to   Target node ID.
 */
public record DagEdge(String from, String to) {
}
