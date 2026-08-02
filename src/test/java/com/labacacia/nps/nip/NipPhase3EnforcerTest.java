// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.core.NpsStatusCodes;
import com.labacacia.nps.nip.x509.Ed25519PublicKeys;
import com.labacacia.nps.nip.x509.NpsX509Oids;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERGeneralizedTime;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NIP v0.12 §7.5 Phase-3 enforcement — port of the reference
 * {@code NipPhase3EnforcerTests}, plus the additional cases brief B calls for.
 */
class NipPhase3EnforcerTest {

    /** Fixed clock, per the reference fixture. */
    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();

    // ── §6 the eight reference scenarios ─────────────────────────────────────

    @Test
    void subsetClaimsWithFreshStaplePass() throws Exception {
        X509Certificate leaf = cert(List.of("memory", "anchor"), List.of("nwp:query", "nwp:action"));
        IdentFrame frame = frame(List.of("memory"), List.of("nwp:query"),
            staple(NOW.plus(Duration.ofHours(6))));

        assertTrue(NipPhase3Enforcer.enforce(frame, leaf, NOW).valid());
    }

    @Test
    void unattestedRoleFailsWithNodeRolesMismatch() throws Exception {
        X509Certificate leaf = cert(List.of("memory"), null);
        IdentFrame frame = frame(List.of("memory", "orchestrator"), List.of("nwp:query"),
            staple(NOW.plus(Duration.ofHours(6))));

        var r = NipPhase3Enforcer.enforce(frame, leaf, NOW);
        assertFalse(r.valid());
        assertEquals(NipErrorCodes.CERT_NODE_ROLES_MISMATCH, r.errorCode());
        assertEquals(3, r.stepFailed());
        assertTrue(r.message().contains("orchestrator"), r.message());
    }

    @Test
    void unattestedCapabilityFailsWithCapabilitiesExceeded() throws Exception {
        X509Certificate leaf = cert(null, List.of("nwp:query"));
        IdentFrame frame = frame(List.of("memory"), List.of("nwp:query", "nop:orchestrate"),
            staple(NOW.plus(Duration.ofHours(6))));

        var r = NipPhase3Enforcer.enforce(frame, leaf, NOW);
        assertFalse(r.valid());
        assertEquals(NipErrorCodes.CERT_CAPABILITIES_EXCEEDED, r.errorCode());
        assertEquals(3, r.stepFailed());
        assertTrue(r.message().contains("nop:orchestrate"), r.message());
    }

    @Test
    void noExtensionsMeansAttributeChecksDoNotApply() throws Exception {
        X509Certificate leaf = cert(null, null);
        IdentFrame frame = frame(List.of("anything", "at", "all"), List.of("whatever:x"),
            staple(NOW.plus(Duration.ofHours(6))));

        assertTrue(NipPhase3Enforcer.enforce(frame, leaf, NOW).valid());
    }

    @Test
    void missingStapleFails() throws Exception {
        X509Certificate leaf = cert(null, null);
        var r = NipPhase3Enforcer.enforce(frame(List.of(), List.of(), null), leaf, NOW);

        assertFalse(r.valid());
        assertEquals(NipErrorCodes.OCSP_STAPLE_EXPIRED, r.errorCode());
        assertEquals(3, r.stepFailed());
    }

    @Test
    void expiredStapleFails() throws Exception {
        X509Certificate leaf = cert(null, null);
        IdentFrame frame = frame(List.of(), List.of(), staple(NOW.minus(Duration.ofMinutes(1))));

        var r = NipPhase3Enforcer.enforce(frame, leaf, NOW);
        assertFalse(r.valid());
        assertEquals(NipErrorCodes.OCSP_STAPLE_EXPIRED, r.errorCode());
        assertTrue(r.message().contains("elapsed"), r.message());
    }

    @Test
    void malformedStapleFailsClosed() throws Exception {
        X509Certificate leaf = cert(null, null);
        IdentFrame frame = frame(List.of(), List.of(), "bm90LWFuLW9jc3A");

        var r = NipPhase3Enforcer.enforce(frame, leaf, NOW);
        assertFalse(r.valid());
        assertEquals(NipErrorCodes.OCSP_STAPLE_EXPIRED, r.errorCode());
    }

    @Test
    void utf8SequenceExtensionParses() throws Exception {
        X509Certificate leaf = cert(List.of("memory", "anchor"), null);

        assertEquals(List.of("memory", "anchor"),
            NipPhase3Enforcer.readUtf8SequenceExtension(leaf, NpsX509Oids.ID_NPS_NODE_ROLES));
        // Absent extension is null — NOT an empty list.
        assertNull(NipPhase3Enforcer.readUtf8SequenceExtension(leaf, NpsX509Oids.ID_NPS_CAPABILITIES));
    }

    // ── §6 "ports SHOULD additionally add" ───────────────────────────────────

    @Test
    void malformedExtensionIsTreatedAsEmptySoAnyClaimFails() throws Exception {
        // A present-but-malformed extension reads as [] (strictest), not as absent.
        X509Certificate leaf = certWithRawExtension(NpsX509Oids.ID_NPS_CAPABILITIES,
            new byte[]{0x30, (byte) 0x80, 0x01, 0x02});
        assertEquals(List.of(),
            NipPhase3Enforcer.readUtf8SequenceExtension(leaf, NpsX509Oids.ID_NPS_CAPABILITIES));

        IdentFrame frame = frame(List.of(), List.of("nwp:query"),
            staple(NOW.plus(Duration.ofHours(6))));
        var r = NipPhase3Enforcer.enforce(frame, leaf, NOW);
        assertFalse(r.valid());
        assertEquals(NipErrorCodes.CERT_CAPABILITIES_EXCEEDED, r.errorCode());
    }

    @Test
    void nextUpdateExactlyNowFails() throws Exception {
        X509Certificate leaf = cert(null, null);
        // `<=`, not `<`.
        var r = NipPhase3Enforcer.enforce(frame(List.of(), List.of(), staple(NOW)), leaf, NOW);
        assertFalse(r.valid());
        assertEquals(NipErrorCodes.OCSP_STAPLE_EXPIRED, r.errorCode());
        assertTrue(r.message().contains("elapsed"));
    }

    @Test
    void nullNodeRolesIsTheEmptySetAndAlwaysASubset() throws Exception {
        X509Certificate leaf = cert(List.of("memory"), List.of("nwp:query"));
        IdentFrame frame = frame(null, null, staple(NOW.plus(Duration.ofHours(6))));
        assertTrue(NipPhase3Enforcer.enforce(frame, leaf, NOW).valid());
    }

    @Test
    void comparisonIsOrdinalNotCaseInsensitive() throws Exception {
        X509Certificate leaf = cert(List.of("memory"), null);
        IdentFrame frame = frame(List.of("Memory"), List.of(), staple(NOW.plus(Duration.ofHours(6))));

        var r = NipPhase3Enforcer.enforce(frame, leaf, NOW);
        assertFalse(r.valid());
        assertEquals(NipErrorCodes.CERT_NODE_ROLES_MISMATCH, r.errorCode());
    }

    @Test
    void evaluationOrderIsRolesThenCapabilitiesThenStaple() throws Exception {
        // All three would fail; node_roles must be reported.
        X509Certificate leaf = cert(List.of("memory"), List.of("nwp:query"));
        IdentFrame frame = frame(List.of("orchestrator"), List.of("nop:orchestrate"), null);

        assertEquals(NipErrorCodes.CERT_NODE_ROLES_MISMATCH,
            NipPhase3Enforcer.enforce(frame, leaf, NOW).errorCode());

        // Roles now fine; capabilities must be reported ahead of the missing staple.
        IdentFrame frame2 = frame(List.of("memory"), List.of("nop:orchestrate"), null);
        assertEquals(NipErrorCodes.CERT_CAPABILITIES_EXCEEDED,
            NipPhase3Enforcer.enforce(frame2, leaf, NOW).errorCode());
    }

    @Test
    void argumentNullValidation() throws Exception {
        X509Certificate leaf = cert(null, null);
        assertThrows(IllegalArgumentException.class, () -> NipPhase3Enforcer.enforce(null, leaf, NOW));
        assertThrows(IllegalArgumentException.class,
            () -> NipPhase3Enforcer.enforce(frame(List.of(), List.of(), null), null, NOW));
    }

    @Test
    void tryGetOcspNextUpdateIsDirectlyUsable() {
        byte[] der = ocspResponseDer(NOW.plus(Duration.ofHours(6)));
        assertEquals(NOW.plus(Duration.ofHours(6)), NipPhase3Enforcer.tryGetOcspNextUpdate(der));

        assertNull(NipPhase3Enforcer.tryGetOcspNextUpdate(new byte[]{1, 2, 3}));
        assertNull(NipPhase3Enforcer.tryGetOcspNextUpdate(null));
        // responseBytes absent ⇒ null.
        assertNull(NipPhase3Enforcer.tryGetOcspNextUpdate(derOf(new DERSequence(new ASN1Enumerated(0)))));
        // nextUpdate absent ⇒ null.
        assertNull(NipPhase3Enforcer.tryGetOcspNextUpdate(ocspResponseDer(null)));
    }

    // ── Error-code registration ──────────────────────────────────────────────

    @Test
    void capabilitiesExceededMapsToAuthForbidden() {
        assertEquals("NIP-CERT-CAPABILITIES-EXCEEDED", NipErrorCodes.CERT_CAPABILITIES_EXCEEDED);
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN,
            NipErrorCodes.NIP_TO_NPS_STATUS.get(NipErrorCodes.CERT_CAPABILITIES_EXCEEDED));
        // Deliberate asymmetry with the sibling code.
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_FRAME,
            NipErrorCodes.NIP_TO_NPS_STATUS.get(NipErrorCodes.CERT_NODE_ROLES_MISMATCH));
    }

    @Test
    void phase3EnforcementDefaultsToFalse() {
        assertFalse(NipVerifierOptions.builder().build().phase3Enforcement());
        assertTrue(NipVerifierOptions.builder().phase3Enforcement(true).build().phase3Enforcement());
    }

    @Test
    void capabilitiesRoundTripOnTheWireButStayOutOfTheSignedPayload() {
        IdentFrame f = new IdentFrame("urn:nps:agent:ca.example.com:p3-001", "ed25519:AAAA",
            Map.of("issued_by", "urn:nps:org:example.com"), "ed25519:test",
            null, IdentCertFormat.V2_X509, List.of("x"), "st", List.of("memory"),
            List.of("nwp:query"));

        assertEquals(List.of("nwp:query"), f.toDict().get("capabilities"));
        assertNull(f.unsignedDict().get("capabilities"));
        assertNull(f.unsignedDict().get("node_roles"));
        assertEquals(List.of("nwp:query"), IdentFrame.fromDict(f.toDict()).capabilities());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Baseline IdentFrame per brief B §6. */
    private static IdentFrame frame(List<String> nodeRoles, List<String> capabilities, String ocspStaple) {
        return new IdentFrame(
            "urn:nps:agent:ca.example.com:p3-001",
            "ed25519:AAAA",
            Map.of("issued_by", "urn:nps:org:example.com",
                   "issued_at", "2026-07-01T00:00:00Z",
                   "expires_at", "2026-08-01T00:00:00Z",
                   "serial", "0x01",
                   "scope", Map.of("nodes", List.of("nwp://example.com/*"))),
            "ed25519:test",
            null,
            IdentCertFormat.V2_X509,
            List.of("placeholder"),
            ocspStaple,
            nodeRoles,
            capabilities);
    }

    /** Self-signed leaf, CN=phase3-test, validity NOW-1d .. NOW+30d, optional id-nps-* extensions. */
    private static X509Certificate cert(List<String> roles, List<String> caps) throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "phase3-test").build();

        X509v3CertificateBuilder b = new X509v3CertificateBuilder(
            subject, BigInteger.ONE,
            Date.from(NOW.minus(Duration.ofDays(1))),
            Date.from(NOW.plus(Duration.ofDays(30))),
            subject,
            Ed25519PublicKeys.fromRawSpki(Ed25519PublicKeys.extractRaw(kp.getPublic())));
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        if (roles != null) b.addExtension(NpsX509Oids.ID_NPS_NODE_ROLES,  false, utf8Sequence(roles));
        if (caps  != null) b.addExtension(NpsX509Oids.ID_NPS_CAPABILITIES, false, utf8Sequence(caps));

        ContentSigner signer = new JcaContentSignerBuilder("Ed25519").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(signer));
    }

    /** Leaf carrying a deliberately malformed extension value. */
    private static X509Certificate certWithRawExtension(ASN1ObjectIdentifier oid, byte[] raw) throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "phase3-test").build();
        X509v3CertificateBuilder b = new X509v3CertificateBuilder(
            subject, BigInteger.ONE,
            Date.from(NOW.minus(Duration.ofDays(1))),
            Date.from(NOW.plus(Duration.ofDays(30))),
            subject,
            Ed25519PublicKeys.fromRawSpki(Ed25519PublicKeys.extractRaw(kp.getPublic())));
        b.addExtension(oid, false, raw);
        ContentSigner signer = new JcaContentSignerBuilder("Ed25519").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(signer));
    }

    private static DERSequence utf8Sequence(List<String> values) {
        ASN1EncodableVector v = new ASN1EncodableVector();
        for (String s : values) v.add(new DERUTF8String(s));
        return new DERSequence(v);
    }

    /** Hand-built minimal RFC 6960 OCSPResponse, base64url without padding. */
    private static String staple(Instant nextUpdate) {
        return URL_ENC.encodeToString(ocspResponseDer(nextUpdate));
    }

    private static byte[] ocspResponseDer(Instant nextUpdate) {
        AlgorithmIdentifier alg = new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.3.101.112"));

        // CertID ::= SEQUENCE { hashAlgorithm, issuerNameHash, issuerKeyHash, serialNumber }
        DERSequence certId = new DERSequence(new ASN1Encodable[]{
            new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.3.14.3.2.26"), DERNull.INSTANCE),
            new DEROctetString(new byte[20]),
            new DEROctetString(new byte[20]),
            new ASN1Integer(BigInteger.ONE)});

        ASN1EncodableVector single = new ASN1EncodableVector();
        single.add(certId);
        single.add(new DERTaggedObject(false, 0, DERNull.INSTANCE));      // certStatus good [0] IMPLICIT
        single.add(new DERGeneralizedTime(Date.from(NOW.minus(Duration.ofHours(1)))));  // thisUpdate
        if (nextUpdate != null) {
            single.add(new DERTaggedObject(true, 0,
                new DERGeneralizedTime(Date.from(nextUpdate))));          // nextUpdate [0] EXPLICIT
        }

        ASN1EncodableVector tbs = new ASN1EncodableVector();
        tbs.add(new DERTaggedObject(true, 1,                              // responderID byName [1]
            new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "ocsp-responder").build()));
        tbs.add(new DERGeneralizedTime(Date.from(NOW)));                  // producedAt
        tbs.add(new DERSequence(new DERSequence(single)));                // responses

        DERSequence basic = new DERSequence(new ASN1Encodable[]{
            new DERSequence(tbs), alg, new DERBitString(new byte[]{0})});

        DERSequence responseBytes = new DERSequence(new ASN1Encodable[]{
            new ASN1ObjectIdentifier("1.3.6.1.5.5.7.48.1.1"),
            new DEROctetString(derOf(basic))});

        return derOf(new DERSequence(new ASN1Encodable[]{
            new ASN1Enumerated(0), new DERTaggedObject(true, 0, responseBytes)}));
    }

    private static byte[] derOf(ASN1Encodable e) {
        try {
            return e.toASN1Primitive().getEncoded("DER");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
