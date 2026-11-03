package com.labacacia.nps.alpha20;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Experimental feature-only parsers. No session negotiation, admission or advertisement. */
public final class FeatureWireParser {
    private FeatureWireParser() {}
    public static final String NATIVE_INVALID = "NCP-FEATURE-NEGOTIATION-INVALID";
    public static final String HTTP_INVALID = "NWP-FEATURE-REQUIREMENTS-INVALID";
    private static final JsonFactory FACTORY = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build();
    private static final ObjectMapper MAPPER = new ObjectMapper(FACTORY);
    public record Revision(int revision, String lifecycle) {}
    public record Selection(String id, int revision) {}
    public record Offer(Map<String, List<Revision>> features, List<String> required) {
        public Offer {
            var copy = new LinkedHashMap<String, List<Revision>>();
            features.forEach((id, revisions) -> copy.put(id, List.copyOf(revisions)));
            features = Collections.unmodifiableMap(copy);
            required = List.copyOf(required);
        }
    }
    public static final class FormatException extends IllegalArgumentException {
        private final String code;
        public FormatException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    private static void need(boolean condition) { if (!condition) throw new IllegalArgumentException(); }
    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                need(++i < value.length() && Character.isLowSurrogate(value.charAt(i)));
            } else need(!Character.isLowSurrogate(c));
        }
    }
    private static String name(String value) {
        need(value != null && value.length() <= 96 && value.matches("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_-]*)+"));
        return value;
    }
    private static int integer(String token) {
        need(token.matches("[1-9][0-9]{0,4}"));
        int value = Integer.parseInt(token); need(value <= 65535); return value;
    }
    private static void members(JsonNode value, String... allowed) {
        need(value != null && value.isObject());
        var names = Set.of(allowed);
        value.fieldNames().forEachRemaining(key -> need(names.contains(key)));
    }
    private static void array(JsonNode value, int minimum, int maximum) {
        need(value != null && value.isArray() && value.size() >= minimum && value.size() <= maximum);
    }
    private static String text(JsonNode value) { need(value != null && value.isTextual()); return value.textValue(); }

    /** Parse a standalone feature block; full Hello payload bounds are a carrier responsibility. */
    public static Offer parseOffer(String raw) {
        try {
            need(raw != null); unicode(raw);
            need(raw.getBytes(StandardCharsets.UTF_8).length <= 16384);
            // Token pass rejects duplicate decoded names, unpaired escaped surrogates,
            // and fraction/exponent syntax before tree conversion erases lexemes.
            try (JsonParser parser = FACTORY.createParser(raw)) {
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) unicode(parser.getText());
                    if (token.isNumeric()) { need(token == JsonToken.VALUE_NUMBER_INT); integer(parser.getText()); }
                }
            }
            JsonNode block;
            try (JsonParser parser = FACTORY.createParser(raw)) {
                block = MAPPER.readTree(parser); need(parser.nextToken() == null);
            }
            members(block, "feature_negotiation", "feature_offer", "required_features");
            if (!block.isEmpty()) {
                var version = block.get("feature_negotiation");
                need(version != null && version.isIntegralNumber() && version.intValue() == 1);
            }
            var features = new LinkedHashMap<String, List<Revision>>();
            if (block.has("feature_offer")) {
                var offers = block.get("feature_offer"); array(offers, 0, 32);
                for (var item : offers) {
                    members(item, "id", "revisions");
                    String id = name(text(item.get("id"))); need(!features.containsKey(id));
                    var revisions = item.get("revisions"); array(revisions, 1, 16);
                    var parsed = new ArrayList<Revision>(); int last = 0;
                    for (var entry : revisions) {
                        members(entry, "revision", "lifecycle");
                        var number = entry.get("revision"); need(number != null && number.isIntegralNumber());
                        int revision = number.intValue(); need(revision > last && revision <= 65535);
                        String lifecycle = text(entry.get("lifecycle"));
                        need(Set.of("experimental", "stable", "deprecated").contains(lifecycle));
                        parsed.add(new Revision(revision, lifecycle)); last = revision;
                    }
                    features.put(id, parsed);
                }
            }
            var required = new ArrayList<String>();
            if (block.has("required_features")) {
                var names = block.get("required_features"); array(names, 0, 32);
                for (var entry : names) {
                    String id = name(text(entry)); need(features.containsKey(id) && !required.contains(id)); required.add(id);
                }
            }
            return new Offer(features, required);
        } catch (java.io.IOException | IllegalArgumentException error) { throw new FormatException(NATIVE_INVALID); }
    }

    /** Accept raw header instances before framework joining; missing differs from empty. */
    public static List<Selection> parseRequiredHeader(List<String> instances) {
        try {
            need(instances != null && instances.size() <= 1);
            if (instances.isEmpty()) return List.of();
            String raw = instances.getFirst(); need(raw != null && raw.length() <= 4096 && raw.chars().allMatch(c -> c <= 127));
            String[] pairs = raw.split(",", -1); need(pairs.length <= 32);
            var selected = new ArrayList<Selection>(); String previous = "";
            for (String pair : pairs) {
                String[] parts = pair.split("=", -1); need(parts.length == 2);
                String id = name(parts[0]); need(id.compareTo(previous) > 0);
                selected.add(new Selection(id, integer(parts[1]))); previous = id;
            }
            return List.copyOf(selected);
        } catch (IllegalArgumentException error) { throw new FormatException(HTTP_INVALID); }
    }
}
