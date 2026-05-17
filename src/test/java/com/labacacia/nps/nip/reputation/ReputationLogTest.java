// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.reputation;

import com.labacacia.nps.nip.NipCanonicalJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReputationLogTest {

    // ── Part 1 — IncidentType ────────────────────────────────────────────────

    @Test
    void incidentType_certRevoked_roundTrip() {
        assertEquals(IncidentType.CERT_REVOKED, IncidentType.fromWire("cert-revoked"));
        assertEquals("cert-revoked", IncidentType.CERT_REVOKED.wire);
    }

    @Test
    void incidentType_rateLimitViolation_roundTrip() {
        assertEquals(IncidentType.RATE_LIMIT_VIOLATION, IncidentType.fromWire("rate-limit-violation"));
        assertEquals("rate-limit-violation", IncidentType.RATE_LIMIT_VIOLATION.wire);
    }

    @Test
    void incidentType_tosViolation_roundTrip() {
        assertEquals(IncidentType.TOS_VIOLATION, IncidentType.fromWire("tos-violation"));
        assertEquals("tos-violation", IncidentType.TOS_VIOLATION.wire);
    }

    @Test
    void incidentType_scrapingPattern_roundTrip() {
        assertEquals(IncidentType.SCRAPING_PATTERN, IncidentType.fromWire("scraping-pattern"));
        assertEquals("scraping-pattern", IncidentType.SCRAPING_PATTERN.wire);
    }

    @Test
    void incidentType_paymentDefault_roundTrip() {
        assertEquals(IncidentType.PAYMENT_DEFAULT, IncidentType.fromWire("payment-default"));
        assertEquals("payment-default", IncidentType.PAYMENT_DEFAULT.wire);
    }

    @Test
    void incidentType_contractDispute_roundTrip() {
        assertEquals(IncidentType.CONTRACT_DISPUTE, IncidentType.fromWire("contract-dispute"));
        assertEquals("contract-dispute", IncidentType.CONTRACT_DISPUTE.wire);
    }

    @Test
    void incidentType_impersonationClaim_roundTrip() {
        assertEquals(IncidentType.IMPERSONATION_CLAIM, IncidentType.fromWire("impersonation-claim"));
        assertEquals("impersonation-claim", IncidentType.IMPERSONATION_CLAIM.wire);
    }

    @Test
    void incidentType_positiveAttestation_roundTrip() {
        assertEquals(IncidentType.POSITIVE_ATTESTATION, IncidentType.fromWire("positive-attestation"));
        assertEquals("positive-attestation", IncidentType.POSITIVE_ATTESTATION.wire);
    }

    @Test
    void incidentType_unknownWire_returnsOther() {
        assertEquals(IncidentType.OTHER, IncidentType.fromWire("completely-unknown-incident-xyz"));
    }

    // ── Part 2 — Severity ────────────────────────────────────────────────────

    @Test
    void severity_info_roundTrip() {
        assertEquals(Severity.INFO, Severity.fromWire("info"));
        assertEquals("info", Severity.INFO.wire);
    }

    @Test
    void severity_minor_roundTrip() {
        assertEquals(Severity.MINOR, Severity.fromWire("minor"));
        assertEquals("minor", Severity.MINOR.wire);
    }

    @Test
    void severity_moderate_roundTrip() {
        assertEquals(Severity.MODERATE, Severity.fromWire("moderate"));
        assertEquals("moderate", Severity.MODERATE.wire);
    }

    @Test
    void severity_major_roundTrip() {
        assertEquals(Severity.MAJOR, Severity.fromWire("major"));
        assertEquals("major", Severity.MAJOR.wire);
    }

    @Test
    void severity_critical_roundTrip() {
        assertEquals(Severity.CRITICAL, Severity.fromWire("critical"));
        assertEquals("critical", Severity.CRITICAL.wire);
    }

    @Test
    void severity_levelsAreOrdered() {
        assertEquals(0, Severity.INFO.level);
        assertEquals(1, Severity.MINOR.level);
        assertEquals(2, Severity.MODERATE.level);
        assertEquals(3, Severity.MAJOR.level);
        assertEquals(4, Severity.CRITICAL.level);

        assertTrue(Severity.INFO.level < Severity.MINOR.level);
        assertTrue(Severity.MINOR.level < Severity.MODERATE.level);
        assertTrue(Severity.MODERATE.level < Severity.MAJOR.level);
        assertTrue(Severity.MAJOR.level < Severity.CRITICAL.level);
    }

    @Test
    void severity_unknownWire_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Severity.fromWire("extreme"));
    }

    // ── Part 3 — ReputationLogEntry toMap() / fromMap() ─────────────────────

    private static ReputationLogEntry buildMinimalEntry() {
        return new ReputationLogEntry.Builder()
            .v(1)
            .logId("urn:nps:org:log.test")
            .seq(42L)
            .timestamp("2026-01-01T00:00:00Z")
            .subjectNid("urn:nps:node:subject.test")
            .incident(IncidentType.CERT_REVOKED)
            .severity(Severity.MINOR)
            .issuerNid("urn:nps:org:issuer.test")
            .signature("ed25519:dGVzdA")
            .build();
    }

    @Test
    void toMap_emitsSnakeCaseKeys() {
        Map<String, Object> m = buildMinimalEntry().toMap();
        assertTrue(m.containsKey("log_id"),      "must contain log_id");
        assertTrue(m.containsKey("subject_nid"), "must contain subject_nid");
        assertTrue(m.containsKey("issuer_nid"),  "must contain issuer_nid");
        assertTrue(m.containsKey("timestamp"),   "must contain timestamp");
        assertTrue(m.containsKey("incident"),    "must contain incident");
        assertTrue(m.containsKey("severity"),    "must contain severity");
        assertTrue(m.containsKey("seq"),         "must contain seq");
        assertTrue(m.containsKey("v"),           "must contain v");
    }

    @Test
    void toMap_omitsNullFields() {
        // Build entry without optional fields
        Map<String, Object> m = buildMinimalEntry().toMap();
        assertFalse(m.containsKey("window"),          "window must be absent when null");
        assertFalse(m.containsKey("observation"),     "observation must be absent when null");
        assertFalse(m.containsKey("evidence_ref"),    "evidence_ref must be absent when null");
        assertFalse(m.containsKey("evidence_sha256"), "evidence_sha256 must be absent when null");
    }

    @Test
    void fromMap_toMap_roundTrip() {
        ReputationLogEntry original = buildMinimalEntry();
        ReputationLogEntry restored = ReputationLogEntry.fromMap(original.toMap());

        assertEquals(original.getV(),           restored.getV());
        assertEquals(original.getLogId(),       restored.getLogId());
        assertEquals(original.getSeq(),         restored.getSeq());
        assertEquals(original.getTimestamp(),   restored.getTimestamp());
        assertEquals(original.getSubjectNid(),  restored.getSubjectNid());
        assertEquals(original.getIncident(),    restored.getIncident());
        assertEquals(original.getSeverity(),    restored.getSeverity());
        assertEquals(original.getIssuerNid(),   restored.getIssuerNid());
        assertEquals(original.getSignature(),   restored.getSignature());
        assertNull(restored.getWindow());
        assertNull(restored.getEvidenceRef());
        assertNull(restored.getEvidenceSha256());
    }

    @Test
    void fromMap_unknownIncidentWire_resolvesToOther() {
        Map<String, Object> m = buildMinimalEntry().toMap();
        m.put("incident", "future-incident-type-9999");
        ReputationLogEntry entry = ReputationLogEntry.fromMap(m);
        assertEquals(IncidentType.OTHER, entry.getIncident());
    }

    // ── Part 4 — signEntry / verifyEntry ─────────────────────────────────────

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        return kpg.generateKeyPair();
    }

    private static ReputationLogEntry buildUnsignedEntry() {
        return new ReputationLogEntry.Builder()
            .v(1)
            .logId("urn:nps:org:log.test")
            .seq(1L)
            .timestamp("2026-01-01T00:00:00Z")
            .subjectNid("urn:nps:node:subject.test")
            .incident(IncidentType.CERT_REVOKED)
            .severity(Severity.INFO)
            .issuerNid("urn:nps:org:issuer.test")
            .signature("")
            .build();
    }

    @Test
    void signEntry_verifyEntry_validSignature() throws Exception {
        KeyPair kp = generateKeyPair();
        ReputationLogEntry unsigned = buildUnsignedEntry();
        ReputationLogEntry signed = ReputationLogClient.signEntry(kp.getPrivate(), unsigned);

        assertNotNull(signed.getSignature());
        assertTrue(signed.getSignature().startsWith("ed25519:"));
        assertTrue(ReputationLogClient.verifyEntry(kp.getPublic(), signed));
    }

    @Test
    void verifyEntry_returnsFalseAfterTamperingSubjectNid() throws Exception {
        KeyPair kp = generateKeyPair();
        ReputationLogEntry signed = ReputationLogClient.signEntry(kp.getPrivate(), buildUnsignedEntry());

        // Tamper: rebuild entry with a different subject_nid but keep the same signature
        ReputationLogEntry tampered = new ReputationLogEntry.Builder()
            .v(signed.getV())
            .logId(signed.getLogId())
            .seq(signed.getSeq())
            .timestamp(signed.getTimestamp())
            .subjectNid("urn:nps:node:attacker.test")   // tampered
            .incident(signed.getIncident())
            .incidentRaw(signed.getIncidentRaw())
            .severity(signed.getSeverity())
            .issuerNid(signed.getIssuerNid())
            .signature(signed.getSignature())
            .build();

        assertFalse(ReputationLogClient.verifyEntry(kp.getPublic(), tampered));
    }

    @Test
    void verifyEntry_returnsFalseForWrongPublicKey() throws Exception {
        KeyPair kp = generateKeyPair();
        KeyPair attackerKp = generateKeyPair();

        ReputationLogEntry signed = ReputationLogClient.signEntry(kp.getPrivate(), buildUnsignedEntry());

        assertFalse(ReputationLogClient.verifyEntry(attackerKp.getPublic(), signed));
    }

    // ── Part 5 — verifyInclusion (Merkle) ────────────────────────────────────

    private static byte[] leafHash(ReputationLogEntry entry) throws Exception {
        Map<String, Object> m = new TreeMap<>(entry.toMap());
        byte[] leafJson = NipCanonicalJson.canonicalize(m);
        byte[] input = new byte[1 + leafJson.length];
        input[0] = 0x00;
        System.arraycopy(leafJson, 0, input, 1, leafJson.length);
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static byte[] nodeHash(byte[] left, byte[] right) throws Exception {
        byte[] buf = new byte[65];
        buf[0] = 0x01;
        System.arraycopy(left,  0, buf,  1, 32);
        System.arraycopy(right, 0, buf, 33, 32);
        return MessageDigest.getInstance("SHA-256").digest(buf);
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static ReputationLogEntry makeSignedEntry(String subjectNid) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        ReputationLogEntry unsigned = new ReputationLogEntry.Builder()
            .v(1)
            .logId("urn:nps:org:log.test")
            .seq(1)
            .timestamp("2026-01-01T00:00:00Z")
            .subjectNid(subjectNid)
            .incident(IncidentType.CERT_REVOKED)
            .severity(Severity.INFO)
            .issuerNid("urn:nps:org:issuer.test")
            .signature("")
            .build();
        return ReputationLogClient.signEntry(kp.getPrivate(), unsigned);
    }

    @Test
    void verifyInclusion_singleLeaf() throws Exception {
        ReputationLogEntry e0 = makeSignedEntry("urn:nps:node:subject0.test");

        byte[] leaf0 = leafHash(e0);
        String rootHash = b64url(leaf0);

        InclusionProof proof = new InclusionProof(0L, 1L, b64url(leaf0), List.of());
        SignedTreeHead sth   = new SignedTreeHead(1L, "2026-01-01T00:00:00Z", rootHash,
                                                  "urn:nps:org:log.test", "ed25519:sig");

        assertTrue(ReputationLogClient.verifyInclusion(proof, sth, e0));
    }

    @Test
    void verifyInclusion_twoLeafTree() throws Exception {
        ReputationLogEntry e0 = makeSignedEntry("urn:nps:node:subject0.test");
        ReputationLogEntry e1 = makeSignedEntry("urn:nps:node:subject1.test");

        byte[] h0 = leafHash(e0);
        byte[] h1 = leafHash(e1);
        byte[] root = nodeHash(h0, h1);
        String rootHash = b64url(root);

        // Verify leaf 0 (left child)
        InclusionProof proof0 = new InclusionProof(0L, 2L, b64url(h0), List.of(b64url(h1)));
        SignedTreeHead sth    = new SignedTreeHead(2L, "2026-01-01T00:00:00Z", rootHash,
                                                   "urn:nps:org:log.test", "ed25519:sig");
        assertTrue(ReputationLogClient.verifyInclusion(proof0, sth, e0));

        // Verify leaf 1 (right child)
        InclusionProof proof1 = new InclusionProof(1L, 2L, b64url(h1), List.of(b64url(h0)));
        assertTrue(ReputationLogClient.verifyInclusion(proof1, sth, e1));
    }

    @Test
    void verifyInclusion_fourLeafTree() throws Exception {
        ReputationLogEntry e0 = makeSignedEntry("urn:nps:node:subject0.test");
        ReputationLogEntry e1 = makeSignedEntry("urn:nps:node:subject1.test");
        ReputationLogEntry e2 = makeSignedEntry("urn:nps:node:subject2.test");
        ReputationLogEntry e3 = makeSignedEntry("urn:nps:node:subject3.test");

        byte[] h0 = leafHash(e0);
        byte[] h1 = leafHash(e1);
        byte[] h2 = leafHash(e2);
        byte[] h3 = leafHash(e3);
        byte[] h01   = nodeHash(h0, h1);
        byte[] h23   = nodeHash(h2, h3);
        byte[] root  = nodeHash(h01, h23);
        String rootHash = b64url(root);

        SignedTreeHead sth = new SignedTreeHead(4L, "2026-01-01T00:00:00Z", rootHash,
                                                "urn:nps:org:log.test", "ed25519:sig");

        // leaf 0 (index 0): sibling h1, then parent-sibling h23
        InclusionProof p0 = new InclusionProof(0L, 4L, b64url(h0), List.of(b64url(h1), b64url(h23)));
        assertTrue(ReputationLogClient.verifyInclusion(p0, sth, e0));

        // leaf 1 (index 1): sibling h0, then parent-sibling h23
        InclusionProof p1 = new InclusionProof(1L, 4L, b64url(h1), List.of(b64url(h0), b64url(h23)));
        assertTrue(ReputationLogClient.verifyInclusion(p1, sth, e1));

        // leaf 2 (index 2): sibling h3, then parent-sibling h01
        InclusionProof p2 = new InclusionProof(2L, 4L, b64url(h2), List.of(b64url(h3), b64url(h01)));
        assertTrue(ReputationLogClient.verifyInclusion(p2, sth, e2));

        // leaf 3 (index 3): sibling h2, then parent-sibling h01
        InclusionProof p3 = new InclusionProof(3L, 4L, b64url(h3), List.of(b64url(h2), b64url(h01)));
        assertTrue(ReputationLogClient.verifyInclusion(p3, sth, e3));
    }

    @Test
    void verifyInclusion_returnsFalseOnTamperedEntry() throws Exception {
        ReputationLogEntry e0 = makeSignedEntry("urn:nps:node:subject0.test");
        ReputationLogEntry e1 = makeSignedEntry("urn:nps:node:subject1.test");

        byte[] h0 = leafHash(e0);
        byte[] h1 = leafHash(e1);
        byte[] root = nodeHash(h0, h1);

        // Proof is for e0, but we verify against a different entry
        InclusionProof proof = new InclusionProof(0L, 2L, b64url(h0), List.of(b64url(h1)));
        SignedTreeHead sth   = new SignedTreeHead(2L, "2026-01-01T00:00:00Z", b64url(root),
                                                  "urn:nps:org:log.test", "ed25519:sig");

        // Pass e1 where e0 is expected — entry doesn't match the leaf hash in the proof
        assertFalse(ReputationLogClient.verifyInclusion(proof, sth, e1));
    }

    @Test
    void verifyInclusion_returnsFalseOnWrongRoot() throws Exception {
        ReputationLogEntry e0 = makeSignedEntry("urn:nps:node:subject0.test");

        byte[] h0 = leafHash(e0);
        byte[] fakeRoot = new byte[32]; // all-zero root
        Arrays.fill(fakeRoot, (byte) 0xAB);

        InclusionProof proof = new InclusionProof(0L, 1L, b64url(h0), List.of());
        SignedTreeHead sth   = new SignedTreeHead(1L, "2026-01-01T00:00:00Z", b64url(fakeRoot),
                                                  "urn:nps:org:log.test", "ed25519:sig");

        assertFalse(ReputationLogClient.verifyInclusion(proof, sth, e0));
    }

    @Test
    void verifyInclusion_returnsFalseOnWrongLeafHash() throws Exception {
        ReputationLogEntry e0 = makeSignedEntry("urn:nps:node:subject0.test");

        byte[] correctLeaf = leafHash(e0);
        byte[] wrongLeaf   = new byte[32];
        Arrays.fill(wrongLeaf, (byte) 0xFF);
        String rootHash = b64url(correctLeaf);

        // Put wrong leaf hash in the proof
        InclusionProof proof = new InclusionProof(0L, 1L, b64url(wrongLeaf), List.of());
        SignedTreeHead sth   = new SignedTreeHead(1L, "2026-01-01T00:00:00Z", rootHash,
                                                  "urn:nps:org:log.test", "ed25519:sig");

        assertFalse(ReputationLogClient.verifyInclusion(proof, sth, e0));
    }

    @Test
    void verifyInclusion_returnsFalseOnCorruptedAuditPath() throws Exception {
        ReputationLogEntry e0 = makeSignedEntry("urn:nps:node:subject0.test");
        ReputationLogEntry e1 = makeSignedEntry("urn:nps:node:subject1.test");

        byte[] h0 = leafHash(e0);
        byte[] h1 = leafHash(e1);
        byte[] root = nodeHash(h0, h1);

        // Corrupt the sibling in the audit path
        byte[] corruptedSibling = new byte[32];
        Arrays.fill(corruptedSibling, (byte) 0x00);

        InclusionProof proof = new InclusionProof(0L, 2L, b64url(h0), List.of(b64url(corruptedSibling)));
        SignedTreeHead sth   = new SignedTreeHead(2L, "2026-01-01T00:00:00Z", b64url(root),
                                                  "urn:nps:org:log.test", "ed25519:sig");

        assertFalse(ReputationLogClient.verifyInclusion(proof, sth, e0));
    }
}
