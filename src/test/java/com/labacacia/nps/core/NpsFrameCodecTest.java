// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.exception.NpsCodecError;
import com.labacacia.nps.core.registry.NpsRegistries;
import com.labacacia.nps.ncp.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NpsFrameCodecTest {

    private static final String AID    = "sha256:" + "a".repeat(64);
    private static final Map<String, Object> SCHEMA = Map.of(
        "fields", List.of(Map.of("name", "id", "type", "uint64"),
                          Map.of("name", "name", "type", "string")));

    private final NpsFrameCodec codec = new NpsFrameCodec(NpsRegistries.createDefault());

    @Test void encodesDecodesAnchorFrameMsgPack() {
        var frame = new AnchorFrame(AID, SCHEMA, 3600);
        var out   = (AnchorFrame) codec.decode(codec.encode(frame));
        assertEquals(AID, out.anchorId());
        assertEquals(3600, out.ttl());
    }

    @Test void encodesDecodesAnchorFrameJson() {
        var frame = new AnchorFrame(AID, SCHEMA, 7200);
        var wire  = codec.encode(frame, EncodingTier.JSON);
        var out   = (AnchorFrame) codec.decode(wire);
        assertEquals(7200, out.ttl());
    }

    @Test void encodesDecodesDiffFrame() {
        var patch = List.of(Map.<String,Object>of("op","replace","path","/name","value","Bob"));
        var frame = new DiffFrame(AID, 3, patch, "ent:1");
        var out   = (DiffFrame) codec.decode(codec.encode(frame));
        assertEquals(3, out.baseSeq());
        assertEquals("replace", out.patch().get(0).get("op"));
        assertEquals("ent:1",   out.entityId());
    }

    @Test void encodesDecodesStreamFrameNonFinal() {
        var frame = new StreamFrame("s-1", 0, false, List.of(Map.of("id", 1)), null, null);
        var wire  = codec.encode(frame);
        assertFalse(NpsFrameCodec.peekHeader(wire).isFinal());
        var out = (StreamFrame) codec.decode(wire);
        assertFalse(out.isLast());
    }

    @Test void encodesDecodesStreamFrameFinal() {
        var frame = new StreamFrame("s-1", 1, true, List.of(Map.of("id", 2)), AID, 10);
        var wire  = codec.encode(frame);
        assertTrue(NpsFrameCodec.peekHeader(wire).isFinal());
        var out = (StreamFrame) codec.decode(wire);
        assertTrue(out.isLast());
        assertEquals(10, out.windowSize());
    }

    @Test void encodesDecodesCapsFrame() {
        var frame = new CapsFrame(AID, 2, List.of(Map.of("id",1), Map.of("id",2)),
            "cursor:X", 100, true, "cl100k");
        var out = (CapsFrame) codec.decode(codec.encode(frame));
        assertEquals(2, out.count());
        assertEquals("cursor:X", out.nextCursor());
        assertEquals("cl100k",   out.tokenizerUsed());
    }

    @Test void encodesDecodesErrorFrame() {
        var frame = new ErrorFrame("NPS-SERVER-INTERNAL", "NCP-ANCHOR-NOT-FOUND",
            "missing anchor", Map.of("ref", AID));
        var out = (ErrorFrame) codec.decode(codec.encode(frame));
        assertEquals("NPS-SERVER-INTERNAL", out.status());
        assertEquals("missing anchor",      out.message());
    }

    @Test void peekHeaderDecodesOnlyHeader() {
        var frame  = new AnchorFrame(AID, SCHEMA);
        var wire   = codec.encode(frame);
        var header = NpsFrameCodec.peekHeader(wire);
        assertEquals(FrameType.ANCHOR, header.frameType);
    }

    @Test void throwsWhenPayloadExceedsMaxPayload() {
        var tiny  = new NpsFrameCodec(NpsRegistries.createDefault(), 5);
        var frame = new AnchorFrame(AID, SCHEMA);
        assertThrows(NpsCodecError.class, () -> tiny.encode(frame));
    }

    @Test void setsExtFlagWhenPayloadExceeds64KiB() {
        var large = new NpsFrameCodec(NpsRegistries.createDefault(), 200_000);
        var data  = new java.util.ArrayList<Map<String,Object>>();
        for (int i = 0; i < 400; i++) data.add(Map.of("id", i, "name", "x".repeat(200)));
        var frame = new CapsFrame(AID, data.size(), data);
        var wire  = large.encode(frame, EncodingTier.JSON);
        assertTrue(NpsFrameCodec.peekHeader(wire).isExtended);
    }

    @Test void registryThrowsForUnknownType() {
        var r = new com.labacacia.nps.core.registry.FrameRegistry();
        assertThrows(com.labacacia.nps.core.exception.NpsFrameError.class,
            () -> r.resolve(FrameType.ANCHOR));
    }

    @Test void helloFramePreferredTierIsJson() {
        var frame = new HelloFrame("0.2", List.of("tier-1","tier-2"), List.of("ncp","nwp"));
        assertEquals(EncodingTier.JSON, frame.preferredTier());
        assertEquals(FrameType.HELLO,   frame.frameType());
    }

    @Test void encodesDecodesHelloFrameFull() {
        var frame = new HelloFrame(
            "0.2",
            List.of("tier-1", "tier-2"),
            List.of("ncp", "nwp", "nip"),
            "0.1",
            "urn:nps:agent:example.com:hello-1",
            0xFFFF,
            true,
            64,
            List.of("aes-256-gcm"));
        var out = (HelloFrame) codec.decode(codec.encode(frame));  // preferred = JSON
        assertEquals("0.2", out.npsVersion());
        assertEquals(List.of("tier-1","tier-2"),          out.supportedEncodings());
        assertEquals(List.of("ncp","nwp","nip"),          out.supportedProtocols());
        assertEquals("0.1",                               out.minVersion());
        assertEquals("urn:nps:agent:example.com:hello-1", out.agentId());
        assertTrue(out.extSupport());
        assertEquals(64, out.maxConcurrentStreams());
        assertEquals(List.of("aes-256-gcm"), out.e2eEncAlgorithms());
    }

    @Test void encodesDecodesHelloFrameMinimalMsgPack() {
        var frame = new HelloFrame("0.2", List.of("tier-1"), List.of("ncp"));
        var wire  = codec.encode(frame, EncodingTier.MSGPACK);
        var out   = (HelloFrame) codec.decode(wire);
        assertEquals("0.2", out.npsVersion());
        assertNull(out.minVersion());
        assertNull(out.agentId());
        assertNull(out.e2eEncAlgorithms());
        assertFalse(out.extSupport());
        assertEquals(0xFFFF, out.maxFramePayload());
        assertEquals(32,     out.maxConcurrentStreams());
    }

    @Test void helloFrameTypeCodeIs06() {
        assertEquals(0x06, FrameType.HELLO.code);
    }
}
