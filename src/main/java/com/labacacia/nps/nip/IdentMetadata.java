// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed wrapper for the {@code metadata} field of an {@link IdentFrame}.
 *
 * <p>Carries the optional {@code reputationPolicy} hint added in alpha.10. The
 * raw metadata map is still passed through so that unknown fields are not lost.
 */
public final class IdentMetadata {

    private final Map<String,Object>        raw;
    private final IdentReputationPolicyHint reputationPolicy; // nullable

    public IdentMetadata(Map<String,Object> raw, IdentReputationPolicyHint reputationPolicy) {
        this.raw              = raw;
        this.reputationPolicy = reputationPolicy;
    }

    public IdentMetadata(Map<String,Object> raw) {
        this(raw, null);
    }

    public Map<String,Object>        raw()              { return raw; }
    public IdentReputationPolicyHint reputationPolicy() { return reputationPolicy; }

    /** Returns a map suitable for use as the {@code metadata} field in a frame dict. */
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (raw != null) m.putAll(raw);
        if (reputationPolicy != null) m.put("reputation_policy", reputationPolicy.toDict());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static IdentMetadata fromDict(Map<String, Object> d) {
        if (d == null) return null;
        IdentReputationPolicyHint rp = null;
        Object rpRaw = d.get("reputation_policy");
        if (rpRaw instanceof Map<?,?> rpMap) {
            rp = IdentReputationPolicyHint.fromDict((Map<String,Object>) rpMap);
        }
        return new IdentMetadata(d, rp);
    }
}
