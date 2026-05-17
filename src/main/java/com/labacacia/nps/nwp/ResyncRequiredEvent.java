// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * Emitted when the subscriber's {@code since_version} is no longer replayable.
 * Wire {@code event_type}: {@code resync_required}. {@link #version} is always 0.
 * Callers MUST issue a fresh {@link AnchorNodeClient#getSnapshot} before
 * resubscribing.
 */
public final class ResyncRequiredEvent extends TopologyEvent {

    public String reason;

    public ResyncRequiredEvent() {
        super(0L);
    }

    public ResyncRequiredEvent(String reason) {
        super(0L);
        this.reason = reason;
    }
}
