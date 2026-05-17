// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * Base type for events pushed by {@code topology.stream} (NPS-2 §12.2).
 * The concrete subclass corresponds 1:1 to the wire {@code event_type}.
 */
public abstract class TopologyEvent {

    /**
     * Post-event topology version. {@code 0} for {@link ResyncRequiredEvent}
     * (the only event that omits a version per §12.2).
     */
    public long version;

    protected TopologyEvent() {}

    protected TopologyEvent(long version) {
        this.version = version;
    }
}
