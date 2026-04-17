// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnnounceFrame implements NpsFrame {

    private final String             nid;
    private final List<Map<String,Object>> addresses;
    private final List<String>       capabilities;
    private final int                ttl;
    private final String             timestamp;
    private final String             signature;
    private final String             nodeType; // nullable

    public AnnounceFrame(String nid, List<Map<String,Object>> addresses,
                         List<String> capabilities, int ttl, String timestamp,
                         String signature, String nodeType) {
        this.nid          = nid;
        this.addresses    = addresses;
        this.capabilities = capabilities;
        this.ttl          = ttl;
        this.timestamp    = timestamp;
        this.signature    = signature;
        this.nodeType     = nodeType;
    }

    @Override public FrameType    frameType()    { return FrameType.ANNOUNCE; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String nid()               { return nid; }
    public List<Map<String,Object>> addresses() { return addresses; }
    public List<String> capabilities(){ return capabilities; }
    public int ttl()                  { return ttl; }
    public String timestamp()         { return timestamp; }
    public String signature()         { return signature; }
    public String nodeType()          { return nodeType; }

    public Map<String, Object> unsignedDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nid",          nid);
        m.put("addresses",    addresses);
        m.put("capabilities", capabilities);
        m.put("ttl",          ttl);
        m.put("timestamp",    timestamp);
        m.put("node_type",    nodeType);
        return m;
    }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>(unsignedDict());
        m.put("signature", signature);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static AnnounceFrame fromDict(Map<String, Object> d) {
        return new AnnounceFrame(
            (String) d.get("nid"),
            (List<Map<String,Object>>) d.get("addresses"),
            (List<String>) d.get("capabilities"),
            ((Number) d.get("ttl")).intValue(),
            (String) d.get("timestamp"),
            (String) d.get("signature"),
            (String) d.get("node_type")
        );
    }
}
