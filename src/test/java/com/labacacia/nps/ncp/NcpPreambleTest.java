// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity tests for NPS-RFC-0001 NCP native-mode connection preamble.
 */
class NcpPreambleTest {

    private static final byte[] SPEC_BYTES =
        new byte[] { 0x4E, 0x50, 0x53, 0x2F, 0x31, 0x2E, 0x30, 0x0A };

    @Test
    void bytes_are_exactly_the_spec_constant() {
        assertEquals(8, NcpPreamble.LENGTH);
        assertEquals("NPS/1.0\n", NcpPreamble.LITERAL);
        assertArrayEquals(SPEC_BYTES, NcpPreamble.getBytes());
    }

    @Test
    void getBytes_returns_a_copy_not_a_shared_buffer() {
        byte[] copy = NcpPreamble.getBytes();
        copy[0] = (byte) 0xFF;
        assertEquals((byte) 0x4E, NcpPreamble.getBytes()[0]);
    }

    @Test
    void matches_returns_true_for_exact_preamble() {
        assertTrue(NcpPreamble.matches(NcpPreamble.getBytes()));
    }

    @Test
    void matches_returns_true_when_preamble_is_at_start_of_longer_buffer() {
        byte[] combined = new byte[16];
        System.arraycopy(NcpPreamble.getBytes(), 0, combined, 0, 8);
        combined[8] = 0x06;
        assertTrue(NcpPreamble.matches(combined));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 7})
    void matches_returns_false_on_short_reads(int length) {
        byte[] truncated = Arrays.copyOf(NcpPreamble.getBytes(), length);
        assertFalse(NcpPreamble.matches(truncated));
    }

    @Test
    void tryValidate_accepts_exact_preamble() {
        String[] reason = {"unset"};
        assertTrue(NcpPreamble.tryValidate(NcpPreamble.getBytes(), reason));
        assertEquals("", reason[0]);
    }

    @Test
    void tryValidate_rejects_short_read_with_reason() {
        String[] reason = {""};
        assertFalse(NcpPreamble.tryValidate(new byte[3], reason));
        assertTrue(reason[0].contains("short read"), reason[0]);
        assertTrue(reason[0].contains("3/8"),       reason[0]);
    }

    @Test
    void tryValidate_rejects_garbage() {
        String[] reason = {""};
        assertFalse(NcpPreamble.tryValidate("GET / HTT".getBytes(StandardCharsets.US_ASCII), reason));
        assertFalse(reason[0].contains("future"),         reason[0]);
        assertTrue(reason[0].contains("not speaking NPS"), reason[0]);
    }

    @Test
    void tryValidate_flags_future_major_distinctly() {
        String[] reason = {""};
        assertFalse(NcpPreamble.tryValidate("NPS/2.0\n".getBytes(StandardCharsets.US_ASCII), reason));
        assertTrue(reason[0].contains("future-major"), reason[0]);
    }

    @Test
    void validate_throws_with_codes_exposed() {
        var ex = assertThrows(NcpPreamble.NcpPreambleInvalidException.class,
            () -> NcpPreamble.validate("BADXXXXX".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("NCP-PREAMBLE-INVALID",       ex.errorCode);
        assertEquals("NPS-PROTO-PREAMBLE-INVALID", ex.statusCode);
        assertNotNull(ex.getMessage());
    }

    @Test
    void write_emits_exactly_the_constant_bytes() throws Exception {
        var out = new ByteArrayOutputStream();
        NcpPreamble.write(out);
        assertArrayEquals(SPEC_BYTES, out.toByteArray());
    }

    @Test
    void status_and_error_code_constants_match_spec() {
        assertEquals("NCP-PREAMBLE-INVALID",       NcpPreamble.ERROR_CODE);
        assertEquals("NPS-PROTO-PREAMBLE-INVALID", NcpPreamble.STATUS_CODE);
    }
}
