// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrustFrame implements NpsFrame {

    private final String       issuerNid;
    private final String       subjectNid;
    private final List<String> scopes;
    private final String       expiresAt;
    private final String       signature;

    public TrustFrame(String issuerNid, String subjectNid,
                      List<String> scopes, String expiresAt, String signature) {
        this.issuerNid  = issuerNid;
        this.subjectNid = subjectNid;
        this.scopes     = scopes;
        this.expiresAt  = expiresAt;
        this.signature  = signature;
    }

    @Override public FrameType    frameType()    { return FrameType.TRUST; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String issuerNid()  { return issuerNid; }
    public String subjectNid() { return subjectNid; }
    public List<String> scopes() { return scopes; }
    public String expiresAt()  { return expiresAt; }
    public String signature()  { return signature; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer_nid",  issuerNid);
        m.put("subject_nid", subjectNid);
        m.put("scopes",      scopes);
        m.put("expires_at",  expiresAt);
        m.put("signature",   signature);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TrustFrame fromDict(Map<String, Object> d) {
        return new TrustFrame(
            (String) d.get("issuer_nid"),
            (String) d.get("subject_nid"),
            (List<String>) d.get("scopes"),
            (String) d.get("expires_at"),
            (String) d.get("signature")
        );
    }
}
