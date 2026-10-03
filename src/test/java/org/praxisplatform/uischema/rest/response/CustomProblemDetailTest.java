package org.praxisplatform.uischema.rest.response;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomProblemDetailTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void typedSettersReplaceAndClearWithoutCreatingExtensions() throws Exception {
        var problem = new CustomProblemDetail("Safe message");
        problem.setCode("A");
        problem.setTarget("fieldA");
        problem.setCode("B");
        problem.setTarget("fieldB");
        JsonNode replaced = strictTree(mapper.writeValueAsString(problem));
        assertThat(replaced.path("code").asText()).isEqualTo("B");
        assertThat(replaced.path("target").asText()).isEqualTo("fieldB");
        assertThat(problem.getProperties()).isEmpty();

        problem.setCode(null);
        problem.setTarget(null);
        assertThat(problem.getCode()).isNull();
        assertThat(problem.getTarget()).isNull();
        JsonNode cleared = strictTree(mapper.writeValueAsString(problem));
        assertThat(cleared.has("code")).isFalse();
        assertThat(cleared.has("target")).isFalse();
        for (String blank : new String[]{"", " ", "\t\n", "\u2003"}) {
            problem.setTarget("fieldA");
            problem.setTarget(blank);
            assertThat(problem.getTarget()).isNull();
            assertThat(strictTree(mapper.writeValueAsString(problem)).has("target")).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "title", "status", "detail", "instance",
            "message", "category", "code", "target", "properties"})
    void reservedMembersCannotBeInsertedOrPartiallyReplaceExtensions(String reserved) {
        var problem = populatedProblem();
        problem.setProperty("traceId", "request-original");
        Map<String, Object> original = problem.getProperties();
        assertThatThrownBy(() -> problem.setProperty(reserved, "shadow"))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("outcome", "CONFLICT_DUPLICATE");
        replacement.put(reserved, "shadow");
        assertThatThrownBy(() -> problem.setProperties(replacement))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(problem.getProperties()).isEqualTo(original);
        assertThat(problem.getCode()).isEqualTo("PUBLIC_CODE");
        assertThat(problem.getTarget()).isEqualTo("name");
        assertThat(problem.getMessage()).isEqualTo("Safe message");
        assertThat(problem.getCategory()).isEqualTo(ErrorCategory.VALIDATION);
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getType()).isEqualTo(URI.create("https://example.com/probs/validation-error"));
        assertThat(problem.getTitle()).isEqualTo("Invalid request");
        assertThat(problem.getDetail()).isEqualTo("Public detail");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/public-resource"));
    }

    @Test
    void nullExtensionNamesAreRejectedWithoutReplacingTheMap() {
        var problem = populatedProblem();
        problem.setProperty("traceId", "request-original");
        assertThatThrownBy(() -> problem.setProperty(null, "shadow"))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("outcome", "new-value");
        invalid.put(null, "shadow");
        assertThatThrownBy(() -> problem.setProperties(invalid))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(problem.getProperties()).containsOnlyKeys("traceId");
    }

    @Test
    void extensionSnapshotsAndCallerMapsCannotMutateTheProblem() {
        var problem = new CustomProblemDetail("Safe message");
        assertThat(problem.getProperties()).isEmpty();
        assertThatThrownBy(() -> problem.getProperties().put("code", "shadow"))
                .isInstanceOf(UnsupportedOperationException.class);
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("traceId", "request-original");
        caller.put("optionalEvidence", null);
        problem.setProperties(caller);
        caller.put("code", "shadow");
        caller.remove("traceId");
        Map<String, Object> snapshot = problem.getProperties();
        assertThat(snapshot).containsEntry("traceId", "request-original")
                .containsEntry("optionalEvidence", null).doesNotContainKey("code");
        assertThatThrownBy(() -> snapshot.put("code", "shadow"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.remove("traceId"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.entrySet().iterator().next().setValue("shadow"))
                .isInstanceOf(UnsupportedOperationException.class);
        problem.setProperty("outcome", "VALIDATION_FAILED");
        assertThat(snapshot).doesNotContainKey("outcome");
        assertThat(problem.getProperties()).containsEntry("outcome", "VALIDATION_FAILED");
        problem.setProperties(null);
        assertThat(problem.getProperties()).isEmpty();
        assertThat(snapshot).containsEntry("traceId", "request-original");
    }

    @Test
    void springMapperRoundTripsTypedRfcMembersAndLegitimateFlattenedExtensions() throws Exception {
        assertThat(mapper.findMixInClassFor(ProblemDetail.class)).isNotNull();
        var original = populatedProblem();
        original.setProperty("traceId", "request-123");
        original.setProperty("outcome", "VALIDATION_FAILED");
        original.setProperty("unknownExtension", Map.of("source", "public-client"));
        original.setProperty("optionalEvidence", null);
        String json = mapper.writeValueAsString(original);
        JsonNode tree = strictTree(json);
        assertThat(tree.has("properties")).isFalse();
        assertThat(tree.path("traceId").asText()).isEqualTo("request-123");
        assertThat(tree.path("outcome").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(tree.has("optionalEvidence")).isTrue();
        assertThat(tree.path("optionalEvidence").isNull()).isTrue();

        CustomProblemDetail decoded = mapper.readValue(json, CustomProblemDetail.class);
        assertThat(decoded.getMessage()).isEqualTo(original.getMessage());
        assertThat(decoded.getCategory()).isEqualTo(original.getCategory());
        assertThat(decoded.getCode()).isEqualTo(original.getCode());
        assertThat(decoded.getTarget()).isEqualTo(original.getTarget());
        assertThat(decoded.getType()).isEqualTo(original.getType());
        assertThat(decoded.getTitle()).isEqualTo(original.getTitle());
        assertThat(decoded.getStatus()).isEqualTo(original.getStatus());
        assertThat(decoded.getDetail()).isEqualTo(original.getDetail());
        assertThat(decoded.getInstance()).isEqualTo(original.getInstance());
        assertThat(decoded.getProperties()).isEqualTo(original.getProperties());
        assertThat(strictTree(mapper.writeValueAsString(decoded))).isEqualTo(tree);
    }

    @Test
    void plainMapperRoundTripsFlatTypedMembersAndExtensionsWithoutSpringMixin() throws Exception {
        ObjectMapper plain = new ObjectMapper();
        assertThat(plain.findMixInClassFor(ProblemDetail.class)).isNull();
        var original = populatedProblem();
        original.setProperty("traceId", "request-plain");
        original.setProperty("outcome", "VALIDATION_FAILED");
        original.setProperty("optionalEvidence", null);

        String json = plain.writeValueAsString(original);
        JsonNode tree = strictTree(plain, json);
        assertThat(tree.path("code").asText()).isEqualTo("PUBLIC_CODE");
        assertThat(tree.path("target").asText()).isEqualTo("name");
        assertThat(tree.path("traceId").asText()).isEqualTo("request-plain");
        assertThat(tree.path("outcome").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(tree.has("optionalEvidence")).isTrue();
        assertThat(tree.path("optionalEvidence").isNull()).isTrue();
        assertThat(tree.has("properties")).isFalse();

        var decoded = plain.readValue(json, CustomProblemDetail.class);
        assertThat(decoded.getCode()).isEqualTo(original.getCode());
        assertThat(decoded.getTarget()).isEqualTo(original.getTarget());
        assertThat(decoded.getType()).isEqualTo(original.getType());
        assertThat(decoded.getTitle()).isEqualTo(original.getTitle());
        assertThat(decoded.getStatus()).isEqualTo(original.getStatus());
        assertThat(decoded.getDetail()).isEqualTo(original.getDetail());
        assertThat(decoded.getInstance()).isEqualTo(original.getInstance());
        assertThat(decoded.getProperties()).isEqualTo(original.getProperties());
        assertThat(strictTree(plain, plain.writeValueAsString(decoded))).isEqualTo(tree);

        var empty = new CustomProblemDetail("Safe");
        assertThat(strictTree(plain, plain.writeValueAsString(empty)).has("properties")).isFalse();
        assertThatThrownBy(() -> strictTree(plain, "{\"code\":\"A\",\"code\":\"B\"}"))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("Duplicate field 'code'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"traceId\":\"legacy-wrapper\"}", "{\"code\":\"shadow\"}",
            "[]", "\"scalar-wrapper\"", "123", "true"})
    void objectReadPolicyRejectsEveryPropertiesWrapper(String wrapper) {
        assertThatThrownBy(() -> mapper.readValue(
                "{\"message\":\"Safe\",\"properties\":" + wrapper + "}",
                CustomProblemDetail.class)).isInstanceOf(JsonProcessingException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObjectMapper().readValue(
                "{\"message\":\"Safe\",\"properties\":" + wrapper + "}",
                CustomProblemDetail.class)).isInstanceOf(JsonProcessingException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void objectReadPolicyRejectsImplicitStringProblems() {
        assertThatThrownBy(() -> mapper.readValue("\"Safe\"", CustomProblemDetail.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void duplicateDetectionRejectsDuplicatesBeforeTreeMaterialization() {
        assertThatThrownBy(() -> strictTree("{\"code\":\"A\",\"code\":\"B\"}"))
                .isInstanceOf(JsonProcessingException.class).hasMessageContaining("Duplicate field 'code'");
    }

    private JsonNode strictTree(String json) throws Exception {
        return strictTree(mapper, json);
    }

    private JsonNode strictTree(ObjectMapper selectedMapper, String json) throws Exception {
        try (JsonParser parser = selectedMapper.createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return selectedMapper.readTree(parser);
        }
    }

    private CustomProblemDetail populatedProblem() {
        var problem = new CustomProblemDetail("Safe message");
        problem.setCategory(ErrorCategory.VALIDATION);
        problem.setCode("PUBLIC_CODE");
        problem.setTarget("name");
        problem.setType(URI.create("https://example.com/probs/validation-error"));
        problem.setTitle("Invalid request");
        problem.setStatus(400);
        problem.setDetail("Public detail");
        problem.setInstance(URI.create("/public-resource"));
        return problem;
    }
}
