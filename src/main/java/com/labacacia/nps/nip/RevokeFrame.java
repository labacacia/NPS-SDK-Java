// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RevokeFrame implements NpsFrame {

    private final String nid;
    private final String reason;    // nullable
    private final String revokedAt; // nullable

    public RevokeFrame(String nid, String reason, String revokedAt) {
        this.nid       = nid;
        this.reason    = reason;
        this.revokedAt = revokedAt;
    }

    public RevokeFrame(String nid) { this(nid, null, null); }

    @Override public FrameType    frameType()    { return FrameType.REVOKE; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String nid()       { return nid; }
    public String reason()    { return reason; }
    public String revokedAt() { return revokedAt; }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nid",        nid);
        m.put("reason",     reason);
        m.put("revoked_at", revokedAt);
        return m;
    }

    public static RevokeFrame fromDict(Map<String, Object> d) {
        return new RevokeFrame(
            (String) d.get("nid"),
            (String) d.get("reason"),
            (String) d.get("revoked_at")
        );
    }
}
