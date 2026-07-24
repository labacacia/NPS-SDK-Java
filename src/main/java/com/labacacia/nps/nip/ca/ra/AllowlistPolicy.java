// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca.ra;

import com.labacacia.nps.nip.NipErrorCodes;
import com.labacacia.nps.nip.ca.NipCaException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Enrollment Tier 1: admits registrations whose {@code identifier} matches at
 * least one glob pattern in the operator-configured allowlist (NPS-CR-0005
 * §3.2). Pattern {@code *} matches anything (open CA).
 */
public final class AllowlistPolicy implements IEnrollmentPolicy {

    private final List<Pattern> compiled;

    public AllowlistPolicy(List<String> patterns) {
        this.compiled = new ArrayList<>();
        for (String p : patterns) compiled.add(globToRegex(p));
    }

    @Override
    public void check(String entityType, String identifier, String pubKey,
                      List<String> capabilities, String scopeJson, String metadataJson,
                      String enrollmentToken) {
        for (Pattern re : compiled) {
            if (re.matcher(identifier).matches()) return;
        }
        throw new NipCaException(
            "Identifier '" + identifier + "' does not match any enrollment allowlist pattern.",
            NipErrorCodes.RA_NID_NOT_ALLOWED);
    }

    private static Pattern globToRegex(String pattern) {
        if (pattern.equals("*")) return Pattern.compile(".*");
        String escaped = Pattern.quote(pattern);
        // Convert quoted \* and \? placeholders back into regex operators.
        escaped = escaped.replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
        return Pattern.compile("^" + escaped + "$");
    }
}
