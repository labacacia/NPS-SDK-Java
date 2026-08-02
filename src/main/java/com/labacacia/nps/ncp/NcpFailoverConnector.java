// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.exception.NpsProtocolError;

import java.io.IOException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;

/**
 * NPS-CR-0009 §3.3 — native-mode failover reconnect and session continuity.
 *
 * <p>Generic in the session type, so the same connector wraps a TCP socket, a TLS
 * session, or a test double identically. It has no NDP dependency: the active-Anchor
 * lookup is an injected delegate, so it composes with either NDP highest-epoch resolution
 * ({@code NdpRegistry.resolveCluster}) or the {@code successor_nid} carried by a received
 * {@code anchor_failover} event.</p>
 *
 * <p><strong>The re-resolution is the point.</strong> {@link ActiveResolver#resolve()} is
 * called once per attempt, <em>before</em> connecting and including the first attempt —
 * that is what picks up the new active Anchor after an ownership transfer. With the
 * default {@code maxAttempts = 2}, a single failure produces exactly two resolutions.</p>
 *
 * @param <S> the session type produced by a successful connect
 */
public final class NcpFailoverConnector<S> {

    /** A host/port pair for the currently active Anchor. */
    public record Endpoint(String host, int port) {}

    /** Resolves the cluster's currently active Anchor. */
    @FunctionalInterface
    public interface ActiveResolver { Endpoint resolve() throws Exception; }

    /** Opens a session against a resolved endpoint. */
    @FunctionalInterface
    public interface SessionConnector<S> { S connect(String host, int port) throws Exception; }

    private final ActiveResolver     resolveActive;
    private final SessionConnector<S> connect;
    private final int                maxAttempts;

    public NcpFailoverConnector(ActiveResolver resolveActive, SessionConnector<S> connect) {
        this(resolveActive, connect, 2);
    }

    public NcpFailoverConnector(ActiveResolver resolveActive, SessionConnector<S> connect,
                                int maxAttempts) {
        if (resolveActive == null) throw new IllegalArgumentException("resolveActive is required");
        if (connect == null)       throw new IllegalArgumentException("connect is required");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        this.resolveActive = resolveActive;
        this.connect       = connect;
        this.maxAttempts   = maxAttempts;
    }

    public int maxAttempts() { return maxAttempts; }

    /**
     * Resolve and connect, retrying failover-shaped failures up to {@link #maxAttempts()}
     * times.
     *
     * <p>Any error that is not failover-shaped propagates immediately, unwrapped and with
     * no retry. On exhaustion the <em>last</em> captured failure is rethrown, preserving
     * its original type.</p>
     *
     * @throws InterruptedException if the calling thread is interrupted between attempts
     */
    public S connect() throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("failover connect cancelled");
            }
            // Re-resolved EVERY attempt, including the first.
            Endpoint endpoint = resolveActive.resolve();
            try {
                return connect.connect(endpoint.host(), endpoint.port());
            } catch (Exception e) {
                if (!isFailoverShaped(e)) throw e;
                last = e;
            }
        }
        throw last;   // maxAttempts >= 1, so at least one failure was captured
    }

    /**
     * A failure is failover-shaped when it is a socket/IO fault, or an NPS protocol error
     * carrying {@link NcpErrorCodes#NCP_NID_MISMATCH} — the session landed on an Anchor
     * that no longer owns the NID binding.
     */
    static boolean isFailoverShaped(Throwable e) {
        if (e == null) return false;
        if (e instanceof SocketException || e instanceof ClosedChannelException) return true;
        if (e instanceof NpsProtocolError p) {
            return NcpErrorCodes.NCP_NID_MISMATCH.equals(p.protocolErrorCode());
        }
        return e instanceof IOException;
    }
}
