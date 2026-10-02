package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable response bytes and their exact JSON interpretation from one source read. */
public final class OpenApiDocumentCapture {
    private final byte[] bytes;
    private final JsonNode document;

    private OpenApiDocumentCapture(byte[] bytes, JsonNode document) {
        this.bytes = bytes;
        this.document = document;
    }

    /** Rejects ambiguous or non-object source responses before they can be published. */
    public static OpenApiDocumentCapture parse(byte[] response, ObjectMapper mapper) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(mapper, "mapper");
        byte[] captured = response.clone();
        try {
            // Decode explicitly: Jackson's byte parser can otherwise autodetect UTF-16/32,
            // although the response will be served unchanged as application/json UTF-8.
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(captured)).toString();
            try (JsonParser parser = mapper.getFactory().createParser(json)) {
                // Published bytes must remain valid for strict JSON clients even if the host mapper is permissive.
                for (var feature : com.fasterxml.jackson.core.json.JsonReadFeature.values())
                    parser.disable(feature.mappedFeature());
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode document = mapper.readerFor(JsonNode.class)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(parser);
                if (document == null || !document.isObject())
                    throw new IllegalStateException("OpenAPI source response must be one JSON object");
                return new OpenApiDocumentCapture(captured, document);
            }
        } catch (IOException malformed) {
            throw new IllegalStateException("OpenAPI source response is malformed or ambiguous", malformed);
        }
    }

    public byte[] bytes() { return bytes.clone(); }
    public JsonNode document() { return document.deepCopy(); }
}
