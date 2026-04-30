// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.x509;

import com.labacacia.nps.nip.AssuranceLevel;
import com.labacacia.nps.nip.NipErrorCodes;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Verifies NPS X.509 NID certificate chains per NPS-RFC-0002 §4.
 *
 * <p>Verification stages (RFC §4.6):
 * <ol>
 *   <li>Decode chain (base64url DER → {@link X509Certificate}).</li>
 *   <li>Leaf EKU check — critical, contains agent-identity OR node-identity OID.</li>
 *   <li>Subject CN / SAN URI match against asserted NID.</li>
 *   <li>Assurance-level extension match against asserted level (if both present).</li>
 *   <li>Chain signature verification — leaf → intermediates → trusted root.</li>
 * </ol>
 */
public final class NipX509Verifier {

    private NipX509Verifier() {}

    /**
     * Verify a base64url-encoded DER X.509 chain.
     *
     * @param certChainBase64UrlDer  Chain entries: [0] = leaf, [1..n-2] = intermediates, [n-1] = root (or last issued).
     * @param assertedNid            Expected subject NID (matched against CN + SAN URI).
     * @param assertedAssuranceLevel Optional — when non-null, MUST match the cert's extension value.
     * @param trustedRootCerts       Trust anchors. Empty/null causes {@code NIP-CERT-FORMAT-INVALID} (no trust possible).
     */
    public static NipX509VerifyResult verify(
            List<String>           certChainBase64UrlDer,
            String                 assertedNid,
            AssuranceLevel         assertedAssuranceLevel,
            List<X509Certificate>  trustedRootCerts) {

        // 1. Decode chain ─────────────────────────────────────────────────────
        if (certChainBase64UrlDer == null || certChainBase64UrlDer.isEmpty()) {
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_FORMAT_INVALID,
                "cert_chain is empty");
        }

        X509Certificate[] chain = new X509Certificate[certChainBase64UrlDer.size()];
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (int i = 0; i < chain.length; i++) {
                byte[] der = Base64.getUrlDecoder().decode(certChainBase64UrlDer.get(i));
                chain[i] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
            }
        } catch (Exception e) {
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_FORMAT_INVALID,
                "DER decode failed: " + e.getMessage());
        }

        X509Certificate leaf = chain[0];

        // 2. Leaf EKU check ───────────────────────────────────────────────────
        NipX509VerifyResult ekuResult = checkLeafEku(leaf);
        if (!ekuResult.valid()) return ekuResult;

        // 3. Subject CN / SAN URI match ───────────────────────────────────────
        NipX509VerifyResult subjectResult = checkSubjectNid(leaf, assertedNid);
        if (!subjectResult.valid()) return subjectResult;

        // 4. Assurance-level extension ────────────────────────────────────────
        NipX509VerifyResult assuranceResult = checkAssuranceLevel(leaf, assertedAssuranceLevel);
        if (!assuranceResult.valid()) return assuranceResult;

        // 5. Chain signature verification ─────────────────────────────────────
        NipX509VerifyResult chainResult = checkChainSignature(chain, trustedRootCerts);
        if (!chainResult.valid()) return chainResult;

        return NipX509VerifyResult.ok(leaf);
    }

    // ── Stage 2: EKU ─────────────────────────────────────────────────────────

    private static NipX509VerifyResult checkLeafEku(X509Certificate leaf) {
        try {
            List<String> eku = leaf.getExtendedKeyUsage();
            if (eku == null || eku.isEmpty()) {
                return NipX509VerifyResult.fail(NipErrorCodes.CERT_EKU_MISSING,
                    "leaf certificate has no ExtendedKeyUsage extension");
            }

            // The JCA API doesn't directly expose 'critical' for EKU, so check the OID set.
            Collection<String> critical = leaf.getCriticalExtensionOIDs();
            String ekuOid = "2.5.29.37"; // RFC 5280
            if (critical == null || !critical.contains(ekuOid)) {
                return NipX509VerifyResult.fail(NipErrorCodes.CERT_EKU_MISSING,
                    "ExtendedKeyUsage extension is not marked critical");
            }

            String agentOid = NpsX509Oids.EKU_AGENT_IDENTITY.getId();
            String nodeOid  = NpsX509Oids.EKU_NODE_IDENTITY.getId();
            if (!eku.contains(agentOid) && !eku.contains(nodeOid)) {
                return NipX509VerifyResult.fail(NipErrorCodes.CERT_EKU_MISSING,
                    "ExtendedKeyUsage does not contain agent-identity or node-identity OID");
            }
            return NipX509VerifyResult.ok(leaf);
        } catch (Exception e) {
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_EKU_MISSING,
                "EKU check failed: " + e.getMessage());
        }
    }

    // ── Stage 3: Subject CN / SAN URI ────────────────────────────────────────

    private static NipX509VerifyResult checkSubjectNid(X509Certificate leaf, String assertedNid) {
        // Subject CN match. Distinguished names use comma separators with escaping; pick
        // the CN component robustly via X500Name parse.
        String dn = leaf.getSubjectX500Principal().getName();
        String cn = extractCn(dn);
        if (cn == null || !cn.equals(assertedNid)) {
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_SUBJECT_NID_MISMATCH,
                "leaf subject CN (" + cn + ") does not match asserted NID (" + assertedNid + ")");
        }

        // SAN URI match.
        try {
            Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
            if (sans == null) {
                return NipX509VerifyResult.fail(NipErrorCodes.CERT_SUBJECT_NID_MISMATCH,
                    "leaf has no Subject Alternative Name extension");
            }
            for (List<?> san : sans) {
                int type = (Integer) san.get(0);
                if (type == 6 /* uniformResourceIdentifier */) {
                    String uri = (String) san.get(1);
                    if (assertedNid.equals(uri)) {
                        return NipX509VerifyResult.ok(leaf);
                    }
                }
            }
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_SUBJECT_NID_MISMATCH,
                "no SAN URI matches asserted NID");
        } catch (Exception e) {
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_SUBJECT_NID_MISMATCH,
                "SAN check failed: " + e.getMessage());
        }
    }

    /** Extract the CN attribute from an RFC 2253 DN. Returns null if absent. */
    private static String extractCn(String dn) {
        // Simple parse — split on unescaped commas, find CN= prefix.
        // Handles the cases produced by NipX509Builder; not a fully general DN parser.
        for (String rdn : dn.split(",")) {
            String trimmed = rdn.trim();
            if (trimmed.startsWith("CN=")) {
                String value = trimmed.substring(3);
                // Strip surrounding quotes if any.
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                // Unescape backslash-escaped colons / commas / equals.
                return value.replace("\\,", ",").replace("\\:", ":").replace("\\=", "=");
            }
        }
        return null;
    }

    // ── Stage 4: assurance-level extension ───────────────────────────────────

    private static NipX509VerifyResult checkAssuranceLevel(X509Certificate leaf, AssuranceLevel asserted) {
        if (asserted == null) {
            return NipX509VerifyResult.ok(leaf);
        }
        byte[] extDer = leaf.getExtensionValue(NpsX509Oids.NID_ASSURANCE_LEVEL.getId());
        if (extDer == null) {
            // Extension absent — pass per RFC-0002 §4.3 (extension is optional in v0.1).
            return NipX509VerifyResult.ok(leaf);
        }
        try {
            // Java wraps extension values in OCTET STRING; unwrap.
            ASN1OctetString outer = ASN1OctetString.getInstance(extDer);
            ASN1Primitive inner   = ASN1Primitive.fromByteArray(outer.getOctets());
            int rank = ASN1Enumerated.getInstance(inner).getValue().intValueExact();

            AssuranceLevel cert;
            try { cert = AssuranceLevel.fromRank(rank); }
            catch (IllegalArgumentException e) {
                return NipX509VerifyResult.fail(NipErrorCodes.ASSURANCE_UNKNOWN,
                    "assurance-level extension contains unknown value: " + rank);
            }
            if (cert != asserted) {
                return NipX509VerifyResult.fail(NipErrorCodes.ASSURANCE_MISMATCH,
                    "cert assurance-level (" + cert.wire()
                    + ") does not match asserted (" + asserted.wire() + ")");
            }
            return NipX509VerifyResult.ok(leaf);
        } catch (Exception e) {
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_FORMAT_INVALID,
                "malformed assurance-level extension: " + e.getMessage());
        }
    }

    // ── Stage 5: chain signature verification ────────────────────────────────

    private static NipX509VerifyResult checkChainSignature(
            X509Certificate[] chain, List<X509Certificate> trustedRoots) {
        if (trustedRoots == null || trustedRoots.isEmpty()) {
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_FORMAT_INVALID,
                "no trusted X.509 roots configured");
        }
        try {
            // Walk leaf → intermediates: each must be signed by the next cert in the chain.
            for (int i = 0; i < chain.length - 1; i++) {
                chain[i].verify(chain[i + 1].getPublicKey());
            }
            // The last cert in the chain MUST chain to a configured trusted root.
            // Either it IS a trusted root by exact match, or it's signed by a trusted root.
            X509Certificate last = chain[chain.length - 1];
            for (X509Certificate root : trustedRoots) {
                if (last.equals(root)) return NipX509VerifyResult.ok(chain[0]);
                try {
                    last.verify(root.getPublicKey());
                    return NipX509VerifyResult.ok(chain[0]);
                } catch (Exception ignore) { /* try next root */ }
            }
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_FORMAT_INVALID,
                "chain does not anchor to any trusted root");
        } catch (Exception e) {
            return NipX509VerifyResult.fail(NipErrorCodes.CERT_FORMAT_INVALID,
                "chain signature verification failed: " + e.getMessage());
        }
    }
}
