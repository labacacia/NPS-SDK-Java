// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * MCP tool-name encoding for inbound Bridges (NPS-CR-0010 §5.1).
 *
 * <p>{@code Encode(node, action) = Sanitize(node) + "__" + EncodeActionSegment(action)}.
 * MCP tool names are a flat namespace and one Bridge may front several nodes, so output
 * is always qualified. Qualifying only when more than one backend exists was rejected:
 * adding a second node later would silently rename every tool.</p>
 *
 * <p><strong>Encoding only — there is deliberately no decode.</strong> The transform is
 * lossy ({@code .} and {@code _} both map to {@code _}; a node name may itself contain
 * {@code __}). Resolution MUST therefore re-encode each candidate and compare, never
 * split the incoming string.</p>
 */
public final class McpToolName {

    private McpToolName() {}

    public static String encode(String node, String action) {
        return sanitize(node) + "__" + encodeActionSegment(action);
    }

    public static String encodeActionSegment(String action) {
        return sanitize(action).replace('.', '_');
    }

    /**
     * Trim; replace every character that is not a letter, digit, {@code _}, {@code -} or
     * {@code .} with {@code _}; then trim leading/trailing {@code _}. An empty result
     * becomes {@code "node"}.
     */
    public static String sanitize(String value) {
        if (value == null) return "node";
        String trimmed = value.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean keep = Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
            sb.append(keep ? c : '_');
        }
        int start = 0;
        int end   = sb.length();
        while (start < end && sb.charAt(start) == '_') start++;
        while (end > start && sb.charAt(end - 1) == '_') end--;
        String out = sb.substring(start, end);
        return out.isEmpty() ? "node" : out;
    }
}
