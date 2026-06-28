// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-CA trust chain and capability grant frame (NPS-3 §5.2).
 *
 * <p>The open SDK carries the full wire frame. Deployments may layer local
 * pinned-grantor validation or NPS Cloud managed federation policy on top.
 */
public final class TrustFrame implements NpsFrame {

    private final String       grantorNid;
    private final String       granteeCa;
    private final List<String> trustScope;
    private final List<String> nodes;
    private final String       issuedAt;
    private final String       expiresAt;
    private final String       serial;
    private final String       signerNid;
    private final String       signature;

    public TrustFrame(String grantorNid, String granteeCa,
                      List<String> trustScope, List<String> nodes,
                      String issuedAt, String expiresAt,
                      String serial, String signerNid, String signature) {
        this.grantorNid = grantorNid;
        this.granteeCa  = granteeCa;
        this.trustScope = trustScope;
        this.nodes      = nodes;
        this.issuedAt   = issuedAt;
        this.expiresAt  = expiresAt;
        this.serial     = serial;
        this.signerNid  = signerNid;
        this.signature  = signature;
    }

    @Override public FrameType    frameType()    { return FrameType.TRUST; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String       grantorNid() { return grantorNid; }
    public String       granteeCa()  { return granteeCa; }
    public List<String> trustScope() { return trustScope; }
    public List<String> nodes()      { return nodes; }
    public String       issuedAt()   { return issuedAt; }
    public String       expiresAt()  { return expiresAt; }
    public String       serial()     { return serial; }
    public String       signerNid()  { return signerNid; }
    public String       signature()  { return signature; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = unsignedDict();
        m.put("signature", signature);
        return m;
    }

    /** Return the toDict representation without the signature, for signing. */
    public Map<String, Object> unsignedDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frame",       "0x21");
        m.put("grantor_nid", grantorNid);
        m.put("grantee_ca",  granteeCa);
        m.put("trust_scope", trustScope);
        m.put("nodes",       nodes);
        m.put("issued_at",   issuedAt);
        m.put("expires_at",  expiresAt);
        m.put("serial",      serial);
        m.put("signer_nid",  signerNid);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static TrustFrame fromDict(Map<String, Object> d) {
        return new TrustFrame(
            (String)       first(d, "grantor_nid", "issuer_nid"),
            (String)       first(d, "grantee_ca", "subject_nid"),
            (List<String>) first(d, "trust_scope", "scopes"),
            (List<String>) d.getOrDefault("nodes", List.of()),
            (String)       d.getOrDefault("issued_at", ""),
            (String)       d.get("expires_at"),
            (String)       d.getOrDefault("serial", ""),
            (String)       first(d, "signer_nid", "grantor_nid", "issuer_nid"),
            (String)       d.get("signature")
        );
    }

    private static Object first(Map<String, Object> d, String... keys) {
        for (String key : keys) {
            if (d.containsKey(key)) return d.get(key);
        }
        return null;
    }
}
