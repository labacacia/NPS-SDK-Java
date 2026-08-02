// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.NpsStatusCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NPS-CR-0009 §5.2 / §5.6 — the {@code anchor_failover} and {@code anchor_quorum_lost}
 * {@code anchor_state} sub-types, {@code TopologySnapshot.cluster_epoch}, and the
 * epoch fence + leader check.
 *
 * <p>These assert on the wire key names inside {@code details}, so a rename fails.</p>
 */
class NwpAnchorFailoverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── §5.2 sub-type wire shapes ────────────────────────────────────────────

    @Test
    void failoverEventCarriesSuccessorEpochReason() {
        AnchorStateEvent ev = AnchorStateEvent.failover(
            "urn:nps:node:x:anchor-b", 3L, AnchorFailoverDetails.REASON_ACTIVE_LOST);

        assertEquals("anchor_failover", ev.field);
        assertEquals("urn:nps:node:x:anchor-b", ev.details.get("successor_nid").asText());
        assertEquals(3L, ev.details.get("cluster_epoch").asLong());
        assertEquals("active_lost", ev.details.get("reason").asText());
    }

    @Test
    void quorumLostEventCarriesCounts() {
        AnchorStateEvent ev = AnchorStateEvent.quorumLost(3, 1);

        assertEquals("anchor_quorum_lost", ev.field);
        assertEquals(3, ev.details.get("quorum_size").asInt());
        assertEquals(1, ev.details.get("available").asInt());
    }

    @Test
    void failoverReasonDefaultsToPlanned() {
        AnchorStateEvent ev = AnchorStateEvent.failover("urn:nps:node:x:anchor-b", 2L);
        assertEquals("planned", ev.details.get("reason").asText());
    }

    @Test
    void subTypeTagConstantsLiveOnTheEventType() {
        assertEquals("version_rebased",    AnchorStateEvent.FIELD_VERSION_REBASED);
        assertEquals("anchor_failover",    AnchorStateEvent.FIELD_ANCHOR_FAILOVER);
        assertEquals("anchor_quorum_lost", AnchorStateEvent.FIELD_ANCHOR_QUORUM_LOST);
    }

    @Test
    void typedDetailsAccessorsRoundTrip() {
        AnchorFailoverDetails f =
            AnchorStateEvent.failover("urn:nps:node:x:anchor-b", 5L, "active_lost").failoverDetails();
        assertEquals("urn:nps:node:x:anchor-b", f.successorNid());
        assertEquals(5L, f.clusterEpoch());
        assertEquals("active_lost", f.reason());
        assertNull(AnchorStateEvent.failover("a", 1L).quorumLostDetails());

        AnchorQuorumLostDetails q = AnchorStateEvent.quorumLost(5, 2).quorumLostDetails();
        assertEquals(5, q.quorumSize());
        assertEquals(2, q.available());
    }

    /** Full envelope round-trip: {@code event_type == "anchor_state"}, payload {field, details}. */
    @Test
    void failoverEnvelopeSerialisesAsAnchorState() throws Exception {
        AnchorStateEvent ev = AnchorStateEvent.failover("urn:nps:node:x:anchor-b", 4L, "planned", 12L);

        Map<String, Object> payload = Map.of("field", ev.field, "details", ev.details);
        Map<String, Object> envelope = Map.of(
            "event_type", "anchor_state", "seq", ev.version, "payload", payload);

        JsonNode back = MAPPER.readTree(MAPPER.writeValueAsBytes(envelope));
        assertEquals("anchor_state", back.get("event_type").asText());
        assertEquals(12L, back.get("seq").asLong());
        assertEquals("anchor_failover", back.get("payload").get("field").asText());
        assertEquals(4L, back.get("payload").get("details").get("cluster_epoch").asLong());
    }

    // ── §1.4 TopologySnapshot.cluster_epoch ──────────────────────────────────

    @Test
    void snapshotClusterEpochUsesTheExplicitWireKeyAndIsOmittedWhenNull() throws Exception {
        var mapper = new ObjectMapper()
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

        TopologySnapshot stamped = new TopologySnapshot(7L, "urn:nps:node:x:a", 0, List.of(), null, 3L);
        assertTrue(mapper.writeValueAsString(stamped).contains("\"cluster_epoch\":3"));

        TopologySnapshot bare = new TopologySnapshot(7L, "urn:nps:node:x:a", 0, List.of(), null);
        assertFalse(mapper.writeValueAsString(bare).contains("cluster_epoch"));
        assertEquals(1L, bare.effectiveClusterEpoch());

        TopologySnapshot parsed = mapper.readValue(
            "{\"version\":1,\"anchor_nid\":\"n\",\"cluster_size\":0,\"cluster_epoch\":9}",
            TopologySnapshot.class);
        assertEquals(9L, parsed.clusterEpoch);
    }

    // ── §2 error codes ───────────────────────────────────────────────────────

    @Test
    void anchorHaErrorCodesMapToClientConflict() {
        assertEquals("NWP-ANCHOR-NOT-LEADER",   NwpErrorCodes.NWP_ANCHOR_NOT_LEADER);
        assertEquals("NWP-ANCHOR-EPOCH-FENCED", NwpErrorCodes.NWP_ANCHOR_EPOCH_FENCED);
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_ANCHOR_NOT_LEADER));
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_ANCHOR_EPOCH_FENCED));
        assertEquals(409, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_CLIENT_CONFLICT));
    }

    // ── §3.2 / §5.6 the epoch fence ──────────────────────────────────────────

    @Test
    void standbyRejectsTopologyWritesWithNotLeader() {
        var fence = new AnchorEpochFence("urn:nps:node:x:anchor-a", 2L, AnchorEpochFence.Role.STANDBY);

        var ex = assertThrows(TopologyProtocolException.class,
            () -> fence.checkInbound(2L, "urn:nps:node:x:anchor-b", true));

        assertEquals(NwpErrorCodes.NWP_ANCHOR_NOT_LEADER, ex.nwpErrorCode);
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT, ex.npsStatus);
        assertEquals(409, ex.httpStatus());
    }

    @Test
    void quorumLostActiveOwnerAlsoRejectsWrites() {
        var fence = new AnchorEpochFence("urn:nps:node:x:anchor-a");
        fence.onQuorumLost(3, 1);

        assertTrue(fence.degraded());
        assertEquals("degraded", fence.healthHint());
        assertEquals(AnchorEpochFence.Role.ACTIVE, fence.role());

        var ex = assertThrows(TopologyProtocolException.class,
            () -> fence.checkInbound(1L, "urn:nps:node:x:anchor-b", true));
        assertEquals(NwpErrorCodes.NWP_ANCHOR_NOT_LEADER, ex.nwpErrorCode);

        // …but reads still succeed.
        assertDoesNotThrow(() -> fence.checkInbound(1L, "urn:nps:node:x:anchor-b", false));

        AnchorStateEvent ev = fence.emittedEvents().get(0);
        assertEquals("anchor_quorum_lost", ev.field);
        assertEquals(3, ev.details.get("quorum_size").asInt());
    }

    @Test
    void fencedLeaderRejectsAHigherEpochInboundFrame() {
        var fence = new AnchorEpochFence("urn:nps:node:x:anchor-a", 2L, AnchorEpochFence.Role.ACTIVE);
        boolean[] streamsClosed = {false};
        fence.onFenceCloseStreams(() -> streamsClosed[0] = true);

        // The fence applies to ANY inbound frame — this one is a read.
        var ex = assertThrows(TopologyProtocolException.class,
            () -> fence.checkInbound(3L, "urn:nps:node:x:anchor-b", false));

        assertEquals(NwpErrorCodes.NWP_ANCHOR_EPOCH_FENCED, ex.nwpErrorCode);
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT, ex.npsStatus);
        assertEquals(AnchorEpochFence.Role.STANDBY, fence.role());
        assertTrue(streamsClosed[0], "the fence must close all topology streams");

        AnchorStateEvent terminal = fence.emittedEvents().get(0);
        assertEquals("anchor_failover", terminal.field);
        assertEquals("urn:nps:node:x:anchor-b", terminal.details.get("successor_nid").asText());
        assertEquals(3L, terminal.details.get("cluster_epoch").asLong());
        assertEquals("active_lost", terminal.details.get("reason").asText());
    }

    @Test
    void equalOrLowerInboundEpochIsAcceptedNotFenced() {
        var fence = new AnchorEpochFence("urn:nps:node:x:anchor-a", 5L, AnchorEpochFence.Role.ACTIVE);

        // The asymmetry with NDP resolution: only STRICTLY greater fences here.
        assertDoesNotThrow(() -> fence.checkInbound(5L, "urn:nps:node:x:anchor-b", true));
        assertDoesNotThrow(() -> fence.checkInbound(4L, "urn:nps:node:x:anchor-b", true));
        assertDoesNotThrow(() -> fence.checkInbound(null, "urn:nps:node:x:anchor-b", true));

        assertEquals(AnchorEpochFence.Role.ACTIVE, fence.role());
        assertTrue(fence.emittedEvents().isEmpty());
    }

    @Test
    void readsSucceedOnAStandby() {
        var fence = new AnchorEpochFence("urn:nps:node:x:anchor-a", 4L, AnchorEpochFence.Role.STANDBY);
        assertDoesNotThrow(() -> fence.checkInbound(4L, "urn:nps:node:x:anchor-b", false));
    }

    @Test
    void everySnapshotResponseCarriesClusterEpoch() {
        var fence = new AnchorEpochFence("urn:nps:node:x:anchor-a", 6L, AnchorEpochFence.Role.ACTIVE);
        TopologySnapshot snap = fence.stampResponse(
            new TopologySnapshot(1L, "urn:nps:node:x:anchor-a", 0, List.of(), null));
        assertEquals(6L, snap.clusterEpoch);

        // A standby stamps its last-known epoch on the stale read it serves.
        var standby = new AnchorEpochFence("urn:nps:node:x:anchor-a", 2L, AnchorEpochFence.Role.STANDBY);
        assertEquals(2L, standby.stampResponse(
            new TopologySnapshot(1L, "urn:nps:node:x:anchor-a", 0, List.of(), null)).clusterEpoch);
    }

    @Test
    void takingOwnershipRequiresAStrictlyGreaterEpoch() {
        var fence = new AnchorEpochFence("urn:nps:node:x:anchor-b", 1L, AnchorEpochFence.Role.STANDBY);

        assertThrows(IllegalArgumentException.class, () -> fence.onTakeOwnership(1L));

        AnchorStateEvent ev = fence.onTakeOwnership(2L, AnchorFailoverDetails.REASON_ACTIVE_LOST);
        assertEquals(2L, fence.ownEpoch());
        assertEquals(AnchorEpochFence.Role.ACTIVE, fence.role());
        assertFalse(fence.degraded());
        assertEquals("urn:nps:node:x:anchor-b", ev.details.get("successor_nid").asText());
        assertEquals("active_lost", ev.details.get("reason").asText());
    }

    @Test
    void ownershipMustExceedEveryEpochEverObserved() {
        var fence = new AnchorEpochFence("urn:nps:node:x:anchor-a", 2L, AnchorEpochFence.Role.ACTIVE);
        // Observing epoch 7 fences us and records 7 as the high-water mark.
        assertThrows(TopologyProtocolException.class,
            () -> fence.checkInbound(7L, "urn:nps:node:x:anchor-b", false));

        assertThrows(IllegalArgumentException.class, () -> fence.onTakeOwnership(7L));
        assertDoesNotThrow(() -> fence.onTakeOwnership(8L));
    }
}
