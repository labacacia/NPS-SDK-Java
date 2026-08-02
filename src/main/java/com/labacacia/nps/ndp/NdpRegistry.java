// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import java.util.ArrayList;
import java.util.List;

/**
 * The NDP registry surface every registry implementation shares.
 *
 * <p>The multi-Anchor cluster-resolution rule (NPS-CR-0009 / NDP v0.10 §9) ships here
 * as a {@code default} method — the Java equivalent of the reference implementation's
 * default interface method — so that <em>any</em> registry implementation inherits the
 * identical rule rather than re-deriving it.</p>
 */
public interface NdpRegistry {

    /**
     * All <strong>live</strong> announcements. Implementations MUST lazily purge entries
     * past {@code registration_time + effective_ttl}; liveness filtering for
     * {@link #resolveCluster(String)} comes entirely from here.
     */
    List<AnnounceFrame> getAll();

    /**
     * Resolve the currently active Anchor of a cluster: the single live member with the
     * highest {@code cluster_epoch} (NDP v0.10 §9, NPS-CR-0009).
     *
     * <p>Rules, in order:</p>
     * <ol>
     *   <li>Members are the live entries whose {@code cluster_anchor} equals
     *       {@code clusterAnchor} by exact (ordinal) string equality. Role is
     *       <em>not</em> filtered — any live matching entry participates.</li>
     *   <li>No members ⇒ {@code null}. That is not an error.</li>
     *   <li>An absent {@code cluster_epoch} coerces to {@code 1} <em>at comparison
     *       time only</em>; the stored frame keeps its {@code null}.</li>
     *   <li>More than one member at the top epoch ⇒ {@link NdpClusterSplitException}.
     *       Two live members that both omit {@code cluster_epoch} therefore split —
     *       both coerce to 1 and tie at the top. There is deliberately no tiebreak.</li>
     * </ol>
     *
     * @param clusterAnchor the cluster NID; must be non-null and non-empty
     * @return the active Anchor's announcement, or {@code null} when the cluster is empty
     * @throws NdpClusterSplitException when the top epoch is shared by several live members
     */
    default AnnounceFrame resolveCluster(String clusterAnchor) {
        if (clusterAnchor == null || clusterAnchor.isEmpty()) {
            throw new IllegalArgumentException("clusterAnchor must be non-null and non-empty");
        }
        List<AnnounceFrame> members = new ArrayList<>();
        for (AnnounceFrame f : getAll()) {
            if (clusterAnchor.equals(f.clusterAnchor())) members.add(f);
        }
        if (members.isEmpty()) return null;

        long top = Long.MIN_VALUE;
        for (AnnounceFrame f : members) {
            long e = f.effectiveClusterEpoch();
            if (e > top) top = e;
        }
        AnnounceFrame leader = null;
        int leaderCount = 0;
        for (AnnounceFrame f : members) {
            if (f.effectiveClusterEpoch() == top) {
                leaderCount++;
                if (leader == null) leader = f;
            }
        }
        if (leaderCount > 1) throw new NdpClusterSplitException(clusterAnchor, top);
        return leader;
    }
}
