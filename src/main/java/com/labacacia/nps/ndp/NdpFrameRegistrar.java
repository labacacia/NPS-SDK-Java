// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.registry.FrameRegistry;

public final class NdpFrameRegistrar {
    private NdpFrameRegistrar() {}

    public static void register(FrameRegistry r) {
        r.register(FrameType.ANNOUNCE, AnnounceFrame::fromDict);
        r.register(FrameType.RESOLVE,  ResolveFrame::fromDict);
        r.register(FrameType.GRAPH,    GraphFrame::fromDict);
    }
}
