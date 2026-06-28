// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.NpsRegistries;
import com.labacacia.nps.nip.NipIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NdpTest {

    private static final String NID  = "urn:nps:node:example.com:data";
    private static final List<Map<String,Object>> ADDRS = List.of(
        Map.of("host", "example.com", "port", 17433, "protocol", "nwp"));
    private static final List<String> CAPS = List.of("nwp/query", "nwp/stream");

    private AnnounceFrame makeAnnounce(NipIdentity id, int ttl) {
        var ts = "2026-01-01T00:00:00Z";
        // Build a temporary frame to get its signed canonical body.
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
        assertNull(back.unsignedDict().get("node_type"));
        assertEquals(60_000, back.unsignedDict().get("heartbeat_interval_ms"));
    }

    @Test void announceLivenessWireOnly() {
        // NDP v0.9 health/last_seen: on the wire, but NOT in the signed canonical form.
        var f = new AnnounceFrame(NID, ADDRS, CAPS, 300, "t", "sig", null, 60_000,
            "draining", "2026-06-13T00:00:00Z");
        var d = f.toDict();
        assertEquals("draining", d.get("health"));
        assertEquals("2026-06-13T00:00:00Z", d.get("last_seen"));
        assertNull(f.unsignedDict().get("health"));
        assertNull(f.unsignedDict().get("last_seen"));
        var back = AnnounceFrame.fromDict(d);
        assertEquals("draining", back.health());
        assertEquals("2026-06-13T00:00:00Z", back.lastSeen());
    }

    @Test void announceOptionalFieldsRoundtrip() {
        var endpoint = Map.<String,Object>of("host", "10.0.0.5", "port", 17440, "protocol", "nwp");
        var f = new AnnounceFrame(
            NID, ADDRS, CAPS, 300, "t", "sig", "memory",
            List.of("memory", "bridge"), "urn:nps:node:anchor.example.com:main",
            "spawnspec:abc", List.of("http", "mcp"), "resident",
            endpoint, 60_000, null, null);
        var d = f.toDict();
        assertEquals(List.of("memory", "bridge"), d.get("node_roles"));
        assertEquals("urn:nps:node:anchor.example.com:main", d.get("cluster_anchor"));
        assertEquals("spawnspec:abc", d.get("spawn_spec_ref"));
        assertEquals(List.of("http", "mcp"), d.get("bridge_protocols"));
        assertEquals("resident", d.get("activation_mode"));
        assertEquals(endpoint, d.get("activation_endpoint"));
        assertEquals(endpoint, f.unsignedDict().get("activation_endpoint"));
        assertEquals(60_000, f.unsignedDict().get("heartbeat_interval_ms"));

        var back = AnnounceFrame.fromDict(d);
        assertEquals(List.of("memory", "bridge"), back.nodeRoles());
        assertEquals("urn:nps:node:anchor.example.com:main", back.clusterAnchor());
        assertEquals("spawnspec:abc", back.spawnSpecRef());
        assertEquals(List.of("http", "mcp"), back.bridgeProtocols());
        assertEquals("resident", back.activationMode());
        assertEquals(endpoint, back.activationEndpoint());
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
        // GraphFrame was rewritten to the §3.3 topology-snapshot format
        // (graph_id / nodes / edges / ttl).
        var f    = new GraphFrame("g1", List.of(), List.of(), 300);
        var back = GraphFrame.fromDict(f.toDict());
        assertEquals("g1", back.graphId());
        assertEquals(300, back.ttl());
    }

    @Test void graphFrameRejectsTooLarge() {
        var nodes = new ArrayList<GraphNode>();
        for (int i = 0; i < 257; i++) {
            nodes.add(new GraphNode("urn:nps:node:example.com:" + i));
        }

        var ex = assertThrows(IllegalArgumentException.class,
            () -> new GraphFrame("too-big", nodes, List.of(), 60));
        assertTrue(ex.getMessage().contains(NdpErrorCodes.NDP_GRAPH_TOO_LARGE));
    }

    @Test void graphFrameRejectsInvalidEdges() {
        var nodes = List.of(new GraphNode("urn:nps:node:example.com:a"));

        var selfEdge = assertThrows(IllegalArgumentException.class,
            () -> new GraphFrame("self-edge", nodes, List.of(new GraphEdge(nodes.get(0).nid(), nodes.get(0).nid())), 60));
        assertTrue(selfEdge.getMessage().contains(NdpErrorCodes.NDP_GRAPH_INVALID));

        var missingEndpoint = assertThrows(IllegalArgumentException.class,
            () -> new GraphFrame("missing-edge", nodes,
                List.of(new GraphEdge(nodes.get(0).nid(), "urn:nps:node:example.com:missing")), 60));
        assertTrue(missingEndpoint.getMessage().contains(NdpErrorCodes.NDP_GRAPH_INVALID));
    }

    @Test void federationForwardedByHelpers() {
        var header = "urn:nps:agent:registry-a.example.com:r1, urn:nps:agent:registry-b.example.com:r2";
        assertEquals(List.of(
            "urn:nps:agent:registry-a.example.com:r1",
            "urn:nps:agent:registry-b.example.com:r2"), NdpFederation.parseForwardedBy(header));

        var next = NdpFederation.appendForwardedBy("urn:nps:agent:registry-c.example.com:r3", header);
        assertTrue(next.isPresent());
        assertTrue(next.get().contains("registry-c"));

        var loop = assertThrows(IllegalArgumentException.class,
            () -> NdpFederation.appendForwardedBy("urn:nps:agent:registry-b.example.com:r2", header));
        assertTrue(loop.getMessage().contains(NdpErrorCodes.NDP_FEDERATION_LOOP));

        var dropped = NdpFederation.appendForwardedBy(
            "urn:nps:agent:registry-d.example.com:r4",
            header + ", urn:nps:agent:registry-c.example.com:r3");
        assertTrue(dropped.isEmpty());
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
        assertEquals("NDP-ANNOUNCE-SIGNATURE-INVALID", v.validate(f).errorCode());
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

    // ── NpsDnsTxt.parseNpsTxtRecord ───────────────────────────────────────────

    @Test void parseNpsTxtRecord_validFullRecord() {
        var result = NpsDnsTxt.parseNpsTxtRecord(
            "v=nps1 type=memory port=17434 nid=urn:nps:node:api.example.com:products fp=sha256:a3f9",
            "api.example.com");
        assertNotNull(result);
        assertEquals("api.example.com", result.host());
        assertEquals(17434, result.port());
        assertEquals(NpsDnsTxt.DEFAULT_TTL, result.ttl());
    }

    @Test void parseNpsTxtRecord_missingV_returnsNull() {
        assertNull(NpsDnsTxt.parseNpsTxtRecord(
            "type=memory port=17433 nid=urn:nps:node:api.example.com:products",
            "api.example.com"));
    }

    @Test void parseNpsTxtRecord_wrongV_returnsNull() {
        assertNull(NpsDnsTxt.parseNpsTxtRecord(
            "v=nps2 nid=urn:nps:node:api.example.com:products",
            "api.example.com"));
    }

    @Test void parseNpsTxtRecord_missingNid_returnsNull() {
        assertNull(NpsDnsTxt.parseNpsTxtRecord(
            "v=nps1 type=memory port=17433",
            "api.example.com"));
    }

    @Test void parseNpsTxtRecord_defaultPort() {
        var result = NpsDnsTxt.parseNpsTxtRecord(
            "v=nps1 nid=urn:nps:node:api.example.com:products",
            "api.example.com");
        assertNotNull(result);
        assertEquals(17433, result.port());
    }

    // ── NpsDnsTxt.extractHost ─────────────────────────────────────────────────

    @Test void extractHost_validUrl() {
        assertEquals("api.example.com", NpsDnsTxt.extractHost("nwp://api.example.com/products"));
    }

    @Test void extractHost_invalidUrl_returnsNull() {
        assertNull(NpsDnsTxt.extractHost("http://api.example.com/products"));
        assertNull(NpsDnsTxt.extractHost(null));
        assertNull(NpsDnsTxt.extractHost(""));
        assertNull(NpsDnsTxt.extractHost("nwp://"));
    }

    // ── resolveViaDns ─────────────────────────────────────────────────────────

    @Test void resolveViaDns_usesRegistryFirst() throws Exception {
        var reg = new InMemoryNdpRegistry();
        var id  = NipIdentity.generate();
        reg.announce(makeAnnounce(id, 300));

        DnsTxtLookup mockLookup = mock(DnsTxtLookup.class);
        var result = reg.resolveViaDns("nwp://example.com/data", mockLookup);

        assertNotNull(result);
        assertEquals("example.com", result.host());
        assertEquals(17433, result.port());
        // DNS must NOT be called when registry hits
        verify(mockLookup, never()).lookup(anyString());
    }

    @Test void resolveViaDns_fallbackToDns() throws Exception {
        var reg = new InMemoryNdpRegistry(); // empty registry

        DnsTxtLookup mockLookup = mock(DnsTxtLookup.class);
        when(mockLookup.lookup("_nps-node.api.example.com"))
            .thenReturn(List.of("v=nps1 nid=urn:nps:node:api.example.com:products port=17434"));

        var result = reg.resolveViaDns("nwp://api.example.com/products", mockLookup);

        assertNotNull(result);
        assertEquals("api.example.com", result.host());
        assertEquals(17434, result.port());
        assertEquals(NpsDnsTxt.DEFAULT_TTL, result.ttl());
        verify(mockLookup).lookup("_nps-node.api.example.com");
    }

    @Test void resolveViaDns_invalidTxt_returnsNull() throws Exception {
        var reg = new InMemoryNdpRegistry();

        DnsTxtLookup mockLookup = mock(DnsTxtLookup.class);
        when(mockLookup.lookup("_nps-node.api.example.com"))
            .thenReturn(List.of("v=nps2 nid=urn:nps:node:api.example.com:products"));

        assertNull(reg.resolveViaDns("nwp://api.example.com/products", mockLookup));
    }
}
