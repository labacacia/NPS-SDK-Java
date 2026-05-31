// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reputation policy hint embedded in {@link IdentMetadata} (alpha.10).
 *
 * <p>Signals to verifiers which log sources may be consulted for reputation
 * data, and whether the identity holder has given consent for such lookups.
 */
public final class IdentReputationPolicyHint {

    private final List<String> logSources; // mandatory
    private final boolean      consent;    // mandatory

    public IdentReputationPolicyHint(List<String> logSources, boolean consent) {
        this.logSources = logSources;
        this.consent    = consent;
    }

    public List<String> logSources() { return logSources; }
    public boolean      consent()    { return consent; }

    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("log_sources", logSources);
        m.put("consent",     consent);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static IdentReputationPolicyHint fromDict(Map<String, Object> d) {
        Object c = d.get("consent");
        return new IdentReputationPolicyHint(
            (List<String>) d.get("log_sources"),
            c instanceof Boolean b ? b : false
        );
    }
}
