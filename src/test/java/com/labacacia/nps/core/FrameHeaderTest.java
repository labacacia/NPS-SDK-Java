// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

import com.labacacia.nps.core.exception.NpsFrameError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameHeaderTest {

    @Test void parsesDefaultHeader() {
        // ANCHOR(0x01), FINAL|TIER2_MSGPACK(0x09), length=10
        byte[] buf = { 0x01, 0x09, 0x00, 0x0A };
        FrameHeader h = FrameHeader.parse(buf);
        assertEquals(FrameType.ANCHOR, h.frameType);
        assertTrue(h.isFinal());
        assertEquals(10, h.payloadLength);
        assertFalse(h.isExtended);
        assertEquals(FrameHeader.DEFAULT_HEADER_SIZE, h.headerSize());
    }

    @Test void parsesExtendedHeader() {
        byte[] buf = new byte[8];
        buf[0] = (byte) FrameType.CAPS.code;
        buf[1] = (byte) (FrameFlags.EXT | FrameFlags.TIER2_MSGPACK | FrameFlags.FINAL);
        // reserved [2-3] = 0
        // payload length [4-7] = 100_000 big-endian
        buf[4] = 0x00;
        buf[5] = 0x01;
        buf[6] = (byte) 0x86;
        buf[7] = (byte) 0xA0;
        FrameHeader h = FrameHeader.parse(buf);
        assertTrue(h.isExtended);
        assertEquals(FrameHeader.EXTENDED_HEADER_SIZE, h.headerSize());
        assertEquals(100_000L, h.payloadLength);
    }

    @Test void roundTripsDefaultHeader() {
        FrameHeader h    = new FrameHeader(FrameType.ANCHOR,
            FrameFlags.FINAL | FrameFlags.TIER2_MSGPACK, 42);
        FrameHeader back = FrameHeader.parse(h.toBytes());
        assertEquals(FrameType.ANCHOR, back.frameType);
        assertEquals(42, back.payloadLength);
    }

    @Test void roundTripsExtendedHeader() {
        FrameHeader h    = new FrameHeader(FrameType.CAPS,
            FrameFlags.EXT | FrameFlags.FINAL | FrameFlags.TIER1_JSON, 70_000);
        FrameHeader back = FrameHeader.parse(h.toBytes());
        assertTrue(back.isExtended);
        assertEquals(70_000L, back.payloadLength);
    }

    @Test void throwsForBufferTooSmall() {
        assertThrows(NpsFrameError.class,
            () -> FrameHeader.parse(new byte[]{ 0x01 }));
    }

    @Test void throwsForExtHeaderWithShortBuffer() {
        byte[] buf = { 0x01, (byte) FrameFlags.EXT, 0x00, 0x00 };
        assertThrows(NpsFrameError.class, () -> FrameHeader.parse(buf));
    }

    @Test void encodingTierDetectedCorrectly() {
        FrameHeader h = new FrameHeader(FrameType.ANCHOR,
            FrameFlags.TIER2_MSGPACK | FrameFlags.FINAL, 0);
        assertEquals(EncodingTier.MSGPACK, h.encodingTier());
    }

    @Test void unknownFrameTypeThrows() {
        byte[] buf = { (byte) 0x99, 0x09, 0x00, 0x00 };
        assertThrows(NpsFrameError.class, () -> FrameHeader.parse(buf));
    }
}
