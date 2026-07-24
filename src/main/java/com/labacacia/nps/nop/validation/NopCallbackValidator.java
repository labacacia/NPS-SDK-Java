// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.validation;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Validates {@code TaskFrame.callback_url} per NPS-5 §8.4.
 * <ul>
 *   <li>MUST be an {@code https://} URL.</li>
 *   <li>SHOULD NOT target a private/loopback address (SSRF guard).</li>
 * </ul>
 *
 * <p>DNS resolution is intentionally avoided to keep validation synchronous and free of
 * network I/O; callers should apply additional network-layer controls.
 */
public final class NopCallbackValidator {
    private NopCallbackValidator() {}

    /**
     * Validates {@code callbackUrl}. Returns {@code null} when valid; otherwise a
     * human-readable error string.
     */
    public static String validateCallbackUrl(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return "callback_url must not be empty.";
        }

        URI uri;
        try {
            uri = new URI(callbackUrl);
        } catch (URISyntaxException e) {
            return "callback_url '" + callbackUrl + "' is not a valid absolute URI.";
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return "callback_url '" + callbackUrl + "' is not a valid absolute URI.";
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "callback_url MUST use the https:// scheme (got '" + uri.getScheme() + "://').";
        }

        if (isPrivateHost(uri.getHost())) {
            return "callback_url host '" + uri.getHost()
                + "' resolves to a private or loopback address (SSRF guard).";
        }

        return null; // valid
    }

    /**
     * Returns {@code true} when {@code host} is a well-known private / loopback /
     * link-local address or hostname, without performing DNS resolution.
     */
    public static boolean isPrivateHost(String host) {
        if (host == null || host.isEmpty()) return true;

        if (host.equalsIgnoreCase("localhost")) return true;

        // Strip IPv6 URI brackets: [::1]
        String stripped = host;
        if (stripped.startsWith("[") && stripped.endsWith("]")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }

        int[] v4 = parseIpv4(stripped);
        if (v4 != null) return isPrivateIpv4(v4);

        if (stripped.indexOf(':') >= 0) {
            return isPrivateIpv6(stripped);
        }

        return false;
    }

    private static int[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return null;
            int val = 0;
            for (int j = 0; j < parts[i].length(); j++) {
                char c = parts[i].charAt(j);
                if (c < '0' || c > '9') return null;
                val = val * 10 + (c - '0');
            }
            if (val > 255) return null;
            out[i] = val;
        }
        return out;
    }

    private static boolean isPrivateIpv4(int[] b) {
        return b[0] == 127                                   //  127.0.0.0/8  loopback
            || b[0] == 10                                    //  10.0.0.0/8
            || b[0] == 0                                     //  0.0.0.0/8
            || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)     //  172.16.0.0/12
            || (b[0] == 192 && b[1] == 168)                  //  192.168.0.0/16
            || (b[0] == 169 && b[1] == 254);                 //  169.254.0.0/16 link-local
    }

    private static boolean isPrivateIpv6(String s) {
        String lower = s.toLowerCase();
        // Handle IPv4-mapped: ::ffff:10.0.0.1
        int lastColon = lower.lastIndexOf(':');
        if (lastColon >= 0 && lower.indexOf('.') > lastColon) {
            int[] v4 = parseIpv4(lower.substring(lastColon + 1));
            if (v4 != null) return isPrivateIpv4(v4);
        }
        if (lower.equals("::1") || lower.equals("::")) return true;         // loopback / unspecified
        if (lower.startsWith("fe80")) return true;                          // fe80::/10 link-local
        if (lower.startsWith("fec0")) return true;                          // fec0::/10 site-local (deprecated)
        if (lower.startsWith("fc") || lower.startsWith("fd")) return true;  // fc00::/7 unique local
        return false;
    }
}
