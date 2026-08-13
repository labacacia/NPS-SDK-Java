// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LlmContextState {
    BUSY("busy"), ACTIVE("active"), RELEASED("released"), EXPIRED("expired"), FAILED("failed");
    private final String wire;
    LlmContextState(String wire) { this.wire = wire; }
    @JsonValue public String wire() { return wire; }
    @JsonCreator public static LlmContextState fromWire(String value) {
        for (var item : values()) if (item.wire.equals(value)) return item;
        throw new IllegalArgumentException("Unknown LLM context state: " + value);
    }
}
