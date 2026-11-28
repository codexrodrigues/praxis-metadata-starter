package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Immutable response bytes (gzip-compressed on heap) and their exact JSON interpretation from one source read.
 */
public final class OpenApiDocumentCapture {
    private static final ObjectMapper STRICT_REHYDRATION_MAPPER = new ObjectMapper();

    private final byte[] compressedBytes;
    private final int rawLength;
    private final OpenApiCanonicalOperationResolver.GroupOperationSummary operationSummary;
    private volatile SoftReference<JsonNode> cachedDocument;

    private OpenApiDocumentCapture(
            byte[] compressedBytes,
            int rawLength,
            OpenApiCanonicalOperationResolver.GroupOperationSummary operationSummary,
            JsonNode document) {
        this.compressedBytes = compressedBytes;
        this.rawLength = rawLength;
        this.operationSummary = operationSummary;
        this.cachedDocument = new SoftReference<>(document);
    }

    /** Rejects ambiguous or non-object source responses before they can be published. */
    public static OpenApiDocumentCapture parse(byte[] response, ObjectMapper mapper) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(mapper, "mapper");
        byte[] captured = response.clone();
        JsonNode document = parseStrictObject(captured, mapper);
        OpenApiCanonicalOperationResolver.GroupOperationSummary summary =
                OpenApiCanonicalOperationResolver.extractGroupOperationSummary(document);
        byte[] compressed = gzip(captured);
        return new OpenApiDocumentCapture(compressed, captured.length, summary, document);
    }

    public byte[] bytes() {
        return gunzip(compressedBytes, rawLength);
    }

    public JsonNode document() {
        return readOnlyDocument().deepCopy();
    }

    JsonNode readOnlyDocument() {
        JsonNode document = cachedDocument.get();
        if (document != null) {
            return document;
        }
        synchronized (this) {
            document = cachedDocument.get();
            if (document == null) {
                document = parseStrictObject(bytes(), STRICT_REHYDRATION_MAPPER);
                cachedDocument = new SoftReference<>(document);
            }
            return document;
        }
    }

    OpenApiCanonicalOperationResolver.GroupOperationSummary operationSummary() {
        return operationSummary;
    }

    private static JsonNode parseStrictObject(byte[] rawBytes, ObjectMapper mapper) {
        try {
            // Decode explicitly: Jackson's byte parser can otherwise autodetect UTF-16/32,
            // although the response will be served unchanged as application/json UTF-8.
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBytes)).toString();
            try (JsonParser parser = mapper.getFactory().createParser(json)) {
                // Published bytes must remain valid for strict JSON clients even if the host mapper is permissive.
                for (var feature : com.fasterxml.jackson.core.json.JsonReadFeature.values())
                    parser.disable(feature.mappedFeature());
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode document = mapper.readerFor(JsonNode.class)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(parser);
                if (document == null || !document.isObject())
                    throw new IllegalStateException("OpenAPI source response must be one JSON object");
                return document;
            }
        } catch (IOException malformed) {
            throw new IllegalStateException("OpenAPI source response is malformed or ambiguous", malformed);
        }
    }

    private static byte[] gzip(byte[] rawBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, rawBytes.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(rawBytes);
        } catch (IOException io) {
            throw new IllegalStateException("Failed to compress captured OpenAPI response", io);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed, int expectedLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expectedLength);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException io) {
            throw new IllegalStateException("Failed to decompress captured OpenAPI response", io);
        }
        byte[] result = out.toByteArray();
        if (result.length != expectedLength) {
            throw new IllegalStateException("Decompressed OpenAPI response length mismatch");
        }
        return result;
    }
}
