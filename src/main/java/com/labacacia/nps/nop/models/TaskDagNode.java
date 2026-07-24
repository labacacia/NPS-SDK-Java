// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.models;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * A single node (vertex) in a task DAG (NPS-5 §3.1.1).
 *
 * <p>Named {@code TaskDagNode} to avoid clashing with the flat wire-level
 * {@code com.labacacia.nps.nop.DagNode} used by {@code TaskFrame} serialization.
 *
 * @param id                      Node unique identifier (unique within the DAG).
 * @param action                  Operation URL ({@code nwp://...}).
 * @param agent                   Worker Agent NID that executes this node.
 * @param inputFrom               Upstream node IDs this node depends on. Empty/null for start nodes.
 * @param inputMapping            Upstream output -&gt; local input parameter mapping using JSONPath (NPS-5 §3.1.3).
 * @param timeoutMs               Per-node timeout in ms. Null means inherit {@code TaskFrame.timeoutMs}.
 * @param retryPolicy             Per-node retry strategy (NPS-5 §3.1.4).
 * @param condition               CEL subset condition; when false the node is skipped (NPS-5 §3.1.5).
 * @param minRequired             K-of-N: minimum {@code inputFrom} deps that must succeed. 0 means all (NPS-5 §3.3.1).
 * @param compensateAction        Saga compensation action URL called on rollback (NPS-5 §3.5). Null = none.
 * @param compensateParamsMapping Parameter mapping for the compensation call (NPS-5 §3.5.2).
 */
public record TaskDagNode(
    String id,
    String action,
    String agent,
    List<String> inputFrom,
    Map<String, JsonNode> inputMapping,
    Long timeoutMs,
    RetryPolicy retryPolicy,
    String condition,
    int minRequired,
    String compensateAction,
    Map<String, JsonNode> compensateParamsMapping) {

    /** Minimal factory: id, action, agent only. */
    public static TaskDagNode of(String id, String action, String agent) {
        return new Builder(id, action, agent).build();
    }

    /** Returns a mutable builder seeded from this node (for synthetic compensation nodes). */
    public Builder toBuilder() {
        return new Builder(id, action, agent)
            .inputFrom(inputFrom)
            .inputMapping(inputMapping)
            .timeoutMs(timeoutMs)
            .retryPolicy(retryPolicy)
            .condition(condition)
            .minRequired(minRequired)
            .compensateAction(compensateAction)
            .compensateParamsMapping(compensateParamsMapping);
    }

    /** Fluent builder for {@link TaskDagNode}. */
    public static final class Builder {
        private final String id;
        private String action;
        private final String agent;
        private List<String> inputFrom;
        private Map<String, JsonNode> inputMapping;
        private Long timeoutMs;
        private RetryPolicy retryPolicy;
        private String condition;
        private int minRequired;
        private String compensateAction;
        private Map<String, JsonNode> compensateParamsMapping;

        public Builder(String id, String action, String agent) {
            this.id = id;
            this.action = action;
            this.agent = agent;
        }

        public Builder action(String v)                             { this.action = v; return this; }
        public Builder inputFrom(List<String> v)                    { this.inputFrom = v; return this; }
        public Builder inputMapping(Map<String, JsonNode> v)        { this.inputMapping = v; return this; }
        public Builder timeoutMs(Long v)                            { this.timeoutMs = v; return this; }
        public Builder retryPolicy(RetryPolicy v)                   { this.retryPolicy = v; return this; }
        public Builder condition(String v)                          { this.condition = v; return this; }
        public Builder minRequired(int v)                           { this.minRequired = v; return this; }
        public Builder compensateAction(String v)                   { this.compensateAction = v; return this; }
        public Builder compensateParamsMapping(Map<String, JsonNode> v) { this.compensateParamsMapping = v; return this; }

        public TaskDagNode build() {
            return new TaskDagNode(id, action, agent, inputFrom, inputMapping, timeoutMs,
                retryPolicy, condition, minRequired, compensateAction, compensateParamsMapping);
        }
    }
}
