// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.Map;

/**
 * NCP v0.8 keepalive/heartbeat frame (0x07).
 * Null payload — either peer MAY send after handshake; receiver MUST accept and SHOULD reply.
 */
public final class NopFrame implements NpsFrame {

    @Override public FrameType    frameType()    { return FrameType.NOP; }
    @Override public EncodingTier preferredTier() { return EncodingTier.JSON; }

    @Override
    public Map<String, Object> toDict() {
        return Map.of();
    }

    public static NopFrame fromDict(@SuppressWarnings("unused") Map<String, Object> d) {
        return new NopFrame();
    }
}
