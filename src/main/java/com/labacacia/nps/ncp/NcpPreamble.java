// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * NCP native-mode connection preamble — the 8-byte ASCII constant
 * {@code "NPS/1.0\n"} that every native-mode client MUST emit immediately
 * after the transport handshake and before its first HelloFrame.
 * Defined by NPS-RFC-0001 and NPS-1 NCP §2.6.1.
 *
 * <p>HTTP-mode connections do not use the preamble.
 */
public final class NcpPreamble {

    public static final String LITERAL            = "NPS/1.0\n";
    public static final int    LENGTH             = 8;
    public static final long   READ_TIMEOUT_MS    = 10_000L;
    public static final long   CLOSE_DEADLINE_MS  = 500L;

    public static final String ERROR_CODE  = "NCP-PREAMBLE-INVALID";
    public static final String STATUS_CODE = "NPS-PROTO-PREAMBLE-INVALID";

    private static final byte[] BYTES = LITERAL.getBytes(StandardCharsets.US_ASCII);

    private NcpPreamble() {}

    /** Returns a copy of the 8-byte preamble byte array. */
    public static byte[] getBytes() {
        return BYTES.clone();
    }

    /**
     * Returns {@code true} iff {@code buf} starts with the 8-byte NPS/1.0 preamble.
     * Safe to call with shorter buffers.
     */
    public static boolean matches(byte[] buf) {
        if (buf == null || buf.length < LENGTH) return false;
        for (int i = 0; i < LENGTH; i++) {
            if (buf[i] != BYTES[i]) return false;
        }
        return true;
    }

    /**
     * Validates a presumed-preamble buffer.
     *
     * @param buf    bytes received from the connection
     * @param reason single-element array; on failure, filled with a human-readable reason
     * @return {@code true} on success; {@code false} for mismatch or short read
     */
    public static boolean tryValidate(byte[] buf, String[] reason) {
        int got = (buf == null) ? 0 : buf.length;
        if (got < LENGTH) {
            reason[0] = "short read (" + got + "/" + LENGTH + " bytes); peer is not speaking NCP";
            return false;
        }
        if (!matches(buf)) {
            if (buf[0] == 'N' && buf[1] == 'P' && buf[2] == 'S' && buf[3] == '/') {
                reason[0] = "future-major-version NPS preamble; close with NPS-PREAMBLE-UNSUPPORTED-VERSION diagnostic";
            } else {
                reason[0] = "preamble mismatch; peer is not speaking NPS/1.x";
            }
            return false;
        }
        reason[0] = "";
        return true;
    }

    /**
     * Validates a presumed-preamble buffer, throwing
     * {@link NcpPreambleInvalidException} on mismatch.
     */
    public static void validate(byte[] buf) {
        String[] reason = {""};
        if (!tryValidate(buf, reason)) throw new NcpPreambleInvalidException(reason[0]);
    }

    /**
     * Writes the preamble bytes to {@code out} in a single call.
     */
    public static void write(OutputStream out) throws IOException {
        out.write(BYTES);
    }

    // ── exception ─────────────────────────────────────────────────────────────

    /** Thrown by {@link NcpPreamble#validate} when the received preamble does not match. */
    public static final class NcpPreambleInvalidException extends RuntimeException {
        public final String errorCode  = ERROR_CODE;
        public final String statusCode = STATUS_CODE;

        public NcpPreambleInvalidException(String reason) {
            super(reason);
        }
    }
}
