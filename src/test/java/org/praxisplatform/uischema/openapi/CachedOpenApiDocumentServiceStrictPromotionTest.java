package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.id.SchemaIdBuilder;
import org.slf4j.Logger;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class CachedOpenApiDocumentServiceStrictPromotionTest {
    private static final String GROUP = "inventory";
    private static final String BASE_PATH = "/v3/api-docs";
    private static final String SCHEMA_ID = SchemaIdBuilder.build(
            "/inventory", "get", "response", false, "id", true);

    @Test
    void promotingPublicCacheToStrictMustFenceAndInvalidateHashesOrRejectWithoutChangingCache() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode oldDocument = document(mapper, "string");
        JsonNode changedDocument = document(mapper, "integer");
        var source = new MutableGroupSource(oldDocument);
        var service = new CachedOpenApiDocumentService(new RestTemplate(), mapper, source);
        ReflectionTestUtils.setField(service, "openApiBasePath", BASE_PATH);

        // The ordinary public read populates a non-strict cache entry, even for an exact group.
        JsonNode publicBefore = service.getDocumentForGroup(GROUP);
        String oldHash = service.getOrComputeSchemaHash(SCHEMA_ID, () -> schema(publicBefore));
        String changedHash = hash(schema(changedDocument));
        assertThat(publicBefore).isEqualTo(oldDocument);
        assertThat(oldHash).isEqualTo(hash(schema(oldDocument))).isNotEqualTo(changedHash);
        assertThat(source.fetches).containsExactly("public");

        List<GuardObservation> guards = new ArrayList<>();
        service.installBulkLifecycleInvalidationGuard(() -> {
            // Observe through public APIs: the fence must finish before either cache changes.
            // This unit callback records the durable-guard seam; it does not claim a DB transition.
            JsonNode visibleDocument = service.getDocumentForGroup(GROUP);
            var hashComputations = new AtomicInteger();
            String visibleHash = service.getOrComputeSchemaHash(SCHEMA_ID, () -> {
                hashComputations.incrementAndGet();
                return schema(visibleDocument);
            });
            guards.add(new GuardObservation(visibleDocument, visibleHash, hashComputations.get()));
        });

        source.replaceWith(changedDocument);
        JsonNode strictResult = null;
        RuntimeException rejection = null;
        try {
            strictResult = service.getDocumentForGroupStrict(GROUP);
        } catch (RuntimeException failure) {
            rejection = failure;
        }

        // Capture the whole outcome before asserting, so a regression reports both unfenced
        // document replacement and retention of the previous structural hash.
        JsonNode publicAfter = service.getDocumentForGroup(GROUP);
        var hashComputationsAfter = new AtomicInteger();
        String hashAfter = service.getOrComputeSchemaHash(SCHEMA_ID, () -> {
            hashComputationsAfter.incrementAndGet();
            return schema(publicAfter);
        });
        JsonNode observedStrictResult = strictResult;
        RuntimeException observedRejection = rejection;
        String observations = "source fetches=" + source.fetches + ", completed guards=" + guards.size()
                + ", public id type=" + schema(publicAfter).at("/properties/id/type").asText()
                + ", retained old hash=" + oldHash.equals(hashAfter)
                + ", hash computations after strict=" + hashComputationsAfter.get();

        assertSoftly(softly -> {
            for (GuardObservation guard : guards) {
                softly.assertThat(guard.document()).as("guard must precede document replacement: %s", observations)
                        .isEqualTo(oldDocument);
                softly.assertThat(guard.hash()).as("guard must precede hash invalidation: %s", observations)
                        .isEqualTo(oldHash);
                softly.assertThat(guard.hashComputations()).as("old hash must still be cached at guard: %s", observations)
                        .isZero();
            }
            if (observedRejection != null) {
                softly.assertThat(observedRejection).as("explicit fail-closed rejection")
                        .isInstanceOf(IllegalStateException.class);
                softly.assertThat(publicAfter).as("rejection must preserve old public document: %s", observations)
                        .isEqualTo(oldDocument);
                softly.assertThat(hashAfter).as("rejection must preserve old cached hash: %s", observations)
                        .isEqualTo(oldHash);
                softly.assertThat(hashComputationsAfter.get()).as("rejection must not evict old hash: %s", observations)
                        .isZero();
            } else {
                softly.assertThat(guards).as("successful replacement requires a completed guard: %s", observations)
                        .isNotEmpty();
                softly.assertThat(observedStrictResult).as("strict read must resolve the changed exact group")
                        .isEqualTo(changedDocument);
                softly.assertThat(publicAfter).as("public and strict documents must remain coherent: %s", observations)
                        .isEqualTo(changedDocument);
                // The new hash proves the old entry is gone, whether recomputation is eager or lazy.
                softly.assertThat(hashAfter).as("hash must describe the new public schema: %s", observations)
                        .isEqualTo(changedHash).isNotEqualTo(oldHash);
            }
        });
        // Recovery is explicit guarded refresh; a strict read must reject without invalidating.
        assertThat(observedRejection).isInstanceOf(IllegalStateException.class);
        assertThat(guards).isEmpty();
    }

    @Test
    void identicalStrictPromotionPreservesHashAndSubsequentExactReadsDoNotFetchAgain() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode original = document(mapper, "string");
        var source = new MutableGroupSource(original);
        var service = new CachedOpenApiDocumentService(new RestTemplate(), mapper, source);
        ReflectionTestUtils.setField(service, "openApiBasePath", BASE_PATH);
        var guardCalls = new AtomicInteger();
        service.installBulkLifecycleInvalidationGuard(guardCalls::incrementAndGet);

        JsonNode publicBefore = service.getDocumentForGroup(GROUP);
        String cachedHash = service.getOrComputeSchemaHash(SCHEMA_ID, () -> schema(publicBefore));
        source.replaceWith(document(mapper, "string"));
        assertThat(service.withSchemaCacheReadLock(() -> service.getDocumentForGroupStrict(GROUP)))
                .isEqualTo(original);

        // Once exact, this is still a cached read, not an implicit source refresh.
        source.replaceWith(document(mapper, "integer"));
        assertThat(service.getDocumentForGroupStrict(GROUP)).isEqualTo(original);
        assertThat(service.getDocumentForGroup(GROUP)).isEqualTo(original);
        assertThat(service.getOrComputeSchemaHash(SCHEMA_ID, () -> {
            throw new AssertionError("Identical promotion must preserve the cached schema hash");
        })).isEqualTo(cachedHash);
        assertThat(guardCalls).hasValue(0);
        assertThat(source.fetches).containsExactly("public", "strict");
    }

    @Test
    void firstStrictReadCanPopulateAnEmptyCacheWithoutInvalidation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode original = document(mapper, "string");
        var source = new MutableGroupSource(original);
        var service = new CachedOpenApiDocumentService(new RestTemplate(), mapper, source);
        ReflectionTestUtils.setField(service, "openApiBasePath", BASE_PATH);
        var guardCalls = new AtomicInteger();
        service.installBulkLifecycleInvalidationGuard(guardCalls::incrementAndGet);

        JsonNode strict = service.getDocumentForGroupStrict(GROUP);
        assertThat(strict).isEqualTo(original);
        assertThat(service.getDocumentForGroupStrict(GROUP)).isEqualTo(original);
        assertThat(service.getDocumentForGroup(GROUP)).isEqualTo(original);
        assertThat(service.getOrComputeSchemaHash(SCHEMA_ID, () -> schema(strict)))
                .isEqualTo(hash(schema(original)));
        assertThat(guardCalls).hasValue(0);
        assertThat(source.fetches).containsExactly("strict");
    }

    private static JsonNode document(ObjectMapper mapper, String idType) throws Exception {
        return mapper.readTree("""
                {"openapi":"3.1.0","info":{"title":"Inventory","version":"1"},
                 "paths":{"/inventory":{"get":{"operationId":"inventory.read",
                   "responses":{"200":{"description":"Inventory item","content":{
                     "application/json":{"schema":{"$ref":"#/components/schemas/Inventory"}}}}}}}},
                 "components":{"schemas":{"Inventory":{"type":"object",
                   "properties":{"id":{"type":"%s"}}}}}}
                """.formatted(idType));
    }

    private static JsonNode schema(JsonNode document) {
        return document.at("/components/schemas/Inventory").deepCopy();
    }

    private static String hash(JsonNode schema) {
        return SchemaHashUtil.sha256Hex(new SchemaCanonicalizer().canonicalize(schema));
    }

    private record GuardObservation(JsonNode document, String hash, int hashComputations) { }

    /** A real mutable exact-group source; all reads return independent JSON trees. */
    private static final class MutableGroupSource extends OpenApiDocsSupport {
        private JsonNode document;
        private final List<String> fetches = new ArrayList<>();

        private MutableGroupSource(JsonNode document) {
            replaceWith(document);
        }

        private void replaceWith(JsonNode replacement) {
            document = replacement.deepCopy();
        }

        @Override
        public JsonNode fetchOpenApiDocument(RestTemplate client, String basePath, String group, Logger logger) {
            return read("public", basePath, group);
        }

        @Override
        public JsonNode fetchOpenApiGroupDocument(RestTemplate client, String basePath, String group, Logger logger) {
            return read("strict", basePath, group);
        }

        @Override
        public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate client, String basePath, String group, Logger logger) {
            return read("fresh", basePath, group);
        }

        private JsonNode read(String kind, String basePath, String group) {
            if (!GROUP.equals(group) || !BASE_PATH.equals(basePath))
                throw new IllegalArgumentException("Only the exact inventory group is available");
            fetches.add(kind);
            return document.deepCopy();
        }
    }
}
