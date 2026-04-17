// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * NIP Ed25519 identity — generate, sign, verify, and encrypted key file persistence.
 * Uses Java 15+ native Ed25519 via {@code java.security.KeyPairGenerator}.
 */
public final class NipIdentity {

    private static final int    PBKDF2_ITERS  = 600_000;
    private static final int    SALT_BYTES    = 16;
    private static final int    IV_BYTES      = 12;
    private static final int    KEY_BYTES     = 32;
    private static final int    GCM_TAG_BITS  = 128;
    private static final String KEY_ALG       = "Ed25519";
    private static final int    KEY_FILE_VER  = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrivateKey privKey;
    private final PublicKey  pubKey;

    private NipIdentity(PrivateKey privKey, PublicKey pubKey) {
        this.privKey = privKey;
        this.pubKey  = pubKey;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static NipIdentity generate() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALG);
            KeyPair kp = kpg.generateKeyPair();
            return new NipIdentity(kp.getPrivate(), kp.getPublic());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available", e);
        }
    }

    public static NipIdentity load(Path path, String passphrase) throws IOException {
        try {
            @SuppressWarnings("unchecked")
            Map<String,String> env = MAPPER.readValue(path.toFile(), Map.class);
            byte[] salt  = HexFormat.of().parseHex(env.get("salt"));
            byte[] iv    = HexFormat.of().parseHex(env.get("iv"));
            byte[] ct    = HexFormat.of().parseHex(env.get("ciphertext"));
            byte[] pk    = HexFormat.of().parseHex(env.get("pub_key"));

            byte[] dk = deriveKey(passphrase, salt);
            byte[] privBytes = aesDecrypt(dk, iv, ct);

            KeyFactory kf      = KeyFactory.getInstance(KEY_ALG);
            PrivateKey privKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            PublicKey  pubKey  = kf.generatePublic(new X509EncodedKeySpec(pk));
            return new NipIdentity(privKey, pubKey);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to load NIP key file: " + e.getMessage(), e);
        }
    }

    public void save(Path path, String passphrase) throws IOException {
        try {
            byte[] salt      = new byte[SALT_BYTES];
            byte[] iv        = new byte[IV_BYTES];
            new SecureRandom().nextBytes(salt);
            new SecureRandom().nextBytes(iv);

            byte[] dk         = deriveKey(passphrase, salt);
            byte[] privBytes  = privKey.getEncoded();
            byte[] ct         = aesEncrypt(dk, iv, privBytes);
            byte[] pubBytes   = pubKey.getEncoded();

            HexFormat hex = HexFormat.of();
            Map<String,Object> env = new LinkedHashMap<>();
            env.put("version",    KEY_FILE_VER);
            env.put("salt",       hex.formatHex(salt));
            env.put("iv",         hex.formatHex(iv));
            env.put("ciphertext", hex.formatHex(ct));
            env.put("pub_key",    hex.formatHex(pubBytes));
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), env);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to save NIP key file: " + e.getMessage(), e);
        }
    }

    // ── Signing ───────────────────────────────────────────────────────────────

    /** Sign a dict payload (canonical JSON, keys sorted). Returns {@code ed25519:<base64>}. */
    public String sign(Map<String, Object> payload) {
        try {
            byte[] message  = canonical(payload);
            Signature signer = Signature.getInstance(KEY_ALG);
            signer.initSign(privKey);
            signer.update(message);
            byte[] sig = signer.sign();
            return "ed25519:" + Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 sign failed", e);
        }
    }

    /** Verify a signature string against a dict payload. */
    public boolean verify(Map<String, Object> payload, String signature) {
        if (!signature.startsWith("ed25519:")) return false;
        try {
            byte[] sigBytes = Base64.getDecoder().decode(signature.substring("ed25519:".length()));
            byte[] message  = canonical(payload);
            Signature verifier = Signature.getInstance(KEY_ALG);
            verifier.initVerify(pubKey);
            verifier.update(message);
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /** Public key as {@code ed25519:<hex>}. */
    public String pubKeyString() {
        return "ed25519:" + HexFormat.of().formatHex(pubKey.getEncoded());
    }

    public PublicKey pubKey() { return pubKey; }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static byte[] canonical(Map<String, Object> payload) {
        // Sort keys and serialise as compact JSON
        Map<String, Object> sorted = new TreeMap<>(payload);
        try {
            return MAPPER.writeValueAsBytes(sorted);
        } catch (Exception e) {
            throw new RuntimeException("Canonical serialisation failed", e);
        }
    }

    private static byte[] deriveKey(String passphrase, byte[] salt) throws Exception {
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERS, KEY_BYTES * 8);
        return skf.generateSecret(spec).getEncoded();
    }

    private static byte[] aesEncrypt(byte[] key, byte[] iv, byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
            new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plain);
    }

    private static byte[] aesDecrypt(byte[] key, byte[] iv, byte[] ct) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
            new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ct);
    }
}
