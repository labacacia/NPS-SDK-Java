// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.Map;

/**
 * Base type for topology stream events.
 *
 * <p>The Anchor client exposes typed subclasses for the well-known event kinds,
 * while the generic fields keep the lightweight alpha.11 model available for
 * callers that need to carry an opaque event envelope.</p>
 */
public class TopologyEvent {
    public long version;
    public String eventId;
    public String eventType;
    public String nodeId;
    public String anchorRef;
    public String timestamp;
    public Map<String, Object> payload;

    public TopologyEvent() {
        this(0L);
    }

    public TopologyEvent(long version) {
        this.version = version;
    }

    public TopologyEvent(String eventId, String eventType, String nodeId,
                         String anchorRef, String timestamp, Map<String, Object> payload) {
        this.version   = 0L;
        this.eventId   = eventId;
        this.eventType = eventType;
        this.nodeId    = nodeId;
        this.anchorRef = anchorRef;
        this.timestamp = timestamp;
        this.payload   = payload;
    }
}
