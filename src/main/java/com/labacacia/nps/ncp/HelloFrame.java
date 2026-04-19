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
 * Native-mode client handshake frame (NPS-1 §4.6).
 *
 * <p>The Agent MUST send this as the very first frame after opening a TCP/QUIC
 * connection; the Node replies with a CapsFrame. Not used in HTTP mode.
 *
 * <p>Preferred encoding is Tier-1 JSON because the encoding has not yet been
 * negotiated at handshake time.
 */
public final class HelloFrame implements NpsFrame {

    public static final int DEFAULT_MAX_FRAME_PAYLOAD      = 0xFFFF; // 65 535 bytes
    public static final int DEFAULT_MAX_CONCURRENT_STREAMS = 32;

    private final String       npsVersion;
    private final List<String> supportedEncodings;
    private final List<String> supportedProtocols;
    private final String       minVersion;            // nullable
    private final String       agentId;               // nullable
    private final int          maxFramePayload;
    private final boolean      extSupport;
    private final int          maxConcurrentStreams;
    private final List<String> e2eEncAlgorithms;      // nullable

    public HelloFrame(String npsVersion,
                      List<String> supportedEncodings,
                      List<String> supportedProtocols,
                      String minVersion,
                      String agentId,
                      int maxFramePayload,
                      boolean extSupport,
                      int maxConcurrentStreams,
                      List<String> e2eEncAlgorithms) {
        this.npsVersion           = npsVersion;
        this.supportedEncodings   = supportedEncodings;
        this.supportedProtocols   = supportedProtocols;
        this.minVersion           = minVersion;
        this.agentId              = agentId;
        this.maxFramePayload      = maxFramePayload;
        this.extSupport           = extSupport;
        this.maxConcurrentStreams = maxConcurrentStreams;
        this.e2eEncAlgorithms     = e2eEncAlgorithms;
    }

    public HelloFrame(String npsVersion,
                      List<String> supportedEncodings,
                      List<String> supportedProtocols) {
        this(npsVersion, supportedEncodings, supportedProtocols,
             null, null,
             DEFAULT_MAX_FRAME_PAYLOAD, false, DEFAULT_MAX_CONCURRENT_STREAMS,
             null);
    }

    @Override public FrameType    frameType()    { return FrameType.HELLO; }
    @Override public EncodingTier preferredTier() { return EncodingTier.JSON; }

    public String       npsVersion()            { return npsVersion; }
    public List<String> supportedEncodings()    { return supportedEncodings; }
    public List<String> supportedProtocols()    { return supportedProtocols; }
    public String       minVersion()            { return minVersion; }
    public String       agentId()               { return agentId; }
    public int          maxFramePayload()       { return maxFramePayload; }
    public boolean      extSupport()            { return extSupport; }
    public int          maxConcurrentStreams()  { return maxConcurrentStreams; }
    public List<String> e2eEncAlgorithms()      { return e2eEncAlgorithms; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nps_version",            npsVersion);
        m.put("supported_encodings",    supportedEncodings);
        m.put("supported_protocols",    supportedProtocols);
        m.put("max_frame_payload",      maxFramePayload);
        m.put("ext_support",            extSupport);
        m.put("max_concurrent_streams", maxConcurrentStreams);
        if (minVersion       != null) m.put("min_version",        minVersion);
        if (agentId          != null) m.put("agent_id",           agentId);
        if (e2eEncAlgorithms != null) m.put("e2e_enc_algorithms", e2eEncAlgorithms);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static HelloFrame fromDict(Map<String, Object> d) {
        Object mfp = d.get("max_frame_payload");
        Object mcs = d.get("max_concurrent_streams");
        Object ext = d.get("ext_support");
        return new HelloFrame(
            (String)       d.get("nps_version"),
            (List<String>) d.get("supported_encodings"),
            (List<String>) d.get("supported_protocols"),
            (String)       d.get("min_version"),
            (String)       d.get("agent_id"),
            mfp instanceof Number n ? n.intValue() : DEFAULT_MAX_FRAME_PAYLOAD,
            ext instanceof Boolean b && b,
            mcs instanceof Number n ? n.intValue() : DEFAULT_MAX_CONCURRENT_STREAMS,
            (List<String>) d.get("e2e_enc_algorithms")
        );
    }
}
