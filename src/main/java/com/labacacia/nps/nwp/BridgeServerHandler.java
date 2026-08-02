// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP hosting layer for the inbound Bridge servers, on the JDK
 * {@link com.sun.net.httpserver} stack — the same binding {@code AnchorNodeServer} uses
 * (NPS-CR-0010, NPS-2 §16.1).
 *
 * <p>Routes {@code /mcp}, {@code /mcp/sse}, {@code /a2a} and
 * {@code /.well-known/agent.json}; applies the auth gate, the bounded body, the dispatch
 * timeout, and sanitised error responses. Everything protocol-specific lives in
 * {@link McpInboundServer} / {@link A2aInboundServer}, which never see an HTTP context.</p>
 */
public final class BridgeServerHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF  = JsonNodeFactory.instance;
    private static final int CHUNK = 80 * 1024;   // 80 KiB streaming accumulate

    private static final String NID_PREFIX  = "urn:nps:agent:";
    private static final int    NID_MAX_LEN = 512;

    private final BridgeServerOptions options;
    private final McpInboundServer     mcp;
    private final A2aInboundServer     a2a;
    private final String               prefix;

    public BridgeServerHandler(BridgeServerOptions options) {
        if (options == null) throw new IllegalArgumentException("options is required");
        this.options = options;
        this.mcp     = new McpInboundServer(options);
        this.a2a     = new A2aInboundServer(options);
        this.prefix  = options.normalisedPrefix();
    }

    public McpInboundServer mcpServer() { return mcp; }
    public A2aInboundServer a2aServer() { return a2a; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!path.startsWith(prefix)) { ex.sendResponseHeaders(404, -1); return; }
            String sub    = path.substring(prefix.length());
            String method = ex.getRequestMethod();

            if (sub.equals(options.a2aAgentCardPath)) {
                if (!"GET".equals(method)) { ex.sendResponseHeaders(405, -1); return; }
                if (!authorize(ex)) return;
                writeJson(ex, 200, a2a.buildAgentCard(prefix + options.a2aPath));
                return;
            }

            boolean isMcp = sub.equals(options.mcpPath) || sub.equals(options.mcpSsePath);
            boolean isA2a = sub.equals(options.a2aPath);
            if (!isMcp && !isA2a) { ex.sendResponseHeaders(404, -1); return; }
            if (!"POST".equals(method)) { ex.sendResponseHeaders(405, -1); return; }
            if (!authorize(ex)) return;

            byte[] body = readBounded(ex);
            if (body == null) return;   // 413 already written

            BridgeJsonRpcRequest request;
            try {
                request = MAPPER.readValue(body, BridgeJsonRpcRequest.class);
            } catch (Exception e) {
                writeJsonRpcError(ex, 400, NF.nullNode(), BridgeErrorMap.PARSE_ERROR, e.getMessage());
                return;
            }
            if (request == null) {
                writeJsonRpcError(ex, 400, NF.nullNode(),
                    BridgeErrorMap.INVALID_REQUEST, "JSON-RPC request is required.");
                return;
            }

            BridgeJsonRpcResponse response = dispatchWithTimeout(ex, isMcp, request);
            if (response == null) return;   // 504 already written
            writeJson(ex, 200, response);

        } catch (Exception e) {
            // Sanitised catch-all: nothing about the exception reaches the foreign client.
            try {
                writeJsonRpcError(ex, 500, NF.nullNode(),
                    BridgeErrorMap.INTERNAL_ERROR, "Bridge server request failed.");
            } catch (IOException ignored) { /* client already gone */ }
        } finally {
            ex.close();
        }
    }

    // ── Auth gate ────────────────────────────────────────────────────────────

    private boolean authorize(HttpExchange ex) throws IOException {
        if (!options.requireAuth) return true;

        List<String> headers = ex.getRequestHeaders().get(NwpHttpHeaders.AGENT);
        String agentNid = headers != null && headers.size() == 1 ? headers.get(0) : null;
        if (agentNid == null || agentNid.isBlank() || !isSyntacticallyValidAgentNid(agentNid)) {
            return denyUnauthenticated(ex);
        }
        // Fail closed: auth required but no verifier configured ⇒ deny everything.
        if (options.verifier == null || !options.verifier.verify(agentNid, ex)) {
            return denyUnauthenticated(ex);
        }
        return true;
    }

    private boolean denyUnauthenticated(HttpExchange ex) throws IOException {
        writeJsonRpcError(ex, 401, NF.nullNode(), BridgeErrorMap.INVALID_REQUEST,
            "A valid " + NwpHttpHeaders.AGENT + " NID is required.");
        return false;
    }

    /** {@code urn:nps:agent:{domain}:{identifier}}, total length ≤ 512. */
    static boolean isSyntacticallyValidAgentNid(String nid) {
        if (nid == null || nid.length() > NID_MAX_LEN || !nid.startsWith(NID_PREFIX)) return false;
        String rest = nid.substring(NID_PREFIX.length());
        int sep = rest.indexOf(':');
        if (sep <= 0 || sep == rest.length() - 1) return false;
        String domain     = rest.substring(0, sep);
        String identifier = rest.substring(sep + 1);
        if (domain.isEmpty() || identifier.isEmpty()) return false;
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-')) return false;
        }
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            boolean ok = Character.isLetterOrDigit(c)
                || c == '.' || c == '_' || c == '~' || c == ':' || c == '@' || c == '/' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    // ── Bounded body ─────────────────────────────────────────────────────────

    /** @return the body, or {@code null} when a 413 has already been written. */
    private byte[] readBounded(HttpExchange ex) throws IOException {
        long cap = options.maxRequestBodyBytes;
        if (cap > 0) {
            String declared = ex.getRequestHeaders().getFirst("Content-Length");
            if (declared != null) {
                try {
                    if (Long.parseLong(declared.trim()) > cap) return tooLarge(ex);
                } catch (NumberFormatException ignored) { /* fall through to the streaming cap */ }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK];
        long total = 0;
        try (InputStream in = ex.getRequestBody()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                // Abort as soon as the running total would exceed the cap, so a lying or
                // absent Content-Length cannot bypass it.
                if (cap > 0 && total > cap) return tooLarge(ex);
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private byte[] tooLarge(HttpExchange ex) throws IOException {
        writeJsonRpcError(ex, 413, NF.nullNode(), BridgeErrorMap.INVALID_REQUEST,
            "Request body exceeds the configured limit.");
        return null;
    }

    // ── Dispatch with timeout ────────────────────────────────────────────────

    private BridgeJsonRpcResponse dispatchWithTimeout(HttpExchange ex, boolean isMcp,
                                                      BridgeJsonRpcRequest request) throws IOException {
        if (options.dispatchTimeoutMs <= 0) {
            return isMcp ? mcp.dispatch(request) : a2a.dispatch(request);
        }
        CompletableFuture<BridgeJsonRpcResponse> future = CompletableFuture.supplyAsync(
            () -> isMcp ? mcp.dispatch(request) : a2a.dispatch(request));
        try {
            return future.get(options.dispatchTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            writeJsonRpcError(ex, 504, request.id == null ? NF.nullNode() : request.id,
                BridgeErrorMap.UPSTREAM_ERROR, "Bridge dispatch timed out.");
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("bridge dispatch interrupted", ie);
        } catch (ExecutionException ee) {
            throw new RuntimeException(ee.getCause());   // handled by the sanitised catch-all
        }
    }

    // ── Writers ──────────────────────────────────────────────────────────────

    private void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] raw = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, raw.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(raw); }
    }

    private void writeJsonRpcError(HttpExchange ex, int httpStatus,
                                   com.fasterxml.jackson.databind.JsonNode id,
                                   int code, String message) throws IOException {
        writeJson(ex, httpStatus, BridgeJsonRpcResponse.fail(id, code,
            message == null ? "" : message));
    }

    static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
