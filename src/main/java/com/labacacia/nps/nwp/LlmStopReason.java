// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LlmStopReason {
    END_TURN("end_turn"), TOOL_USE("tool_use"), TOOL_CALLS("tool_calls"),
    MAX_TOKENS("max_tokens"), LENGTH("length"), ERROR("error");

    private final String wire;
    LlmStopReason(String wire) { this.wire = wire; }
    @JsonValue public String wire() { return wire; }
    @JsonCreator public static LlmStopReason fromWire(String value) {
        for (var item : values()) if (item.wire.equals(value)) return item;
        throw new IllegalArgumentException("Unknown LLM stop reason: " + value);
    }
}
