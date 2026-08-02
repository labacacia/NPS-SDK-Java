// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Transport-independent NDP 0.12 registry conformance profile. */
public final class NdpRegistryProfile {

    public enum Decision {
        ACCEPTED, DUPLICATE, REFRESHED, REMOVED, REJECTED
    }

    public record Admission(Decision decision, String errorCode) {}

    public record ClusterSelection(String nid, Long epoch, String errorCode) {}

    private record Entry(
        Map<String, Object> frame,
        String signedDigest,
        Instant expiresAt) {}

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> EXCLUDED =
        Set.of("frame", "signature", "health", "last_seen");
    private static final byte[] ED25519_SPKI_PREFIX =
        java.util.HexFormat.of().parseHex("302a300506032b6570032100");

    private final String securityProfile;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Long> sequences = new LinkedHashMap<>();

    public NdpRegistryProfile() {
        this("local-dev");
    }

    public NdpRegistryProfile(String securityProfile) {
        this.securityProfile = securityProfile;
    }

    /** Canonical NDP 0.12 Announce signed body. */
    public static String canonicalAnnounceJson(Map<String, Object> frame) {
        try {
            Map<String, Object> root = new TreeMap<>();
            for (var item : frame.entrySet()) {
                if (!EXCLUDED.contains(item.getKey()) && item.getValue() != null) {
                    root.put(item.getKey(), withoutNulls(item.getValue()));
                }
            }
            root.putIfAbsent("heartbeat_interval_ms", 60_000);
            return CANONICAL_MAPPER.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot canonicalize AnnounceFrame.", ex);
        }
    }

    /** Verify an {@code ed25519:<base64url>} Announce signature. */
    public static boolean verifyAnnounceSignature(
            Map<String, Object> frame,
            String encodedPublicKey,
            String encodedSignature) {
        String prefix = "ed25519:";
        if (encodedPublicKey == null || encodedSignature == null
                || !encodedPublicKey.startsWith(prefix)
                || !encodedSignature.startsWith(prefix)) {
            return false;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(
                encodedPublicKey.substring(prefix.length()));
            if (raw.length != 32) return false;
            byte[] spki = new byte[ED25519_SPKI_PREFIX.length + raw.length];
            System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0,
                ED25519_SPKI_PREFIX.length);
            System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, raw.length);
            var publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(spki));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(canonicalAnnounceJson(frame)
                .getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getUrlDecoder().decode(
                encodedSignature.substring(prefix.length())));
        } catch (Exception ex) {
            return false;
        }
    }

    public Admission applyAnnounce(
            Map<String, Object> frame,
            boolean signatureValid,
            Instant receivedAt) {
        if (!signatureValid) {
            return reject(NdpErrorCodes.NDP_ANNOUNCE_SIGNATURE_INVALID);
        }

        String nid = stringValue(frame, "nid");
        Instant timestamp = timeValue(frame, "timestamp");
        if (nid == null || nid.isEmpty() || timestamp == null) {
            return reject(NdpErrorCodes.NDP_ANNOUNCE_PROFILE_VIOLATION);
        }

        boolean sequencePresent = frame.containsKey("graph_seq");
        Long parsedSequence = unsignedInteger(frame.get("graph_seq"), Long.MAX_VALUE);
        Long ttl = unsignedInteger(frame.get("ttl"), 0xffff_ffffL);
        if ((sequencePresent && parsedSequence == null)
                || (!sequencePresent && !"local-dev".equals(securityProfile))
                || ttl == null) {
            return reject(NdpErrorCodes.NDP_ANNOUNCE_PROFILE_VIOLATION);
        }
        long sequence = parsedSequence == null ? 0 : parsedSequence;
        if (!bridgeShapeIsValid(frame)) {
            return reject(NdpErrorCodes.NDP_ANNOUNCE_PROFILE_VIOLATION);
        }
        if (!"local-dev".equals(securityProfile)
                && Math.abs(receivedAt.getEpochSecond() - timestamp.getEpochSecond()) > 300) {
            return reject(NdpErrorCodes.NDP_ANNOUNCE_SIGNATURE_INVALID);
        }

        String digest = sha256(canonicalAnnounceJson(frame));
        Long highest = sequences.get(nid);
        if (highest != null) {
            if (sequence < highest) {
                return reject(NdpErrorCodes.NDP_GRAPH_SEQ_ROLLBACK);
            }
            if (sequence == highest) {
                Entry current = entries.get(nid);
                if (current == null) return new Admission(Decision.DUPLICATE, null);
                if (!current.signedDigest().equals(digest)) {
                    return reject(NdpErrorCodes.NDP_ANNOUNCE_CONFLICT);
                }
                if (sameLiveness(current.frame(), frame)) {
                    return new Admission(Decision.DUPLICATE, null);
                }
                Instant expiresAt = freshnessDeadline(frame);
                if (expiresAt == null || !expiresAt.isAfter(receivedAt)) {
                    return reject(NdpErrorCodes.NDP_ANNOUNCE_STALE);
                }
                entries.put(nid, new Entry(copyMap(frame), digest, expiresAt));
                return new Admission(Decision.REFRESHED, null);
            }
        }

        if (ttl == 0) {
            sequences.put(nid, sequence);
            entries.remove(nid);
            return new Admission(Decision.REMOVED, null);
        }

        Instant expiresAt = freshnessDeadline(frame);
        if (expiresAt == null || !expiresAt.isAfter(receivedAt)) {
            return reject(NdpErrorCodes.NDP_ANNOUNCE_STALE);
        }
        sequences.put(nid, sequence);
        entries.put(nid, new Entry(copyMap(frame), digest, expiresAt));
        return new Admission(Decision.ACCEPTED, null);
    }

    public List<String> liveNids(Instant now) {
        return entries.entrySet().stream()
            .filter(item -> item.getValue().expiresAt().isAfter(now))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }

    public Map<String, Long> highestSequences() {
        return new TreeMap<>(sequences);
    }

    public boolean hasStaleEntry(Instant now) {
        return entries.values().stream()
            .anyMatch(entry -> !entry.expiresAt().isAfter(now));
    }

    public ClusterSelection resolveCluster(String clusterAnchor, Instant now) {
        record Member(String nid, long epoch) {}
        List<Member> members = entries.entrySet().stream()
            .filter(item -> item.getValue().expiresAt().isAfter(now))
            .filter(item -> clusterAnchor.equals(
                stringValue(item.getValue().frame(), "cluster_anchor")))
            .filter(item -> strings(item.getValue().frame(), "node_roles")
                .contains("anchor"))
            .map(item -> new Member(
                item.getKey(),
                numberValue(item.getValue().frame(), "cluster_epoch", 1)))
            .toList();
        if (members.isEmpty()) return new ClusterSelection(null, null, null);
        long top = members.stream().mapToLong(Member::epoch).max().orElse(1);
        List<String> leaders = members.stream()
            .filter(member -> member.epoch() == top)
            .map(Member::nid)
            .sorted()
            .toList();
        return leaders.size() == 1
            ? new ClusterSelection(leaders.getFirst(), top, null)
            : new ClusterSelection(
                null, null, NdpErrorCodes.NDP_CLUSTER_SPLIT);
    }

    public List<String> discoverBridges(
            String direction,
            String protocol,
            Instant now) {
        String field = switch (direction) {
            case "inbound" -> "bridge_inbound_protocols";
            case "outbound" -> "bridge_protocols";
            default -> throw new IllegalArgumentException(
                "Bridge direction must be 'inbound' or 'outbound'.");
        };
        return entries.entrySet().stream()
            .filter(item -> item.getValue().expiresAt().isAfter(now))
            .filter(item -> !"draining".equals(
                stringValue(item.getValue().frame(), "health")))
            .filter(item -> isBridge(item.getValue().frame()))
            .filter(item -> strings(item.getValue().frame(), field).contains(protocol))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }

    private static Admission reject(String errorCode) {
        return new Admission(Decision.REJECTED, errorCode);
    }

    private static boolean bridgeShapeIsValid(Map<String, Object> frame) {
        ProtocolList outbound = protocolList(frame, "bridge_protocols");
        ProtocolList inbound = protocolList(frame, "bridge_inbound_protocols");
        if (outbound == null || inbound == null) return false;
        return isBridge(frame)
            ? !outbound.values().isEmpty() || !inbound.values().isEmpty()
            : !outbound.present() && !inbound.present();
    }

    private record ProtocolList(boolean present, List<String> values) {}

    private static ProtocolList protocolList(
            Map<String, Object> frame,
            String field) {
        if (!frame.containsKey(field)) return new ProtocolList(false, List.of());
        Object value = frame.get(field);
        if (!(value instanceof List<?> list)) return null;
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) return null;
            values.add(text);
        }
        return new ProtocolList(true, values);
    }

    private static boolean isBridge(Map<String, Object> frame) {
        return strings(frame, "node_roles").contains("bridge")
            || "bridge".equals(stringValue(frame, "node_type"));
    }

    private static boolean sameLiveness(
            Map<String, Object> left,
            Map<String, Object> right) {
        return java.util.Objects.equals(left.get("health"), right.get("health"))
            && java.util.Objects.equals(left.get("last_seen"), right.get("last_seen"));
    }

    private static Instant freshnessDeadline(Map<String, Object> frame) {
        Instant source = timeValue(frame, "last_seen");
        if (source == null) source = timeValue(frame, "timestamp");
        return source == null
            ? null
            : source.plusSeconds(numberValue(frame, "ttl", 0));
    }

    private static String stringValue(Map<String, Object> frame, String field) {
        Object value = frame.get(field);
        return value instanceof String string ? string : null;
    }

    private static long numberValue(
            Map<String, Object> frame,
            String field,
            long fallback) {
        Object value = frame.get(field);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static Long unsignedInteger(Object value, long maximum) {
        if (value instanceof Byte || value instanceof Short ||
                value instanceof Integer || value instanceof Long) {
            long parsed = ((Number) value).longValue();
            return parsed >= 0 && parsed <= maximum ? parsed : null;
        }
        return null;
    }

    private static Instant timeValue(Map<String, Object> frame, String field) {
        String value = stringValue(frame, field);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static List<String> strings(
            Map<String, Object> frame,
            String field) {
        Object value = frame.get(field);
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .toList();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return (Map<String, Object>) withoutNulls(value);
    }

    private static Object withoutNulls(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            for (var item : map.entrySet()) {
                if (item.getKey() instanceof String key && item.getValue() != null) {
                    result.put(key, withoutNulls(item.getValue()));
                }
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(withoutNulls(item));
            return result;
        }
        return value;
    }
}
