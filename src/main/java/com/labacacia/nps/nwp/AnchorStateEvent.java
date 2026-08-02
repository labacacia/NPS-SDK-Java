// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Anchor internal-state change. Wire {@code event_type}: {@code anchor_state};
 * payload {@code { "field": <sub-type tag>, "details": {...} }}.
 *
 * <p>The sub-type tag constants live here on the event type rather than on a shared
 * topology constant bag, mirroring the reference implementation. Subscribers MUST
 * ignore unknown sub-types, so the NPS-CR-0009 additions are safe for older peers.</p>
 */
public final class AnchorStateEvent extends TopologyEvent {

    /** NPS-2 §12.3 restart-and-rebase (pre-existing). */
    public static final String FIELD_VERSION_REBASED     = "version_rebased";
    /** NPS-CR-0009 §1.2 — cluster ownership moved to {@code successor_nid}. */
    public static final String FIELD_ANCHOR_FAILOVER     = "anchor_failover";
    /** NPS-CR-0009 §1.3 — the Anchor lost quorum and is read-only-degraded. */
    public static final String FIELD_ANCHOR_QUORUM_LOST  = "anchor_quorum_lost";

    public String field;
    public JsonNode details;

    public AnchorStateEvent() {}

    public AnchorStateEvent(long version, String field, JsonNode details) {
        super(version);
        this.field   = field;
        this.details = details;
    }

    // ── NPS-CR-0009 sub-type factories ───────────────────────────────────────

    /**
     * {@code anchor_failover} — ownership of the cluster transferred.
     *
     * @param successorNid the Anchor that took ownership
     * @param clusterEpoch the new, strictly greater epoch
     * @param reason       {@code "planned"} or {@code "active_lost"}; null ⇒ {@code "planned"}
     * @param version      post-event topology version
     */
    public static AnchorStateEvent failover(String successorNid, long clusterEpoch,
                                            String reason, long version) {
        return new AnchorStateEvent(version, FIELD_ANCHOR_FAILOVER,
            new AnchorFailoverDetails(successorNid, clusterEpoch, reason).toJson());
    }

    /** {@code anchor_failover} with {@code reason = "planned"} and {@code version = 0}. */
    public static AnchorStateEvent failover(String successorNid, long clusterEpoch) {
        return failover(successorNid, clusterEpoch, AnchorFailoverDetails.REASON_PLANNED, 0L);
    }

    /** {@code anchor_failover} with {@code version = 0}. */
    public static AnchorStateEvent failover(String successorNid, long clusterEpoch, String reason) {
        return failover(successorNid, clusterEpoch, reason, 0L);
    }

    /** {@code anchor_quorum_lost} — the Anchor can no longer reach a write quorum. */
    public static AnchorStateEvent quorumLost(int quorumSize, int available, long version) {
        return new AnchorStateEvent(version, FIELD_ANCHOR_QUORUM_LOST,
            new AnchorQuorumLostDetails(quorumSize, available).toJson());
    }

    /** {@code anchor_quorum_lost} with {@code version = 0}. */
    public static AnchorStateEvent quorumLost(int quorumSize, int available) {
        return quorumLost(quorumSize, available, 0L);
    }

    // ── Typed details accessors ──────────────────────────────────────────────

    /** Typed view of {@link #details} when {@link #field} is {@code anchor_failover}. */
    public AnchorFailoverDetails failoverDetails() {
        return FIELD_ANCHOR_FAILOVER.equals(field) ? AnchorFailoverDetails.fromJson(details) : null;
    }

    /** Typed view of {@link #details} when {@link #field} is {@code anchor_quorum_lost}. */
    public AnchorQuorumLostDetails quorumLostDetails() {
        return FIELD_ANCHOR_QUORUM_LOST.equals(field) ? AnchorQuorumLostDetails.fromJson(details) : null;
    }
}
