// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.nip.x509.NpsX509Oids;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.ASN1UTF8String;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NIP v0.12 §7.5 Phase-3 enforcement: turns the Phase-1–2 advisory CA-attestation checks
 * into hard failures.
 *
 * <p>Applies only to {@code v2-x509} frames whose chain already verified. Each attribute
 * check applies <em>only when the corresponding certificate extension is present</em>, so
 * self-declared / v1 NIDs and certs without attestation extensions are unaffected. The
 * role and capability checks are <b>subset</b> checks — the frame MUST NOT claim more
 * than the CA attested; under-claiming is fine. Comparison is exact-byte string equality:
 * no case folding, no normalisation, no trimming.</p>
 *
 * <p>Evaluation order is fixed: {@code node_roles} → {@code capabilities} → OCSP staple.
 * All Phase-3 failures report step 3.</p>
 *
 * <p>Stateless and pure — no I/O, no network. The clock is injectable so that staple
 * freshness is deterministic under test.</p>
 *
 * <p><strong>Split to be aware of</strong>: the fourth normative row, the
 * <em>assurance</em> check, lives in {@code NipX509Verifier.checkAssuranceLevel} and runs
 * unconditionally as part of chain validation regardless of the flag. This class
 * implements the other three rows.</p>
 */
public final class NipPhase3Enforcer {

    private NipPhase3Enforcer() {}

    /** {@code id-pkix-ocsp-basic} (RFC 6960). */
    private static final String ID_PKIX_OCSP_BASIC = "1.3.6.1.5.5.7.48.1.1";

    private static final DateTimeFormatter ISO_ROUNDTRIP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX").withZone(ZoneOffset.UTC);

    /**
     * Run the Phase-3 checks against the leaf certificate.
     *
     * @param frame the IdentFrame being verified; must be non-null
     * @param leaf  {@code cert_chain[0]}, already DER-decoded; must be non-null
     * @param now   injectable clock; {@code null} ⇒ {@link Instant#now()}
     * @return {@link NipIdentVerifyResult#ok()} when every applicable check passes
     */
    public static NipIdentVerifyResult enforce(IdentFrame frame, X509Certificate leaf, Instant now) {
        if (frame == null) throw new IllegalArgumentException("frame must not be null");
        if (leaf  == null) throw new IllegalArgumentException("leaf must not be null");
        Instant when = now == null ? Instant.now() : now;

        // 1. node_roles ⊆ id-nps-node-roles (only when the extension is present).
        List<String> attestedRoles = readUtf8SequenceExtension(leaf, NpsX509Oids.ID_NPS_NODE_ROLES);
        if (attestedRoles != null) {
            List<String> excess = excess(frame.nodeRoles(), attestedRoles);
            if (!excess.isEmpty()) {
                return NipIdentVerifyResult.fail(3, NipErrorCodes.CERT_NODE_ROLES_MISMATCH,
                    "IdentFrame.node_roles claims role(s) not attested by id-nps-node-roles: "
                        + String.join(", ", excess) + ".");
            }
        }

        // 2. capabilities ⊆ id-nps-capabilities (only when the extension is present).
        List<String> attestedCaps = readUtf8SequenceExtension(leaf, NpsX509Oids.ID_NPS_CAPABILITIES);
        if (attestedCaps != null) {
            List<String> excess = excess(frame.capabilities(), attestedCaps);
            if (!excess.isEmpty()) {
                return NipIdentVerifyResult.fail(3, NipErrorCodes.CERT_CAPABILITIES_EXCEEDED,
                    "IdentFrame.capabilities claims capabilit(ies) not attested by id-nps-capabilities: "
                        + String.join(", ", excess) + ".");
            }
        }

        // 3. OCSP staple MUST be present and unexpired — the one unconditional check.
        //    Every failure mode collapses onto NIP-OCSP-STAPLE-EXPIRED: fail closed.
        String staple = frame.ocspStaple();
        if (staple == null || staple.isEmpty()) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.OCSP_STAPLE_EXPIRED,
                "Phase-3 enforcement requires ocsp_staple on v2-x509 IdentFrames; none was supplied.");
        }
        byte[] stapleDer;
        try {
            stapleDer = decodeBase64Url(staple);
        } catch (IllegalArgumentException e) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.OCSP_STAPLE_EXPIRED,
                "ocsp_staple is not valid base64url.");
        }
        Instant nextUpdate = tryGetOcspNextUpdate(stapleDer);
        if (nextUpdate == null) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.OCSP_STAPLE_EXPIRED,
                "ocsp_staple could not be parsed as a DER OCSPResponse with a nextUpdate.");
        }
        // NOTE: `<=`, not `<` — nextUpdate exactly at `now` has elapsed.
        if (!nextUpdate.isAfter(when)) {
            return NipIdentVerifyResult.fail(3, NipErrorCodes.OCSP_STAPLE_EXPIRED,
                "ocsp_staple nextUpdate " + ISO_ROUNDTRIP.format(nextUpdate) + " has elapsed.");
        }

        return NipIdentVerifyResult.ok();
    }

    /** {@link #enforce(IdentFrame, X509Certificate, Instant)} against the system clock. */
    public static NipIdentVerifyResult enforce(IdentFrame frame, X509Certificate leaf) {
        return enforce(frame, leaf, null);
    }

    /**
     * Read a {@code SEQUENCE OF UTF8String} extension ({@code id-nps-node-roles} /
     * {@code id-nps-capabilities}). The tri-state return <em>is</em> the rule:
     *
     * <ul>
     *   <li>{@code null} — the extension is absent from the certificate; the
     *       corresponding subset check is skipped entirely and the frame may claim
     *       anything.</li>
     *   <li>a parsed list, possibly empty — the subset check runs against it.</li>
     *   <li>an <em>empty</em> list for a present-but-malformed extension — the strictest
     *       reading: any claim then exceeds it and fails.</li>
     * </ul>
     *
     * <p>Callers MUST NOT collapse "absent" and "present but empty": that turns a
     * fail-closed case into a skip.</p>
     */
    public static List<String> readUtf8SequenceExtension(X509Certificate cert, ASN1ObjectIdentifier oid) {
        if (cert == null || oid == null) return null;
        byte[] wrapped = cert.getExtensionValue(oid.getId());
        if (wrapped == null) return null;           // extension absent → skip the check
        try {
            byte[] raw = ASN1OctetString.getInstance(wrapped).getOctets();
            ASN1Sequence seq = ASN1Sequence.getInstance(raw);
            List<String> values = new ArrayList<>(seq.size());
            for (ASN1Encodable e : seq) {
                ASN1Encodable prim = e.toASN1Primitive();
                if (!(prim instanceof ASN1UTF8String s)) return List.of();  // malformed → strictest
                values.add(s.getString());
            }
            return List.copyOf(values);
        } catch (RuntimeException e) {
            return List.of();                        // malformed → strictest reading
        }
    }

    /** Overload taking the dotted OID string. */
    public static List<String> readUtf8SequenceExtension(X509Certificate cert, String oid) {
        return readUtf8SequenceExtension(cert, oid == null ? null : new ASN1ObjectIdentifier(oid));
    }

    /**
     * Minimal RFC 6960 DER walk of {@code OCSPResponse → BasicOCSPResponse →} the
     * <em>first</em> {@code SingleResponse.nextUpdate}. Signature verification of the
     * staple is the full OCSP pipeline's job; the Phase-3 gate needs only freshness.
     *
     * <pre>
     * OCSPResponse      ::= SEQUENCE { responseStatus ENUMERATED,
     *                                  responseBytes [0] EXPLICIT ResponseBytes OPTIONAL }
     * ResponseBytes     ::= SEQUENCE { responseType OID (id-pkix-ocsp-basic),
     *                                  response OCTET STRING }   -- wraps BasicOCSPResponse
     * BasicOCSPResponse ::= SEQUENCE { tbsResponseData ResponseData, ... }
     * ResponseData      ::= SEQUENCE { version [0] EXPLICIT OPTIONAL,
     *                                  responderID CHOICE [1]/[2],
     *                                  producedAt GeneralizedTime,
     *                                  responses SEQUENCE OF SingleResponse }
     * SingleResponse    ::= SEQUENCE { certID SEQUENCE, certStatus CHOICE,
     *                                  thisUpdate GeneralizedTime,
     *                                  nextUpdate [0] EXPLICIT GeneralizedTime OPTIONAL, ... }
     * </pre>
     *
     * @return the {@code nextUpdate} instant, or {@code null} when {@code responseBytes}
     *         is absent, {@code responses} is empty, {@code nextUpdate} is absent, or any
     *         ASN.1 content is malformed
     */
    public static Instant tryGetOcspNextUpdate(byte[] der) {
        if (der == null || der.length == 0) return null;
        try {
            ASN1Sequence root = ASN1Sequence.getInstance(der);
            // [0] responseStatus ENUMERATED; [1] responseBytes [0] EXPLICIT OPTIONAL
            if (root.size() < 2) return null;
            if (!(root.getObjectAt(1).toASN1Primitive() instanceof ASN1TaggedObject rbTag)
                || rbTag.getTagNo() != 0) return null;
            ASN1Sequence responseBytes = ASN1Sequence.getInstance(rbTag, true);
            if (responseBytes.size() < 2) return null;
            ASN1ObjectIdentifier responseType =
                ASN1ObjectIdentifier.getInstance(responseBytes.getObjectAt(0));
            if (!ID_PKIX_OCSP_BASIC.equals(responseType.getId())) return null;

            byte[] basicDer = ASN1OctetString.getInstance(responseBytes.getObjectAt(1)).getOctets();
            ASN1Sequence basic = ASN1Sequence.getInstance(basicDer);
            if (basic.size() < 1) return null;
            ASN1Sequence tbs = ASN1Sequence.getInstance(basic.getObjectAt(0));

            int i = 0;
            // version [0] EXPLICIT OPTIONAL — skip when present.
            if (i < tbs.size() && tbs.getObjectAt(i).toASN1Primitive() instanceof ASN1TaggedObject v
                && v.getTagNo() == 0) {
                i++;
            }
            // responderID CHOICE [1] byName / [2] byKey — skip any context-specific value.
            if (i < tbs.size() && tbs.getObjectAt(i).toASN1Primitive() instanceof ASN1TaggedObject) {
                i++;
            }
            i++; // producedAt GeneralizedTime
            if (i >= tbs.size()) return null;
            ASN1Sequence responses = ASN1Sequence.getInstance(tbs.getObjectAt(i));
            if (responses.size() == 0) return null;

            ASN1Sequence single = ASN1Sequence.getInstance(responses.getObjectAt(0));
            // 0 certID, 1 certStatus, 2 thisUpdate, 3 nextUpdate [0] EXPLICIT OPTIONAL
            if (single.size() < 4) return null;
            if (!(single.getObjectAt(3).toASN1Primitive() instanceof ASN1TaggedObject nuTag)
                || nuTag.getTagNo() != 0) return null;
            ASN1GeneralizedTime nextUpdate = ASN1GeneralizedTime.getInstance(nuTag, true);
            return nextUpdate.getDate().toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decode a base64url string, tolerating both missing padding and the standard
     * alphabet ({@code -}→{@code +}, {@code _}→{@code /}, re-pad to a multiple of 4).
     *
     * @throws IllegalArgumentException when the input is not valid base64
     */
    public static byte[] decodeBase64Url(String s) {
        String padded = s.replace('-', '+').replace('_', '/');
        int rem = padded.length() % 4;
        if (rem != 0) padded = padded + "====".substring(rem);
        return Base64.getDecoder().decode(padded);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * {@code claimed \ attested}, preserving claim order and de-duplicating. A null claim
     * list is the empty set, which is always a subset. Ordinal (exact-byte) comparison.
     */
    private static List<String> excess(List<String> claimed, List<String> attested) {
        if (claimed == null || claimed.isEmpty()) return List.of();
        Set<String> allowed = new LinkedHashSet<>(attested);
        List<String> out = new ArrayList<>();
        for (String c : claimed) {
            if (!allowed.contains(c) && !out.contains(c)) out.add(c);
        }
        return out;
    }
}
