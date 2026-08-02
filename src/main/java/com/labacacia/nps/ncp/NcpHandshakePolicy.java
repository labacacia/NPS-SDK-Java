// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameFlags;
import com.labacacia.nps.core.FrameHeader;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsStatusCodes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure NCP v0.11 native-server admission and negotiation policy. */
public final class NcpHandshakePolicy {
    private NcpHandshakePolicy() {}

    public enum Action { CONTINUE, ACCEPT, SILENT_CLOSE, ERROR_CLOSE }

    public record Decision(
        Action action,
        String status,
        String error,
        String diagnosticError,
        String sessionVersion,
        String negotiatedEncoding,
        List<String> enabledEncodings,
        List<String> supportedProtocols,
        Integer maxFramePayload,
        Boolean extSupport,
        Integer maxConcurrentStreams
    ) {
        public static Decision of(Action action) {
            return new Decision(action, null, null, null, null, null,
                null, null, null, null, null);
        }
    }

    public static Decision evaluatePreamble(
            byte[] received, long elapsedMs, long timeoutMs) {
        if (timeoutMs > 0 && elapsedMs >= timeoutMs) {
            return Decision.of(Action.SILENT_CLOSE);
        }
        if (received.length < NcpPreamble.LENGTH) {
            return Decision.of(Action.CONTINUE);
        }
        byte[] expected = NcpPreamble.getBytes();
        for (int i = 0; i < NcpPreamble.LENGTH; i++) {
            if (received[i] != expected[i]) {
                return new Decision(Action.SILENT_CLOSE, null, null,
                    NcpErrorCodes.NCP_PREAMBLE_INVALID, null, null,
                    null, null, null, null, null);
            }
        }
        return Decision.of(Action.CONTINUE);
    }

    public static Decision evaluateHelloHeader(
            FrameHeader header,
            long elapsedMs,
            long timeoutMs,
            long maxHelloPayload) {
        if (timeoutMs > 0 && elapsedMs >= timeoutMs) {
            return Decision.of(Action.SILENT_CLOSE);
        }
        if (header.frameType != FrameType.HELLO
                || header.encodingTier() != EncodingTier.JSON
                || (header.flags & FrameFlags.ENCRYPTED) != 0
                || header.isExtended
                || header.payloadLength > maxHelloPayload) {
            return Decision.of(Action.SILENT_CLOSE);
        }
        return Decision.of(Action.CONTINUE);
    }

    public static Decision negotiate(
            NcpHandshakeProfile server, HelloFrame client) {
        int[] serverMin = parseVersion(server.minVersion());
        int[] serverMax = parseVersion(server.npsVersion());
        int[] clientMin = parseVersion(
            client.minVersion() != null ? client.minVersion() : client.npsVersion());
        int[] clientMax = parseVersion(client.npsVersion());
        if (serverMin == null || serverMax == null
                || clientMin == null || clientMax == null
                || compare(serverMin, serverMax) > 0
                || compare(clientMin, clientMax) > 0) {
            return versionError();
        }
        int[] overlapMin = max(serverMin, clientMin);
        int[] overlapMax = min(serverMax, clientMax);
        if (compare(overlapMin, overlapMax) > 0) return versionError();

        Set<String> serverEncodings = Set.copyOf(server.supportedEncodings());
        String stable = null;
        for (String token : client.supportedEncodings()) {
            if (("msgpack".equals(token) || "json".equals(token))
                    && serverEncodings.contains(token)) {
                stable = token;
                break;
            }
        }
        if (stable == null) {
            return new Decision(
                Action.ERROR_CLOSE,
                NpsStatusCodes.NPS_SERVER_ENCODING_UNSUPPORTED,
                NcpErrorCodes.NCP_ENCODING_UNSUPPORTED,
                null, null, null, null, null, null, null, null);
        }

        Set<String> serverProtocols = Set.copyOf(server.supportedProtocols());
        LinkedHashSet<String> protocols = new LinkedHashSet<>();
        for (String token : client.supportedProtocols()) {
            if (serverProtocols.contains(token)) protocols.add(token);
        }
        if (!protocols.contains("ncp")
                || client.maxFramePayload() <= 0
                || server.maxFramePayload() <= 0
                || client.maxConcurrentStreams() <= 0
                || server.maxConcurrentStreams() <= 0) {
            return versionError();
        }

        List<String> enabled = new ArrayList<>();
        enabled.add(stable);
        if (serverEncodings.contains("binary_vector.v1")
                && client.supportedEncodings().contains("binary_vector.v1")) {
            enabled.add("binary_vector.v1");
        }
        return new Decision(
            Action.ACCEPT,
            null,
            null,
            null,
            overlapMax[0] + "." + overlapMax[1],
            stable,
            List.copyOf(enabled),
            List.copyOf(protocols),
            Math.min(server.maxFramePayload(), client.maxFramePayload()),
            server.extSupport() && client.extSupport(),
            Math.min(server.maxConcurrentStreams(), client.maxConcurrentStreams()));
    }

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)$");

    private static int[] parseVersion(String value) {
        if (value == null) return null;
        Matcher match = VERSION.matcher(value);
        if (!match.matches()) return null;
        try {
            return new int[]{
                Integer.parseInt(match.group(1)),
                Integer.parseInt(match.group(2))};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int compare(int[] a, int[] b) {
        int major = Integer.compare(a[0], b[0]);
        return major != 0 ? major : Integer.compare(a[1], b[1]);
    }

    private static int[] min(int[] a, int[] b) {
        return compare(a, b) <= 0 ? a : b;
    }

    private static int[] max(int[] a, int[] b) {
        return compare(a, b) >= 0 ? a : b;
    }

    private static Decision versionError() {
        return new Decision(
            Action.ERROR_CLOSE,
            NpsStatusCodes.NPS_PROTO_VERSION_INCOMPATIBLE,
            NcpErrorCodes.NCP_VERSION_INCOMPATIBLE,
            null, null, null, null, null, null, null, null);
    }
}
