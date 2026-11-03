package com.labacacia.nps.alpha20;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.ConformanceFixtures;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FeatureWireParserTest {
    @TestFactory List<DynamicTest> sharedFixtures() throws Exception {
        var mapper = new ObjectMapper();
        var cases = mapper.readTree(Files.readString(ConformanceFixtures.resolve("alpha20/feature_negotiation_vectors.json"))).get("cases");
        assertEquals(44, cases.size());
        var tests = new ArrayList<DynamicTest>();
        for (var vector : cases) tests.add(DynamicTest.dynamicTest(vector.get("id").asText(), () -> {
            String error = vector.get("expected").path("error").asText();
            boolean nativeOffer = vector.get("kind").asText().equals("native-offer");
            var headers = new ArrayList<String>(); vector.path("headers").forEach(v -> headers.add(v.asText()));
            if (error.equals(FeatureWireParser.NATIVE_INVALID) || error.equals(FeatureWireParser.HTTP_INVALID)) {
                var exception = assertThrows(FeatureWireParser.FormatException.class, () -> {
                    if (nativeOffer) FeatureWireParser.parseOffer(vector.get("raw").asText());
                    else FeatureWireParser.parseRequiredHeader(headers);
                });
                assertEquals(error, exception.code());
            } else if (nativeOffer) {
                var offer = FeatureWireParser.parseOffer(vector.get("raw").asText());
                var input = mapper.readTree(vector.get("raw").asText());
                assertEquals(input.path("feature_offer").size(), offer.features().size());
                for (var item : input.path("feature_offer"))
                    assertEquals(item.get("revisions"), mapper.valueToTree(offer.features().get(item.get("id").asText())));
                var required = new ArrayList<String>(); input.path("required_features").forEach(v -> required.add(v.asText()));
                assertEquals(required, offer.required());
            } else assertEquals(vector.get("expected").get("required"), mapper.valueToTree(FeatureWireParser.parseRequiredHeader(headers)));
        }));
        return tests;
    }
    @Test void rawRegressions() {
        for (String token : List.of("1.0", "1e0", "-1", "null", "true"))
            assertThrows(FeatureWireParser.FormatException.class, () -> FeatureWireParser.parseOffer("{\"feature_negotiation\":" + token + "}"));
        for (String raw : List.of("{\"feature_negotiation\":1,\"feature_\\u006eegotiation\":1}", "{} {}",
            "{\"feature_negotiation\":1,\"required_features\":[\"example.echo\\ud800\"]}",
            "{\"feature_negotiation\":1,\"required_features\":[\"example.echo\ud800\"]}"))
            assertThrows(FeatureWireParser.FormatException.class, () -> FeatureWireParser.parseOffer(raw));
        for (String raw : List.of("example.echo=1\n", "example.echo\r=1", "example.echo=1\u2028"))
            assertThrows(FeatureWireParser.FormatException.class, () -> FeatureWireParser.parseRequiredHeader(List.of(raw)));
    }
    @Test void immutable() {
        var offer = FeatureWireParser.parseOffer("{\"feature_negotiation\":1,\"feature_offer\":[{\"id\":\"example.echo\",\"revisions\":[{\"revision\":1,\"lifecycle\":\"stable\"}]}]}");
        assertThrows(UnsupportedOperationException.class, () -> offer.features().clear());
        assertThrows(UnsupportedOperationException.class, () -> offer.features().get("example.echo").clear());
        assertThrows(UnsupportedOperationException.class, () -> offer.required().add("other.id"));
        assertThrows(UnsupportedOperationException.class, () -> FeatureWireParser.parseRequiredHeader(List.of("example.echo=1")).clear());
    }
}
