// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LlmContextOperation {
    CREATE("create"), APPEND("append"), FORK("fork"), RESET("reset"), RELEASE("release");
    private final String wire;
    LlmContextOperation(String wire) { this.wire = wire; }
    @JsonValue public String wire() { return wire; }
    @JsonCreator public static LlmContextOperation fromWire(String value) {
        for (var item : values()) if (item.wire.equals(value)) return item;
        throw new IllegalArgumentException("Unknown LLM context operation: " + value);
    }
}
