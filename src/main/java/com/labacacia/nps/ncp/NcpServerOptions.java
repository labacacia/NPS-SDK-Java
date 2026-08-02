// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import java.net.Socket;

/**
 * Server-side native NCP transport options (NPS-1 §4.6).
 *
 * <p>Instances are immutable; build with the fluent {@code with*} methods, e.g.
 * {@code new NcpServerOptions().withHandshakeReadTimeoutMs(5000)}.
 */
public final class NcpServerOptions {

    /**
     * Optional hook that wraps or authenticates the accepted socket before the
     * NCP preamble is read. Use this to install TLS/mTLS. Return the (possibly
     * wrapped) socket to use for the rest of the handshake.
     */
    @FunctionalInterface
    public interface StreamAuthenticator {
        Socket authenticate(Socket socket) throws Exception;
    }

    private final StreamAuthenticator authenticator;         // nullable
    private final boolean             requireAuthenticated;
    private final long                maxHelloPayload;
    private final long                handshakeReadTimeoutMs;
    private final long                helloReadTimeoutMs;
    private final NcpHandshakeProfile handshakeProfile;

    /** Default maximum HelloFrame payload: the non-extended frame ceiling (65 535 bytes). */
    public static final long DEFAULT_MAX_HELLO_PAYLOAD = 0xFFFFL;

    public NcpServerOptions() {
        this(null, false, DEFAULT_MAX_HELLO_PAYLOAD,
            NcpPreamble.READ_TIMEOUT_MS, 5_000, NcpHandshakeProfile.defaults());
    }

    private NcpServerOptions(StreamAuthenticator authenticator,
                             boolean requireAuthenticated,
                             long maxHelloPayload,
                             long handshakeReadTimeoutMs,
                             long helloReadTimeoutMs,
                             NcpHandshakeProfile handshakeProfile) {
        this.authenticator          = authenticator;
        this.requireAuthenticated   = requireAuthenticated;
        this.maxHelloPayload        = maxHelloPayload;
        this.handshakeReadTimeoutMs = handshakeReadTimeoutMs;
        this.helloReadTimeoutMs     = helloReadTimeoutMs;
        this.handshakeProfile       = handshakeProfile;
    }

    public StreamAuthenticator authenticator()      { return authenticator; }
    public boolean requireAuthenticatedStream()     { return requireAuthenticated; }
    public long    maxHelloPayload()                { return maxHelloPayload; }
    public long    handshakeReadTimeoutMs()         { return handshakeReadTimeoutMs; }
    public long    helloReadTimeoutMs()             { return helloReadTimeoutMs; }
    public NcpHandshakeProfile handshakeProfile()   { return handshakeProfile; }

    public NcpServerOptions withAuthenticator(StreamAuthenticator authenticator) {
        return new NcpServerOptions(authenticator, requireAuthenticated, maxHelloPayload,
            handshakeReadTimeoutMs, helloReadTimeoutMs, handshakeProfile);
    }

    public NcpServerOptions withRequireAuthenticatedStream(boolean require) {
        return new NcpServerOptions(authenticator, require, maxHelloPayload,
            handshakeReadTimeoutMs, helloReadTimeoutMs, handshakeProfile);
    }

    public NcpServerOptions withMaxHelloPayload(long maxHelloPayload) {
        return new NcpServerOptions(authenticator, requireAuthenticated, maxHelloPayload,
            handshakeReadTimeoutMs, helloReadTimeoutMs, handshakeProfile);
    }

    public NcpServerOptions withHandshakeReadTimeoutMs(long handshakeReadTimeoutMs) {
        return new NcpServerOptions(authenticator, requireAuthenticated, maxHelloPayload,
            handshakeReadTimeoutMs, helloReadTimeoutMs, handshakeProfile);
    }

    public NcpServerOptions withHelloReadTimeoutMs(long value) {
        return new NcpServerOptions(authenticator, requireAuthenticated, maxHelloPayload,
            handshakeReadTimeoutMs, value, handshakeProfile);
    }

    public NcpServerOptions withHandshakeProfile(NcpHandshakeProfile value) {
        return new NcpServerOptions(authenticator, requireAuthenticated, maxHelloPayload,
            handshakeReadTimeoutMs, helloReadTimeoutMs, value);
    }
}
