// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.registry.FrameRegistry;

public final class NwpFrameRegistrar {
    private NwpFrameRegistrar() {}

    public static void register(FrameRegistry r) {
        r.register(FrameType.QUERY,  QueryFrame::fromDict);
        r.register(FrameType.ACTION, ActionFrame::fromDict);
    }
}
