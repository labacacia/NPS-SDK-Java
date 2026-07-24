// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Java parallel of the .NET {@code TrustFrameValidator} tests. */
class TrustFrameValidatorTests {

    private static final String GRANTOR = "urn:nps:org:ca.org-a.com";
    private static final String GRANTEE = "urn:nps:org:ca.org-b.com";

    private static TrustFrame frame(String expiresAt, List<String> trustScope, List<String> nodes) {
        return new TrustFrame(
            GRANTOR, GRANTEE, trustScope, nodes,
            Instant.now().minus(1, ChronoUnit.HOURS).toString(),
            expiresAt,
            "0xTRUST01",
            GRANTOR,
            "ed25519:AAAA");
    }

    private static TrustFrameValidationContext.Builder ctx() {
        return TrustFrameValidationContext.builder()
            .trustedGrantors(Set.of(GRANTOR))
            .expectedGranteeCa(GRANTEE);
    }

    @Test
    void validFrame() {
        TrustFrame f = frame(Instant.now().plus(1, ChronoUnit.HOURS).toString(),
            List.of("nwp:query"), List.of("nwp://api.org-a.com/public/*"));
        NipIdentVerifyResult r = TrustFrameValidator.validate(f, ctx()
            .requiredCapabilities(List.of("nwp:query"))
            .targetNodePath("nwp://api.org-a.com/public/data")
            .build());
        assertTrue(r.valid(), () -> r.errorCode() + " " + r.message());
    }

    @Test
    void missingFields() {
        TrustFrame f = frame(Instant.now().plus(1, ChronoUnit.HOURS).toString(),
            List.of(), List.of()); // empty trust_scope + nodes
        NipIdentVerifyResult r = TrustFrameValidator.validate(f, ctx().build());
        assertFalse(r.valid());
        assertEquals(3, r.stepFailed());
        assertEquals(NipErrorCodes.TRUST_FRAME_INVALID, r.errorCode());
    }

    @Test
    void expired() {
        TrustFrame f = frame(Instant.now().minus(1, ChronoUnit.HOURS).toString(),
            List.of("nwp:query"), List.of("*"));
        NipIdentVerifyResult r = TrustFrameValidator.validate(f, ctx().build());
        assertFalse(r.valid());
        assertEquals(3, r.stepFailed());
        assertEquals(NipErrorCodes.TRUST_FRAME_EXPIRED, r.errorCode());
    }

    @Test
    void untrustedGrantor() {
        TrustFrame f = frame(Instant.now().plus(1, ChronoUnit.HOURS).toString(),
            List.of("nwp:query"), List.of("*"));
        NipIdentVerifyResult r = TrustFrameValidator.validate(f,
            TrustFrameValidationContext.builder()
                .trustedGrantors(Set.of("urn:nps:org:other"))
                .expectedGranteeCa(GRANTEE)
                .build());
        assertFalse(r.valid());
        assertEquals(3, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_UNTRUSTED_ISSUER, r.errorCode());
    }

    @Test
    void granteeMismatch() {
        TrustFrame f = frame(Instant.now().plus(1, ChronoUnit.HOURS).toString(),
            List.of("nwp:query"), List.of("*"));
        NipIdentVerifyResult r = TrustFrameValidator.validate(f,
            ctx().expectedGranteeCa("urn:nps:org:someone-else").build());
        assertFalse(r.valid());
        assertEquals(3, r.stepFailed());
        assertEquals(NipErrorCodes.TRUST_FRAME_INVALID, r.errorCode());
    }

    @Test
    void scopeExceedsGrantor() {
        TrustFrame f = frame(Instant.now().plus(1, ChronoUnit.HOURS).toString(),
            List.of("nwp:query"), List.of("*"));
        NipIdentVerifyResult r = TrustFrameValidator.validate(f,
            ctx().requiredCapabilities(List.of("nwp:query", "nwp:admin")).build());
        assertFalse(r.valid());
        assertEquals(5, r.stepFailed());
        assertEquals(NipErrorCodes.TRUST_FRAME_SCOPE_EXCEEDS_GRANTOR, r.errorCode());
    }

    @Test
    void nodeScopeViolation() {
        TrustFrame f = frame(Instant.now().plus(1, ChronoUnit.HOURS).toString(),
            List.of("nwp:query"), List.of("nwp://api.org-a.com/private/*"));
        NipIdentVerifyResult r = TrustFrameValidator.validate(f,
            ctx().targetNodePath("nwp://api.org-a.com/public/data").build());
        assertFalse(r.valid());
        assertEquals(6, r.stepFailed());
        assertEquals(NipErrorCodes.CERT_SCOPE_VIOLATION, r.errorCode());
    }
}
