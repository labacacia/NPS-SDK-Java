// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.registry;

import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.exception.NpsFrameError;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Maps FrameType codes to frame deserialiser functions. */
public final class FrameRegistry {

    @FunctionalInterface
    public interface FrameDecoder {
        NpsFrame decode(Map<String, Object> dict);
    }

    private final Map<FrameType, FrameDecoder> decoders = new HashMap<>();

    public void register(FrameType type, FrameDecoder decoder) {
        decoders.put(type, decoder);
    }

    public FrameDecoder resolve(FrameType type) {
        FrameDecoder d = decoders.get(type);
        if (d == null) throw new NpsFrameError("No decoder registered for frame type: " + type);
        return d;
    }

    public boolean isRegistered(FrameType type) {
        return decoders.containsKey(type);
    }
}
