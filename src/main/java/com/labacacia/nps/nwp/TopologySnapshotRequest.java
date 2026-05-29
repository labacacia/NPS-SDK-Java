// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

public record TopologySnapshotRequest(
    String kind,
    String anchorRef,
    boolean includeBridges,
    boolean includeCapabilities,
    Integer maxDepth,
    String since
) {
    public TopologySnapshotRequest {
        if (kind == null) kind = NwpTopology.SNAPSHOT;
    }
}
