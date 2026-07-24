// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.NpsFrame;

import java.util.Map;

/** Serializes NPS frames to snake_case JSON for Bridge server payloads. */
final class BridgeFrameJson {

    /** Snake_case, null-omitting mapper matching the .NET BridgeNodeMiddleware.Json. */
    static final ObjectMapper JSON =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private BridgeFrameJson() {}

    static String serialize(NpsFrame frame) {
        try {
            return JSON.writeValueAsString(dict(frame));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    static JsonNode toNode(NpsFrame frame) {
        return JSON.valueToTree(dict(frame));
    }

    private static Map<String, Object> dict(NpsFrame frame) {
        Map<String, Object> d = frame.toDict();
        d.values().removeIf(v -> v == null);
        return d;
    }
}
