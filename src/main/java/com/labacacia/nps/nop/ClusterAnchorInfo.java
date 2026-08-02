// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

/**
 * The currently active Anchor of a cluster, as seen by {@link ClusterDelegationResolver}
 * (NPS-CR-0009 §3.4).
 *
 * @param activeNid    NID of the Anchor that currently owns the cluster
 * @param clusterEpoch the epoch under which it owns it; strictly increases per cluster
 */
public record ClusterAnchorInfo(String activeNid, long clusterEpoch) {

    public ClusterAnchorInfo {
        if (activeNid == null || activeNid.isEmpty()) {
            throw new IllegalArgumentException("activeNid must be non-null and non-empty");
        }
    }
}
