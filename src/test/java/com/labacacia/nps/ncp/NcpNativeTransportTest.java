// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameHeader;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.FrameRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the REAL {@link NcpServer} (bound to an ephemeral port on a background
 * thread) with the REAL {@link NcpNativeClient} over loopback TCP.
 */
class NcpNativeTransportTest {

    private NcpServer server;

    private static NpsFrameCodec sessionCodec() {
        FrameRegistry r = new FrameRegistry();
        NcpFrameRegistrar.register(r);
        return new NpsFrameCodec(r);
    }

    private static HelloFrame hello(List<String> encodings) {
        return new HelloFrame("0.8", encodings, List.of("ncp", "nwp"),
            null, "urn:nps:agent:test:client",
            HelloFrame.DEFAULT_MAX_FRAME_PAYLOAD, true,
            HelloFrame.DEFAULT_MAX_CONCURRENT_STREAMS, null);
    }

    /** Starts a background accept loop; each connection is accepted with {@code caps}. */
    private BlockingQueue<NcpServerConnection> startAccepting(NcpHandshakeCapsFrame caps) throws IOException {
        BlockingQueue<NcpServerConnection> accepted = new ArrayBlockingQueue<>(4);
        server = new NcpServer(new InetSocketAddress("127.0.0.1", 0), sessionCodec(), null);
        server.runAsync(conn -> {
            try {
                NcpSession session = conn.accept(caps);
                // keep the socket open for the caller; hand back the connection
                accepted.add(conn);
                // echo one frame if the client sends one (for live-session test)
                echoOneFrameQuietly(session);
            } catch (IOException e) {
                // connection closed by client; ignore
            }
        });
        return accepted;
    }

    private static void echoOneFrameQuietly(NcpSession session) {
        try {
            NpsFrame frame = session.receiveFrame();
            session.sendFrame(frame);
        } catch (IOException ignored) {
            // client did not send a frame — fine for handshake-only tests
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.close();
    }

    // ── happy path ─────────────────────────────────────────────────────────────

    @Test
    void handshakeHappyPath() throws IOException {
        NcpHandshakeCapsFrame caps = new NcpHandshakeCapsFrame(
            "urn:nps:agent:test:node", List.of("ncp", "nwp"));
        startAccepting(caps);

        NcpNativeClient client = new NcpNativeClient(sessionCodec());
        try (NcpSession session = client.connect("127.0.0.1", server.getLocalPort(),
                hello(List.of("msgpack", "json")))) {
            assertTrue(session.isConnected());
            assertEquals("urn:nps:agent:test:node", session.serverCaps().nodeId());
            assertEquals(List.of("ncp", "nwp"), session.serverCaps().caps());
            // client offered msgpack first → msgpack is the stable default
            assertEquals(EncodingTier.MSGPACK, session.negotiatedTier());
        }
    }

    // ── encoding negotiation ────────────────────────────────────────────────────

    @Test
    void negotiatesJsonWhenMsgpackNotOffered() throws IOException {
        startAccepting(new NcpHandshakeCapsFrame("node", List.of("ncp")));

        NcpNativeClient client = new NcpNativeClient(sessionCodec());
        try (NcpSession session = client.connect("127.0.0.1", server.getLocalPort(),
                hello(List.of("json")))) {
            assertEquals(EncodingTier.JSON, session.negotiatedTier());
            assertEquals(List.of("json"), session.encodingPolicy().enabledEncodings());
            assertFalse(session.encodingPolicy().binaryVectorEnabled());
        }
    }

    @Test
    void negotiatesBinaryVectorExtension() throws IOException {
        startAccepting(new NcpHandshakeCapsFrame("node", List.of("ncp")));

        NcpNativeClient client = new NcpNativeClient(sessionCodec());
        try (NcpSession session = client.connect("127.0.0.1", server.getLocalPort(),
                hello(List.of("msgpack", "json", "binary_vector.v1")))) {
            assertEquals(EncodingTier.MSGPACK, session.negotiatedTier());
            assertTrue(session.encodingPolicy().binaryVectorEnabled());
            assertEquals(List.of("msgpack", "binary_vector.v1"),
                session.encodingPolicy().enabledEncodings());
        }
    }

    // ── server rejection ────────────────────────────────────────────────────────

    @Test
    void serverRejectionThrowsHandshakeException() throws IOException {
        server = new NcpServer(new InetSocketAddress("127.0.0.1", 0), sessionCodec(), null);
        server.runAsync(conn -> {
            try {
                conn.reject(new ErrorFrame(
                    "NPS-CLIENT-FORBIDDEN",
                    NcpErrorCodes.NCP_VERSION_INCOMPATIBLE,
                    "server too old",
                    null));
            } catch (IOException ignored) {
            }
        });

        NcpNativeClient client = new NcpNativeClient(sessionCodec());
        NcpHandshakeException ex = assertThrows(NcpHandshakeException.class,
            () -> client.connect("127.0.0.1", server.getLocalPort(), hello(List.of("json"))));
        assertEquals(NcpErrorCodes.NCP_VERSION_INCOMPATIBLE, ex.errorCode());
        assertTrue(ex.getMessage().contains("server too old"));
    }

    // ── EXT header round-trip ────────────────────────────────────────────────────

    @Test
    void extHeaderRoundTrip() throws IOException {
        // A payload > 65 535 bytes forces the 8-byte extended header on the wire.
        byte[] blob = new byte[70_000];
        FrameHeader ext = new FrameHeader(FrameType.STREAM,
            FrameHeader.buildFlags(EncodingTier.JSON, true, true), blob.length);
        assertTrue(ext.isExtended);

        byte[] hdr = ext.toBytes();
        assertEquals(FrameHeader.EXTENDED_HEADER_SIZE, hdr.length);

        // Read it back the same way the transport does (peek 2, detect EXT, read 6 more).
        FrameHeader parsed = NcpNativeClient.readFrameHeader(new java.io.ByteArrayInputStream(hdr));
        assertTrue(parsed.isExtended);
        assertEquals(FrameType.STREAM, parsed.frameType);
        assertEquals(70_000L, parsed.payloadLength);
        assertEquals(EncodingTier.JSON, parsed.encodingTier());
    }

    // ── live-session frame exchange ─────────────────────────────────────────────

    @Test
    void liveSessionFrameExchange() throws IOException {
        startAccepting(new NcpHandshakeCapsFrame("node", List.of("ncp")));

        NcpNativeClient client = new NcpNativeClient(sessionCodec());
        try (NcpSession session = client.connect("127.0.0.1", server.getLocalPort(),
                hello(List.of("msgpack", "json")))) {

            AnchorFrame sent = new AnchorFrame("urn:nps:anchor:test:1",
                Map.of("type", "object"), 1200);
            session.sendFrame(sent);

            NpsFrame received = session.receiveFrame(); // server echoes it back
            assertInstanceOf(AnchorFrame.class, received);
            AnchorFrame echoed = (AnchorFrame) received;
            assertEquals("urn:nps:anchor:test:1", echoed.anchorId());
            assertEquals(1200, echoed.ttl());
        }
    }

    // ── preamble rejection ───────────────────────────────────────────────────────

    @Test
    void invalidPreambleRejectedByServer() throws Exception {
        server = new NcpServer(new InetSocketAddress("127.0.0.1", 0), sessionCodec(), null);
        server.runAsync(conn -> { /* accepted connections not expected here */ });

        // Open a raw socket and send a bad preamble; the accept loop's
        // acceptConnection() throws NcpPreambleInvalidException and closes the socket.
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", server.getLocalPort())) {
            s.getOutputStream().write("XXXX/9.9\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            int b = s.getInputStream().read();
            assertEquals(-1, b, "server should close the connection on invalid preamble");
        }
    }

    // ── NcpEncodingPolicy allow/deny ─────────────────────────────────────────────

    @Test
    void encodingPolicyAllowsAndDenies() {
        NcpEncodingPolicy jsonOnly = new NcpEncodingPolicy(EncodingTier.JSON);
        assertTrue(jsonOnly.allows(EncodingTier.JSON, FrameType.ANCHOR));
        assertFalse(jsonOnly.allows(EncodingTier.MSGPACK, FrameType.ANCHOR));
        assertFalse(jsonOnly.allows(EncodingTier.BINARY_VECTOR, FrameType.QUERY));

        NcpEncodingPolicy withBv = new NcpEncodingPolicy(EncodingTier.MSGPACK, true);
        // BinaryVector allowed only for QUERY frames, not arbitrary frames
        assertTrue(withBv.allows(EncodingTier.BINARY_VECTOR, FrameType.QUERY));
        assertFalse(withBv.allows(EncodingTier.BINARY_VECTOR, FrameType.ANCHOR));
        assertTrue(withBv.allows(EncodingTier.MSGPACK, FrameType.ANCHOR));

        // ensureAllows throws on a forbidden tier/type combination
        FrameHeader bad = new FrameHeader(FrameType.ANCHOR,
            FrameHeader.buildFlags(EncodingTier.BINARY_VECTOR, true, false), 0);
        assertThrows(NcpEncodingPolicy.NcpEncodingUnsupportedException.class,
            () -> withBv.ensureAllows(bad));

        // ensureAllows passes on a permitted combination
        FrameHeader good = new FrameHeader(FrameType.ANCHOR,
            FrameHeader.buildFlags(EncodingTier.MSGPACK, true, false), 0);
        assertDoesNotThrow(() -> withBv.ensureAllows(good));
    }

    @Test
    void encodingPolicyFromEnabledEncodings() {
        NcpEncodingPolicy p = NcpEncodingPolicy.fromEnabledEncodings(
            EncodingTier.MSGPACK, List.of("msgpack", "binary_vector.v1"));
        assertEquals(EncodingTier.MSGPACK, p.defaultTier());
        assertTrue(p.binaryVectorEnabled());

        NcpEncodingPolicy p2 = NcpEncodingPolicy.fromEnabledEncodings(EncodingTier.JSON, null);
        assertEquals(EncodingTier.JSON, p2.defaultTier());
        assertFalse(p2.binaryVectorEnabled());
    }

    // ── NcpPatchFormat ───────────────────────────────────────────────────────────

    @Test
    void patchFormatConstantsAndHelpers() {
        assertEquals("json_patch", com.labacacia.nps.core.NcpPatchFormat.JSON_PATCH);
        assertEquals("binary_bitset", com.labacacia.nps.core.NcpPatchFormat.BINARY_BITSET);

        assertTrue(com.labacacia.nps.core.NcpPatchFormat.isValid("json_patch"));
        assertTrue(com.labacacia.nps.core.NcpPatchFormat.isValid("binary_bitset"));
        assertFalse(com.labacacia.nps.core.NcpPatchFormat.isValid("nope"));

        assertTrue(com.labacacia.nps.core.NcpPatchFormat.requiresMsgPack("binary_bitset"));
        assertFalse(com.labacacia.nps.core.NcpPatchFormat.requiresMsgPack("json_patch"));

        assertTrue(com.labacacia.nps.core.NcpPatchFormat
            .isAllowedForTier("binary_bitset", EncodingTier.MSGPACK));
        assertFalse(com.labacacia.nps.core.NcpPatchFormat
            .isAllowedForTier("binary_bitset", EncodingTier.JSON));
        assertTrue(com.labacacia.nps.core.NcpPatchFormat
            .isAllowedForTier("json_patch", EncodingTier.JSON));
    }
}
