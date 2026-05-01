// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Static helpers for NPS DNS TXT record format (NDP spec §5).
 *
 * <p>TXT record format:</p>
 * <pre>
 * _nps-node.api.example.com.  IN TXT  "v=nps1 type=memory port=17434 nid=urn:nps:node:api.example.com:products fp=sha256:a3f9..."
 * </pre>
 *
 * <p>Required keys: {@code v} (must be {@code nps1}), {@code nid}.<br>
 * Optional keys: {@code port} (default 17433), {@code type}, {@code fp}.</p>
 */
public final class NpsDnsTxt {

    /** Default TTL (seconds) applied to DNS-resolved entries. */
    public static final int DEFAULT_TTL = 300;

    /** DNS subdomain prefix used when building the lookup hostname. */
    private static final String DNS_PREFIX = "_nps-node.";

    private NpsDnsTxt() {}

    /**
     * Extracts the hostname from an {@code nwp://} target URL.
     *
     * @param target e.g. {@code nwp://api.example.com/products}
     * @return the hostname portion, or {@code null} if the target is null, empty,
     *         not an {@code nwp://} URL, or otherwise malformed
     */
    public static String extractHost(String target) {
        if (target == null || target.isBlank()) return null;
        if (!target.startsWith("nwp://")) return null;
        try {
            // Replace the scheme so java.net.URI can parse it
            URI uri = new URI("http://" + target.substring("nwp://".length()));
            String host = uri.getHost();
            if (host == null || host.isBlank()) return null;
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the DNS TXT lookup hostname for a given node hostname.
     * For example, {@code api.example.com} → {@code _nps-node.api.example.com}.
     */
    public static String dnsLookupName(String host) {
        return DNS_PREFIX + host;
    }

    /**
     * Parses a single NPS DNS TXT record string into a {@link InMemoryNdpRegistry.ResolveResult}.
     *
     * <p>Validation rules:</p>
     * <ul>
     *   <li>{@code v} key must be present and equal to {@code nps1}</li>
     *   <li>{@code nid} key must be present and non-blank</li>
     *   <li>{@code port} defaults to 17433 if absent</li>
     * </ul>
     *
     * @param txt  the raw TXT record value (space-separated key=value pairs)
     * @param host the host address to use in the returned result
     * @return a populated {@link InMemoryNdpRegistry.ResolveResult}, or {@code null} if the record
     *         is invalid (missing/wrong {@code v}, missing {@code nid}, or parse error)
     */
    public static InMemoryNdpRegistry.ResolveResult parseNpsTxtRecord(String txt, String host) {
        if (txt == null || txt.isBlank()) return null;

        Map<String, String> kv = parseKeyValues(txt);

        // v= must be present and equal to "nps1"
        String v = kv.get("v");
        if (!"nps1".equals(v)) return null;

        // nid= must be present and non-blank
        String nid = kv.get("nid");
        if (nid == null || nid.isBlank()) return null;

        // port= defaults to 17433
        int port = 17433;
        String portStr = kv.get("port");
        if (portStr != null && !portStr.isBlank()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return new InMemoryNdpRegistry.ResolveResult(host, port, DEFAULT_TTL);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Splits a space-separated {@code key=value} string into a map.
     * Tokens without '=' are ignored. The last occurrence of a key wins.
     */
    private static Map<String, String> parseKeyValues(String txt) {
        Map<String, String> map = new HashMap<>();
        for (String token : txt.trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                map.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return map;
    }
}
