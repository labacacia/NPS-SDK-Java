// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NPS-CR-0009 §3.1 — highest-epoch cluster resolution and the equal-epoch split-brain
 * fault. Port of the reference {@code NdpClusterResolutionTests}.
 */
class NdpClusterResolutionTest {

    private static final String CLUSTER = "urn:nps:cluster:api.test:main";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AnnounceFrame member(String nid, Long epoch) {
        return member(nid, epoch, 3600);
    }

    private static AnnounceFrame member(String nid, Long epoch, int ttl) {
        return new AnnounceFrame(
            nid,
            List.of(Map.of("host", "10.0.0.1", "port", 17433, "protocol", "nwp")),
            List.of("topology.read"),
            ttl,
            "2026-07-05T00:00:00Z",
            "ed25519:placeholder",
            "anchor",
            List.of("anchor"),
            CLUSTER,
            epoch,
            null, null, null, null,
            60_000,
            null, null);
    }

    // ── §5.1 ─────────────────────────────────────────────────────────────────

    @Test
    void resolvesTheHighestEpochActiveAnchor() {
        var reg = new InMemoryNdpRegistry();
        reg.announce(member("urn:nps:node:api.test:anchor-a", 1L));
        reg.announce(member("urn:nps:node:api.test:anchor-b", 3L));

        AnnounceFrame active = reg.resolveCluster(CLUSTER);

        assertNotNull(active);
        assertEquals("urn:nps:node:api.test:anchor-b", active.nid());
        assertEquals(3L, active.clusterEpoch());
    }

    @Test
    void absentEpochIsTreatedAsOne() {
        var reg = new InMemoryNdpRegistry();
        reg.announce(member("urn:nps:node:api.test:anchor-a", null));

        AnnounceFrame active = assertDoesNotThrow(() -> reg.resolveCluster(CLUSTER));

        assertNotNull(active);
        assertEquals("urn:nps:node:api.test:anchor-a", active.nid());
        // Coercion happens at comparison time only — the stored frame keeps its null.
        assertNull(active.clusterEpoch());
        assertEquals(1L, active.effectiveClusterEpoch());
    }

    @Test
    void splitBrainAtTheTopEpochThrows() {
        var reg = new InMemoryNdpRegistry();
        reg.announce(member("urn:nps:node:api.test:anchor-a", 2L));
        reg.announce(member("urn:nps:node:api.test:anchor-b", 2L));

        var ex = assertThrows(NdpClusterSplitException.class, () -> reg.resolveCluster(CLUSTER));

        assertEquals("NDP-CLUSTER-SPLIT", ex.errorCode());
        assertEquals(2L, ex.epoch());
        assertEquals(CLUSTER, ex.clusterAnchor());
    }

    @Test
    void noLiveMembersResolvesToNull() {
        var reg = new InMemoryNdpRegistry();
        assertNull(assertDoesNotThrow(() -> reg.resolveCluster(CLUSTER)));
    }

    // ── §5.1 "ports SHOULD add" ──────────────────────────────────────────────

    @Test
    void twoMembersBothOmittingEpochSplit() {
        var reg = new InMemoryNdpRegistry();
        reg.announce(member("urn:nps:node:api.test:anchor-a", null));
        reg.announce(member("urn:nps:node:api.test:anchor-b", null));

        var ex = assertThrows(NdpClusterSplitException.class, () -> reg.resolveCluster(CLUSTER));
        assertEquals(1L, ex.epoch());
    }

    @Test
    void ttlExpiredMemberIsExcludedFromTheElection() {
        var reg = new InMemoryNdpRegistry();
        long[] now = {0L};
        reg.clock = () -> now[0];
        reg.announce(member("urn:nps:node:api.test:anchor-a", 1L, 3600));
        reg.announce(member("urn:nps:node:api.test:anchor-b", 3L, 10));

        assertEquals("urn:nps:node:api.test:anchor-b", reg.resolveCluster(CLUSTER).nid());

        now[0] = 20_000L; // anchor-b's 10 s TTL has elapsed
        assertEquals("urn:nps:node:api.test:anchor-a", reg.resolveCluster(CLUSTER).nid());
    }

    @Test
    void ttlZeroAnnounceEvictsAndChangesTheWinner() {
        var reg = new InMemoryNdpRegistry();
        reg.announce(member("urn:nps:node:api.test:anchor-a", 1L));
        reg.announce(member("urn:nps:node:api.test:anchor-b", 3L));
        assertEquals("urn:nps:node:api.test:anchor-b", reg.resolveCluster(CLUSTER).nid());

        // Orderly shutdown of the epoch-3 owner.
        reg.announce(member("urn:nps:node:api.test:anchor-b", 3L, 0));

        assertEquals("urn:nps:node:api.test:anchor-a", reg.resolveCluster(CLUSTER).nid());
    }

    @Test
    void membersOfAnotherClusterDoNotParticipate() {
        var reg = new InMemoryNdpRegistry();
        reg.announce(member("urn:nps:node:api.test:anchor-a", 1L));
        reg.announce(new AnnounceFrame(
            "urn:nps:node:api.test:other", List.of(Map.of("host", "10.0.0.2", "port", 17433)),
            List.of(), 3600, "2026-07-05T00:00:00Z", "ed25519:placeholder", "anchor",
            List.of("anchor"), "urn:nps:cluster:api.test:other", 9L,
            null, null, null, null, 60_000, null, null));

        assertEquals("urn:nps:node:api.test:anchor-a", reg.resolveCluster(CLUSTER).nid());
    }

    @Test
    void blankClusterAnchorIsRejected() {
        var reg = new InMemoryNdpRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.resolveCluster(null));
        assertThrows(IllegalArgumentException.class, () -> reg.resolveCluster(""));
    }

    @Test
    void clusterSplitMapsToClientConflict() {
        assertEquals("NPS-CLIENT-CONFLICT",
            NdpErrorCodes.NDP_TO_NPS_STATUS.get(NdpErrorCodes.NDP_CLUSTER_SPLIT));
    }

    // ── §5.5 signature canonical-form regression ─────────────────────────────

    @Test
    void canonicalJsonContainsClusterEpochWhenSet() throws Exception {
        AnnounceFrame f = member("urn:nps:node:api.test:anchor-b", 3L);
        String json = canonical(f);
        assertTrue(json.contains("\"cluster_epoch\":3"), json);
    }

    @Test
    void canonicalJsonOmitsClusterEpochWhenNullAndIsByteIdenticalToPreCr0009() throws Exception {
        // A frame that never carried an epoch must canonicalise EXACTLY as it did
        // before CR-0009 — this is what keeps existing signed announcements verifying.
        AnnounceFrame withEpoch = member("urn:nps:node:api.test:anchor-a", null);
        // The pre-CR-0009 16-arg constructor, unchanged in arity and meaning.
        AnnounceFrame preCr0009 = new AnnounceFrame(
            "urn:nps:node:api.test:anchor-a",
            List.of(Map.of("host", "10.0.0.1", "port", 17433, "protocol", "nwp")),
            List.of("topology.read"), 3600, "2026-07-05T00:00:00Z",
            "ed25519:placeholder", "anchor", List.of("anchor"), CLUSTER,
            null, null, null, null, 60_000, null, null);

        byte[] a = canonical(withEpoch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = canonical(preCr0009).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(canonical(withEpoch).contains("cluster_epoch"));
        assertArrayEquals(b, a);
    }

    @Test
    void canonicalJsonStillExcludesTheWireOnlyKeys() throws Exception {
        AnnounceFrame f = new AnnounceFrame(
            "urn:nps:node:api.test:anchor-a",
            List.of(Map.of("host", "10.0.0.1", "port", 17433)),
            List.of("topology.read"), 3600, "2026-07-05T00:00:00Z",
            "ed25519:sig", "anchor", List.of("anchor"), CLUSTER, 4L,
            null, null, null, null, 60_000, "degraded", "2026-07-05T00:01:00Z");

        String json = canonical(f);
        assertFalse(json.contains("\"signature\""));
        assertFalse(json.contains("\"health\""));
        assertFalse(json.contains("\"last_seen\""));
        assertTrue(json.contains("\"cluster_epoch\":4"));
        // …but they ARE on the wire.
        assertEquals("degraded", f.toDict().get("health"));
        assertEquals(4L, f.toDict().get("cluster_epoch"));
    }

    @Test
    void clusterEpochRoundTripsThroughFromDict() {
        AnnounceFrame f = member("urn:nps:node:api.test:anchor-b", 7L);
        AnnounceFrame back = AnnounceFrame.fromDict(f.toDict());
        assertEquals(7L, back.clusterEpoch());
        assertEquals(CLUSTER, back.clusterAnchor());

        AnnounceFrame none = AnnounceFrame.fromDict(member("urn:nps:node:api.test:a", null).toDict());
        assertNull(none.clusterEpoch());
        assertEquals(1L, none.effectiveClusterEpoch());
    }

    /** Reproduces {@code NdpAnnounceValidator}'s canonicalization exactly. */
    private static String canonical(AnnounceFrame f) throws Exception {
        return MAPPER.writeValueAsString(new TreeMap<>(f.unsignedDict()));
    }
}
