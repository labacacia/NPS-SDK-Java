// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Basic open TrustFrame validator for self-hosted deployments that pin trusted
 * grantor anchors explicitly (NPS-3 §5.2). It checks frame shape, expiry,
 * grantor/grantee membership, required capability scope, and target node scope.
 *
 * <p>Java parallel of the .NET {@code TrustFrameValidator}. Error codes and step
 * numbers match the reference exactly.
 */
public final class TrustFrameValidator {

    private TrustFrameValidator() {}

    public static NipIdentVerifyResult validate(TrustFrame frame, TrustFrameValidationContext context) {
        if (blank(frame.grantorNid())
                || blank(frame.granteeCa())
                || blank(frame.issuedAt())
                || blank(frame.expiresAt())
                || blank(frame.serial())
                || blank(frame.signerNid())
                || blank(frame.signature())
                || frame.trustScope() == null || frame.trustScope().isEmpty()
                || frame.nodes() == null || frame.nodes().isEmpty()) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.TRUST_FRAME_INVALID,
                "TrustFrame is missing grantor, grantee, issued_at, expires_at, serial, "
                + "signer_nid, signature, trust_scope, or nodes.");
        }

        if (parseInstant(frame.issuedAt()) == null) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.TRUST_FRAME_INVALID,
                "TrustFrame issued_at is not a valid timestamp: " + frame.issuedAt() + ".");
        }

        Instant expiresAt = parseInstant(frame.expiresAt());
        if (expiresAt == null) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.TRUST_FRAME_INVALID,
                "TrustFrame expires_at is not a valid timestamp: " + frame.expiresAt() + ".");
        }

        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        if (!expiresAt.isAfter(now)) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.TRUST_FRAME_EXPIRED,
                "TrustFrame expired at " + frame.expiresAt() + ".");
        }

        if (context.trustedGrantors() == null || !context.trustedGrantors().contains(frame.grantorNid())) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.CERT_UNTRUSTED_ISSUER,
                "TrustFrame grantor '" + frame.grantorNid() + "' is not a trusted grantor.");
        }

        if (!frame.granteeCa().equals(context.expectedGranteeCa())) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.TRUST_FRAME_INVALID,
                "TrustFrame grantee '" + frame.granteeCa() + "' does not match expected CA '"
                + context.expectedGranteeCa() + "'.");
        }

        List<String> required = context.requiredCapabilities();
        if (required != null && !required.isEmpty()) {
            Set<String> granted = new HashSet<>(frame.trustScope());
            List<String> missing = required.stream().filter(c -> !granted.contains(c)).toList();
            if (!missing.isEmpty()) {
                return NipIdentVerifyResult.fail(5, NipErrorCodes.TRUST_FRAME_SCOPE_EXCEEDS_GRANTOR,
                    "TrustFrame is missing required capabilities: " + String.join(", ", missing) + ".");
            }
        }

        if (context.targetNodePath() != null) {
            boolean covered = frame.nodes().stream()
                .anyMatch(p -> NipIdentVerifier.nwpPathMatches(p, context.targetNodePath()));
            if (!covered) {
                return NipIdentVerifyResult.fail(6, NipErrorCodes.CERT_SCOPE_VIOLATION,
                    "Target path '" + context.targetNodePath()
                    + "' is not covered by the TrustFrame node scope.");
            }
        }

        return NipIdentVerifyResult.ok();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(raw);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
