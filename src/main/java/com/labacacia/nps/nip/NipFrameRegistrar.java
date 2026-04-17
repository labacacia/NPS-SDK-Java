// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.registry.FrameRegistry;

public final class NipFrameRegistrar {
    private NipFrameRegistrar() {}

    public static void register(FrameRegistry r) {
        r.register(FrameType.IDENT,  IdentFrame::fromDict);
        r.register(FrameType.TRUST,  TrustFrame::fromDict);
        r.register(FrameType.REVOKE, RevokeFrame::fromDict);
    }
}
