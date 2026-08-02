// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * NPS-CR-0009 §3.4 — resolves a {@link DelegateFrame} to the NID it should actually be
 * dispatched to, following cluster ownership as it moves.
 *
 * <p>The cluster lookup is an injected delegate so that NOP carries no NDP dependency;
 * a composition root adapts an {@code AnnounceFrame} to
 * {@code new ClusterAnchorInfo(frame.nid(), frame.effectiveClusterEpoch())}.</p>
 *
 * <p><strong>Monotonic per cluster.</strong> A failover event whose epoch is less than or
 * equal to the cached one is stale and ignored — <em>equal is stale</em>, not
 * idempotent-accept. The cache is invalidated only by a strictly newer
 * {@code anchor_failover} or an explicit {@link #invalidate(String)}; no TTL is
 * modelled.</p>
 *
 * <p>Thread-safe by design: the map is a {@link ConcurrentHashMap} and every
 * compare-then-set runs inside an atomic {@code compute}.</p>
 */
public final class ClusterDelegationResolver {

    /** Looks a cluster NID up in NDP; returns {@code null} when the cluster is unknown. */
    @FunctionalInterface
    public interface ClusterLookup { ClusterAnchorInfo resolve(String clusterAnchor); }

    private final ClusterLookup resolveCluster;
    private final ConcurrentHashMap<String, ClusterAnchorInfo> active = new ConcurrentHashMap<>();

    public ClusterDelegationResolver(ClusterLookup resolveCluster) {
        if (resolveCluster == null) throw new IllegalArgumentException("resolveCluster is required");
        this.resolveCluster = resolveCluster;
    }

    /**
     * The NID this delegation should be sent to.
     *
     * <p>With no {@code target_cluster_anchor} the frame's {@code target_agent_nid} is
     * returned directly and <strong>no NDP lookup happens at all</strong>. Returning
     * {@code null} means "cannot resolve" — the caller decides retry versus fail; this
     * method never throws for that.</p>
     */
    public String resolveDelegateTarget(DelegateFrame frame) {
        if (frame == null) throw new IllegalArgumentException("frame is required");
        String cluster = frame.targetClusterAnchor();
        if (cluster == null || cluster.isEmpty()) return frame.agentNid();
        ClusterAnchorInfo info = resolveActive(cluster);
        return info == null ? null : info.activeNid();
    }

    /**
     * The cached active Anchor of a cluster, looking it up on a miss. A cache hit does
     * <strong>no</strong> NDP lookup; a negative result is not cached.
     */
    public ClusterAnchorInfo resolveActive(String clusterAnchor) {
        if (clusterAnchor == null || clusterAnchor.isEmpty()) {
            throw new IllegalArgumentException("clusterAnchor must be non-null and non-empty");
        }
        ClusterAnchorInfo cached = active.get(clusterAnchor);
        if (cached != null) return cached;

        ClusterAnchorInfo fresh = resolveCluster.resolve(clusterAnchor);
        if (fresh != null) active.put(clusterAnchor, fresh);
        return fresh;
    }

    /**
     * Apply an observed {@code anchor_failover}: subsequent delegations for this cluster
     * go to {@code successorNid}.
     *
     * @return {@code true} when the event was accepted; {@code false} when it was stale
     *         ({@code clusterEpoch <= } the cached epoch — equal counts as stale). A
     *         first observation of a cluster is accepted unconditionally.
     */
    public boolean onAnchorFailover(String clusterAnchor, String successorNid, long clusterEpoch) {
        if (clusterAnchor == null || clusterAnchor.isEmpty()) {
            throw new IllegalArgumentException("clusterAnchor must be non-null and non-empty");
        }
        if (successorNid == null || successorNid.isEmpty()) {
            throw new IllegalArgumentException("successorNid must be non-null and non-empty");
        }
        boolean[] accepted = {false};
        active.compute(clusterAnchor, (key, current) -> {
            if (current != null && clusterEpoch <= current.clusterEpoch()) {
                accepted[0] = false;
                return current;                    // stale — ignore
            }
            accepted[0] = true;
            return new ClusterAnchorInfo(successorNid, clusterEpoch);
        });
        return accepted[0];
    }

    /**
     * Drop the cached entry, forcing a fresh NDP lookup on the next resolution.
     *
     * <p>This is the documented recovery path after a dispatch is rejected with
     * {@code NWP-ANCHOR-NOT-LEADER}: invalidate, re-resolve, retry.</p>
     */
    public void invalidate(String clusterAnchor) {
        if (clusterAnchor != null) active.remove(clusterAnchor);
    }

    /** Adapter helper for a composition root that already holds a lookup function. */
    public static ClusterDelegationResolver of(Function<String, ClusterAnchorInfo> lookup) {
        return new ClusterDelegationResolver(lookup::apply);
    }
}
