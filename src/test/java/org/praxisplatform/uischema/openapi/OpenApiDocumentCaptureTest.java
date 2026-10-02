package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenApiDocumentCaptureTest {
    @Test
    void rejectsAutodetectedNonUtf8AndMalformedUtf8WireBytes() {
        var mapper = new ObjectMapper();
        String json = "{\"paths\":{},\"label\":\"ação\"}";
        for (var encoding : new java.nio.charset.Charset[]{StandardCharsets.UTF_16,
                StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE,
                java.nio.charset.Charset.forName("UTF-32LE"), java.nio.charset.Charset.forName("UTF-32BE")})
            assertThrows(IllegalStateException.class,
                    () -> OpenApiDocumentCapture.parse(json.getBytes(encoding), mapper));
        for (byte[] invalid : new byte[][]{
                {'{', '"', 'x', '"', ':', '"', (byte) 0xc0, (byte) 0xaf, '"', '}'},
                {'{', '"', 'x', '"', ':', '"', (byte) 0xed, (byte) 0xa0, (byte) 0x80, '"', '}'},
                {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'}})
            assertThrows(IllegalStateException.class, () -> OpenApiDocumentCapture.parse(invalid, mapper));
        assertThat(OpenApiDocumentCapture.parse(json.getBytes(StandardCharsets.UTF_8), mapper)
                .document().path("label").asText()).isEqualTo("ação");
    }
    @Test
    void preservesWireBytesAndTreeAsIndependentViewsOfOneCapture() throws Exception {
        byte[] wire = " { \"paths\" : {}, \"label\" : \"ação\" }\n".getBytes(StandardCharsets.UTF_8);
        byte[] expected = wire.clone();
        var capture = OpenApiDocumentCapture.parse(wire, new ObjectMapper());
        wire[0] = 0;
        byte[] exposed = capture.bytes();
        exposed[0] = 1;
        ((ObjectNode) capture.document()).put("label", "tampered");
        assertThat(capture.bytes()).isEqualTo(expected);
        assertThat(capture.document().path("label").asText()).isEqualTo("ação");
        assertThat(new ObjectMapper().readTree(capture.bytes())).isEqualTo(capture.document());
    }

    @Test
    void rejectsDuplicateMembersTrailingDocumentsAndNonObjectsWithoutReconfiguringTheSharedMapper() {
        var mapper = new ObjectMapper();
        for (String malformed : new String[]{"{\"paths\":{},\"paths\":{\"/hidden\":{}}}", "{} {}", "[]", "null", ""})
            assertThrows(IllegalStateException.class,
                    () -> OpenApiDocumentCapture.parse(malformed.getBytes(StandardCharsets.UTF_8), mapper));
        assertThat(mapper.getFactory().isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
                .isFalse();
        assertThat(mapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                .isFalse();
    }
    @Test
    void rejectsNonJsonWireSyntaxEvenWithAPermissiveHostMapper() {
        var mapper = new ObjectMapper();
        for (var feature : com.fasterxml.jackson.core.json.JsonReadFeature.values())
            mapper.getFactory().enable(feature.mappedFeature());
        for (String malformed : new String[]{"{/*comment*/\"paths\":{}}", "{'paths':{}}",
                "{paths:{}}", "{\"paths\":{},}", "{\"value\":NaN}", "{\"value\":01}"})
            assertThrows(IllegalStateException.class,
                    () -> OpenApiDocumentCapture.parse(malformed.getBytes(StandardCharsets.UTF_8), mapper));
        for (var feature : com.fasterxml.jackson.core.json.JsonReadFeature.values())
            assertThat(mapper.getFactory().isEnabled(feature.mappedFeature())).isTrue();
    }

}
