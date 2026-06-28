// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RevokeFrame implements NpsFrame {

    private static final String REASON_PARENT_REVOKED = "parent_revoked";

    private final String targetNid;
    private final String serial;    // nullable
    private final String reason;
    private final String revokedAt;
    private final String parentNid; // nullable
    private final String signerNid;
    private final String signature;

    public RevokeFrame(String targetNid, String serial, String reason, String revokedAt,
                       String parentNid, String signerNid, String signature) {
        this.targetNid = targetNid;
        this.serial    = serial;
        this.reason    = reason;
        this.revokedAt = revokedAt;
        this.parentNid = parentNid;
        this.signerNid = signerNid;
        this.signature = signature;
        validate();
    }

    public RevokeFrame(String targetNid, String reason, String revokedAt,
                       String signerNid, String signature) {
        this(targetNid, null, reason, revokedAt, null, signerNid, signature);
    }

    public RevokeFrame(String nid, String reason, String revokedAt) {
        this(nid, null, reason, revokedAt, null, null, null);
    }

    public RevokeFrame(String nid) { this(nid, null, null); }

    @Override public FrameType    frameType()    { return FrameType.REVOKE; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String targetNid() { return targetNid; }
    public String nid()       { return targetNid; } // legacy accessor
    public String serial()    { return serial; }
    public String reason()    { return reason; }
    public String revokedAt() { return revokedAt; }
    public String parentNid() { return parentNid; }
    public String signerNid() { return signerNid; }
    public String signature() { return signature; }

    public void validate() {
        if (REASON_PARENT_REVOKED.equals(reason)) {
            if (parentNid == null || parentNid.isEmpty()) {
                throw new IllegalArgumentException(
                    NipErrorCodes.REVOKE_FRAME_INVALID + ": parent_nid is required when reason=parent_revoked");
            }
        } else if (parentNid != null) {
            throw new IllegalArgumentException(
                NipErrorCodes.REVOKE_FRAME_INVALID + ": parent_nid must be omitted unless reason=parent_revoked");
        }
    }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = unsignedDict();
        if (signature != null) m.put("signature", signature);
        return m;
    }

    /** Return the toDict representation without the signature, for signing. */
    public Map<String, Object> unsignedDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frame",      "0x22");
        m.put("target_nid", targetNid);
        if (serial != null) m.put("serial", serial);
        m.put("reason",     reason);
        m.put("revoked_at", revokedAt);
        if (parentNid != null) m.put("parent_nid", parentNid);
        if (signerNid != null) m.put("signer_nid", signerNid);
        return m;
    }

    public static RevokeFrame fromDict(Map<String, Object> d) {
        return new RevokeFrame(
            (String) first(d, "target_nid", "nid"),
            (String) d.get("serial"),
            (String) d.get("reason"),
            (String) d.get("revoked_at"),
            (String) d.get("parent_nid"),
            (String) d.get("signer_nid"),
            (String) d.get("signature")
        );
    }

    private static Object first(Map<String, Object> d, String... keys) {
        for (String key : keys) {
            if (d.containsKey(key)) return d.get(key);
        }
        return null;
    }
}
