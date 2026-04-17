// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

public enum TaskState {
    PENDING("pending"), PREFLIGHT("preflight"), RUNNING("running"),
    WAITING_SYNC("waiting_sync"), COMPLETED("completed"),
    FAILED("failed"), CANCELLED("cancelled"), SKIPPED("skipped");

    public final String value;
    TaskState(String value) { this.value = value; }

    public static TaskState fromValue(String v) {
        for (TaskState s : values()) if (s.value.equals(v)) return s;
        throw new IllegalArgumentException("Unknown TaskState: " + v);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
