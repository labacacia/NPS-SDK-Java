// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IdentFrame implements NpsFrame {

    private final String             nid;
    private final String             pubKey;
    private final Map<String,Object> metadata;
    private final String             signature;

    public IdentFrame(String nid, String pubKey, Map<String,Object> metadata, String signature) {
        this.nid       = nid;
        this.pubKey    = pubKey;
        this.metadata  = metadata;
        this.signature = signature;
    }

    @Override public FrameType    frameType()    { return FrameType.IDENT; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String nid()              { return nid; }
    public String pubKey()           { return pubKey; }
    public Map<String,Object> metadata() { return metadata; }
    public String signature()        { return signature; }

    public Map<String, Object> unsignedDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nid",      nid);
        m.put("pub_key",  pubKey);
        m.put("metadata", metadata);
        return m;
    }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>(unsignedDict());
        m.put("signature", signature);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static IdentFrame fromDict(Map<String, Object> d) {
        return new IdentFrame(
            (String) d.get("nid"),
            (String) d.get("pub_key"),
            (Map<String,Object>) d.get("metadata"),
            (String) d.get("signature")
        );
    }
}
