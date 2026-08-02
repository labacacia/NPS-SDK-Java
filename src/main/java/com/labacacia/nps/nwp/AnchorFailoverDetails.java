// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code details} payload of the {@code anchor_state} sub-type {@code anchor_failover}
 * (NPS-CR-0009 §1.2, NWP v0.18).
 *
 * <p>Wire keys, all required: {@code successor_nid}, {@code cluster_epoch},
 * {@code reason}. Not signed.</p>
 */
public record AnchorFailoverDetails(String successorNid, long clusterEpoch, String reason) {

    /** {@code reason} enum member — an orderly, operator-initiated transfer. Factory default. */
    public static final String REASON_PLANNED     = "planned";
    /** {@code reason} enum member — the previous active Anchor was lost. */
    public static final String REASON_ACTIVE_LOST = "active_lost";

    public AnchorFailoverDetails {
        if (reason == null || reason.isEmpty()) reason = REASON_PLANNED;
    }

    /** Convenience: {@code reason = "planned"}. */
    public AnchorFailoverDetails(String successorNid, long clusterEpoch) {
        this(successorNid, clusterEpoch, REASON_PLANNED);
    }

    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("successor_nid", successorNid);
        m.put("cluster_epoch", clusterEpoch);
        m.put("reason",        reason);
        return m;
    }

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("successor_nid", successorNid);
        n.put("cluster_epoch", clusterEpoch);
        n.put("reason",        reason);
        return n;
    }

    public static AnchorFailoverDetails fromJson(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        return new AnchorFailoverDetails(
            n.path("successor_nid").asText(null),
            n.path("cluster_epoch").asLong(1L),
            n.path("reason").asText(REASON_PLANNED));
    }
}
