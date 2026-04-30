// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.nip.x509.NipX509VerifyResult;
import com.labacacia.nps.nip.x509.NipX509Verifier;

import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

/**
 * Verifies an {@link IdentFrame} per NPS-RFC-0002 §8.1 Phase 1 dual-trust:
 * <ol>
 *   <li>Step 1 — v1 Ed25519 signature check against issuer's CA public key.</li>
 *   <li>Step 2 — assurance-level minimum check (if {@link NipVerifierOptions#minAssuranceLevel()} set).</li>
 *   <li>Step 3b — X.509 chain validation (only if {@code cert_format == "v2-x509"} AND
 *       {@code trustedX509Roots} is configured).</li>
 * </ol>
 *
 * <p>Verifiers without trusted X.509 roots configured remain v1-compatible — they ignore the
 * {@code cert_chain} field on incoming v2 frames.
 */
public final class NipIdentVerifier {

    private final NipVerifierOptions options;

    public NipIdentVerifier(NipVerifierOptions options) {
        this.options = options;
    }

    /**
     * @param frame    The IdentFrame to verify.
     * @param issuerNid The asserted issuer NID — used to look up the CA public key in
     *                  {@link NipVerifierOptions#trustedCaPublicKeys()} for Step 1.
     */
    public NipIdentVerifyResult verify(IdentFrame frame, String issuerNid) {
        // Step 1: v1 Ed25519 signature check ─────────────────────────────────
        PublicKey caPub = options.trustedCaPublicKeys().get(issuerNid);
        if (caPub == null) {
            return NipIdentVerifyResult.fail(1, NipErrorCodes.CERT_UNTRUSTED_ISSUER,
                "no trusted CA public key for issuer: " + issuerNid);
        }
        if (frame.signature() == null || !frame.signature().startsWith("ed25519:")) {
            return NipIdentVerifyResult.fail(1, NipErrorCodes.CERT_SIGNATURE_INVALID,
                "missing or malformed signature");
        }
        try {
            byte[] sigBytes = Base64.getDecoder().decode(
                frame.signature().substring("ed25519:".length()));
            byte[] message  = NipCanonicalJson.canonicalize(frame.unsignedDict());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(caPub);
            verifier.update(message);
            if (!verifier.verify(sigBytes)) {
                return NipIdentVerifyResult.fail(1, NipErrorCodes.CERT_SIGNATURE_INVALID,
                    "v1 Ed25519 signature did not verify against issuer CA key");
            }
        } catch (Exception e) {
            return NipIdentVerifyResult.fail(1, NipErrorCodes.CERT_SIGNATURE_INVALID,
                "v1 signature verification error: " + e.getMessage());
        }

        // Step 2: minimum assurance level ────────────────────────────────────
        AssuranceLevel min = options.minAssuranceLevel();
        if (min != null) {
            AssuranceLevel got = frame.assuranceLevel() == null
                ? AssuranceLevel.ANONYMOUS : frame.assuranceLevel();
            if (!got.meetsOrExceeds(min)) {
                return NipIdentVerifyResult.fail(2, NipErrorCodes.ASSURANCE_MISMATCH,
                    "assurance_level (" + got.wire()
                    + ") below required minimum (" + min.wire() + ")");
            }
        }

        // Step 3b: X.509 chain check (only if configured + frame opts in) ────
        List<?> trustedRoots = options.trustedX509Roots();
        boolean hasV2Trust = trustedRoots != null && !trustedRoots.isEmpty();
        boolean isV2Frame  = IdentCertFormat.V2_X509.equals(frame.certFormat());

        if (hasV2Trust && isV2Frame) {
            NipX509VerifyResult x509 = NipX509Verifier.verify(
                frame.certChain() == null ? List.of() : frame.certChain(),
                frame.nid(),
                frame.assuranceLevel(),
                options.trustedX509Roots());
            if (!x509.valid()) {
                return NipIdentVerifyResult.fail(3, x509.errorCode(), x509.message());
            }
        }

        return NipIdentVerifyResult.ok();
    }
}
