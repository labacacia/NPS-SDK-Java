// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.NipErrorCodes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Flattened group-JWS verification (NPS-CR-0003 §3.5). */
class NipGroupJwsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private static String b64url(byte[] b) { return B64URL.encodeToString(b); }

    private static NipGroupJws.FlattenedJws sign(KeyPair kp, Map<String, Object> header, Map<String, Object> payload) throws Exception {
        String prot = b64url(M.writeValueAsBytes(header));
        String pay = b64url(M.writeValueAsBytes(payload));
        byte[] signingInput = (prot + "." + pay).getBytes(StandardCharsets.US_ASCII);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(signingInput);
        return new NipGroupJws.FlattenedJws(prot, pay, b64url(s.sign()));
    }

    @Test
    void verifiesGoodJws() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var header = Map.<String, Object>of("alg", "EdDSA", "kid", "urn:nps:agent:ca:group-1", "nps-purpose", "session-issue");
        var payload = Map.<String, Object>of("session_pub_key", "ed25519:xyz", "iat", 123L);
        var jws = sign(kp, header, payload);

        var r = NipGroupJws.verify(jws, kp.getPublic());
        assertTrue(r.ok());
        assertEquals("urn:nps:agent:ca:group-1", r.kid());
        assertTrue(r.payloadJson().contains("session_pub_key"));
    }

    @Test
    void rejectsWrongPurpose() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var header = Map.<String, Object>of("alg", "EdDSA", "kid", "g", "nps-purpose", "other");
        var jws = sign(kp, header, Map.of("iat", 1L));
        var r = NipGroupJws.verify(jws, kp.getPublic());
        assertFalse(r.ok());
        assertEquals(NipErrorCodes.CA_JWS_INVALID, r.errorCode());
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var header = Map.<String, Object>of("alg", "EdDSA", "kid", "g", "nps-purpose", "session-issue");
        var jws = sign(signer, header, Map.of("iat", 1L));
        // Verify against a different key → fails.
        var r = NipGroupJws.verify(jws, other.getPublic());
        assertFalse(r.ok());
        assertEquals(NipErrorCodes.CA_JWS_INVALID, r.errorCode());
    }
}
