// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.NpsRegistries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NipIdentityTest {

    @Test void generateCreatesDistinctKeys() {
        var a = NipIdentity.generate();
        var b = NipIdentity.generate();
        assertNotEquals(a.pubKeyString(), b.pubKeyString());
    }

    @Test void pubKeyStringFormat() {
        assertTrue(NipIdentity.generate().pubKeyString().matches("ed25519:[0-9a-f]+"));
    }

    @Test void signVerifyRoundtrip() {
        var id      = NipIdentity.generate();
        var payload = Map.<String,Object>of("action", "test", "nid", "urn:nps:node:a:1");
        var sig     = id.sign(payload);
        assertTrue(sig.startsWith("ed25519:"));
        assertTrue(id.verify(payload, sig));
    }

    @Test void verifyReturnsFalseForTamperedPayload() {
        var id  = NipIdentity.generate();
        var sig = id.sign(Map.of("foo", "bar"));
        assertFalse(id.verify(Map.of("foo", "baz"), sig));
    }

    @Test void verifyReturnsFalseForWrongPrefix() {
        var id = NipIdentity.generate();
        assertFalse(id.verify(Map.of("x", 1), "rsa:abc"));
    }

    @Test void verifyReturnsFalseForCorruptedBase64() {
        var id = NipIdentity.generate();
        assertFalse(id.verify(Map.of("x", 1), "ed25519:!!!garbage"));
    }

    @Test void signIsCanonicalKeyOrderIndependent() {
        var id  = NipIdentity.generate();
        var p1  = Map.<String,Object>of("b", 2, "a", 1);
        var p2  = Map.<String,Object>of("a", 1, "b", 2);
        assertEquals(id.sign(p1), id.sign(p2));
    }

    @Test void saveAndLoadRoundtrip(@TempDir Path dir) throws Exception {
        var id   = NipIdentity.generate();
        var path = dir.resolve("key.json");
        id.save(path, "test-pass");
        var loaded  = NipIdentity.load(path, "test-pass");
        assertEquals(id.pubKeyString(), loaded.pubKeyString());
        var payload = Map.<String,Object>of("hello", "world");
        assertTrue(loaded.verify(payload, id.sign(payload)));
    }

    @Test void loadWithWrongPassphraseThrows(@TempDir Path dir) throws IOException {
        var id   = NipIdentity.generate();
        var path = dir.resolve("key.json");
        id.save(path, "correct-pass");
        assertThrows(Exception.class, () -> NipIdentity.load(path, "wrong-pass"));
    }

    // ── Frame round-trips ────────────────────────────────────────────────────

    @Test void identFrameRoundtrip() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var meta  = Map.<String,Object>of("issuer", "urn:nps:ca:root", "issuedAt", "2026-01-01T00:00:00Z");
        var frame = new IdentFrame("urn:nps:node:a:1", "ed25519:aabbcc", meta, "ed25519:sig");
        var out   = (IdentFrame) codec.decode(codec.encode(frame));
        assertEquals("urn:nps:node:a:1", out.nid());
        assertNull(out.unsignedDict().get("signature"));
    }

    @Test void trustFrameRoundtrip() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var frame = new TrustFrame("urn:nps:node:a:1", "urn:nps:node:b:1",
            List.of("nwp/query"), "2027-01-01T00:00:00Z", "ed25519:sig");
        var out   = (TrustFrame) codec.decode(codec.encode(frame));
        assertEquals("urn:nps:node:b:1", out.subjectNid());
    }

    @Test void revokeFrameRoundtrip() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var frame = new RevokeFrame("urn:nps:node:a:1", "compromised", "2026-06-01T00:00:00Z");
        var out   = (RevokeFrame) codec.decode(codec.encode(frame));
        assertEquals("compromised", out.reason());
        assertEquals("2026-06-01T00:00:00Z", out.revokedAt());
    }

    @Test void revokeFrameOptionalFieldsNull() {
        var codec = new NpsFrameCodec(NpsRegistries.createFull());
        var frame = new RevokeFrame("urn:nps:node:a:1");
        var out   = (RevokeFrame) codec.decode(codec.encode(frame));
        assertNull(out.reason());
        assertNull(out.revokedAt());
    }
}
