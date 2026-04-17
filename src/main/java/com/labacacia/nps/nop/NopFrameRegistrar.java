// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.registry.FrameRegistry;

public final class NopFrameRegistrar {
    private NopFrameRegistrar() {}

    public static void register(FrameRegistry r) {
        r.register(FrameType.TASK,         TaskFrame::fromDict);
        r.register(FrameType.DELEGATE,     DelegateFrame::fromDict);
        r.register(FrameType.SYNC,         SyncFrame::fromDict);
        r.register(FrameType.ALIGN_STREAM, AlignStreamFrame::fromDict);
    }
}
