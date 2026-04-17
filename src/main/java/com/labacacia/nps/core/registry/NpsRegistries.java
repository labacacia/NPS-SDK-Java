// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.registry;

import com.labacacia.nps.ncp.NcpFrameRegistrar;
import com.labacacia.nps.ndp.NdpFrameRegistrar;
import com.labacacia.nps.nip.NipFrameRegistrar;
import com.labacacia.nps.nop.NopFrameRegistrar;
import com.labacacia.nps.nwp.NwpFrameRegistrar;

/** Factory methods for pre-configured {@link FrameRegistry} instances. */
public final class NpsRegistries {
    private NpsRegistries() {}

    /** NCP frames only (ANCHOR, DIFF, STREAM, CAPS, ERROR). */
    public static FrameRegistry createDefault() {
        FrameRegistry r = new FrameRegistry();
        NcpFrameRegistrar.register(r);
        return r;
    }

    /** All five protocols registered. */
    public static FrameRegistry createFull() {
        FrameRegistry r = new FrameRegistry();
        NcpFrameRegistrar.register(r);
        NwpFrameRegistrar.register(r);
        NipFrameRegistrar.register(r);
        NdpFrameRegistrar.register(r);
        NopFrameRegistrar.register(r);
        return r;
    }
}
