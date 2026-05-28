// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NWP SubscribeFrame (frame type 0x12) — spec §8.1.
 *
 * <p>Used to open a server-sent event subscription on a named stream. The
 * {@code action} and {@code streamId} fields are required; all others are optional.
 */
public final class SubscribeFrame implements NpsFrame {

    private final String             action;            // required
    private final String             streamId;          // required  (serialized as "stream_id")
    private final String             anchorRef;         // nullable  (serialized as "anchor_ref")
    private final Map<String,Object> filter;            // nullable
    private final Integer            heartbeatInterval; // nullable  (serialized as "heartbeat_interval")
    private final Long               resumeFromSeq;     // nullable  (serialized as "resume_from_seq")
    private final String             type;              // nullable

    public SubscribeFrame(String action, String streamId, String anchorRef,
                          Map<String,Object> filter, Integer heartbeatInterval,
                          Long resumeFromSeq, String type) {
        this.action            = action;
        this.streamId          = streamId;
        this.anchorRef         = anchorRef;
        this.filter            = filter;
        this.heartbeatInterval = heartbeatInterval;
        this.resumeFromSeq     = resumeFromSeq;
        this.type              = type;
    }

    /** Minimal constructor for required fields only. */
    public SubscribeFrame(String action, String streamId) {
        this(action, streamId, null, null, null, null, null);
    }

    @Override public FrameType    frameType()    { return FrameType.SUBSCRIBE; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String             action()            { return action; }
    public String             streamId()          { return streamId; }
    public String             anchorRef()         { return anchorRef; }
    public Map<String,Object> filter()            { return filter; }
    public Integer            heartbeatInterval() { return heartbeatInterval; }
    public Long               resumeFromSeq()     { return resumeFromSeq; }
    public String             type()              { return type; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action",             action);
        m.put("stream_id",          streamId);
        m.put("anchor_ref",         anchorRef);
        m.put("filter",             filter);
        m.put("heartbeat_interval", heartbeatInterval);
        m.put("resume_from_seq",    resumeFromSeq);
        m.put("type",               type);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SubscribeFrame fromDict(Map<String, Object> d) {
        Object hi  = d.get("heartbeat_interval");
        Object rfs = d.get("resume_from_seq");
        return new SubscribeFrame(
            (String) d.get("action"),
            (String) d.get("stream_id"),
            (String) d.get("anchor_ref"),
            (Map<String,Object>) d.get("filter"),
            hi  instanceof Number n ? n.intValue() : null,
            rfs instanceof Number n ? n.longValue() : null,
            (String) d.get("type")
        );
    }
}
