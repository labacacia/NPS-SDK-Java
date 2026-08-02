// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.labacacia.nps.core.NpsStatusCodes;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** NPS-CR-0009 §5.4 — NOP cluster delegation re-resolution. */
class ClusterDelegationResolverTest {

    private static final String CLUSTER   = "urn:nps:cluster:x:main";
    private static final String AGENT_NID = "urn:nps:agent:x:w1";
    private static final String ANCHOR_A  = "urn:nps:node:x:anchor-a";
    private static final String ANCHOR_B  = "urn:nps:node:x:anchor-b";

    private static DelegateFrame frame(String targetClusterAnchor) {
        return new DelegateFrame("t1", "s1", "do", AGENT_NID,
            Map.of(), Map.of(), null, targetClusterAnchor);
    }

    // ── §5.4 ─────────────────────────────────────────────────────────────────

    @Test
    void withoutClusterTargetUsesAgentNid() {
        var resolver = new ClusterDelegationResolver(
            c -> fail("NDP lookup must never be invoked without a cluster target"));

        assertEquals(AGENT_NID, resolver.resolveDelegateTarget(frame(null)));
        assertEquals(AGENT_NID, resolver.resolveDelegateTarget(frame("")));
    }

    @Test
    void clusterTargetResolvesToActiveAnchorAndCaches() {
        var lookups = new AtomicInteger();
        var resolver = new ClusterDelegationResolver(c -> {
            lookups.incrementAndGet();
            return new ClusterAnchorInfo(ANCHOR_A, 1L);
        });

        assertEquals(ANCHOR_A, resolver.resolveDelegateTarget(frame(CLUSTER)));
        assertEquals(ANCHOR_A, resolver.resolveDelegateTarget(frame(CLUSTER)));
        assertEquals(1, lookups.get(), "a cache hit must not do an NDP lookup");
    }

    @Test
    void failoverEventRedirectsSubsequentDelegationsToSuccessor() {
        var resolver = new ClusterDelegationResolver(c -> new ClusterAnchorInfo(ANCHOR_A, 1L));
        assertEquals(ANCHOR_A, resolver.resolveDelegateTarget(frame(CLUSTER)));   // warm the cache @1

        assertTrue(resolver.onAnchorFailover(CLUSTER, ANCHOR_B, 2L));
        assertEquals(ANCHOR_B, resolver.resolveDelegateTarget(frame(CLUSTER)));
        assertEquals(2L, resolver.resolveActive(CLUSTER).clusterEpoch());
    }

    @Test
    void staleFailoverEventIsIgnoredAndEqualCountsAsStale() {
        var resolver = new ClusterDelegationResolver(c -> new ClusterAnchorInfo(ANCHOR_B, 3L));
        assertEquals(ANCHOR_B, resolver.resolveDelegateTarget(frame(CLUSTER)));   // warm the cache @3

        assertFalse(resolver.onAnchorFailover(CLUSTER, ANCHOR_A, 3L), "equal epoch is stale");
        assertFalse(resolver.onAnchorFailover(CLUSTER, ANCHOR_A, 2L), "lower epoch is stale");
        assertEquals(ANCHOR_B, resolver.resolveActive(CLUSTER).activeNid());
        assertEquals(3L, resolver.resolveActive(CLUSTER).clusterEpoch());
    }

    @Test
    void invalidateForcesAFreshLookup() {
        Deque<ClusterAnchorInfo> queue = new ArrayDeque<>(List.of(
            new ClusterAnchorInfo(ANCHOR_A, 1L),
            new ClusterAnchorInfo(ANCHOR_B, 2L)));
        var resolver = new ClusterDelegationResolver(c -> queue.poll());

        assertEquals(ANCHOR_A, resolver.resolveDelegateTarget(frame(CLUSTER)));
        resolver.invalidate(CLUSTER);
        assertEquals(ANCHOR_B, resolver.resolveDelegateTarget(frame(CLUSTER)));
    }

    // ── Additional contract details ──────────────────────────────────────────

    @Test
    void firstObservationOfAClusterIsAcceptedUnconditionally() {
        var resolver = new ClusterDelegationResolver(c -> null);
        assertTrue(resolver.onAnchorFailover(CLUSTER, ANCHOR_B, 1L));
        assertEquals(ANCHOR_B, resolver.resolveActive(CLUSTER).activeNid());
    }

    @Test
    void negativeLookupResultsAreNotCached() {
        var lookups = new AtomicInteger();
        var resolver = new ClusterDelegationResolver(c -> {
            lookups.incrementAndGet();
            return null;
        });

        assertNull(resolver.resolveDelegateTarget(frame(CLUSTER)));
        assertNull(resolver.resolveDelegateTarget(frame(CLUSTER)));
        assertEquals(2, lookups.get(), "a negative result must not be cached");
    }

    @Test
    void unresolvableReturnsNullRatherThanThrowing() {
        var resolver = new ClusterDelegationResolver(c -> null);
        assertDoesNotThrow(() -> resolver.resolveDelegateTarget(frame(CLUSTER)));
    }

    @Test
    void argumentValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ClusterDelegationResolver(null));
        var resolver = new ClusterDelegationResolver(c -> new ClusterAnchorInfo(ANCHOR_A, 1L));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveDelegateTarget(null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveActive(""));
        assertThrows(IllegalArgumentException.class, () -> resolver.onAnchorFailover(null, ANCHOR_B, 2L));
        assertThrows(IllegalArgumentException.class, () -> resolver.onAnchorFailover(CLUSTER, "", 2L));
        assertThrows(IllegalArgumentException.class, () -> new ClusterAnchorInfo(null, 1L));
    }

    /** The compare-then-set must be atomic: only one racing failover may win. */
    @Test
    void concurrentFailoversAreSerialisedAndMonotonic() throws Exception {
        var resolver = new ClusterDelegationResolver(c -> new ClusterAnchorInfo(ANCHOR_A, 1L));
        resolver.resolveActive(CLUSTER);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException ignored) { return; }
                    // Every thread proposes the SAME epoch: exactly one may be accepted.
                    if (resolver.onAnchorFailover(CLUSTER, ANCHOR_B, 2L)) accepted.incrementAndGet();
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, accepted.get(), "equal epochs are stale, so exactly one may win");
        assertEquals(ANCHOR_B, resolver.resolveActive(CLUSTER).activeNid());
        assertEquals(2L, resolver.resolveActive(CLUSTER).clusterEpoch());
    }

    // ── DelegateFrame wire shape ─────────────────────────────────────────────

    @Test
    void targetClusterAnchorIsOmittedWhenUnsetRatherThanEmittedAsNull() {
        // Cross-SDK alignment: every other tree conditionally emits this key.
        assertFalse(frame(null).toDict().containsKey("target_cluster_anchor"));
        assertEquals(CLUSTER, frame(CLUSTER).toDict().get("target_cluster_anchor"));
        assertEquals(CLUSTER, DelegateFrame.fromDict(frame(CLUSTER).toDict()).targetClusterAnchor());
        assertNull(DelegateFrame.fromDict(frame(null).toDict()).targetClusterAnchor());
    }

    @Test
    void claimConflictCodeIsRegistered() {
        assertEquals("NOP-CLAIM-CONFLICT", NopErrorCodes.NOP_CLAIM_CONFLICT);
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT,
            NopErrorCodes.NOP_TO_NPS_STATUS.get(NopErrorCodes.NOP_CLAIM_CONFLICT));
    }
}
