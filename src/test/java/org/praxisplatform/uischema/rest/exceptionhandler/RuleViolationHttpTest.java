package org.praxisplatform.uischema.rest.exceptionhandler;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.uischema.command.*;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.runtime.*;
import org.praxisplatform.rules.violation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = RuleViolationHttpTest.Controller.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import({RuleViolationHttpTest.Controller.class, GlobalExceptionHandler.class})
class RuleViolationHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @BeforeEach void reset() { Controller.writes.set(0); }

    @ParameterizedTest
    @CsvSource({"DENY,BUSINESS_LOGIC", "NOT_APPLICABLE,BUSINESS_LOGIC", "INCONCLUSIVE,BUSINESS_LOGIC", "TECHNICAL_ERROR,BUSINESS_LOGIC"})
    void actualEngineRejectionsHave422UniqueRfcMembersFieldTargetsAndNoSideEffects(String decision, String category) throws Exception {
        var response = mvc.perform(post("/rule-wire/" + decision).header("X-Request-ID", "rule-correlation"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse();
        String raw = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body;
        try (JsonParser parser = mapper.createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            body = mapper.readTree(parser);
        }
        assertThat(body.path("status").asText()).isEqualTo("failure");
        JsonNode problem = body.path("errors").get(0);
        assertThat(problem.path("status").asInt()).isEqualTo(422);
        assertThat(problem.path("decision").asText()).isEqualTo(decision);
        assertThat(problem.path("category").asText()).isEqualTo(category);
        assertThat(problem.path("traceId").asText()).isEqualTo("rule-correlation");
        assertThat(problem.has("properties")).isFalse();
        if (!decision.equals("TECHNICAL_ERROR")) {
            assertThat(problem.path("code").asText()).isEqualTo("LIMIT");
            assertThat(problem.path("target").asText()).isEqualTo("amount");
            assertThat(problem.path("violations").get(0).path("targetField").asText()).isEqualTo("amount");
            assertThat(problem.path("violations").get(0).path("source").asText()).isEqualTo("PRODUCT");
            assertThat(problem.path("reasonCodes").get(0).asText()).isEqualTo("LIMIT");
            assertThat(problem.path("slotKeys").get(0).asText()).isEqualTo("limit");
            assertThat(problem.path("message").asText()).isEqualTo("Limite excedido.");
        } else {
            assertThat(problem.path("code").asText()).isEqualTo("FACTS_INVALID");
            assertThat(problem.hasNonNull("target")).isFalse();
            assertThat(problem.path("slotKeys").isEmpty()).isTrue();
        }
        assertThat(raw).doesNotContain("PRIVATE_SECRET", "stackTrace", "cause", "bindingResults", "factsDigest");
        assertThat(Controller.writes.get()).isZero();
    }

    @Test void allowReachesTheOperationOnce() throws Exception {
        mvc.perform(post("/rule-wire/ALLOW")).andExpect(status().isOk());
        assertThat(Controller.writes.get()).isEqualTo(1);
    }

    @Test void conflictCategoryUses409InResponseAndProblemAndRetainsTheCatalogMessage() throws Exception {
        var response = mvc.perform(post("/rule-wire/conflict"))
                .andExpect(status().isConflict()).andReturn().getResponse();
        var body = mapper.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var error = body.path("errors").get(0);
        assertThat(error.path("status").asInt()).isEqualTo(409);
        assertThat(error.path("category").asText()).isEqualTo("CONFLICT");
        assertThat(error.at("/violations/0/category").asText()).isEqualTo("conflict");
        assertThat(error.path("message").asText()).isEqualTo("Estado incompatível.");
        assertThat(Controller.writes.get()).isZero();
    }

    @Test void customFieldCommandFailurePreservesTheCanonicalValidationTarget() throws Exception {
        var response = mvc.perform(post("/rule-wire/command/custom"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        var error = mapper.readTree(response.getContentAsString()).path("errors").get(0);
        assertThat(error.path("target").asText()).isEqualTo("customFields.amount");
        assertThat(Controller.writes.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENY", "NOT_APPLICABLE", "INCONCLUSIVE", "TECHNICAL_ERROR"})
    void governedCommandsPreserveThe422RuleContract(String decision) throws Exception {
        var response = mvc.perform(post("/rule-wire/command/" + decision))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse();
        var problem = mapper.readTree(response.getContentAsString()).path("errors").get(0);
        assertThat(problem.path("decision").asText()).isEqualTo(decision);
        assertThat(problem.path("violations").isArray()).isTrue();
        assertThat(problem.path("code").asText()).isEqualTo(decision.equals("TECHNICAL_ERROR") ? "FACTS_INVALID" : "LIMIT");
        assertThat(Controller.writes.get()).isZero();
    }

    @Test void multipleFieldRejectionsPublishOneAggregateAndKeepEveryCorrectionTarget() throws Exception {
        var response = mvc.perform(post("/rule-wire/NOT_APPLICABLE")).andExpect(status().isUnprocessableEntity()).andReturn().getResponse();
        var errors = mapper.readTree(response.getContentAsString()).path("errors");
        assertThat(errors.size()).isEqualTo(2);
        assertThat(errors.get(0).path("violations").size()).isEqualTo(2);
        assertThat(errors.get(0).path("target").asText()).isEqualTo("amount");
        assertThat(errors.get(1).path("target").asText()).isEqualTo("capacity");
        assertThat(errors.get(1).has("violations")).isFalse();
    }

    @Test void customFieldConstraintRejectionUsesTheExistingFieldValidationWireBeforeMutation() throws Exception {
        var response = mvc.perform(post("/rule-wire/custom")).andExpect(status().isBadRequest()).andReturn().getResponse();
        var error = mapper.readTree(response.getContentAsString()).path("errors").get(0);
        assertThat(error.path("target").asText()).isEqualTo("customFields.amount");
        assertThat(Controller.writes.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_APPLICABLE", "INCONCLUSIVE"})
    void mixedBranchesExposeOnlyTheActualDenialTargetThroughGovernedCommands(String prior) throws Exception {
        var response = mvc.perform(post("/rule-wire/command/MIXED_" + prior))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse();
        var errors = mapper.readTree(response.getContentAsString()).path("errors");
        assertThat(errors.size()).isEqualTo(1);
        assertThat(errors.get(0).path("code").asText()).isEqualTo("CAPACITY");
        assertThat(errors.get(0).path("target").asText()).isEqualTo("capacity");
        assertThat(errors.get(0).path("reasonCodes").toString()).isEqualTo("[\"CAPACITY\"]");
        assertThat(errors.get(0).path("violations").size()).isEqualTo(1);
        assertThat(Controller.writes.get()).isZero();
    }

    @RestController
    static class Controller {
        static final AtomicInteger writes = new AtomicInteger();
        static final ObjectMapper JSON = new ObjectMapper();
        final GovernedRuleEngineTemplate template = new GovernedRuleEngineTemplate(RuleBindingExecutorRegistry.empty(),
                RuleErrorCatalog.of(Map.of("LIMIT", new RuleErrorDescriptor("LIMIT", "amount", "Limite excedido.", "policy:limit", "LIMIT"),
                        "CAPACITY", new RuleErrorDescriptor("CAPACITY", "capacity", "Capacidade insuficiente.", "policy:capacity", "CAPACITY"))));

        @PostMapping("/rule-wire/command/{decision}")
        org.springframework.http.ResponseEntity<?> command(@PathVariable String decision) {
            var executor = new GovernedResourceCommandExecutor(request -> {
                try {
                    if (decision.equals("custom")) { custom(); return null; }
                    return ResourceCommandExecutionResult.success(request, request.resourceId(), execute(decision), Map.of());
                } catch (RuntimeException ex) { throw ex; }
                catch (Exception ex) { throw new IllegalStateException(ex); }
            });
            var request = ResourceCommandExecutionRequest.item("enterprise.requests", "/rule-wire", "approve", 42L,
                    null, ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT);
            return new ResourceCommandHttpResponseAdapter().toResponse(executor.execute(request));
        }

        @PostMapping("/rule-wire/conflict")
        void conflict() {
            throw new PraxisRuleViolationException(new SlotViolationDetail("STATE", "state", "state.product", DecisionSource.PRODUCT,
                    DecisionStage.DOMAIN_DECISION, RuleDecision.DENY, "status", "Estado incompatível.", null, "conflict"));
        }

        @PostMapping("/rule-wire/{decision}")
        Map<String, Object> execute(@PathVariable String decision) throws Exception {
            boolean mixed = decision.startsWith("MIXED_");
            RuleDecision requested = mixed ? RuleDecision.DENY : RuleDecision.valueOf(decision);
            var definition = new RuleSetDefinition(new RuleSetRef("enterprise", "requests", "requests.limit", "create", 1),
                    List.of("draft"), List.of(new DecisionSlot("limit", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                    OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null),
                    new DecisionSlot("capacity", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                            OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null)),
                    List.of(new DecisionBinding("limit.product", "limit", DecisionSource.PRODUCT, null,
                    new RuleExecutorRef(RuleExecutorType.JSON_LOGIC, null, "1.0", JSON.readTree("{\"==\":[{\"var\":\"draft.valid\"},true]}")),
                    List.of(), 0, true, mixed ? RuleDecision.valueOf(decision.substring("MIXED_".length()))
                            : requested == RuleDecision.ALLOW || requested == RuleDecision.TECHNICAL_ERROR ? RuleDecision.DENY : requested,
                    "LIMIT", List.of("draft.valid"), null),
                    new DecisionBinding("capacity.product", "capacity", DecisionSource.PRODUCT, null,
                            new RuleExecutorRef(RuleExecutorType.JSON_LOGIC, null, "1.0", JSON.readTree("{\"==\":[{\"var\":\"draft.valid\"},true]}")),
                            List.of(), 1, true, requested == RuleDecision.ALLOW || requested == RuleDecision.TECHNICAL_ERROR ? RuleDecision.DENY : requested,
                            "CAPACITY", List.of("draft.valid"), null)), RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
            var facts = requested == RuleDecision.TECHNICAL_ERROR ? null : JSON.createObjectNode()
                    .put("private", "PRIVATE_SECRET").set("draft", JSON.createObjectNode().put("valid", requested == RuleDecision.ALLOW));
            template.enforce(template.compile(definition, "tenant-a"), facts, "2026-11-01T12:00:00Z", "UTC");
            writes.incrementAndGet();
            return Map.of("accepted", true);
        }

        record Draft(@org.praxisplatform.uischema.customfields.CustomFields Map<String, Object> customFields) { }
        @PostMapping("/rule-wire/custom")
        void custom() {
            var field = new org.praxisplatform.uischema.customfields.CustomFieldDescriptor("amount", "Amount", "Requested amount",
                    org.praxisplatform.uischema.customfields.CustomFieldDataType.INTEGER, null, null, 0, false, false,
                    false, false, false, null, null, null, null, null, null, null, null, List.of(), List.of(), null, null, Map.of(),
                    Map.of("jsonLogic", Map.of("<=", List.of(Map.of("var", "value"), 10)), "message", "Limite excedido."));
            new org.praxisplatform.uischema.customfields.CustomFieldValidator((r,t,s) -> List.of(field))
                    .validate(new Draft(Map.of("amount", 11)), "allocations", "a");
            writes.incrementAndGet();
        }
    }
}
