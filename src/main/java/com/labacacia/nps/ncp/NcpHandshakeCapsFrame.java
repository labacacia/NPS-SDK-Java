// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server's capability response to a {@link HelloFrame} in native mode (NPS-1 §4.6).
 *
 * <p>Carries the server NID and its capability list. Uses frame type 0x04 (CAPS)
 * on the wire — the same type byte as the anchor-query {@link CapsFrame}, but a
 * different payload shape. Handshake code paths decode CAPS frames with a
 * dedicated registry that maps 0x04 to this type.
 *
 * <p>The response frame header determines the stable default encoding; the optional
 * payload fields ({@code negotiated_encoding} / {@code enabled_encodings}) echo the
 * full enabled encoding policy for extensions such as BinaryVector.
 *
 * <p>Preferred encoding is Tier-1 JSON (the server MAY answer in JSON before the
 * client observes the negotiated default tier from the response header).
 */
public final class NcpHandshakeCapsFrame implements NpsFrame {

    private final String       nodeId;
    private final List<String> caps;
    private final String       negotiatedEncoding; // nullable
    private final List<String> enabledEncodings;   // nullable
    private final String       anchorRef;          // nullable
    private final Object       payload;            // nullable
    private final String       sessionVersion;
    private final List<String> supportedProtocols;
    private final Integer      maxFramePayload;
    private final Boolean      extSupport;
    private final Integer      maxConcurrentStreams;

    public NcpHandshakeCapsFrame(String nodeId,
                                 List<String> caps,
                                 String negotiatedEncoding,
                                 List<String> enabledEncodings,
                                 String anchorRef,
                                 Object payload) {
        this(nodeId, caps, negotiatedEncoding, enabledEncodings, anchorRef, payload,
            null, null, null, null, null);
    }

    public NcpHandshakeCapsFrame(String nodeId,
                                 List<String> caps,
                                 String negotiatedEncoding,
                                 List<String> enabledEncodings,
                                 String anchorRef,
                                 Object payload,
                                 String sessionVersion,
                                 List<String> supportedProtocols,
                                 Integer maxFramePayload,
                                 Boolean extSupport,
                                 Integer maxConcurrentStreams) {
        this.nodeId             = nodeId;
        this.caps               = caps;
        this.negotiatedEncoding = negotiatedEncoding;
        this.enabledEncodings   = enabledEncodings;
        this.anchorRef          = anchorRef;
        this.payload            = payload;
        this.sessionVersion     = sessionVersion;
        this.supportedProtocols = supportedProtocols;
        this.maxFramePayload    = maxFramePayload;
        this.extSupport         = extSupport;
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    public NcpHandshakeCapsFrame(String nodeId, List<String> caps) {
        this(nodeId, caps, null, null, null, null);
    }

    @Override public FrameType    frameType()    { return FrameType.CAPS; }
    @Override public EncodingTier preferredTier() { return EncodingTier.JSON; }

    public String       nodeId()             { return nodeId; }
    public List<String> caps()               { return caps; }
    public String       negotiatedEncoding() { return negotiatedEncoding; }
    public List<String> enabledEncodings()   { return enabledEncodings; }
    public String       anchorRef()          { return anchorRef; }
    public Object       payload()            { return payload; }
    public String       sessionVersion()     { return sessionVersion; }
    public List<String> supportedProtocols() { return supportedProtocols; }
    public Integer      maxFramePayload()    { return maxFramePayload; }
    public Boolean      extSupport()         { return extSupport; }
    public Integer      maxConcurrentStreams() { return maxConcurrentStreams; }

    /**
     * Returns a copy of this frame with {@code negotiatedEncoding} and
     * {@code enabledEncodings} replaced — mirrors the C# {@code with} expression
     * used when the server fills in the negotiated policy before sending.
     */
    public NcpHandshakeCapsFrame withNegotiation(String negotiatedEncoding,
                                                 List<String> enabledEncodings) {
        return new NcpHandshakeCapsFrame(
            nodeId, caps, negotiatedEncoding, enabledEncodings, anchorRef, payload);
    }

    public NcpHandshakeCapsFrame withNegotiation(
            NcpHandshakePolicy.Decision decision) {
        return new NcpHandshakeCapsFrame(
            nodeId, caps,
            decision.negotiatedEncoding(),
            decision.enabledEncodings(),
            anchorRef, payload,
            decision.sessionVersion(),
            decision.supportedProtocols(),
            decision.maxFramePayload(),
            decision.extSupport(),
            decision.maxConcurrentStreams());
    }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node_id", nodeId);
        m.put("caps",    caps);
        if (negotiatedEncoding != null) m.put("negotiated_encoding", negotiatedEncoding);
        if (enabledEncodings   != null) m.put("enabled_encodings",   enabledEncodings);
        if (sessionVersion     != null) m.put("session_version",     sessionVersion);
        if (supportedProtocols != null) m.put("supported_protocols", supportedProtocols);
        if (maxFramePayload    != null) m.put("max_frame_payload",   maxFramePayload);
        if (extSupport         != null) m.put("ext_support",         extSupport);
        if (maxConcurrentStreams != null) {
            m.put("max_concurrent_streams", maxConcurrentStreams);
        }
        if (anchorRef          != null) m.put("anchor_ref",          anchorRef);
        if (payload            != null) m.put("payload",             payload);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static NcpHandshakeCapsFrame fromDict(Map<String, Object> d) {
        return new NcpHandshakeCapsFrame(
            (String)       d.get("node_id"),
            (List<String>) d.get("caps"),
            (String)       d.get("negotiated_encoding"),
            (List<String>) d.get("enabled_encodings"),
            (String)       d.get("anchor_ref"),
            d.get("payload"),
            (String)       d.get("session_version"),
            (List<String>) d.get("supported_protocols"),
            d.get("max_frame_payload") instanceof Number n ? n.intValue() : null,
            (Boolean)      d.get("ext_support"),
            d.get("max_concurrent_streams") instanceof Number n ? n.intValue() : null
        );
    }
}
