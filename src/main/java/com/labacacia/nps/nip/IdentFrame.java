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

    private final String              nid;
    private final String              pubKey;
    private final Map<String,Object>  metadata;
    private final String              signature;

    // RFC-0003: optional, parse-only at v1.0-alpha.3 / enforced from alpha.4 paths that opt in.
    private final AssuranceLevel      assuranceLevel;

    // RFC-0002: optional dual-trust X.509 chain. Backward compatible — v1 verifiers ignore.
    private final String              certFormat;   // null ≡ "v1-proprietary"
    private final List<String>        certChain;    // base64url(DER), [leaf, intermediates..., root]

    // alpha.11: optional OCSP staple (base64-encoded DER).
    private final String              ocspStaple;   // nullable

    // alpha.13: NIP v0.10 self-declared node-role tags.
    private final List<String>        nodeRoles;    // nullable

    /** Backward-compatible constructor — produces a v1-proprietary frame with no assurance level. */
    public IdentFrame(String nid, String pubKey, Map<String,Object> metadata, String signature) {
        this(nid, pubKey, metadata, signature, null, null, null, null, null);
    }

    /** Full constructor including RFC-0003 assurance level + RFC-0002 cert chain. */
    public IdentFrame(
            String              nid,
            String              pubKey,
            Map<String,Object>  metadata,
            String              signature,
            AssuranceLevel      assuranceLevel,
            String              certFormat,
            List<String>        certChain) {
        this(nid, pubKey, metadata, signature, assuranceLevel, certFormat, certChain, null, null);
    }

    /** Full constructor including alpha.11 OCSP staple. */
    public IdentFrame(
            String              nid,
            String              pubKey,
            Map<String,Object>  metadata,
            String              signature,
            AssuranceLevel      assuranceLevel,
            String              certFormat,
            List<String>        certChain,
            String              ocspStaple) {
        this(nid, pubKey, metadata, signature, assuranceLevel, certFormat, certChain, ocspStaple, null);
    }

    /** Full constructor including alpha.13 node_roles. */
    public IdentFrame(
            String              nid,
            String              pubKey,
            Map<String,Object>  metadata,
            String              signature,
            AssuranceLevel      assuranceLevel,
            String              certFormat,
            List<String>        certChain,
            String              ocspStaple,
            List<String>        nodeRoles) {
        this.nid             = nid;
        this.pubKey          = pubKey;
        this.metadata        = metadata;
        this.signature       = signature;
        this.assuranceLevel  = assuranceLevel;
        this.certFormat      = certFormat;
        this.certChain       = certChain;
        this.ocspStaple      = ocspStaple;
        this.nodeRoles       = nodeRoles;
    }

    @Override public FrameType    frameType()    { return FrameType.IDENT; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String              nid()             { return nid; }
    public String              pubKey()          { return pubKey; }
    public Map<String,Object>  metadata()        { return metadata; }
    public String              signature()       { return signature; }
    public AssuranceLevel      assuranceLevel()  { return assuranceLevel; }
    public String              certFormat()      { return certFormat; }
    public List<String>        certChain()       { return certChain; }
    public String              ocspStaple()      { return ocspStaple; }
    public List<String>        nodeRoles()       { return nodeRoles; }

    public Map<String, Object> unsignedDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nid",      nid);
        m.put("pub_key",  pubKey);
        m.put("metadata", metadata);
        if (assuranceLevel != null) m.put("assurance_level", assuranceLevel.wire());
        // cert_format / cert_chain deliberately excluded from the signed payload —
        // the v1 Ed25519 signature covers (nid, pub_key, metadata, [assurance_level]) only.
        return m;
    }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>(unsignedDict());
        m.put("signature", signature);
        if (certFormat  != null) m.put("cert_format",  certFormat);
        if (certChain   != null) m.put("cert_chain",   certChain);
        if (ocspStaple  != null) m.put("ocsp_staple",  ocspStaple);
        if (nodeRoles   != null) m.put("node_roles",   nodeRoles);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static IdentFrame fromDict(Map<String, Object> d) {
        AssuranceLevel level = null;
        Object lvl = d.get("assurance_level");
        if (lvl instanceof String s) level = AssuranceLevel.fromWire(s);

        Object chain = d.get("cert_chain");
        List<String> certChain = (chain instanceof List<?>) ? (List<String>) chain : null;
        Object roles = d.get("node_roles");
        List<String> nodeRoles = (roles instanceof List<?>) ? (List<String>) roles : null;

        return new IdentFrame(
            (String) d.get("nid"),
            (String) d.get("pub_key"),
            (Map<String,Object>) d.get("metadata"),
            (String) d.get("signature"),
            level,
            (String) d.get("cert_format"),
            certChain,
            (String) d.get("ocsp_staple"),
            nodeRoles
        );
    }
}
