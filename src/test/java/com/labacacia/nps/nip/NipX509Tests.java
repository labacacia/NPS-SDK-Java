// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.nip.x509.Ed25519PublicKeys;
import com.labacacia.nps.nip.x509.NipX509Builder;
import com.labacacia.nps.nip.x509.NpsX509Oids;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java parallel of .NET {@code NipX509Tests.cs} per NPS-RFC-0002 §4.
 * Covers the 5 verification scenarios documented in the .NET reference.
 */
class NipX509Tests {

    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();

    /** End-to-end happy path: issue v2 IdentFrame, dual-trust verifier accepts. */
    @Test
    void registerX509_RoundTrip_VerifierAccepts() throws Exception {
        String caNid    = "urn:nps:ca:test";
        String agentNid = "urn:nps:agent:happy:1";

        // 1) CA root keypair + self-signed root cert.
        KeyPair caKp     = generate();
        X509Certificate root = NipX509Builder.issueRoot(caNid, caKp.getPrivate(),
            Ed25519PublicKeys.extractRaw(caKp.getPublic()),
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(365)),
            BigInteger.valueOf(1));

        // 2) Agent keypair + leaf signed by CA.
        KeyPair agentKp = generate();
        X509Certificate leaf = NipX509Builder.issueLeaf(
            agentNid, Ed25519PublicKeys.extractRaw(agentKp.getPublic()),
            caKp.getPrivate(), caNid,
            NipX509Builder.LeafRole.AGENT,
            AssuranceLevel.ATTESTED,
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(30)),
            BigInteger.valueOf(2));

        // 3) Build a v2 IdentFrame: v1 Ed25519 signature signed by CA + cert chain.
        IdentFrame frame = buildV2Frame(agentNid, agentKp, caKp, AssuranceLevel.ATTESTED, leaf, root);

        // 4) Verifier with both v1 CA pubkey AND v2 trusted root accepts.
        NipVerifierOptions opts = NipVerifierOptions.builder()
            .trustedCaPublicKeys(Map.of(caNid, caKp.getPublic()))
            .trustedX509Roots(List.of(root))
            .build();
        NipIdentVerifyResult result = new NipIdentVerifier(opts).verify(frame, caNid);

        assertTrue(result.valid(), () -> "Expected valid; got step " + result.stepFailed()
            + " err=" + result.errorCode() + " msg=" + result.message());
    }

    /** Tampered chain whose leaf has no EKU extension is rejected with NIP-CERT-EKU-MISSING. */
    @Test
    void registerX509_LeafEkuStripped_VerifierRejectsCertEkuMissing() throws Exception {
        String caNid    = "urn:nps:ca:test";
        String agentNid = "urn:nps:agent:eku-stripped:1";

        KeyPair caKp     = generate();
        X509Certificate root = NipX509Builder.issueRoot(caNid, caKp.getPrivate(),
            Ed25519PublicKeys.extractRaw(caKp.getPublic()),
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(365)),
            BigInteger.valueOf(1));

        KeyPair agentKp = generate();
        X509Certificate tamperedLeaf = buildLeafWithoutEku(
            agentNid, Ed25519PublicKeys.extractRaw(agentKp.getPublic()),
            caKp.getPrivate(), caNid, BigInteger.valueOf(99));

        IdentFrame frame = buildV2Frame(agentNid, agentKp, caKp, null, tamperedLeaf, root);

        NipVerifierOptions opts = NipVerifierOptions.builder()
            .trustedCaPublicKeys(Map.of(caNid, caKp.getPublic()))
            .trustedX509Roots(List.of(root))
            .build();
        NipIdentVerifyResult result = new NipIdentVerifier(opts).verify(frame, caNid);

        assertFalse(result.valid());
        assertEquals(NipErrorCodes.CERT_EKU_MISSING, result.errorCode());
        assertEquals(3, result.stepFailed());
    }

    /** Forged leaf for a different NID is rejected with NIP-CERT-SUBJECT-NID-MISMATCH. */
    @Test
    void registerX509_LeafForDifferentNid_VerifierRejectsSubjectMismatch() throws Exception {
        String caNid    = "urn:nps:ca:test";
        String agentNid = "urn:nps:agent:victim:1";
        String forgedNid = "urn:nps:agent:attacker:9";

        KeyPair caKp     = generate();
        X509Certificate root = NipX509Builder.issueRoot(caNid, caKp.getPrivate(),
            Ed25519PublicKeys.extractRaw(caKp.getPublic()),
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(365)),
            BigInteger.valueOf(1));

        KeyPair agentKp = generate();
        // Issue a leaf whose CN/SAN are the *forged* NID, but splice it into a frame
        // claiming the *victim* NID. The IdentFrame v1 signature still asserts the victim.
        X509Certificate forgedLeaf = NipX509Builder.issueLeaf(
            forgedNid, Ed25519PublicKeys.extractRaw(agentKp.getPublic()),
            caKp.getPrivate(), caNid,
            NipX509Builder.LeafRole.AGENT,
            AssuranceLevel.ANONYMOUS,
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(30)),
            BigInteger.valueOf(77));

        IdentFrame frame = buildV2Frame(agentNid, agentKp, caKp, null, forgedLeaf, root);

        NipVerifierOptions opts = NipVerifierOptions.builder()
            .trustedCaPublicKeys(Map.of(caNid, caKp.getPublic()))
            .trustedX509Roots(List.of(root))
            .build();
        NipIdentVerifyResult result = new NipIdentVerifier(opts).verify(frame, caNid);

        assertFalse(result.valid());
        assertEquals(NipErrorCodes.CERT_SUBJECT_NID_MISMATCH, result.errorCode());
        assertEquals(3, result.stepFailed());
    }

    /** Phase 1 backward compat: v1-only verifier ignores cert_chain and accepts v2 frames. */
    @Test
    void v1OnlyVerifier_AcceptsV2FrameByIgnoringCertChain() throws Exception {
        String caNid    = "urn:nps:ca:test";
        String agentNid = "urn:nps:agent:v1-compat:1";

        KeyPair caKp     = generate();
        X509Certificate root = NipX509Builder.issueRoot(caNid, caKp.getPrivate(),
            Ed25519PublicKeys.extractRaw(caKp.getPublic()),
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(365)),
            BigInteger.valueOf(1));

        KeyPair agentKp = generate();
        X509Certificate leaf = NipX509Builder.issueLeaf(
            agentNid, Ed25519PublicKeys.extractRaw(agentKp.getPublic()),
            caKp.getPrivate(), caNid,
            NipX509Builder.LeafRole.AGENT,
            AssuranceLevel.ANONYMOUS,
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(30)),
            BigInteger.valueOf(2));

        IdentFrame frame = buildV2Frame(agentNid, agentKp, caKp, null, leaf, root);

        // Verifier WITHOUT trustedX509Roots — Step 3b is skipped entirely.
        NipVerifierOptions opts = NipVerifierOptions.builder()
            .trustedCaPublicKeys(Map.of(caNid, caKp.getPublic()))
            .build();
        NipIdentVerifyResult result = new NipIdentVerifier(opts).verify(frame, caNid);

        assertTrue(result.valid(), () -> "v1-only verifier MUST accept v2 frames; got "
            + result.errorCode() + " " + result.message());
    }

    /** v2 verifier whose trust roots don't include this chain rejects with NIP-CERT-FORMAT-INVALID. */
    @Test
    void v2Verifier_RejectsV2FrameWhenTrustedRootsMissing() throws Exception {
        String caNid    = "urn:nps:ca:test";
        String agentNid = "urn:nps:agent:wrong-trust:1";

        KeyPair caKp     = generate();
        X509Certificate root = NipX509Builder.issueRoot(caNid, caKp.getPrivate(),
            Ed25519PublicKeys.extractRaw(caKp.getPublic()),
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(365)),
            BigInteger.valueOf(1));

        KeyPair agentKp = generate();
        X509Certificate leaf = NipX509Builder.issueLeaf(
            agentNid, Ed25519PublicKeys.extractRaw(agentKp.getPublic()),
            caKp.getPrivate(), caNid,
            NipX509Builder.LeafRole.AGENT,
            AssuranceLevel.ANONYMOUS,
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(30)),
            BigInteger.valueOf(2));

        IdentFrame frame = buildV2Frame(agentNid, agentKp, caKp, null, leaf, root);

        // Different unrelated CA root — chain won't anchor.
        KeyPair otherCaKp = generate();
        X509Certificate otherRoot = NipX509Builder.issueRoot("urn:nps:ca:other",
            otherCaKp.getPrivate(),
            Ed25519PublicKeys.extractRaw(otherCaKp.getPublic()),
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now().plus(Duration.ofDays(365)),
            BigInteger.valueOf(1));

        NipVerifierOptions opts = NipVerifierOptions.builder()
            .trustedCaPublicKeys(Map.of(caNid, caKp.getPublic()))
            .trustedX509Roots(List.of(otherRoot))
            .build();
        NipIdentVerifyResult result = new NipIdentVerifier(opts).verify(frame, caNid);

        assertFalse(result.valid());
        assertEquals(NipErrorCodes.CERT_FORMAT_INVALID, result.errorCode());
        assertEquals(3, result.stepFailed());
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    private static KeyPair generate() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** Build a v2 IdentFrame including the v1 Ed25519 CA signature. */
    private static IdentFrame buildV2Frame(
            String          subjectNid,
            KeyPair         subjectKp,
            KeyPair         caKp,
            AssuranceLevel  level,
            X509Certificate leaf,
            X509Certificate root) throws Exception {
        // Build unsigned dict (matches IdentFrame.unsignedDict order).
        Map<String, Object> unsigned = new HashMap<>();
        unsigned.put("nid",      subjectNid);
        unsigned.put("pub_key",  "ed25519:" + bytesHex(Ed25519PublicKeys.extractRaw(subjectKp.getPublic())));
        unsigned.put("metadata", Map.of("issued_by", "test-ca"));
        if (level != null) unsigned.put("assurance_level", level.wire());

        // Sign with CA private key (canonicalize → Ed25519).
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(caKp.getPrivate());
        signer.update(NipCanonicalJson.canonicalize(unsigned));
        String signatureWire = "ed25519:" + Base64.getEncoder().encodeToString(signer.sign());

        // Build cert chain: [leaf, root].
        List<String> chain = List.of(
            URL_ENC.encodeToString(leaf.getEncoded()),
            URL_ENC.encodeToString(root.getEncoded()));

        return new IdentFrame(
            subjectNid,
            (String) unsigned.get("pub_key"),
            (Map<String, Object>) unsigned.get("metadata"),
            signatureWire,
            level,
            IdentCertFormat.V2_X509,
            chain);
    }

    private static String bytesHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Build a leaf cert WITHOUT the EKU extension — exercises the verifier's EKU presence check. */
    private static X509Certificate buildLeafWithoutEku(
            String     subjectNid,
            byte[]     subjectPubRaw,
            PrivateKey caPriv,
            String     caNid,
            BigInteger serial) throws Exception {
        X500Name issuer  = nameFor(caNid);
        X500Name subject = nameFor(subjectNid);

        X509v3CertificateBuilder b = new X509v3CertificateBuilder(
            issuer, serial,
            Date.from(Instant.now().minus(Duration.ofMinutes(1))),
            Date.from(Instant.now().plus(Duration.ofDays(30))),
            subject,
            Ed25519PublicKeys.fromRawSpki(subjectPubRaw));

        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        // ★ Deliberately NO EKU extension.
        b.addExtension(Extension.subjectAlternativeName, false,
            new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, subjectNid)));

        ContentSigner signer = new JcaContentSignerBuilder("Ed25519").build(caPriv);
        return new JcaX509CertificateConverter().getCertificate(b.build(signer));
    }

    private static X500Name nameFor(String nid) {
        return new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, nid).build();
    }
}
