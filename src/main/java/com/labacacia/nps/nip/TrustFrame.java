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
 * <p><b>Note:</b> Business logic for trust chain validation is a commercial
 * NPS Cloud feature. This class provides the frame definition for codec use;
 * trust chain enforcement is not implemented in the OSS library.
 */
public final class TrustFrame implements NpsFrame {

    private final String       grantorNid;
    private final String       granteeCa;
    private final List<String> trustScope;
    private final List<String> nodes;
    private final String       expiresAt;
    private final String       signature;

    public TrustFrame(String grantorNid, String granteeCa,
                      List<String> trustScope, List<String> nodes,
                      String expiresAt, String signature) {
        this.grantorNid = grantorNid;
        this.granteeCa  = granteeCa;
        this.trustScope = trustScope;
        this.nodes      = nodes;
        this.expiresAt  = expiresAt;
        this.signature  = signature;
    }

    @Override public FrameType    frameType()    { return FrameType.TRUST; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String       grantorNid() { return grantorNid; }
    public String       granteeCa()  { return granteeCa; }
    public List<String> trustScope() { return trustScope; }
    public List<String> nodes()      { return nodes; }
    public String       expiresAt()  { return expiresAt; }
    public String       signature()  { return signature; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grantor_nid", grantorNid);
        m.put("grantee_ca",  granteeCa);
        m.put("trust_scope", trustScope);
        m.put("nodes",       nodes);
        m.put("expires_at",  expiresAt);
        m.put("signature",   signature);
        return m;
    }

    /** Return the toDict representation without the signature, for signing. */
    public Map<String, Object> unsignedDict() {
        Map<String, Object> d = toDict();
        d.remove("signature");
        return d;
    }

    @SuppressWarnings("unchecked")
    public static TrustFrame fromDict(Map<String, Object> d) {
        return new TrustFrame(
            (String)       d.get("grantor_nid"),
            (String)       d.get("grantee_ca"),
            (List<String>) d.get("trust_scope"),
            (List<String>) d.get("nodes"),
            (String)       d.get("expires_at"),
            (String)       d.get("signature")
        );
    }
}
