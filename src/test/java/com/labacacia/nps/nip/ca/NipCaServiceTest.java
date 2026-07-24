// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

import com.labacacia.nps.nip.NipErrorCodes;
import com.labacacia.nps.nip.ca.ra.AllowlistPolicy;
import com.labacacia.nps.nip.ca.ra.BootstrapTokenPolicy;
import com.labacacia.nps.nip.ca.ra.IBootstrapTokenStore;
import com.labacacia.nps.nip.ca.ra.IEnrollmentPolicy;
import com.labacacia.nps.nip.ca.ra.IPendingStore;
import com.labacacia.nps.nip.ca.ra.InMemoryBootstrapTokenStore;
import com.labacacia.nps.nip.ca.ra.InMemoryPendingStore;
import com.labacacia.nps.nip.ca.ra.NipRaPendingException;
import com.labacacia.nps.nip.ca.ra.PendingQueuePolicy;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Core CA service behaviour: register/verify, duplicate, renewal, revoke + cascade, group/session, RA tiers. */
class NipCaServiceTest {

    private static final String CA_NID = "urn:nps:org:ca.example.com";
    private static final String PUBKEY = "ed25519:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static KeyPair caKeys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static NipCaService svc(NipCaOptions opts, INipCaStore store) throws Exception {
        KeyPair kp = caKeys();
        return new NipCaService(opts, store, kp.getPrivate(), kp.getPublic());
    }

    private static NipCaOptions opts() {
        NipCaOptions o = new NipCaOptions();
        o.caNid = CA_NID;
        o.baseUrl = "https://ca.example.com";
        return o;
    }

    @Test
    void registerThenVerify() throws Exception {
        var store = new InMemoryNipCaStore();
        var ca = svc(opts(), store);

        var frame = ca.register("agent", "acme", PUBKEY, List.of("nwp:query"), "{}", null);
        assertEquals("urn:nps:agent:ca.example.com:acme", frame.nid());
        assertTrue(frame.signature().startsWith("ed25519:"));
        assertEquals("0x20", frame.toDict().get("frame"));

        var result = ca.verify(frame.nid());
        assertTrue(result.valid());
        assertEquals(frame.nid(), result.record().nid());
    }

    @Test
    void verifyUnknownNidFails() throws Exception {
        var ca = svc(opts(), new InMemoryNipCaStore());
        var r = ca.verify("urn:nps:agent:ca.example.com:ghost");
        assertFalse(r.valid());
        assertEquals(NipErrorCodes.CA_NID_NOT_FOUND, r.errorCode());
    }

    @Test
    void duplicateNidRejected() throws Exception {
        var ca = svc(opts(), new InMemoryNipCaStore());
        ca.register("agent", "dup", PUBKEY, List.of(), "{}", null);
        var ex = assertThrows(NipCaException.class,
            () -> ca.register("agent", "dup", PUBKEY, List.of(), "{}", null));
        assertEquals(NipErrorCodes.CA_NID_ALREADY_EXISTS, ex.errorCode());
    }

    @Test
    void renewalTooEarly() throws Exception {
        var o = opts();
        o.agentCertValidityDays = 30;
        o.renewalWindowDays = 7;
        var ca = svc(o, new InMemoryNipCaStore());

        ca.register("agent", "renewme", PUBKEY, List.of(), "{}", null);
        String nid = "urn:nps:agent:ca.example.com:renewme";

        // Just issued (30d out) — renewal window opens at day 23, so too early now.
        var ex = assertThrows(NipCaException.class, () -> ca.renew(nid));
        assertEquals(NipErrorCodes.CA_RENEWAL_TOO_EARLY, ex.errorCode());
    }

    @Test
    void renewalWithinWindow() throws Exception {
        var o = opts();
        o.agentCertValidityDays = 30;
        o.renewalWindowDays = 7;
        var store = new InMemoryNipCaStore();
        var ca = svc(o, store);

        // Seed a record already near expiry (inside the 7-day renewal window).
        String nid = "urn:nps:agent:ca.example.com:soon";
        String serial = store.nextSerial();
        store.save(NipCertRecord.builder()
            .nid(nid).entityType("agent").serial(serial).pubKey(PUBKEY)
            .capabilities(List.of("nwp:query")).scopeJson("{}").issuedBy(CA_NID)
            .issuedAt(Instant.now().minus(Duration.ofDays(27)))
            .expiresAt(Instant.now().plus(Duration.ofDays(3)))
            .build());

        var renewed = ca.renew(nid);
        assertEquals(nid, renewed.nid());
        assertNotEquals(serial, renewed.serial());
        assertTrue(ca.verify(nid).valid());
    }

    @Test
    void revokeThenVerifyRevoked() throws Exception {
        var ca = svc(opts(), new InMemoryNipCaStore());
        ca.register("agent", "gone", PUBKEY, List.of(), "{}", null);
        String nid = "urn:nps:agent:ca.example.com:gone";

        var revokeFrame = ca.revoke(nid, "key_compromise");
        assertEquals(nid, revokeFrame.targetNid());
        assertTrue(revokeFrame.signature().startsWith("ed25519:"));

        var r = ca.verify(nid);
        assertFalse(r.valid());
        assertEquals(NipErrorCodes.CERT_REVOKED, r.errorCode());
    }

    @Test
    void groupRegisterAndIssueSessionWithCascadeRevoke() throws Exception {
        var store = new InMemoryNipCaStore();
        var ca = svc(opts(), store);

        var group = ca.registerGroup("group-orch1", PUBKEY,
            List.of("nwp:query", "nwp:stream"), "{}", "user-1", "kid-1", null);
        assertTrue(group.nid().contains("group-orch1"));
        assertEquals("group", group.lineage().get("role"));

        var session = ca.issueSession(group.nid(), PUBKEY, Duration.ofMinutes(30),
            "run", null, null, null);
        assertEquals("session", session.lineage().get("role"));
        assertEquals(group.nid(), session.lineage().get("group_nid"));
        // Session inherits group capabilities and owner lineage.
        assertEquals(List.of("nwp:query", "nwp:stream"), session.capabilities());
        assertEquals("user-1", session.lineage().get("owner_user_id"));

        assertTrue(ca.verify(session.nid()).valid());

        // Cascade: revoking the group revokes the live session and verify rejects on the chain.
        ca.revoke(group.nid(), "cessation_of_operation");
        var sr = ca.verify(session.nid());
        assertFalse(sr.valid());
        assertEquals(NipErrorCodes.CERT_REVOKED, sr.errorCode());
    }

    @Test
    void sessionValidityClamped() throws Exception {
        var o = opts();
        o.sessionMinValidity = Duration.ofMinutes(1);
        o.sessionMaxValidity = Duration.ofHours(24);
        var ca = svc(o, new InMemoryNipCaStore());
        var group = ca.registerGroup("group-clamp", PUBKEY, List.of("a"), "{}", null, null, null);

        // Below min → invalid.
        var tooShort = assertThrows(NipCaException.class,
            () -> ca.issueSession(group.nid(), PUBKEY, Duration.ofSeconds(5), null, null, null, null));
        assertEquals(NipErrorCodes.CA_SESSION_VALIDITY_INVALID, tooShort.errorCode());

        // Above max → invalid.
        var tooLong = assertThrows(NipCaException.class,
            () -> ca.issueSession(group.nid(), PUBKEY, Duration.ofHours(48), null, null, null, null));
        assertEquals(NipErrorCodes.CA_SESSION_VALIDITY_INVALID, tooLong.errorCode());
    }

    @Test
    void sessionCapabilitySubsetEnforced() throws Exception {
        var ca = svc(opts(), new InMemoryNipCaStore());
        var group = ca.registerGroup("group-subset", PUBKEY, List.of("nwp:query"), "{}", null, null, null);

        var ex = assertThrows(NipCaException.class,
            () -> ca.issueSession(group.nid(), PUBKEY, null, null, List.of("nwp:query", "admin:root"), null, null));
        assertEquals(NipErrorCodes.CA_SCOPE_EXPANSION_DENIED, ex.errorCode());
    }

    @Test
    void issueSessionUnderNonGroupRejected() throws Exception {
        var ca = svc(opts(), new InMemoryNipCaStore());
        ca.register("agent", "plain", PUBKEY, List.of("a"), "{}", null);
        var ex = assertThrows(NipCaException.class,
            () -> ca.issueSession("urn:nps:agent:ca.example.com:plain", PUBKEY, null, null, null, null, null));
        assertEquals(NipErrorCodes.CA_PARENT_NOT_GROUP, ex.errorCode());
    }

    // ── RA tiers ─────────────────────────────────────────────────────────────

    @Test
    void raAllowlistTier() throws Exception {
        var o = opts();
        o.enrollmentTier = EnrollmentTier.ALLOWLIST;
        o.enrollmentAllowlistPatterns = List.of("acme-*");
        var ca = svc(o, new InMemoryNipCaStore());
        IEnrollmentPolicy policy = NipCaService.createEnrollmentPolicy(o, null, null);
        assertTrue(policy instanceof AllowlistPolicy);

        // Matches pattern → issued.
        var f = ca.registerWithRa("agent", "acme-1", PUBKEY, List.of(), "{}", null, null, policy);
        assertTrue(f.nid().endsWith(":acme-1"));

        // Does not match → denied.
        var ex = assertThrows(NipCaException.class,
            () -> ca.registerWithRa("agent", "evil-1", PUBKEY, List.of(), "{}", null, null, policy));
        assertEquals(NipErrorCodes.RA_NID_NOT_ALLOWED, ex.errorCode());
    }

    @Test
    void raBootstrapTokenTier() throws Exception {
        var o = opts();
        o.enrollmentTier = EnrollmentTier.BOOTSTRAP_TOKEN;
        var ca = svc(o, new InMemoryNipCaStore());
        IBootstrapTokenStore tokens = new InMemoryBootstrapTokenStore();
        IEnrollmentPolicy policy = NipCaService.createEnrollmentPolicy(o, tokens, null);
        assertTrue(policy instanceof BootstrapTokenPolicy);

        // Missing/invalid token → invalid.
        var noTok = assertThrows(NipCaException.class,
            () -> ca.registerWithRa("agent", "n1", PUBKEY, List.of(), "{}", null, null, policy));
        assertEquals(NipErrorCodes.RA_TOKEN_INVALID, noTok.errorCode());

        String raw = tokens.create("ci", Instant.now().plus(Duration.ofHours(1)));
        // Valid token → issued.
        var f = ca.registerWithRa("agent", "n1", PUBKEY, List.of(), "{}", null, raw, policy);
        assertTrue(f.nid().endsWith(":n1"));

        // Single-use → second attempt with same token expired/consumed.
        var reuse = assertThrows(NipCaException.class,
            () -> ca.registerWithRa("agent", "n2", PUBKEY, List.of(), "{}", null, raw, policy));
        assertEquals(NipErrorCodes.RA_TOKEN_EXPIRED, reuse.errorCode());
    }

    @Test
    void raPendingQueueTier() throws Exception {
        var o = opts();
        o.enrollmentTier = EnrollmentTier.PENDING_QUEUE;
        var ca = svc(o, new InMemoryNipCaStore());
        IPendingStore pending = new InMemoryPendingStore(Duration.ofDays(7));
        IEnrollmentPolicy policy = NipCaService.createEnrollmentPolicy(o, null, pending);
        assertTrue(policy instanceof PendingQueuePolicy);

        var queued = assertThrows(NipRaPendingException.class,
            () -> ca.registerWithRa("agent", "q1", PUBKEY, List.of(), "{}", null, null, policy));
        assertNotNull(queued.pendingId());
        assertEquals(1, pending.pendingCount());
        assertEquals(IPendingStore.PendingStatus.PENDING, pending.get(queued.pendingId()).status());
    }
}
