// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code details} payload of the {@code anchor_state} sub-type
 * {@code anchor_quorum_lost} (NPS-CR-0009 §1.3, NWP v0.18).
 *
 * <p>Wire keys, both required: {@code quorum_size}, {@code available} (uint32).
 * Not signed. An Anchor that emits this enters read-only-degraded state and MUST
 * reject topology writes with {@code NWP-ANCHOR-NOT-LEADER}.</p>
 */
public record AnchorQuorumLostDetails(int quorumSize, int available) {

    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quorum_size", quorumSize);
        m.put("available",   available);
        return m;
    }

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("quorum_size", quorumSize);
        n.put("available",   available);
        return n;
    }

    public static AnchorQuorumLostDetails fromJson(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        return new AnchorQuorumLostDetails(
            n.path("quorum_size").asInt(0),
            n.path("available").asInt(0));
    }
}
