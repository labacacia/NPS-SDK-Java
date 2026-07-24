// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * JDK {@link HttpHandler} exposing inbound MCP/A2A Bridge server adapters
 * (com.sun.net.httpserver, zero third-party deps). Server-side counterpart of
 * the .NET BridgeServerMiddleware.
 */
public final class BridgeServerMiddleware implements HttpHandler {

    private final McpServerBridge mcp;
    private final A2aServerBridge a2a;
    private final BridgeServerOptions options;
    private final ExecutorService dispatchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "nps-bridge-dispatch");
        t.setDaemon(true);
        return t;
    });

    /** Create Bridge server middleware. */
    public BridgeServerMiddleware(McpServerBridge mcp, A2aServerBridge a2a, BridgeServerOptions options) {
        this.mcp = mcp;
        this.a2a = a2a;
        this.options = options;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String prefix = trimTrailingSlash(options.pathPrefix);

            if (!path.regionMatches(true, 0, prefix, 0, prefix.length())) {
                ex.sendResponseHeaders(404, -1);
                return;
            }

            String sub = path.substring(prefix.length());
            if (matches(sub, options.mcpPath) || matches(sub, append(options.mcpPath, "/sse"))) {
                boolean useSse = isSseRequest(ex) || matches(sub, append(options.mcpPath, "/sse"));
                handleMcp(ex, useSse);
                return;
            }
            if (matches(sub, options.a2aPath)) {
                handleA2a(ex);
                return;
            }
            if (matches(sub, options.a2aAgentCardPath)) {
                handleAgentCard(ex);
                return;
            }
            ex.sendResponseHeaders(404, -1);
        } finally {
            ex.close();
        }
    }

    private void handleMcp(HttpExchange ex, boolean useSse) throws IOException {
        if ("GET".equals(ex.getRequestMethod()) && useSse) {
            byte[] body = ("event: endpoint\ndata: " + join(options.pathPrefix, options.mcpPath) + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            return;
        }

        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }

        AuthResult auth = authorize(ex);
        if (!auth.authorized) {
            writeJsonRpcError(ex, 401, BridgeJsonRpc.ErrorCodes.INVALID_REQUEST, auth.message);
            return;
        }

        HttpResult result = readAndDispatch(ex, mcp::dispatch);
        if (useSse) {
            writeSse(ex, result.response, result.httpStatus);
        } else {
            writeJson(ex, result.httpStatus, result.response);
        }
    }

    private void handleA2a(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }

        AuthResult auth = authorize(ex);
        if (!auth.authorized) {
            writeJsonRpcError(ex, 401, BridgeJsonRpc.ErrorCodes.INVALID_REQUEST, auth.message);
            return;
        }

        HttpResult result = readAndDispatch(ex, a2a::dispatch);
        writeJson(ex, result.httpStatus, result.response);
    }

    private void handleAgentCard(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) {
            host = ex.getLocalAddress().getHostString() + ":" + ex.getLocalAddress().getPort();
        }
        String scheme = "http";
        String endpoint = scheme + "://" + host + join(options.pathPrefix, options.a2aPath);
        writeJson(ex, 200, a2a.buildAgentCard(endpoint));
    }

    private HttpResult readAndDispatch(
            HttpExchange ex,
            Function<BridgeJsonRpc.Request, BridgeJsonRpc.Response> dispatch) throws IOException {
        try {
            BridgeJsonRpc.Request request = readJsonRpcRequest(ex);
            if (request == null) {
                return new HttpResult(400, BridgeJsonRpc.error(
                    (com.fasterxml.jackson.databind.JsonNode) null,
                    BridgeJsonRpc.ErrorCodes.INVALID_REQUEST, "JSON-RPC request is required."));
            }
            return new HttpResult(200, dispatchWithTimeout(request, dispatch));
        } catch (PayloadTooLargeException exc) {
            return new HttpResult(413, BridgeJsonRpc.error(
                (com.fasterxml.jackson.databind.JsonNode) null,
                BridgeJsonRpc.ErrorCodes.INVALID_REQUEST, exc.getMessage()));
        } catch (DispatchTimeoutException exc) {
            return new HttpResult(504, BridgeJsonRpc.error(
                (com.fasterxml.jackson.databind.JsonNode) null,
                BridgeJsonRpc.ErrorCodes.UPSTREAM_ERROR, exc.getMessage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
            return new HttpResult(400, BridgeJsonRpc.error(
                (com.fasterxml.jackson.databind.JsonNode) null,
                BridgeJsonRpc.ErrorCodes.PARSE_ERROR, exc.getMessage()));
        } catch (Exception exc) {
            return new HttpResult(500, BridgeJsonRpc.error(
                (com.fasterxml.jackson.databind.JsonNode) null,
                BridgeJsonRpc.ErrorCodes.INTERNAL_ERROR, "Bridge server request failed."));
        }
    }

    private BridgeJsonRpc.Request readJsonRpcRequest(HttpExchange ex) throws IOException {
        long maxBytes = options.maxRequestBodyBytes;
        byte[] raw = readBody(ex.getRequestBody(), maxBytes);
        if (raw.length == 0) {
            return null;
        }
        return BridgeJsonRpc.JSON.readValue(raw, BridgeJsonRpc.Request.class);
    }

    private static byte[] readBody(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (maxBytes > 0 && buffer.size() + read > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private BridgeJsonRpc.Response dispatchWithTimeout(
            BridgeJsonRpc.Request request,
            Function<BridgeJsonRpc.Request, BridgeJsonRpc.Response> dispatch) {
        if (options.dispatchTimeoutMs == 0) {
            return dispatch.apply(request);
        }

        Callable<BridgeJsonRpc.Response> task = () -> dispatch.apply(request);
        Future<BridgeJsonRpc.Response> future = dispatchExecutor.submit(task);
        try {
            return future.get(options.dispatchTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exc) {
            future.cancel(true);
            throw new DispatchTimeoutException(options.dispatchTimeoutMs);
        } catch (Exception exc) {
            Throwable cause = exc.getCause() != null ? exc.getCause() : exc;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    private AuthResult authorize(HttpExchange ex) {
        if (!options.requireAuth) {
            return AuthResult.ALLOW;
        }

        List<String> values = ex.getRequestHeaders().get(NwpHttpHeaders.AGENT);
        if (values == null || values.size() != 1 || values.get(0) == null || values.get(0).isBlank()) {
            return AuthResult.deny("A valid X-NWP-Agent NID is required.");
        }

        String agentNid = values.get(0).trim();
        if (!isValidAgentNid(agentNid)) {
            return AuthResult.deny("A valid X-NWP-Agent NID is required.");
        }

        // Deployments verify X-NWP-Agent against NIP certs / capabilities / policy
        // in a subclass or wrapper; the reference port accepts any well-formed NID.
        return AuthResult.ALLOW;
    }

    private static boolean isValidAgentNid(String nid) {
        final String prefix = "urn:nps:agent:";
        if (!nid.startsWith(prefix) || nid.length() > 512) {
            return false;
        }
        String rest = nid.substring(prefix.length());
        int sep = rest.indexOf(':');
        if (sep <= 0 || sep == rest.length() - 1) {
            return false;
        }
        String domain = rest.substring(0, sep);
        String identifier = rest.substring(sep + 1);
        return allMatch(domain, BridgeServerMiddleware::isDomainChar)
            && allMatch(identifier, BridgeServerMiddleware::isIdentifierChar);
    }

    private static boolean allMatch(String s, java.util.function.IntPredicate p) {
        for (int i = 0; i < s.length(); i++) {
            if (!p.test(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isDomainChar(int ch) {
        return isAsciiLetterOrDigit(ch) || ch == '.' || ch == '-';
    }

    private static boolean isIdentifierChar(int ch) {
        return isAsciiLetterOrDigit(ch)
            || ch == '.' || ch == '_' || ch == '-' || ch == '~'
            || ch == ':' || ch == '@' || ch == '/';
    }

    private static boolean isAsciiLetterOrDigit(int ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
    }

    private static void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] raw = BridgeJsonRpc.JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, raw.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(raw);
        }
    }

    private static void writeJsonRpcError(HttpExchange ex, int status, int code, String message)
            throws IOException {
        writeJson(ex, status, BridgeJsonRpc.error(
            (com.fasterxml.jackson.databind.JsonNode) null, code, message));
    }

    private static void writeSse(HttpExchange ex, BridgeJsonRpc.Response response, int status)
            throws IOException {
        String payload = BridgeJsonRpc.JSON.writeValueAsString(response);
        byte[] raw = ("event: message\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(status, raw.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(raw);
        }
    }

    private static boolean matches(String actual, String expected) {
        String normalized = expected.startsWith("/") ? expected : "/" + expected;
        return actual.equalsIgnoreCase(normalized) || actual.equalsIgnoreCase(normalized + "/");
    }

    private static String append(String path, String suffix) {
        return trimTrailingSlash(path) + suffix;
    }

    private static String join(String prefix, String path) {
        String left = trimTrailingSlash(prefix);
        String right = path.startsWith("/") ? path : "/" + path;
        return left.isEmpty() ? right : left + right;
    }

    private static boolean isSseRequest(HttpExchange ex) {
        List<String> accept = ex.getRequestHeaders().get("Accept");
        if (accept == null) {
            return false;
        }
        return accept.stream().anyMatch(v -> v != null
            && v.toLowerCase().contains("text/event-stream"));
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    private record HttpResult(int httpStatus, BridgeJsonRpc.Response response) {}

    private record AuthResult(boolean authorized, String message) {
        static final AuthResult ALLOW = new AuthResult(true, "");
        static AuthResult deny(String message) {
            return new AuthResult(false, message);
        }
    }

    private static final class PayloadTooLargeException extends RuntimeException {
        PayloadTooLargeException(long maxBytes) {
            super("Bridge server request body exceeds the configured " + maxBytes + " byte limit.");
        }
    }

    private static final class DispatchTimeoutException extends RuntimeException {
        DispatchTimeoutException(long timeoutMs) {
            super("Bridge server dispatch timed out after " + timeoutMs + "ms.");
        }
    }
}
