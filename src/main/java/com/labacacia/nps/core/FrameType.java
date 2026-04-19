// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

/** NPS frame type byte codes (spec: frame-registry.yaml). */
public enum FrameType {
    // NCP
    ANCHOR      (0x01),
    DIFF        (0x02),
    STREAM      (0x03),
    CAPS        (0x04),
    HELLO       (0x06),
    // NWP
    QUERY       (0x10),
    ACTION      (0x11),
    // NIP
    IDENT       (0x20),
    TRUST       (0x21),
    REVOKE      (0x22),
    // NDP
    ANNOUNCE    (0x30),
    RESOLVE     (0x31),
    GRAPH       (0x32),
    // NOP
    TASK        (0x40),
    DELEGATE    (0x41),
    SYNC        (0x42),
    ALIGN_STREAM(0x43),
    // Shared
    ERROR       (0xFE);

    public final int code;

    FrameType(int code) { this.code = code; }

    public static FrameType fromCode(int code) {
        for (FrameType t : values()) {
            if (t.code == code) return t;
        }
        throw new com.labacacia.nps.core.exception.NpsFrameError(
            "Unknown frame type: 0x" + Integer.toHexString(code));
    }
}
