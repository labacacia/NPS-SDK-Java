// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.reputation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.NipCanonicalJson;
import com.labacacia.nps.nip.NipErrorCodes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * HTTP client for an NPS-RFC-0004 reputation log server.
 *
 * <p>All network I/O is synchronous (blocking). Construct with a base URL such as
 * {@code https://rep.example.com} — no trailing slash.
 *
 * <p>JSON (de)serialization uses Jackson (already a project dependency) via the same
 * canonical approach as {@link com.labacacia.nps.nip.NipIdentity} — top-level keys
 * sorted, compact encoding.
 */
public final class ReputationLogClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
        new TypeReference<>() {};

    private final String     baseUrl;
    private final HttpClient http;

    public ReputationLogClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http    = HttpClient.newHttpClient();
    }

    /** For testing — supply a custom {@link HttpClient}. */
    ReputationLogClient(String baseUrl, HttpClient http) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http    = http;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submit a signed entry to the log ({@code POST /v1/log/entries}).
     *
     * @return the echoed/sequenced entry as returned by the server
     */
    public ReputationLogEntry submit(ReputationLogEntry entry) throws ReputationLogException {
        byte[] body = serializeEntry(entry);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/log/entries"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        Map<String, Object> resp = doRequest(req);
        return ReputationLogEntry.fromMap(resp);
    }

    /**
     * Query log entries for a subject NID ({@code GET /v1/log/entries?nid=&amp;since=}).
     *
     * @param nid      subject NID to filter by (required)
     * @param sinceSeq optional lower-bound sequence number (inclusive), pass {@code null} to omit
     * @return list of matching entries in ascending sequence order
     */
    public List<ReputationLogEntry> query(String nid, Long sinceSeq) throws ReputationLogException {
        StringBuilder url = new StringBuilder(baseUrl).append("/v1/log/entries?nid=").append(nid);
        if (sinceSeq != null) url.append("&since=").append(sinceSeq);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url.toString()))
            .GET()
            .build();
        List<Map<String, Object>> list = doListRequest(req);
        List<ReputationLogEntry> result = new ArrayList<>(list.size());
        for (Map<String, Object> m : list) {
            result.add(ReputationLogEntry.fromMap(m));
        }
        return result;
    }

    /**
     * Fetch the current Signed Tree Head ({@code GET /v1/log/sth}).
     */
    public SignedTreeHead getSth() throws ReputationLogException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/log/sth"))
            .GET()
            .build();
        return SignedTreeHead.fromMap(doRequest(req));
    }

    /**
     * Fetch a Merkle inclusion proof for the given sequence number
     * ({@code GET /v1/log/proof?seq=&lt;seq&gt;}).
     */
    public InclusionProof getProof(long seq) throws ReputationLogException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/log/proof?seq=" + seq))
            .GET()
            .build();
        return InclusionProof.fromMap(doRequest(req));
    }

    /**
     * Fetch the gossip Signed Tree Head ({@code GET /v1/log/gossip/sth}).
     */
    public SignedTreeHead getGossipSth() throws ReputationLogException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/log/gossip/sth"))
            .GET()
            .build();
        return SignedTreeHead.fromMap(doRequest(req));
    }

    // ── Signing / verification ────────────────────────────────────────────────

    /**
     * Sign a {@link ReputationLogEntry} with an Ed25519 private key.
     *
     * <p>The payload is the canonical JSON of {@link ReputationLogEntry#toMap()} with
     * the {@code "signature"} key removed, sorted at the top level (matching
     * {@link NipCanonicalJson}).
     *
     * @return a new entry equal to {@code entry} but with {@code signature} set to
     *         {@code "ed25519:<base64>"}
     */
    public static ReputationLogEntry signEntry(PrivateKey privKey, ReputationLogEntry entry) {
        try {
            Map<String, Object> payload = entry.toMap();
            payload.remove("signature");
            byte[] message = NipCanonicalJson.canonicalize(payload);

            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privKey);
            signer.update(message);
            byte[] sigBytes = signer.sign();
            String sig = "ed25519:" + Base64.getEncoder().encodeToString(sigBytes);

            return ReputationLogEntry.builder()
                .v(entry.getV())
                .logId(entry.getLogId())
                .seq(entry.getSeq())
                .timestamp(entry.getTimestamp())
                .subjectNid(entry.getSubjectNid())
                .incident(entry.getIncident())
                .incidentRaw(entry.getIncidentRaw())
                .severity(entry.getSeverity())
                .window(entry.getWindow())
                .observation(entry.getObservation())
                .evidenceRef(entry.getEvidenceRef())
                .evidenceSha256(entry.getEvidenceSha256())
                .issuerNid(entry.getIssuerNid())
                .signature(sig)
                .build();
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 sign failed", e);
        }
    }

    /**
     * Verify the Ed25519 signature on a {@link ReputationLogEntry}.
     *
     * @return {@code true} if the signature is valid
     */
    public static boolean verifyEntry(PublicKey pubKey, ReputationLogEntry entry) {
        String sig = entry.getSignature();
        if (sig == null || !sig.startsWith("ed25519:")) return false;
        try {
            byte[] sigBytes = Base64.getDecoder().decode(sig.substring("ed25519:".length()));
            Map<String, Object> payload = entry.toMap();
            payload.remove("signature");
            byte[] message = NipCanonicalJson.canonicalize(payload);

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pubKey);
            verifier.update(message);
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Merkle inclusion verification ─────────────────────────────────────────

    /**
     * Verify a Merkle inclusion proof against a Signed Tree Head.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Compute the leaf hash: {@code SHA-256(0x00 || leafJson)} where
     *       {@code leafJson} is the canonical JSON of the entry including its
     *       {@code signature} field (top-level keys sorted).</li>
     *   <li>Compare the computed leaf hash to {@code proof.leafHash}.</li>
     *   <li>Fold the audit path: for each step {@code i}, combine the current hash
     *       with the sibling using the position bit to determine order, then
     *       {@code SHA-256(0x01 || left || right)}.</li>
     *   <li>Compare the resulting root hash to {@code sth.sha256RootHash}.</li>
     * </ol>
     * Both {@code leafHash} and audit path entries and the STH root hash are
     * Base64URL-encoded (no padding) SHA-256 digests.
     *
     * @return {@code true} if the proof is valid
     */
    public static boolean verifyInclusion(InclusionProof proof, SignedTreeHead sth,
                                          ReputationLogEntry entry) {
        try {
            // 1. Build canonical leaf JSON (with signature)
            Map<String, Object> leafMap = entry.toMap();
            byte[] leafJson = NipCanonicalJson.canonicalize(leafMap);

            // 2. Compute leaf hash: 0x00 || leafJson
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((byte) 0x00);
            sha256.update(leafJson);
            byte[] nodeHash = sha256.digest();

            // 3. Verify leaf hash matches proof
            byte[] expectedLeaf = Base64.getUrlDecoder().decode(proof.getLeafHash());
            if (!MessageDigest.isEqual(nodeHash, expectedLeaf)) return false;

            // 4. Fold audit path
            List<String> auditPath = proof.getAuditPath();
            long leafIndex = proof.getLeafIndex();
            for (int i = 0; i < auditPath.size(); i++) {
                byte[] sibling = Base64.getUrlDecoder().decode(auditPath.get(i));
                sha256 = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[1 + nodeHash.length + sibling.length];
                buf[0] = 0x01;
                if (((leafIndex >> i) & 1L) == 0L) {
                    // current node is left child
                    System.arraycopy(nodeHash, 0, buf, 1,                  nodeHash.length);
                    System.arraycopy(sibling,  0, buf, 1 + nodeHash.length, sibling.length);
                } else {
                    // current node is right child
                    System.arraycopy(sibling,  0, buf, 1,                  sibling.length);
                    System.arraycopy(nodeHash, 0, buf, 1 + sibling.length, nodeHash.length);
                }
                nodeHash = sha256.digest(buf);
            }

            // 5. Compare to STH root hash (constant-time)
            byte[] rootHash = Base64.getUrlDecoder().decode(sth.getSha256RootHash());
            return MessageDigest.isEqual(nodeHash, rootHash);

        } catch (Exception e) {
            return false;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private byte[] serializeEntry(ReputationLogEntry entry) throws ReputationLogException {
        try {
            Map<String, Object> m = entry.toMap();
            return NipCanonicalJson.canonicalize(m);
        } catch (Exception e) {
            throw new ReputationLogException(
                NipErrorCodes.REPUTATION_ENTRY_INVALID, null,
                "Failed to serialize entry: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> doRequest(HttpRequest req) throws ReputationLogException {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                handleErrorResponse(resp);
            }
            return MAPPER.readValue(resp.body(), MAP_TYPE);
        } catch (ReputationLogException e) {
            throw e;
        } catch (Exception e) {
            throw new ReputationLogException(
                NipErrorCodes.REPUTATION_LOG_UNREACHABLE, null,
                "HTTP request failed: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> doListRequest(HttpRequest req) throws ReputationLogException {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                handleErrorResponse(resp);
            }
            // Server may return either a JSON array or a wrapper object with an "entries" key
            String body = resp.body().trim();
            if (body.startsWith("[")) {
                return MAPPER.readValue(body, LIST_MAP_TYPE);
            } else {
                Map<String, Object> wrapper = MAPPER.readValue(body, MAP_TYPE);
                Object entries = wrapper.get("entries");
                if (entries instanceof List) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Object item : (List<?>) entries) {
                        if (item instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) item;
                            result.add(m);
                        }
                    }
                    return result;
                }
                return List.of();
            }
        } catch (ReputationLogException e) {
            throw e;
        } catch (Exception e) {
            throw new ReputationLogException(
                NipErrorCodes.REPUTATION_LOG_UNREACHABLE, null,
                "HTTP request failed: " + e.getMessage(), e);
        }
    }

    private void handleErrorResponse(HttpResponse<String> resp) throws ReputationLogException {
        String nipCode  = NipErrorCodes.REPUTATION_LOG_UNREACHABLE;
        String npsStatus = null;
        String message  = "HTTP " + resp.statusCode();
        try {
            Map<String, Object> err = MAPPER.readValue(resp.body(), MAP_TYPE);
            Object code = err.get("error_code");
            if (code instanceof String) nipCode = (String) code;
            Object st = err.get("status");
            if (st instanceof String) npsStatus = (String) st;
            Object msg = err.get("message");
            if (msg instanceof String) message = (String) msg;
        } catch (Exception ignored) {
            // body not parseable as JSON — keep defaults
        }
        throw new ReputationLogException(nipCode, npsStatus, message);
    }
}
