// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Result of a topology.snapshot reserved query (NPS-2 §12.1). Returned by
 * {@link AnchorNodeClient#getSnapshot} and parsed from the single {@code data[0]}
 * element of the response CapsFrame.
 */
public final class TopologySnapshot {

    /** Monotonically increasing topology version (NPS-2 §12.3). */
    @JsonProperty("version")
    public long version;

    /** NID of the responding Anchor Node. */
    @JsonProperty("anchor_nid")
    public String anchorNid;

    /** Total direct members, regardless of any depth truncation. */
    @JsonProperty("cluster_size")
    public int clusterSize;

    /** Per-member metadata. */
    @JsonProperty("members")
    public List<MemberInfo> members;

    /** {@code true} iff the depth cap was hit; otherwise null or false. */
    @JsonProperty("truncated")
    public Boolean truncated;

    /**
     * NPS-CR-0009 — the epoch under which the responding Anchor owns its cluster.
     * Absent ⇒ 1 (single-Anchor). Not signed.
     *
     * <p>NWP §12.2: every {@code topology.snapshot} / {@code topology.stream} response
     * and every topology-mutating write MUST carry the current {@code cluster_epoch}.
     * The wire key is declared explicitly here rather than left to a serializer naming
     * policy.</p>
     */
    @JsonProperty("cluster_epoch")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Long clusterEpoch;

    /** No-arg constructor for Jackson. */
    public TopologySnapshot() {}

    public TopologySnapshot(long version, String anchorNid, int clusterSize,
                            List<MemberInfo> members, Boolean truncated) {
        this(version, anchorNid, clusterSize, members, truncated, null);
    }

    public TopologySnapshot(long version, String anchorNid, int clusterSize,
                            List<MemberInfo> members, Boolean truncated, Long clusterEpoch) {
        this.version      = version;
        this.anchorNid    = anchorNid;
        this.clusterSize  = clusterSize;
        this.members      = members;
        this.truncated    = truncated;
        this.clusterEpoch = clusterEpoch;
    }

    /** {@code cluster_epoch} with the NDP §9 default applied: absent ⇒ 1. */
    public long effectiveClusterEpoch() { return clusterEpoch == null ? 1L : clusterEpoch; }
}
