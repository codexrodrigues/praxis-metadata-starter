package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandExecutionResult;
import org.praxisplatform.uischema.command.ResourceCommandHttpResponseAdapter;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.praxisplatform.uischema.command.ResourceCommandOutcome;
import org.praxisplatform.uischema.command.ResourceCommandResponsePolicy;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.praxisplatform.uischema.rest.failure.ResourceOperationFailure;
import org.praxisplatform.uischema.rest.failure.ResourceOperationFailureException;
import org.praxisplatform.uischema.rest.failure.ResourceOperationFailureKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Canonical MVC/Jackson wire proof; it does not prove host HTTP adoption or IAM. */
@WebMvcTest(value = CustomProblemDetailHttpSerializationTest.WireController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import({CustomProblemDetailHttpSerializationTest.WireController.class, GlobalExceptionHandler.class})
class CustomProblemDetailHttpSerializationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({
            "invalid,400,INVALID_PARAMETER,VALIDATION,name",
            "conflict,409,PUBLIC_DUPLICATE,BUSINESS_LOGIC,name",
            "unexpected,500,INTERNAL_SERVER_ERROR,SYSTEM,-",
            "unavailable,503,GOVERNED_OPENAPI_PUBLICATION_UNAVAILABLE,SYSTEM,-"
    })
    void handlerPayloadsContainUniqueTypedMembersAndPreserveCorrelation(
            String failure, int httpStatus, String code, String category, String target) throws Exception {
        assertThat(mapper.findMixInClassFor(ProblemDetail.class)).isNotNull();
        String raw = mockMvc.perform(get("/problem-wire/" + failure).header("X-Request-ID", "request-wire"))
                .andExpect(status().is(httpStatus)).andReturn().getResponse().getContentAsString();
        JsonNode body = strictTree(raw);
        assertThat(body.path("status").asText()).isEqualTo("failure");
        assertThat(body.path("errors").size()).isEqualTo(1);
        JsonNode problem = body.path("errors").get(0);
        assertThat(problem.path("status").asInt()).isEqualTo(httpStatus);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("category").asText()).isEqualTo(category);
        assertThat(problem.path("traceId").asText()).isEqualTo("request-wire");
        assertThat(problem.path("message").asText()).isNotBlank();
        assertThat(problem.has("properties")).isFalse();
        if (target.equals("-")) {
            assertThat(problem.has("target")).isFalse();
        } else {
            assertThat(problem.path("target").asText()).isEqualTo(target);
        }
        assertThat(raw).doesNotContain("PRIVATE_SQL", "PRIVATE_SECRET", "diagnostic-token",
                "\"cause\"", "\"stackTrace\"");
    }

    @Test
    void commandAdapterPreservesTheLegitimateOutcomeBesideUniqueTypedCodeAndTarget() throws Exception {
        String raw = mockMvc.perform(get("/problem-wire/command-conflict"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        JsonNode body = strictTree(raw);
        assertThat(body.path("status").asText()).isEqualTo("failure");
        assertThat(body.path("errors").size()).isEqualTo(1);
        JsonNode problem = body.path("errors").get(0);
        assertThat(problem.path("code").asText()).isEqualTo("PUBLIC_DUPLICATE");
        assertThat(problem.path("target").asText()).isEqualTo("name");
        assertThat(problem.path("outcome").asText()).isEqualTo("CONFLICT_DUPLICATE");
        assertThat(problem.path("category").asText()).isEqualTo("BUSINESS_LOGIC");
        assertThat(problem.has("properties")).isFalse();
    }

    private JsonNode strictTree(String json) throws Exception {
        try (JsonParser parser = mapper.createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return mapper.readTree(parser);
        }
    }

    @RestController
    static class WireController {
        @GetMapping("/problem-wire/command-conflict")
        ResponseEntity<?> commandConflict() {
            return new ResourceCommandHttpResponseAdapter().toResponse(new ResourceCommandExecutionResult(
                    ResourceCommandOutcome.CONFLICT_DUPLICATE,
                    ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT,
                    null, null,
                    List.of(new ResourceCommandMessage(ResourceCommandErrorCategory.CONFLICT_DUPLICATE,
                            "PUBLIC_DUPLICATE", "Public duplicate.", "name", Map.of())), Map.of()));
        }

        @GetMapping("/problem-wire/{failure}")
        Object failure(@PathVariable("failure") String failure) {
            var privateCause = new IllegalStateException("PRIVATE_SQL PRIVATE_SECRET=diagnostic-token");
            throw switch (failure) {
                case "invalid" -> new ResourceOperationFailureException(new ResourceOperationFailure(
                        ResourceOperationFailureKind.INVALID_INPUT, "INVALID_PARAMETER", "Public invalid input.",
                        "name"), privateCause);
                case "conflict" -> new ResourceOperationFailureException(new ResourceOperationFailure(
                        ResourceOperationFailureKind.CONFLICT_DUPLICATE, "PUBLIC_DUPLICATE", "Public duplicate.",
                        "name"), privateCause);
                case "unavailable" -> new GovernedOpenApiPublicationUnavailableException(privateCause);
                default -> privateCause;
            };
        }
    }
}
