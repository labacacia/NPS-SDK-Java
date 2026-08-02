// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.NpsStatusCodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NPS-CR-0009 §3.2 — the Anchor epoch fence and leader check for topology traffic
 * (NWP v0.18 §12.2, {@code spec/cr/NPS-CR-0009-multi-anchor-ha.md} §4).
 *
 * <p>The reference .NET implementation ships the two error constants but no fence; this
 * is built directly from the change request. Intra-cluster consensus (Raft/Paxos/lease
 * store) is explicitly out of scope and implementation-defined — only the observable
 * wire contract modelled here is normative.</p>
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Epoch fence</b> — applies to <em>any</em> inbound frame, read or write. An
 *       inbound {@code cluster_epoch} strictly greater than our own means we are a
 *       superseded leader: demote to standby, emit a terminal {@code anchor_failover},
 *       close every topology stream, and fail with {@code NWP-ANCHOR-EPOCH-FENCED}.
 *       An equal or lower inbound epoch is <em>not</em> an error.</li>
 *   <li><b>Leader check</b> — writes only. A standby, or an active owner that has lost
 *       quorum, rejects topology writes with {@code NWP-ANCHOR-NOT-LEADER}.</li>
 *   <li><b>Reads</b> always proceed; a standby MAY serve stale reads stamped with its
 *       last-known epoch.</li>
 * </ol>
 *
 * <p>All mutating operations are synchronized; the instance is safe to share across
 * request threads.</p>
 */
public final class AnchorEpochFence {

    /** Ownership role of this Anchor within its cluster. */
    public enum Role { ACTIVE, STANDBY }

    /** Hook invoked when the fence trips, to terminate every open topology stream. */
    @FunctionalInterface
    public interface StreamCloser { void closeAll(); }

    private final String selfNid;
    private final List<AnchorStateEvent> emitted = new ArrayList<>();

    private long    ownEpoch;
    private long    highestObservedEpoch;
    private Role    role;
    private boolean degraded;
    private StreamCloser streamCloser = () -> { };

    /** A fresh single-Anchor cluster: epoch 1, ACTIVE, healthy. */
    public AnchorEpochFence(String selfNid) {
        this(selfNid, 1L, Role.ACTIVE);
    }

    public AnchorEpochFence(String selfNid, long ownEpoch, Role role) {
        if (selfNid == null || selfNid.isEmpty()) {
            throw new IllegalArgumentException("selfNid must be non-null and non-empty");
        }
        this.selfNid              = selfNid;
        this.ownEpoch             = ownEpoch;
        this.highestObservedEpoch = ownEpoch;
        this.role                 = role == null ? Role.ACTIVE : role;
    }

    // ── State ────────────────────────────────────────────────────────────────

    public String  selfNid()  { return selfNid; }
    public synchronized long    ownEpoch() { return ownEpoch; }
    public synchronized Role    role()     { return role; }
    public synchronized boolean degraded() { return degraded; }

    /** {@code health} value this Anchor should publish on its NDP self-announcement. */
    public synchronized String healthHint() { return degraded ? "degraded" : "healthy"; }

    /** Events emitted so far, oldest first. */
    public synchronized List<AnchorStateEvent> emittedEvents() {
        return Collections.unmodifiableList(new ArrayList<>(emitted));
    }

    /** Register the callback used to terminate topology streams when the fence trips. */
    public synchronized void onFenceCloseStreams(StreamCloser closer) {
        this.streamCloser = closer == null ? () -> { } : closer;
    }

    // ── (a) + (b) inbound gate ───────────────────────────────────────────────

    /**
     * Run the epoch fence and, for writes, the leader check.
     *
     * @param inboundClusterEpoch the frame's {@code cluster_epoch}; {@code null} ⇒ 1
     * @param senderAnchorNid     the sending Anchor's NID, used as the failover successor
     * @param isTopologyWrite     {@code true} for a topology-mutating request
     * @throws TopologyProtocolException {@code NWP-ANCHOR-EPOCH-FENCED} or
     *                                   {@code NWP-ANCHOR-NOT-LEADER}, both
     *                                   {@code NPS-CLIENT-CONFLICT} (HTTP 409)
     */
    public void checkInbound(Long inboundClusterEpoch, String senderAnchorNid, boolean isTopologyWrite) {
        StreamCloser closer = null;
        TopologyProtocolException fenced = null;
        synchronized (this) {
            long inbound = inboundClusterEpoch == null ? 1L : inboundClusterEpoch;
            if (inbound > highestObservedEpoch) highestObservedEpoch = inbound;

            // (a) EPOCH FENCE — first, for ANY inbound frame, read or write.
            if (inbound > ownEpoch) {
                role     = Role.STANDBY;
                emitted.add(AnchorStateEvent.failover(
                    senderAnchorNid, inbound, AnchorFailoverDetails.REASON_ACTIVE_LOST, 0L));
                closer = streamCloser;
                fenced = new TopologyProtocolException(
                    NwpErrorCodes.NWP_ANCHOR_EPOCH_FENCED, NpsStatusCodes.NPS_CLIENT_CONFLICT,
                    "Inbound cluster_epoch " + inbound + " supersedes this Anchor's epoch "
                        + ownEpoch + "; this Anchor has self-fenced.");
            } else if (isTopologyWrite && (role != Role.ACTIVE || degraded)) {
                // (b) LEADER CHECK — writes only. inbound <= ownEpoch is NOT an error.
                throw new TopologyProtocolException(
                    NwpErrorCodes.NWP_ANCHOR_NOT_LEADER, NpsStatusCodes.NPS_CLIENT_CONFLICT,
                    degraded && role == Role.ACTIVE
                        ? "This Anchor has lost quorum and is read-only-degraded; topology writes are refused."
                        : "This Anchor is a standby; topology writes must be sent to the active owner.");
            }
            // (c) reads always proceed.
        }
        if (fenced != null) {
            closer.closeAll();
            throw fenced;
        }
    }

    /** Convenience overload for a read. */
    public void checkInbound(Long inboundClusterEpoch, String senderAnchorNid) {
        checkInbound(inboundClusterEpoch, senderAnchorNid, false);
    }

    // ── Response stamping ────────────────────────────────────────────────────

    /**
     * Stamp a snapshot with the current {@code cluster_epoch}. NWP §12.2 requires every
     * {@code topology.snapshot} / {@code topology.stream} response to carry it.
     */
    public synchronized TopologySnapshot stampResponse(TopologySnapshot snapshot) {
        if (snapshot != null) snapshot.clusterEpoch = ownEpoch;
        return snapshot;
    }

    // ── Lifecycle transitions ────────────────────────────────────────────────

    /**
     * The Anchor lost its write quorum: enter read-only-degraded and emit
     * {@code anchor_quorum_lost}. The caller should also flip its NDP self-announcement
     * {@code health} to {@link #healthHint()}.
     */
    public synchronized AnchorStateEvent onQuorumLost(int quorumSize, int available) {
        degraded = true;
        AnchorStateEvent ev = AnchorStateEvent.quorumLost(quorumSize, available);
        emitted.add(ev);
        return ev;
    }

    /** Quorum restored: leave read-only-degraded. */
    public synchronized void onQuorumRestored() { degraded = false; }

    /**
     * This Anchor took ownership of the cluster at {@code newEpoch}.
     *
     * <p>The new epoch MUST be strictly greater than every epoch ever observed. The
     * caller MUST then re-sign and re-publish its AnnounceFrame with the new
     * {@code cluster_epoch} — the field is inside the signed canonical form.</p>
     *
     * @return the {@code anchor_failover} event to broadcast
     */
    public synchronized AnchorStateEvent onTakeOwnership(long newEpoch, String reason) {
        if (newEpoch <= highestObservedEpoch) {
            throw new IllegalArgumentException(
                "cluster_epoch must strictly increase: " + newEpoch
                    + " does not exceed the highest observed epoch " + highestObservedEpoch);
        }
        ownEpoch             = newEpoch;
        highestObservedEpoch = newEpoch;
        role                 = Role.ACTIVE;
        degraded             = false;
        AnchorStateEvent ev = AnchorStateEvent.failover(selfNid, newEpoch, reason, 0L);
        emitted.add(ev);
        return ev;
    }

    /** {@link #onTakeOwnership(long, String)} with {@code reason = "planned"}. */
    public AnchorStateEvent onTakeOwnership(long newEpoch) {
        return onTakeOwnership(newEpoch, AnchorFailoverDetails.REASON_PLANNED);
    }
}
