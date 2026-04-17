// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Validates Ed25519 signatures on {@link AnnounceFrame} instances. */
public final class NdpAnnounceValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "ed25519:";

    private final ConcurrentHashMap<String, String> keys = new ConcurrentHashMap<>();

    public void registerPublicKey(String nid, String encodedPubKey) {
        keys.put(nid, encodedPubKey);
    }

    public void removePublicKey(String nid) {
        keys.remove(nid);
    }

    public Map<String, String> knownPublicKeys() {
        return Map.copyOf(keys);
    }

    public NdpAnnounceResult validate(AnnounceFrame frame) {
        String encoded = keys.get(frame.nid());
        if (encoded == null) {
            return NdpAnnounceResult.fail("NDP-ANNOUNCE-NID-MISMATCH",
                "No public key registered for NID: " + frame.nid());
        }

        String sig = frame.signature();
        if (!sig.startsWith(PREFIX)) {
            return NdpAnnounceResult.fail("NDP-ANNOUNCE-SIG-INVALID",
                "Signature must start with 'ed25519:'");
        }

        try {
            String hexKey    = encoded.startsWith(PREFIX) ? encoded.substring(PREFIX.length()) : encoded;
            byte[] pubBytes  = HexFormat.of().parseHex(hexKey);
            byte[] sigBytes  = Base64.getDecoder().decode(sig.substring(PREFIX.length()));

            PublicKey pubKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(pubBytes));

            Map<String, Object> unsigned   = frame.unsignedDict();
            Map<String, Object> sorted     = new TreeMap<>(unsigned);
            byte[] message = MAPPER.writeValueAsBytes(sorted);

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pubKey);
            verifier.update(message);
            boolean valid = verifier.verify(sigBytes);

            if (!valid) return NdpAnnounceResult.fail("NDP-ANNOUNCE-SIG-INVALID",
                "Ed25519 signature verification failed.");
            return NdpAnnounceResult.ok();
        } catch (Exception e) {
            return NdpAnnounceResult.fail("NDP-ANNOUNCE-SIG-INVALID",
                "Ed25519 signature verification failed.");
        }
    }
}
