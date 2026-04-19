// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.registry.FrameRegistry;

/** Registers all NCP frame decoders into a {@link FrameRegistry}. */
public final class NcpFrameRegistrar {
    private NcpFrameRegistrar() {}

    public static void register(FrameRegistry r) {
        r.register(FrameType.ANCHOR, AnchorFrame::fromDict);
        r.register(FrameType.DIFF,   DiffFrame::fromDict);
        r.register(FrameType.STREAM, StreamFrame::fromDict);
        r.register(FrameType.CAPS,   CapsFrame::fromDict);
        r.register(FrameType.HELLO,  HelloFrame::fromDict);
        r.register(FrameType.ERROR,  ErrorFrame::fromDict);
    }
}
