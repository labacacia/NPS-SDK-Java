// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.NipErrorCodes;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Group-JWS verifier for NPS-CR-0003 §3.5 / §5.1.3 session-issue requests.
 *
 * <p>The flattened JWS shape is
 * {@code { "protected": "<b64url(header)>", "payload": "<b64url(payload)>",
 * "signature": "<b64url(Ed25519 sig)>" }} where the protected header MUST be
 * {@code { "alg": "EdDSA", "kid": "<group_nid>", "nps-purpose": "session-issue" }}
 * and the signature covers the ASCII bytes of {@code protected || "." || payload}
 * per RFC 7515 §3.
 */
public final class NipGroupJws {

    private NipGroupJws() {}

    public static final String EXPECTED_ALG     = "EdDSA";
    public static final String EXPECTED_PURPOSE  = "session-issue";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    /** Flattened JWS object as it appears on the wire. */
    public record FlattenedJws(String protectedHeader, String payload, String signature) {}

    /** Result of a JWS verification: {@code ok} plus decoded payload / kid, or an error code. */
    public record Result(boolean ok, String payloadJson, String kid, String errorCode) {
        static Result fail(String code) { return new Result(false, null, null, code); }
        static Result ok(String payloadJson, String kid) { return new Result(true, payloadJson, kid, null); }
    }

    /**
     * Parses and verifies a flattened JWS against {@code groupPubKey}. On
     * success the {@link Result} carries the decoded UTF-8 payload JSON and the
     * asserted {@code kid}; on failure it carries the matching
     * {@link NipErrorCodes} code.
     */
    public static Result verify(FlattenedJws jws, PublicKey groupPubKey) {
        if (jws == null
                || isBlank(jws.protectedHeader())
                || isBlank(jws.payload())
                || isBlank(jws.signature())) {
            return Result.fail(NipErrorCodes.CA_JWS_INVALID);
        }

        byte[] headerBytes, payloadBytes, sigBytes;
        try {
            headerBytes  = B64URL.decode(jws.protectedHeader());
            payloadBytes = B64URL.decode(jws.payload());
            sigBytes     = B64URL.decode(jws.signature());
        } catch (RuntimeException e) {
            return Result.fail(NipErrorCodes.CA_JWS_INVALID);
        }

        JsonNode header;
        try {
            header = MAPPER.readTree(headerBytes);
        } catch (Exception e) {
            return Result.fail(NipErrorCodes.CA_JWS_INVALID);
        }
        String alg     = text(header, "alg");
        String kid     = text(header, "kid");
        String purpose = text(header, "nps-purpose");
        if (!EXPECTED_ALG.equals(alg)
                || !EXPECTED_PURPOSE.equals(purpose)
                || isBlank(kid)) {
            return Result.fail(NipErrorCodes.CA_JWS_INVALID);
        }

        // RFC 7515 §3 signing input: ASCII(protected) "." ASCII(payload)
        byte[] signingInput =
            (jws.protectedHeader() + "." + jws.payload()).getBytes(StandardCharsets.US_ASCII);
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(groupPubKey);
            verifier.update(signingInput);
            if (!verifier.verify(sigBytes)) return Result.fail(NipErrorCodes.CA_JWS_INVALID);
        } catch (Exception e) {
            return Result.fail(NipErrorCodes.CA_JWS_INVALID);
        }

        return Result.ok(new String(payloadBytes, StandardCharsets.UTF_8), kid);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v != null && v.isTextual()) ? v.asText() : null;
    }

    private static boolean isBlank(String s) { return s == null || s.isEmpty(); }
}
