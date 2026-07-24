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

    public NcpHandshakeCapsFrame(String nodeId,
                                 List<String> caps,
                                 String negotiatedEncoding,
                                 List<String> enabledEncodings,
                                 String anchorRef,
                                 Object payload) {
        this.nodeId             = nodeId;
        this.caps               = caps;
        this.negotiatedEncoding = negotiatedEncoding;
        this.enabledEncodings   = enabledEncodings;
        this.anchorRef          = anchorRef;
        this.payload            = payload;
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

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node_id", nodeId);
        m.put("caps",    caps);
        if (negotiatedEncoding != null) m.put("negotiated_encoding", negotiatedEncoding);
        if (enabledEncodings   != null) m.put("enabled_encodings",   enabledEncodings);
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
            d.get("payload")
        );
    }
}
