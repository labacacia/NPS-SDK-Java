// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.acme;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.labacacia.nps.nip.x509.Ed25519PublicKeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * JWS signing helpers for ACME using Ed25519 ({@code alg: "EdDSA"} per RFC 8037).
 *
 * <p>Wire shape (RFC 8555 §6.2 + RFC 7515 flattened JWS JSON serialization):
 * <pre>{@code
 * {
 *   "protected": base64url(JSON({alg, nonce, url, [jwk|kid]})),
 *   "payload":   base64url(JSON(payload)),  // empty string for POST-as-GET
 *   "signature": base64url(Ed25519(protected || "." || payload))
 * }
 * }</pre>
 */
public final class AcmeJws {

    private AcmeJws() {}

    public static final String ALG_EDDSA  = "EdDSA";    // RFC 8037 §3.1
    public static final String KTY_OKP    = "OKP";      // RFC 8037 §2
    public static final String CRV_ED25519 = "Ed25519"; // RFC 8037 §2

    private static final ObjectMapper MAPPER = new ObjectMapper()
        // Avoid pretty-printing — JWS thumbprint canonicalization needs compact JSON.
        .configure(SerializationFeature.INDENT_OUTPUT, false);

    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DEC = Base64.getUrlDecoder();

    // ── DTOs ─────────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Jwk(
        @JsonProperty("kty") String kty,    // "OKP"
        @JsonProperty("crv") String crv,    // "Ed25519"
        @JsonProperty("x")   String x) {}   // base64url(32-byte raw pubkey)

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProtectedHeader(
        @JsonProperty("alg")   String alg,    // always "EdDSA"
        @JsonProperty("nonce") String nonce,
        @JsonProperty("url")   String url,
        @JsonProperty("jwk")   Jwk    jwk,    // newAccount only
        @JsonProperty("kid")   String kid) {} // post-account requests

    public record Envelope(
        @JsonProperty("protected") String protectedB64u,
        @JsonProperty("payload")   String payload,
        @JsonProperty("signature") String signature) {}

    // ── Public API ───────────────────────────────────────────────────────────

    /** Build a JWK from a JCA Ed25519 public key. */
    public static Jwk jwkFromPublicKey(PublicKey pub) {
        byte[] raw = Ed25519PublicKeys.extractRaw(pub);
        return new Jwk(KTY_OKP, CRV_ED25519, URL_ENC.encodeToString(raw));
    }

    /**
     * RFC 7638 §3.2 thumbprint of an Ed25519 JWK: SHA-256 of canonical
     * {@code {"crv":"Ed25519","kty":"OKP","x":"<base64url>"}}, base64url-encoded.
     * Used as the account's stable identifier across ACME requests.
     */
    public static String thumbprint(Jwk jwk) {
        // Canonical form: members in lexicographic order, no whitespace.
        String canonical = "{\"crv\":\"" + jwk.crv()
            + "\",\"kty\":\"" + jwk.kty()
            + "\",\"x\":\"" + jwk.x() + "\"}";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return URL_ENC.encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /**
     * Sign a JWS request.
     *
     * @param header  Protected header (alg/nonce/url/[jwk|kid]).
     * @param payload Java object → JSON via Jackson; may be null for POST-as-GET (RFC 8555 §6.3).
     * @param privKey Account or new-account Ed25519 private key.
     * @return Flattened JSON envelope ready for HTTP body.
     */
    public static Envelope sign(ProtectedHeader header, Object payload, PrivateKey privKey) {
        try {
            String headerB64u = URL_ENC.encodeToString(MAPPER.writeValueAsBytes(header));
            String payloadB64u = payload == null
                ? ""  // POST-as-GET
                : URL_ENC.encodeToString(MAPPER.writeValueAsBytes(payload));

            String signingInput = headerB64u + "." + payloadB64u;
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privKey);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String sigB64u = URL_ENC.encodeToString(signer.sign());

            return new Envelope(headerB64u, payloadB64u, sigB64u);
        } catch (Exception e) {
            throw new RuntimeException("JWS sign failed", e);
        }
    }

    /**
     * Verify a JWS envelope. Returns the parsed protected header on success, or null on failure.
     *
     * @param envelope JWS flattened JSON.
     * @param pub      Public key to verify against.
     */
    public static ProtectedHeader verify(Envelope envelope, PublicKey pub) {
        try {
            String signingInput = envelope.protectedB64u() + "." + envelope.payload();
            byte[] sig = URL_DEC.decode(envelope.signature());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pub);
            verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(sig)) return null;

            byte[] headerJson = URL_DEC.decode(envelope.protectedB64u());
            return MAPPER.readValue(headerJson, ProtectedHeader.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Decode the payload portion of an envelope to a typed Java object. */
    public static <T> T decodePayload(Envelope envelope, Class<T> type) {
        try {
            if (envelope.payload() == null || envelope.payload().isEmpty()) return null;
            byte[] bytes = URL_DEC.decode(envelope.payload());
            return MAPPER.readValue(bytes, type);
        } catch (Exception e) {
            throw new RuntimeException("JWS decode payload failed", e);
        }
    }

    /** Build a {@link PublicKey} from a JWK (assumes OKP/Ed25519). */
    public static PublicKey publicKeyFromJwk(Jwk jwk) {
        if (!KTY_OKP.equals(jwk.kty()) || !CRV_ED25519.equals(jwk.crv())) {
            throw new IllegalArgumentException(
                "JWK is not OKP/Ed25519: kty=" + jwk.kty() + " crv=" + jwk.crv());
        }
        byte[] raw = URL_DEC.decode(jwk.x());
        return Ed25519PublicKeys.fromRaw(raw);
    }
}
