// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.nip.x509.Ed25519PublicKeys;

public final class NipCaClient {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final String baseUrl;
    private final String prefix;
    private final HttpClient http;

    public NipCaClient(String baseUrl) {
        this(baseUrl, "", null);
    }

    public NipCaClient(String baseUrl, String routePrefix, HttpClient httpClient) {
        this.baseUrl = trimRight(baseUrl);
        this.prefix = trimRight(routePrefix == null ? "" : routePrefix);
        this.http = httpClient != null ? httpClient : HttpClient.newHttpClient();
    }

    public NipCaDiscoveryDocument getDiscovery() throws IOException, InterruptedException {
        return getJson("/.well-known/nps-ca", NipCaDiscoveryDocument.class);
    }

    public NipCaCrl getCrl() throws IOException, InterruptedException {
        return getJson(prefix + "/v1/crl", NipCaCrl.class);
    }

    public NipCaCertificateList getCertificates(String bearerToken)
            throws IOException, InterruptedException {
        return sendJson(
            "GET", prefix + "/v1/certificates", null, bearerToken,
            NipCaCertificateList.class);
    }

    /** Verifies a signed NIP v0.13 CRL artifact. */
    public static boolean verifyCrlSignature(
            NipCaCrl crl, String caPublicKey) {
        try {
            byte[] raw = decodeEd25519(caPublicKey);
            byte[] signature = decodeEd25519(crl.signature());
            if (raw.length != 32) return false;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("issued_by", crl.issuedBy());
            body.put("issued_at", crl.issuedAt());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (NipCaCrlEntry entry : crl.entries()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nid", entry.nid());
                item.put("serial", entry.serial());
                item.put("revoked_at", entry.revokedAt());
                item.put("reason", entry.reason());
                entries.add(item);
            }
            body.put("entries", entries);

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(Ed25519PublicKeys.fromRaw(raw));
            verifier.update(canonicalJson(body).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception ex) {
            return false;
        }
    }

    public NipCaIdentFrame registerAgent(NipCaRegisterRequest request, String bearerToken) throws IOException, InterruptedException {
        return sendJson("POST", prefix + "/v1/agents/register", request, bearerToken, NipCaIdentFrame.class);
    }

    public NipCaIdentFrame registerNode(NipCaRegisterRequest request, String bearerToken) throws IOException, InterruptedException {
        return sendJson("POST", prefix + "/v1/nodes/register", request, bearerToken, NipCaIdentFrame.class);
    }

    public NipCaIdentFrame registerAgentX509(NipCaRegisterX509Request request, String bearerToken) throws IOException, InterruptedException {
        return sendJson("POST", prefix + "/v1/agents/register-x509", request, bearerToken, NipCaIdentFrame.class);
    }

    public NipCaIdentFrame registerNodeX509(NipCaRegisterX509Request request, String bearerToken) throws IOException, InterruptedException {
        return sendJson("POST", prefix + "/v1/nodes/register-x509", request, bearerToken, NipCaIdentFrame.class);
    }

    public NipCaIdentFrame renewAgent(String nid, String bearerToken) throws IOException, InterruptedException {
        return sendJson("POST", prefix + "/v1/agents/" + esc(nid) + "/renew", null, bearerToken, NipCaIdentFrame.class);
    }

    public NipCaIdentFrame renewNode(String nid, String bearerToken) throws IOException, InterruptedException {
        return sendJson("POST", prefix + "/v1/nodes/" + esc(nid) + "/renew", null, bearerToken, NipCaIdentFrame.class);
    }

    public NipCaRevokeFrame revokeAgent(String nid, String reason, String bearerToken) throws IOException, InterruptedException {
        return sendJson("POST", prefix + "/v1/agents/" + esc(nid) + "/revoke",
            Map.of("reason", reason == null || reason.isBlank() ? "cessation_of_operation" : reason),
            bearerToken, NipCaRevokeFrame.class);
    }

    public NipCaRevokeFrame revokeNode(String nid, String reason, String bearerToken) throws IOException, InterruptedException {
        return sendJson("POST", prefix + "/v1/nodes/" + esc(nid) + "/revoke",
            Map.of("reason", reason == null || reason.isBlank() ? "cessation_of_operation" : reason),
            bearerToken, NipCaRevokeFrame.class);
    }

    public NipCaVerifyResponse verifyAgent(String nid) throws IOException, InterruptedException {
        return getJson(prefix + "/v1/agents/" + esc(nid) + "/verify", NipCaVerifyResponse.class);
    }

    public NipCaVerifyResponse verifyNode(String nid) throws IOException, InterruptedException {
        return getJson(prefix + "/v1/nodes/" + esc(nid) + "/verify", NipCaVerifyResponse.class);
    }

    private <T> T getJson(String path, Class<T> type) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .GET()
            .header("Accept", "application/json")
            .build();
        return readResponse(http.send(request, HttpResponse.BodyHandlers.ofByteArray()), type);
    }

    private <T> T sendJson(String method, String path, Object body, String bearerToken, Class<T> type)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .method(method, publisher)
            .header("Accept", "application/json");
        if (body != null) builder.header("Content-Type", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) builder.header("Authorization", "Bearer " + bearerToken);
        return readResponse(http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray()), type);
    }

    private static <T> T readResponse(HttpResponse<byte[]> response, Class<T> type) throws IOException {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return MAPPER.readValue(response.body(), type);
        }
        Map<String, Object> error = Map.of();
        if (response.body().length > 0) {
            error = MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        }
        String code = String.valueOf(error.getOrDefault("error_code", error.getOrDefault("error", "NIP-CA-HTTP-ERROR")));
        String message = String.valueOf(error.getOrDefault("message", "NIP CA returned HTTP " + status + "."));
        throw new NipCaClientException(code, message, status);
    }

    private static String trimRight(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private static String esc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] decodeEd25519(String encoded) {
        if (encoded == null || !encoded.startsWith("ed25519:")) {
            throw new IllegalArgumentException("Invalid Ed25519 value.");
        }
        String body = encoded.substring("ed25519:".length());
        if (body.matches("[0-9a-fA-F]{64}")) {
            return java.util.HexFormat.of().parseHex(body);
        }
        return Base64.getUrlDecoder().decode(body);
    }

    private static String canonicalJson(Object value) throws IOException {
        JsonNode node = MAPPER.valueToTree(value);
        StringBuilder out = new StringBuilder();
        writeCanonical(node, out);
        return out.toString();
    }

    private static void writeCanonical(JsonNode node, StringBuilder out)
            throws IOException {
        if (node.isObject()) {
            out.append('{');
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fieldNames().forEachRemaining(
                name -> sorted.put(name, node.get(name)));
            boolean first = true;
            for (var entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(MAPPER.writeValueAsString(entry.getKey())).append(':');
                writeCanonical(entry.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                writeCanonical(node.get(i), out);
            }
            out.append(']');
        } else {
            out.append(node);
        }
    }
}
