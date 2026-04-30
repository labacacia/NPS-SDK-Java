// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.x509;

import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * Helpers for converting between raw 32-byte Ed25519 public keys and JCA / BC representations.
 * <p>
 * The Java SDK's {@link com.labacacia.nps.nip.NipIdentity} ships pubkeys as
 * {@code "ed25519:<hex>"}; X.509 building/verifying needs a SubjectPublicKeyInfo
 * structure (RFC 8410). This class is the bridge.
 */
public final class Ed25519PublicKeys {

    private Ed25519PublicKeys() {}

    /**
     * Extract the raw 32-byte Ed25519 public key bytes from a JCA {@link PublicKey}.
     * <p>
     * JCA Ed25519's {@code getEncoded()} returns a 44-byte X.509 SubjectPublicKeyInfo
     * structure: a 12-byte prefix (SEQUENCE / AlgorithmIdentifier with OID 1.3.101.112)
     * followed by the 32-byte raw key inside a BIT STRING.
     */
    public static byte[] extractRaw(PublicKey pubKey) {
        SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(pubKey.getEncoded());
        return spki.getPublicKeyData().getBytes();
    }

    /** Build a {@link SubjectPublicKeyInfo} from a raw 32-byte Ed25519 public key. */
    public static SubjectPublicKeyInfo fromRawSpki(byte[] rawPubKey) {
        if (rawPubKey == null || rawPubKey.length != 32) {
            throw new IllegalArgumentException(
                "Ed25519 raw public key must be exactly 32 bytes (got "
                + (rawPubKey == null ? "null" : rawPubKey.length) + ")");
        }
        return new SubjectPublicKeyInfo(
            new AlgorithmIdentifier(NpsX509Oids.ED25519),
            rawPubKey);
    }

    /** Build a JCA {@link PublicKey} from a raw 32-byte Ed25519 public key. */
    public static PublicKey fromRaw(byte[] rawPubKey) {
        try {
            byte[] spkiDer = fromRawSpki(rawPubKey).getEncoded("DER");
            return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(spkiDer));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to construct Ed25519 PublicKey from raw bytes", e);
        }
    }
}
