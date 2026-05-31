// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NWP SubscribeFrame (frame type 0x12) — NWP v0.13 §13.
 *
 * <p>Opens a server-push subscription. The server streams DiffFrame events
 * until the subscription is cancelled or max_events is reached.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SubscribeFrame implements NpsFrame {

    @JsonProperty("subscription_id") public final String subscriptionId;
    @JsonProperty("filter")          public final Map<String, Object> filter;
    @JsonProperty("heartbeat_interval_ms") public final Integer heartbeatIntervalMs;
    @JsonProperty("max_events")      public final Integer maxEvents;
    @JsonProperty("cursor")          public final String cursor;

    /** Minimal constructor (required field only). */
    public SubscribeFrame(String subscriptionId) {
        this(subscriptionId, null, null, null, null);
    }

    @JsonCreator
    public SubscribeFrame(
            @JsonProperty("subscription_id")      String subscriptionId,
            @JsonProperty("filter")               Map<String, Object> filter,
            @JsonProperty("heartbeat_interval_ms") Integer heartbeatIntervalMs,
            @JsonProperty("max_events")           Integer maxEvents,
            @JsonProperty("cursor")               String cursor) {
        this.subscriptionId      = subscriptionId;
        this.filter              = filter;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.maxEvents           = maxEvents;
        this.cursor              = cursor;
    }

    @Override public FrameType    frameType()     { return FrameType.SUBSCRIBE; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subscription_id", subscriptionId);
        if (filter              != null) m.put("filter",               filter);
        if (heartbeatIntervalMs != null) m.put("heartbeat_interval_ms", heartbeatIntervalMs);
        if (maxEvents           != null) m.put("max_events",            maxEvents);
        if (cursor              != null) m.put("cursor",                cursor);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SubscribeFrame fromDict(Map<String, Object> d) {
        Object hi = d.get("heartbeat_interval_ms");
        Object me = d.get("max_events");
        return new SubscribeFrame(
            (String) d.get("subscription_id"),
            (Map<String, Object>) d.get("filter"),
            hi instanceof Number n ? n.intValue() : null,
            me instanceof Number n ? n.intValue() : null,
            (String) d.get("cursor")
        );
    }
}
