// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import java.util.ArrayList;
import java.util.List;

/** Incremental NIP v0.13 live-revocation policy evaluator. */
public final class NipRevocationPolicy {
    public enum Mode { IF_CONFIGURED, REQUIRED }
    public enum Source { LOCAL_CRL, CALLBACK, CA_STORE, OCSP }
    public enum Outcome { GOOD, REVOKED, UNAVAILABLE }

    private final Mode mode;
    private final boolean ocspFailOpen;
    private final List<Source> consulted = new ArrayList<>();

    public NipRevocationPolicy(Mode mode, boolean ocspFailOpen) {
        this.mode = mode;
        this.ocspFailOpen = ocspFailOpen;
    }

    public List<Source> consultedSources() {
        return List.copyOf(consulted);
    }

    public NipIdentVerifyResult observe(Source source, Outcome outcome) {
        consulted.add(source);
        if (outcome == Outcome.UNAVAILABLE
                && source == Source.OCSP && ocspFailOpen) {
            return null;
        }
        return switch (outcome) {
            case GOOD -> null;
            case REVOKED -> NipIdentVerifyResult.fail(
                4, NipErrorCodes.CERT_REVOKED,
                "Revocation source " + source + " reports the certificate revoked.");
            case UNAVAILABLE -> NipIdentVerifyResult.fail(
                4, NipErrorCodes.OCSP_UNAVAILABLE,
                "Revocation source " + source + " is unavailable.");
        };
    }

    public NipIdentVerifyResult complete() {
        if (mode == Mode.REQUIRED && consulted.isEmpty()) {
            return NipIdentVerifyResult.fail(
                4, NipErrorCodes.OCSP_UNAVAILABLE,
                "Revocation mode is required, but no revocation source is configured.");
        }
        return NipIdentVerifyResult.ok();
    }
}
