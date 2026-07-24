// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.nop.validation.NopCallbackValidator;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Validates outbound Bridge endpoints before dereferencing them. */
public final class BridgeEndpointValidator {

    private BridgeEndpointValidator() {}

    /**
     * Parse and validate an HTTP(S) Bridge endpoint. By default, both
     * {@code http://} and {@code https://} are accepted, while private and
     * loopback hosts are rejected as an SSRF guard.
     */
    public static URI parseHttpEndpoint(BridgeTarget target) {
        if (target == null) {
            throw new NullPointerException("target");
        }

        URI uri;
        try {
            uri = new URI(target.endpoint);
        } catch (Exception ex) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.ENDPOINT_INVALID,
                "bridge_target.endpoint must be an absolute http:// or https:// URI.");
        }

        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null ||
            scheme == null ||
            (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.ENDPOINT_INVALID,
                "bridge_target.endpoint must be an absolute http:// or https:// URI.");
        }

        boolean allowHttp = getBool(target, "allow_http", true);
        if (!allowHttp && scheme.equalsIgnoreCase("http")) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.ENDPOINT_INVALID,
                "bridge_target.endpoint MUST use https:// unless bridge_target.allow_http is true.");
        }

        List<String> allowedPrefixes = getStringList(target, "allowed_prefixes");
        if (!allowedPrefixes.isEmpty() &&
            allowedPrefixes.stream().noneMatch(prefix -> matchesAllowedPrefix(uri, prefix))) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.ENDPOINT_INVALID,
                "bridge_target.endpoint '" + target.endpoint
                    + "' is not in bridge_target.allowed_prefixes.");
        }

        boolean rejectPrivate = getBool(target, "reject_private", true);
        if (rejectPrivate && NopCallbackValidator.isPrivateHost(uri.getHost())) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.ENDPOINT_INVALID,
                "bridge_target.endpoint host '" + uri.getHost()
                    + "' is private or loopback (SSRF guard).");
        }

        return uri;
    }

    private static boolean getBool(BridgeTarget target, String name, boolean defaultValue) {
        Object value = BridgeTargetParser.getJson(target, name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return defaultValue;
    }

    private static List<String> getStringList(BridgeTarget target, String name) {
        Object value = BridgeTargetParser.getJson(target, name);
        List<String> items = new ArrayList<>();
        if (value == null) {
            return items;
        }
        if (value instanceof String s) {
            if (!s.isBlank()) items.add(s);
            return items;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    items.add(s);
                }
            }
        }
        return items;
    }

    private static boolean matchesAllowedPrefix(URI endpoint, String rawPrefix) {
        URI prefix;
        try {
            prefix = new URI(rawPrefix);
        } catch (Exception ex) {
            return false;
        }
        if (!prefix.isAbsolute() || prefix.getHost() == null || prefix.getScheme() == null) {
            return false;
        }

        if (!equalsIgnoreCase(endpoint.getScheme(), prefix.getScheme()) ||
            !equalsIgnoreCase(endpoint.getHost(), prefix.getHost()) ||
            effectivePort(endpoint) != effectivePort(prefix)) {
            return false;
        }

        String prefixPath = normalizePath(prefix.getPath());
        if (prefixPath.equals("/")) {
            return true;
        }

        String endpointPath = normalizePath(endpoint.getPath());
        if (!startsWithIgnoreCase(endpointPath, prefixPath)) {
            return false;
        }

        return endpointPath.length() == prefixPath.length() ||
               prefixPath.endsWith("/") ||
               endpointPath.charAt(prefixPath.length()) == '/';
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String normalizePath(String path) {
        return (path == null || path.isEmpty()) ? "/" : path;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
