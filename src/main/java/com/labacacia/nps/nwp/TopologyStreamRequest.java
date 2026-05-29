// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.List;

public record TopologyStreamRequest(
    String kind,
    String anchorRef,
    boolean includeInitialSnapshot,
    List<String> eventTypes,
    String since
) {
    public TopologyStreamRequest {
        if (kind == null) kind = NwpTopology.STREAM;
    }
}
