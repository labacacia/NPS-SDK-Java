// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.FrameRegistry;
import com.labacacia.nps.ncp.CapsFrame;
import com.labacacia.nps.ncp.ErrorFrame;
import com.labacacia.nps.ncp.NcpFrameRegistrar;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class NwpNativeNodeServerTest {
    @Test
    void dispatchWireReturnsCapsForQuery() {
        var codec = new NpsFrameCodec(registry());
        var server = new NwpNativeNodeServer(
            codec,
            EncodingTier.MSGPACK,
            "native:test",
            query -> new CapsFrame("native:test", 1, List.of(Map.<String, Object>of("id", 42))),
            null);

        byte[] out = server.dispatchWire(codec.encode(new QueryFrame("sha256:a", null, null, null, null, null, null, null)));
        NpsFrame frame = codec.decode(out);

        assertInstanceOf(CapsFrame.class, frame);
        assertEquals(1, ((CapsFrame) frame).count());
    }

    @Test
    void dispatchWireWrapsActionResult() {
        var codec = new NpsFrameCodec(registry());
        var server = new NwpNativeNodeServer(
            codec,
            EncodingTier.MSGPACK,
            "native:test",
            null,
            action -> Map.of("action", action.actionId()));

        byte[] out = server.dispatchWire(codec.encode(new ActionFrame("ping")));
        NpsFrame frame = codec.decode(out);

        assertInstanceOf(CapsFrame.class, frame);
        assertEquals("ping", ((CapsFrame) frame).data().getFirst().get("action"));
    }

    @Test
    void actionFrameAcceptsLegacyActionKey() {
        var frame = ActionFrame.fromDict(Map.of("action", "ping"));

        assertEquals("ping", frame.actionId());
    }

    @Test
    void dispatchWireRejectsUnnegotiatedBinaryVector() {
        var codec = new NpsFrameCodec(registry());
        var server = new NwpNativeNodeServer(
            codec,
            EncodingTier.MSGPACK,
            "native:test",
            query -> new CapsFrame("native:test", 1, List.of(Map.<String, Object>of("id", 42))),
            null);

        byte[] out = server.dispatchWire(codec.encode(vectorQuery(), EncodingTier.BINARY_VECTOR));
        NpsFrame frame = codec.decode(out);

        assertInstanceOf(ErrorFrame.class, frame);
        assertEquals("NPS-SERVER-ENCODING-UNSUPPORTED", ((ErrorFrame) frame).status());
        assertEquals("NCP-ENCODING-UNSUPPORTED", ((ErrorFrame) frame).error());
    }

    @Test
    void dispatchWireAllowsNegotiatedBinaryVectorQuery() {
        var codec = new NpsFrameCodec(registry());
        var server = new NwpNativeNodeServer(
            codec,
            EncodingTier.MSGPACK,
            List.of("msgpack", "binary_vector.v1"),
            "native:test",
            query -> new CapsFrame("native:test", 1, List.of(Map.<String, Object>of("id", 42))),
            null);

        byte[] out = server.dispatchWire(codec.encode(vectorQuery(), EncodingTier.BINARY_VECTOR));
        NpsFrame frame = codec.decode(out);

        assertInstanceOf(CapsFrame.class, frame);
        assertEquals(1, ((CapsFrame) frame).count());
    }

    private static QueryFrame vectorQuery() {
        return new QueryFrame(
            null,
            null,
            1,
            null,
            null,
            null,
            Map.of(
                "field", "embedding",
                "vector", List.of(0.25f, -1.5f, 3.0f),
                "top_k", 1),
            null);
    }

    private static FrameRegistry registry() {
        FrameRegistry registry = new FrameRegistry();
        NcpFrameRegistrar.register(registry);
        NwpFrameRegistrar.register(registry);
        return registry;
    }
}
