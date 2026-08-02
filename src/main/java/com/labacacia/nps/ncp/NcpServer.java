// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.FrameHeader;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.exception.NpsFrameError;
import com.labacacia.nps.core.registry.FrameRegistry;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * NCP native-mode TCP server (NPS-1 §4.6). Listens on a configured endpoint,
 * validates the connection preamble, reads the client's {@link HelloFrame}, and
 * returns an {@link NcpServerConnection} for the application to
 * {@link NcpServerConnection#accept accept} or {@link NcpServerConnection#reject reject}.
 *
 * <p>Two accept styles are provided: a blocking {@link #acceptConnection()} and a
 * background-thread loop via {@link #runAsync(Consumer)}.
 */
public final class NcpServer implements Closeable {

    /**
     * Handshake-only registry: 0x06 (HELLO) → {@link HelloFrame},
     * 0xFE (ERROR) → {@link ErrorFrame}. Used to decode the inbound Hello payload.
     */
    private static final FrameRegistry HANDSHAKE_REGISTRY = buildHandshakeRegistry();

    private final ServerSocket    listener;
    private final NpsFrameCodec   codec;
    private final NpsFrameCodec   handshakeCodec;
    private final NcpServerOptions options;
    private final AtomicBoolean   running = new AtomicBoolean(false);
    private volatile Thread       acceptThread;

    /** Binds to {@code port} on all interfaces. */
    public NcpServer(int port, NpsFrameCodec codec) throws IOException {
        this(new InetSocketAddress((InetAddress) null, port), codec, null);
    }

    /** Binds to {@code endpoint}. Use port 0 for an ephemeral port. */
    public NcpServer(InetSocketAddress endpoint, NpsFrameCodec codec, NcpServerOptions options) throws IOException {
        this.codec          = codec;
        this.handshakeCodec = new NpsFrameCodec(HANDSHAKE_REGISTRY);
        this.options        = options != null ? options : new NcpServerOptions();
        this.listener       = new ServerSocket();
        this.listener.bind(endpoint);
    }

    private static FrameRegistry buildHandshakeRegistry() {
        FrameRegistry r = new FrameRegistry();
        r.register(FrameType.HELLO, HelloFrame::fromDict);
        r.register(FrameType.ERROR, ErrorFrame::fromDict);
        return r;
    }

    /** The local port the listener is bound to (useful with an ephemeral port). */
    public int getLocalPort() { return listener.getLocalPort(); }

    /**
     * Accepts the next inbound connection, validates the NPS preamble, reads the
     * client's {@link HelloFrame}, and returns an {@link NcpServerConnection}.
     *
     * @throws NcpPreamble.NcpPreambleInvalidException client did not send a valid preamble.
     * @throws NpsFrameError first frame was not a {@link HelloFrame}, or exceeded the payload cap.
     */
    public NcpServerConnection acceptConnection() throws IOException {
        Socket socket = listener.accept();
        try {
            socket = authenticate(socket);

            if (options.handshakeReadTimeoutMs() > 0) {
                socket.setSoTimeout((int) Math.min(options.handshakeReadTimeoutMs(), Integer.MAX_VALUE));
            }

            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 1 — read & validate preamble
            byte[] preambleBuf = new byte[NcpPreamble.LENGTH];
            in.readFully(preambleBuf);
            NcpPreamble.validate(preambleBuf); // throws on mismatch

            if (options.helloReadTimeoutMs() > 0) {
                socket.setSoTimeout((int) Math.min(
                    options.helloReadTimeoutMs(), Integer.MAX_VALUE));
            } else {
                socket.setSoTimeout(0);
            }

            // 2 — read frame header
            FrameHeader header = NcpNativeClient.readFrameHeader(in);

            var headerDecision = NcpHandshakePolicy.evaluateHelloHeader(
                header, 0, 0, options.maxHelloPayload());
            if (headerDecision.action() != NcpHandshakePolicy.Action.CONTINUE) {
                throw new NpsFrameError("Invalid native NCP Hello header.");
            }

            // 3 — read payload and decode HelloFrame
            byte[] payload = in.readNBytes((int) header.payloadLength);
            if (payload.length != header.payloadLength) {
                throw new EOFException("Truncated HelloFrame payload: expected "
                    + header.payloadLength + " bytes, got " + payload.length);
            }

            HelloFrame hello = (HelloFrame) decodeHandshakeFrame(header, payload);
            if (hello == null) {
                throw new NpsFrameError("HelloFrame payload deserialised to null.");
            }

            // clear the handshake read timeout for the live session
            socket.setSoTimeout(0);

            return new NcpServerConnection(
                socket, codec, hello, options.handshakeProfile());
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * Runs a background accept loop; each accepted connection is passed to
     * {@code handler}. Returns immediately. The handler owns each connection
     * (must accept/reject/close it). Exceptions from individual connections are
     * swallowed so the loop keeps serving; the loop exits when the server is closed.
     */
    public void runAsync(Consumer<NcpServerConnection> handler) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("NcpServer accept loop already running.");
        }
        Thread t = new Thread(() -> {
            while (running.get() && !listener.isClosed()) {
                NcpServerConnection conn;
                try {
                    conn = acceptConnection();
                } catch (IOException | RuntimeException e) {
                    if (!running.get() || listener.isClosed()) break;
                    continue; // reject/protocol error on one connection — keep serving
                }
                try {
                    handler.accept(conn);
                } catch (RuntimeException ignored) {
                    // handler owns cleanup; keep serving
                }
            }
        }, "ncp-server-accept");
        t.setDaemon(true);
        this.acceptThread = t;
        t.start();
    }

    private Socket authenticate(Socket socket) throws IOException {
        NcpServerOptions.StreamAuthenticator auth = options.authenticator();
        if (auth == null) {
            if (options.requireAuthenticatedStream()) {
                throw new NpsFrameError(
                    "NcpServerOptions.requireAuthenticatedStream is true, but no authenticator is configured.");
            }
            return socket;
        }
        try {
            Socket authenticated = auth.authenticate(socket);
            if (authenticated == null) {
                throw new NpsFrameError("NCP stream authentication hook returned null.");
            }
            if (options.requireAuthenticatedStream() && authenticated == socket) {
                throw new NpsFrameError(
                    "NCP stream authentication hook returned the original socket while requireAuthenticatedStream is true.");
            }
            return authenticated;
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new NpsFrameError("NCP stream authentication hook failed: " + e.getMessage(), e);
        }
    }

    private Object decodeHandshakeFrame(FrameHeader header, byte[] payload) {
        byte[] hdr  = header.toBytes();
        byte[] wire = new byte[hdr.length + payload.length];
        System.arraycopy(hdr, 0, wire, 0, hdr.length);
        System.arraycopy(payload, 0, wire, hdr.length, payload.length);
        return handshakeCodec.decode(wire);
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        listener.close();
        Thread t = acceptThread;
        if (t != null) t.interrupt();
    }
}
