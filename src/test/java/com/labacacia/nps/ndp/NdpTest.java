// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.NpsRegistries;
import com.labacacia.nps.nip.NipIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NdpTest {

    private static final String NID  = "urn:nps:node:example.com:data";
    private static final List<Map<String,Object>> ADDRS = List.of(
        Map.of("host", "example.com", "port", 17433, "protocol", "nwp"));
    private static final List<String> CAPS = List.of("nwp/query", "nwp/stream");

    private AnnounceFrame makeAnnounce(NipIdentity id, int ttl) {
        var ts = "2026-01-01T00:00:00Z";
        // Build a temporary frame to get its unsignedDict (includes node_type:null)
        var tmp     = new AnnounceFrame(NID, ADDRS, CAPS, ttl, ts, "placeholder", null);
        var sig     = id.sign(tmp.unsignedDict());
        return new AnnounceFrame(NID, ADDRS, CAPS, ttl, ts, sig, null);
    }

    // ── AnnounceFrame ─────────────────────────────────────────────────────────

    @Test void announceFrameRoundtrip() {
        var id    = NipIdentity.generate();
        var frame = makeAnnounce(id, 300);
        var back  = AnnounceFrame.fromDict(frame.toDict());
        assertEquals(NID, back.nid());
        assertEquals(300, back.ttl());
        assertNull(back.unsignedDict().get("signature"));
    }

    @Test void announceFrameCodecRoundtrip() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var id    = NipIdentity.generate();
        var frame = makeAnnounce(id, 300);
        var out   = (AnnounceFrame) codec.decode(codec.encode(frame));
        assertEquals(NID, out.nid());
    }

    // ── ResolveFrame ─────────────────────────────────────────────────────────

    @Test void resolveFrameRoundtrip() {
        var f    = new ResolveFrame("nwp://example.com/data", "urn:nps:node:a:1",
            Map.of("host", "example.com", "port", 17433, "ttl", 300));
        var back = ResolveFrame.fromDict(f.toDict());
        assertEquals("nwp://example.com/data", back.target());
        assertNotNull(back.resolved());
    }

    @Test void resolveFrameOptionalFieldsNull() {
        var f    = new ResolveFrame("nwp://example.com/data");
        var back = ResolveFrame.fromDict(f.toDict());
        assertNull(back.requesterNid());
        assertNull(back.resolved());
    }

    // ── GraphFrame ────────────────────────────────────────────────────────────

    @Test void graphFrameRoundtrip() {
        var nodes = List.of(Map.<String,Object>of("nid", NID, "addresses", ADDRS));
        var f     = new GraphFrame(1, true, nodes, null);
        var back  = GraphFrame.fromDict(f.toDict());
        assertEquals(1, back.seq());
        assertTrue(back.initialSync());
        assertNull(back.patch());
    }

    // ── InMemoryNdpRegistry ───────────────────────────────────────────────────

    @Test void announceAndGetByNid() {
        var reg = new InMemoryNdpRegistry();
        var id  = NipIdentity.generate();
        var f   = makeAnnounce(id, 300);
        reg.announce(f);
        assertSame(f, reg.getByNid(NID));
    }

    @Test void getByNidReturnsNullForUnknown() {
        assertNull(new InMemoryNdpRegistry().getByNid("urn:nps:node:x:y"));
    }

    @Test void ttlZeroDeregisters() {
        var reg = new InMemoryNdpRegistry();
        var id  = NipIdentity.generate();
        reg.announce(makeAnnounce(id, 300));
        reg.announce(makeAnnounce(id, 0));
        assertNull(reg.getByNid(NID));
    }

    @Test void ttlExpiry() {
        var reg = new InMemoryNdpRegistry();
        long[] now = {0};
        reg.clock = () -> now[0];
        var id = NipIdentity.generate();
        reg.announce(makeAnnounce(id, 10));
        now[0] = 11_000;
        assertNull(reg.getByNid(NID));
    }

    @Test void resolveReturnsMatchingEntry() {
        var reg = new InMemoryNdpRegistry();
        var id  = NipIdentity.generate();
        reg.announce(makeAnnounce(id, 300));
        var r = reg.resolve("nwp://example.com/data/sub");
        assertNotNull(r);
        assertEquals("example.com", r.host());
        assertEquals(17433, r.port());
    }

    @Test void resolveReturnsNullForNonMatch() {
        var reg = new InMemoryNdpRegistry();
        reg.announce(makeAnnounce(NipIdentity.generate(), 300));
        assertNull(reg.resolve("nwp://other.com/data"));
    }

    @Test void getAllReturnsActiveEntries() {
        var reg = new InMemoryNdpRegistry();
        long[] now = {0};
        reg.clock = () -> now[0];
        var id1  = NipIdentity.generate();
        var id2  = NipIdentity.generate();
        var nid1 = "urn:nps:node:a.com:x";
        var nid2 = "urn:nps:node:b.com:y";
        var ts   = "2026-01-01T00:00:00Z";
        var f1   = new AnnounceFrame(nid1, ADDRS, CAPS, 100, ts, "ph", null);
        var f2   = new AnnounceFrame(nid2, ADDRS, CAPS, 1,   ts, "ph", null);
        reg.announce(new AnnounceFrame(nid1, ADDRS, CAPS, 100, ts, id1.sign(f1.unsignedDict()), null));
        reg.announce(new AnnounceFrame(nid2, ADDRS, CAPS, 1,   ts, id2.sign(f2.unsignedDict()), null));
        now[0] = 2_000;
        var all = reg.getAll();
        assertEquals(1, all.size());
        assertEquals(nid1, all.get(0).nid());
    }

    // ── nwpTargetMatchesNid ───────────────────────────────────────────────────

    @Test void exactMatch()       { assertTrue(InMemoryNdpRegistry.nwpTargetMatchesNid(NID, "nwp://example.com/data")); }
    @Test void subPathMatch()     { assertTrue(InMemoryNdpRegistry.nwpTargetMatchesNid(NID, "nwp://example.com/data/sub")); }
    @Test void differentAuthority(){ assertFalse(InMemoryNdpRegistry.nwpTargetMatchesNid(NID, "nwp://other.com/data")); }
    @Test void siblingPath()      { assertFalse(InMemoryNdpRegistry.nwpTargetMatchesNid(NID, "nwp://example.com/dataset")); }
    @Test void invalidNid()       { assertFalse(InMemoryNdpRegistry.nwpTargetMatchesNid("invalid", "nwp://example.com/data")); }
    @Test void nonNwpTarget()     { assertFalse(InMemoryNdpRegistry.nwpTargetMatchesNid(NID, "http://example.com/data")); }
    @Test void noSlashInTarget()  { assertFalse(InMemoryNdpRegistry.nwpTargetMatchesNid(NID, "nwp://example.com")); }

    // ── NdpAnnounceValidator ──────────────────────────────────────────────────

    @Test void validatorFailsWhenNoKeyRegistered() {
        var v = new NdpAnnounceValidator();
        var r = v.validate(makeAnnounce(NipIdentity.generate(), 300));
        assertFalse(r.isValid());
        assertEquals("NDP-ANNOUNCE-NID-MISMATCH", r.errorCode());
    }

    @Test void validatesCorrectlySignedFrame() {
        var id = NipIdentity.generate();
        var v  = new NdpAnnounceValidator();
        v.registerPublicKey(NID, id.pubKeyString());
        var f  = makeAnnounce(id, 300);
        assertTrue(v.validate(f).isValid());
    }

    @Test void rejectsWrongSignaturePrefix() {
        var id = NipIdentity.generate();
        var v  = new NdpAnnounceValidator();
        v.registerPublicKey(NID, id.pubKeyString());
        var f = new AnnounceFrame(NID, ADDRS, CAPS, 300, "2026-01-01T00:00:00Z", "rsa:invalid", null);
        assertFalse(v.validate(f).isValid());
        assertEquals("NDP-ANNOUNCE-SIG-INVALID", v.validate(f).errorCode());
    }

    @Test void removePublicKeyDeregisters() {
        var id = NipIdentity.generate();
        var v  = new NdpAnnounceValidator();
        v.registerPublicKey(NID, id.pubKeyString());
        v.removePublicKey(NID);
        assertFalse(v.knownPublicKeys().containsKey(NID));
    }

    // ── NdpAnnounceResult ─────────────────────────────────────────────────────

    @Test void resultOk()   { assertTrue(NdpAnnounceResult.ok().isValid()); }
    @Test void resultFail() {
        var r = NdpAnnounceResult.fail("CODE", "msg");
        assertFalse(r.isValid());
        assertEquals("CODE", r.errorCode());
        assertEquals("msg",  r.message());
    }
}
