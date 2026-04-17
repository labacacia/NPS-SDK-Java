// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.exception.NpsCodecError;
import com.labacacia.nps.core.registry.FrameRegistry;

import java.util.Map;

/** Tier-1: UTF-8 JSON codec using Jackson. */
public final class Tier1JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public byte[] encode(NpsFrame frame) {
        try {
            return MAPPER.writeValueAsBytes(frame.toDict());
        } catch (Exception e) {
            throw new NpsCodecError("JSON encode failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public NpsFrame decode(FrameType frameType, byte[] payload, FrameRegistry registry) {
        try {
            Map<String, Object> dict = MAPPER.readValue(payload, Map.class);
            return registry.resolve(frameType).decode(dict);
        } catch (NpsCodecError e) {
            throw e;
        } catch (Exception e) {
            throw new NpsCodecError("JSON decode failed: " + e.getMessage(), e);
        }
    }
}
