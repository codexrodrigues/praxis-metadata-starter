package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.ServletContext;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityContext;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityProvider;
import org.praxisplatform.uischema.controller.base.AbstractCreateUpdateResourceController;
import org.praxisplatform.uischema.e2e.fixture.E2eFixtureApplication;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.id.SchemaIdBuilder;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.CanonicalOpenApiGroupSnapshot;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** TCP/schema discovery plus real PostgreSQL lifecycle; does not claim a host domain mutation or grant model. */
@SpringBootTest(classes = E2eFixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.cache.disabled=true")
@ActiveProfiles("e2e-h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({BulkCrudOperationalCapabilityHttpTest.Controller.class, BulkCrudOperationalCapabilityHttpTest.Configuration.class})
class BulkCrudOperationalCapabilityHttpTest {
    static final String RESOURCE = "crud-operational.items";
    static final String PATH = "/crud-operational-items";
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired BulkOperationLifecycle lifecycle;
    @Autowired OpenApiDocumentService documents;
    @Autowired CanonicalOperationResolver operations;
    @Autowired Probe probe;
    @Autowired ProducerGenerationProbe producer;
    @Autowired ServletContext servletContext;
    @Autowired BulkCrudOperationalLifecyclePostgresTest.Store store;

    @Test
    void readyCrudAndCommandReusePublishedPhotographOnBothCapabilityRoutesAndServeEverySchemaRef() throws Exception {
        int coldGenerations = producer.visits.get();
        assertPublicationUnavailable(PATH + "/capabilities");
        assertEquals(coldGenerations, producer.visits.get(),
                "cold capability discovery must not invoke the producer outside publication");
        withLocalRequest(() -> {
            int beforePublication = producer.visits.get();
            lifecycle.publish(identity("crud.uniform"), 0);
            int generatedForPublication = producer.visits.get();
            assertTrue(generatedForPublication > beforePublication,
                    "first publication must invoke the real SpringDoc producer");
            lifecycle.publish(identity("crud.items"), 0);
            lifecycle.publish(identity("crud.uniform-atomic"), 0);
            lifecycle.publish(identity("crud.items-atomic"), 0);
            lifecycle.publish(identity("crud.command"), 0);
            assertEquals(generatedForPublication, producer.visits.get(),
                    "subsequent operations must share the published document generation");
            Map<String, CanonicalOpenApiGroupSnapshot> snapshots = new java.util.HashMap<>();
            var canonicalizer = new SchemaCanonicalizer();
            for (String family : List.of("uniform", "items", "uniform-atomic", "items-atomic", "command")) {
                var operation = operations.requireResourceOperation(RESOURCE, "crud." + family + ".evaluation", "POST");
                var snapshot = snapshots.computeIfAbsent(operation.group(), group -> CanonicalOpenApiGroupSnapshot.capture(documents, group));
                String pointer = family.startsWith("items") ? BulkCrudOperationalCompositionTest.ITEMS_POINTER
                        : BulkCrudOperationalCompositionTest.UNIFORM_POINTER;
                JsonNode actual = snapshot.requireRequestSchema(operation).schema().at(pointer);
                assertEquals(canonicalizer.canonicalize(BulkIdentityCodecs.integers().canonicalWireSchema()),
                        canonicalizer.canonicalize(actual), family + " published identity schema: " + actual);
            }
            return null;
        });
        int publishedGenerations = producer.visits.get();
        for (String endpoint : List.of(PATH + "/capabilities", PATH + "/7/capabilities")) {
            probe.visits.set(0);
            var response = rest.getForEntity(url(endpoint), JsonNode.class);
            assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
            assertEquals(publishedGenerations, producer.visits.get(),
                    "capability discovery must reuse the installed photograph without regenerating SpringDoc");
            assertEquals(4, probe.visits.get());
            JsonNode body = response.getBody();
            assertNotNull(body);
            for (String id : List.of("bulk-update", "bulk-update-items", "bulk-update-atomic", "bulk-update-items-atomic")) {
                JsonNode operation = body.path("operations").path(id);
                assertTrue(operation.path("supported").asBoolean());
                assertEquals("COLLECTION", operation.path("scope").asText());
                assertTrue(operation.path("availability").path("allowed").asBoolean());
                JsonNode bulk = operation.path("bulk");
                assertFalse(bulk.has("parametersPointer"));
                assertEquals(id.startsWith("bulk-update-items") ? "PER_ITEM_UPDATE" : "UNIFORM_UPDATE", bulk.path("mode").asText());
                assertEquals(id.endsWith("-atomic") ? "ATOMIC" : "PER_ITEM", bulk.path("atomicity").asText());
                JsonNode editable = bulk.path("editableFields");
                assertEquals("crud.update", editable.at("/sourceOperation/operationId").asText());
                assertEquals(List.of("active", "count", "note"), mapper.convertValue(editable.path("writableFields"), List.class));
                assertEquals(List.of("note"), mapper.convertValue(editable.path("clearableFields"), List.class));
                assertSchema(editable.path("sourceOperation"), editable.path("requestSchema"));
                for (String role : List.of("evaluationOperation", "confirmationOperation", "proposalOperation", "proposalResultsOperation",
                        "executionOperation", "resultsOperation", "cancelOperation")) {
                    JsonNode ref = bulk.path(role);
                    assertSchema(ref.path("operation"), ref.path("responseSchema"));
                    if (ref.has("requestSchema")) assertSchema(ref.path("operation"), ref.path("requestSchema"));
                }
            }
            if (endpoint.equals(PATH + "/capabilities")) {
                JsonNode command = body.path("actions").findValue("bulk");
                assertNotNull(command);
                assertEquals("DOMAIN_COMMAND", command.path("mode").asText());
                assertEquals("/properties/parameters", command.path("parametersPointer").asText());
                assertFalse(command.has("editableFields"));
            }
        }
        var deniedHeaders = new HttpHeaders(); deniedHeaders.set("X-Fixture-Deny", "true");
        var denied = rest.exchange(url(PATH + "/capabilities"), HttpMethod.GET, new HttpEntity<>(deniedHeaders), JsonNode.class);
        assertEquals(200, denied.getStatusCode().value());
        assertFalse(denied.getBody().at("/operations/bulk-update/availability/allowed").asBoolean());
        assertTrue(denied.getBody().at("/operations/bulk-update/bulk").isObject(), "authorization does not erase structural evidence");
        assertRealProtocolPayloads();
        assertEquals(publishedGenerations, producer.visits.get(),
                "schema references and protocol reads must not regenerate the producer");
        long suspendedGeneration = withLocalRequest(() -> lifecycle.suspend(identity("crud.uniform"), 1));
        withLocalRequest(() -> {
            for (String id : List.of("crud.uniform", "crud.items", "crud.uniform-atomic", "crud.items-atomic", "crud.command")) {
                assertThrows(IllegalStateException.class, () -> lifecycle.requireReady(identity(id)),
                        id + " must lose READY after global suspension");
            }
            return null;
        });
        assertPublicationUnavailable(PATH + "/capabilities");
        assertEquals(publishedGenerations, producer.visits.get(),
                "global suspension and failed reads must not regenerate the producer");
        withLocalRequest(() -> {
            lifecycle.publish(identity("crud.uniform"), suspendedGeneration);
            return null;
        });
        int republishedGenerations = producer.visits.get();
        assertTrue(republishedGenerations > publishedGenerations,
                "explicit republication must capture a new producer generation");
        var recovered = rest.getForEntity(url(PATH + "/capabilities"), JsonNode.class);
        assertEquals(200, recovered.getStatusCode().value(), String.valueOf(recovered.getBody()));
        assertTrue(recovered.getBody().path("operations").has("bulk-update"));
        assertFalse(recovered.getBody().path("operations").has("bulk-update-items"));
        assertFalse(recovered.getBody().path("operations").has("bulk-update-atomic"));
        assertFalse(recovered.getBody().path("operations").has("bulk-update-items-atomic"));
        assertNull(recovered.getBody().path("actions").findValue("bulk"));
        assertEquals(republishedGenerations, producer.visits.get(),
                "recovered discovery must read the newly published photograph without recapture");
    }

    private void assertPublicationUnavailable(String endpoint) {
        var response = rest.getForEntity(url(endpoint), JsonNode.class);
        assertEquals(503, response.getStatusCode().value(), String.valueOf(response.getBody()));
        JsonNode body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.path("status").asText());
        assertEquals("Governed OpenAPI publication is temporarily unavailable.", body.path("message").asText());
        assertEquals("SYSTEM", body.at("/errors/0/category").asText());
        assertEquals("GOVERNED_OPENAPI_PUBLICATION_UNAVAILABLE",
                body.at("/errors/0/code").asText());
        assertFalse(body.at("/errors/0").has("properties"));
        assertFalse(body.toString().contains("No governed OpenAPI publication is installed locally"),
                "the internal snapshot guard message must stay private");
        assertTrue(body.path("data").isMissingNode() || body.path("data").isNull());
        assertFalse(body.has("operations"));
        assertFalse(body.has("actions"));
    }

    private void assertRealProtocolPayloads() throws Exception {
        String uniform = """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[{"id":7,"expectedVersion":"v0"}]},
                 "changes":[{"field":"active","operator":"SET","value":false},{"field":"count","operator":"SET","value":0},{"field":"note","operator":"CLEAR"}]}
                """;
        String items = """
                {"executionMode":"SYNC","items":[{"id":7,"expectedVersion":"v0","changes":[{"field":"note","operator":"CLEAR"}]}]}
                """;
        for (var input : Map.of("uniform", uniform, "items", items).entrySet()) {
            var headers = new HttpHeaders(); headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            var response = rest.postForEntity(url(PATH + "/bulk/" + input.getKey() + "/evaluation"), new HttpEntity<>(input.getValue(), headers), JsonNode.class);
            assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
            assertEquals(1, response.getBody().at("/data/targets").asInt());
        }
        var request = new BulkProtocolReader<>(BulkIdentityCodecs.integers()).readUniform(uniform.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ObjectNode current = mapper.createObjectNode().put("active", true).put("count", 3).put("note", "old").put("omitted", "kept");
        var applied = BulkFieldChanges.applyTo(current, request.changes(), Set.of("active", "count", "note"), Set.of("note"));
        assertFalse(applied.path("active").asBoolean()); assertEquals(0, applied.path("count").asInt());
        assertTrue(applied.path("note").isNull()); assertEquals("kept", applied.path("omitted").asText());
    }

    private void assertSchema(JsonNode operation, JsonNode ref) throws Exception {
        assertFalse(ref.isMissingNode());
        var response = rest.getRestTemplate().getForEntity(URI.create(url(ref.path("url").asText())), String.class);
        assertEquals(200, response.getStatusCode().value(), ref.toString());
        JsonNode schema = mapper.readTree(response.getBody());
        var resource = schema.at("/x-ui/resource");
        assertEquals(ref.path("schemaId").asText(), SchemaIdBuilder.build(operation.path("path").asText(),
                operation.path("method").asText().toLowerCase(java.util.Locale.ROOT), ref.path("schemaType").asText(), false,
                resource.path("idField").asText(), resource.path("readOnly").asBoolean()));
        ObjectNode structural = schema.deepCopy();
        if (structural.path("x-ui") instanceof ObjectNode ui) ui.remove("operationExamples");
        String hash = SchemaHashUtil.sha256Hex(new SchemaCanonicalizer().canonicalize(structural));
        assertEquals(hash, response.getHeaders().getFirst("X-Schema-Hash"));
        assertEquals("\"" + hash + "\"", response.getHeaders().getETag());
        var headers = new HttpHeaders(); headers.setIfNoneMatch(response.getHeaders().getETag());
        assertEquals(304, rest.getRestTemplate().exchange(URI.create(url(ref.path("url").asText())), HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value());
    }

    private <T> T withLocalRequest(java.util.function.Supplier<T> work) {
        var previous = RequestContextHolder.getRequestAttributes();
        var request = new MockHttpServletRequest(servletContext, "GET", PATH);
        request.setContextPath(servletContext.getContextPath());
        request.setServletPath(PATH);
        request.setLocalAddr("127.0.0.1"); request.setLocalPort(port); request.setServerPort(port);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { return work.get(); } finally { if (previous == null) RequestContextHolder.resetRequestAttributes(); else RequestContextHolder.setRequestAttributes(previous); }
    }
    private String url(String path) { return "http://localhost:" + port + path; }
    private static BulkOperationControlIdentity identity(String operation) { return BulkCrudOperationalLifecyclePostgresTest.identity(operation); }

    @TestConfiguration
    static class Configuration {
        @Bean ProducerGenerationProbe producerGenerationProbe() { return new ProducerGenerationProbe(); }
        @Bean GlobalOpenApiCustomizer producerGenerationCustomizer(ProducerGenerationProbe probe) {
            return document -> probe.visits.incrementAndGet();
        }
        @Bean(destroyMethod = "close") BulkCrudOperationalLifecyclePostgresTest.Store bulkStore() throws Exception {
            return new BulkCrudOperationalLifecyclePostgresTest.Store(List.of("crud.uniform", "crud.items",
                    "crud.uniform-atomic", "crud.items-atomic", "crud.command"));
        }
        @Bean BulkExecutionInfrastructure bulkRuntime(BulkCrudOperationalLifecyclePostgresTest.Store store) { return store.runtime; }
        @Bean BulkControlPlaneInfrastructure bulkControl(BulkCrudOperationalLifecyclePostgresTest.Store store) { return store.control; }
        @Bean BulkOperationDescriptorProvider uniformProvider(BulkExecutionInfrastructure runtime) {
            return new BulkCrudOperationalCompositionTest.Provider("crud.uniform", "r1", BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, runtime);
        }
        @Bean BulkOperationDescriptorProvider itemsProvider(BulkExecutionInfrastructure runtime) {
            return new BulkCrudOperationalCompositionTest.Provider("crud.items", "r1", BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, runtime);
        }
        @Bean BulkOperationDescriptorProvider uniformAtomicProvider(BulkExecutionInfrastructure runtime) {
            return new BulkCrudOperationalCompositionTest.Provider("crud.uniform-atomic", "r1", BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, runtime);
        }
        @Bean BulkOperationDescriptorProvider itemsAtomicProvider(BulkExecutionInfrastructure runtime) {
            return new BulkCrudOperationalCompositionTest.Provider("crud.items-atomic", "r1", BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, runtime);
        }
        @Bean BulkOperationDescriptorProvider commandProvider(BulkExecutionInfrastructure runtime) {
            return new BulkCrudOperationalCompositionTest.Provider("crud.command", "r1", BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.DOMAIN_COMMAND, runtime);
        }
        @Bean @Primary
        Probe availabilityProbe(ObjectProvider<BulkOperationLifecycle> lifecycle, OpenApiDocumentService documents,
                @Qualifier("employeeResourceOperationAvailabilityProvider") ResourceOperationAvailabilityProvider delegate) {
            return new Probe(lifecycle, documents, delegate);
        }
    }

    static final class ProducerGenerationProbe {
        final AtomicInteger visits = new AtomicInteger();
    }

    static final class Probe implements ResourceOperationAvailabilityProvider {
        final ObjectProvider<BulkOperationLifecycle> lifecycle;
        final OpenApiDocumentService documents;
        final ResourceOperationAvailabilityProvider delegate;
        final AtomicInteger visits = new AtomicInteger();
        Probe(ObjectProvider<BulkOperationLifecycle> lifecycle, OpenApiDocumentService documents,
                ResourceOperationAvailabilityProvider delegate) {
            this.lifecycle = lifecycle;
            this.documents = documents;
            this.delegate = delegate;
        }
        public AvailabilityDecision evaluate(ResourceOperationAvailabilityContext context) {
            // Observe only this fixture's four CRUD operations; retain the baseline fixture's policies elsewhere.
            if (!RESOURCE.equals(context.resourceKey())
                    || !Set.of("bulk-update", "bulk-update-items", "bulk-update-atomic", "bulk-update-items-atomic")
                            .contains(context.operationId())) {
                return delegate.evaluate(context);
            }
            BulkCrudOperationalLifecyclePostgresTest.assertUnlocked((CachedOpenApiDocumentService) documents);
            assertNull(context.resourceId()); assertNull(context.resourceState()); assertEquals("COLLECTION", context.scope());
            visits.incrementAndGet();
            String operation = switch (context.operationId()) {
                case "bulk-update" -> "crud.uniform";
                case "bulk-update-items" -> "crud.items";
                case "bulk-update-atomic" -> "crud.uniform-atomic";
                case "bulk-update-items-atomic" -> "crud.items-atomic";
                default -> throw new AssertionError("Unexpected capability");
            };
            lifecycle.getObject().requireReady(identity(operation)); lifecycle.getObject().requireReady(identity(operation));
            var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            return "true".equals(request.getHeader("X-Fixture-Deny")) ? AvailabilityDecision.deny("fixture-denied", Map.of()) : AvailabilityDecision.allowAll();
        }
    }

    public record Update(@BulkEditable(allowClear = true) @Schema(nullable = true) String note,
            @BulkEditable Boolean active, @BulkEditable Integer count, Integer identity, Long revision) { }
    public record Filter(String name) implements GenericFilterDTO { }
    public record Target(@Schema(type = "integer", format = "int32", minimum = "-2147483648", maximum = "2147483647") Integer id,
            String expectedVersion) { }
    public record Selection(BulkSelectionMode mode, List<Target> targets) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Change(String field, BulkChangeOperator operator, JsonNode value) { }
    public record Uniform(BulkExecutionMode executionMode, Selection selection, List<Change> changes) { }
    public record Item(@Schema(type = "integer", format = "int32", minimum = "-2147483648", maximum = "2147483647") Integer id,
            String expectedVersion, List<Change> changes) { }
    public record Items(BulkExecutionMode executionMode, List<Item> items) { }
    public record Parameters(String reason) { }
    public record Command(BulkExecutionMode executionMode, Selection selection, Parameters parameters) { }
    public record View(String proposalId, int targets) { }

    @ApiResource(value = PATH, resourceKey = RESOURCE) @ApiGroup("crud-operational")
    @BulkResourceOperations(proposalOperationId = "crud.proposal", proposalResultsOperationId = "crud.proposal-results",
            executionOperationId = "crud.execution", executionResultsOperationId = "crud.results", cancelOperationId = "crud.cancel",
            updateSourceOperationId = "crud.update", protectedUpdateFields = {"identity", "revision"})
    public static class Controller extends AbstractCreateUpdateResourceController<Update, Integer, Filter, Update, Update> {
        private final BaseCreateUpdateResourceService<Update, Integer, Filter, Update, Update> service;
        private final ObjectMapper mapper;
        @SuppressWarnings("unchecked") public Controller(ObjectMapper mapper) {
            this.mapper = mapper; service = mock(BaseCreateUpdateResourceService.class); when(service.getIdFieldName()).thenReturn("identity");
        }
        @Override protected BaseCreateUpdateResourceService<Update, Integer, Filter, Update, Update> getService() { return service; }
        @Override protected Integer getResponseId(Update dto) { return dto.identity(); }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL) @GetMapping("/bulk/proposal") public RestApiResponse<View> proposal() { return view(0); }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS) @GetMapping("/bulk/proposal-results") public RestApiResponse<View> proposals() { return view(0); }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION) @GetMapping("/bulk/execution") public RestApiResponse<View> execution() { return view(0); }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS) @GetMapping("/bulk/results") public RestApiResponse<View> results() { return view(0); }
        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL) @PostMapping("/bulk/cancel") public RestApiResponse<View> cancel() { return view(0); }
        @Operation(operationId = "crud.uniform.evaluation") @PostMapping("/bulk/uniform/evaluation")
        public RestApiResponse<View> evaluate(@RequestBody Uniform input) throws Exception {
            return view(new BulkProtocolReader<>(BulkIdentityCodecs.integers()).readUniform(mapper.writeValueAsBytes(input)).selection().targets().size());
        }
        @Operation(operationId = "crud.items.evaluation") @PostMapping("/bulk/items/evaluation")
        public RestApiResponse<View> evaluateItems(@RequestBody Items input) throws Exception {
            return view(new BulkProtocolReader<>(BulkIdentityCodecs.integers()).readItems(mapper.writeValueAsBytes(input)).items().size());
        }
        @Operation(operationId = "crud.uniform-atomic.evaluation") @PostMapping("/bulk/uniform-atomic/evaluation")
        public RestApiResponse<View> evaluateAtomic(@RequestBody Uniform input) throws Exception {
            return view(new BulkProtocolReader<>(BulkIdentityCodecs.integers()).readUniform(mapper.writeValueAsBytes(input)).selection().targets().size());
        }
        @Operation(operationId = "crud.items-atomic.evaluation") @PostMapping("/bulk/items-atomic/evaluation")
        public RestApiResponse<View> evaluateItemsAtomic(@RequestBody Items input) throws Exception {
            return view(new BulkProtocolReader<>(BulkIdentityCodecs.integers()).readItems(mapper.writeValueAsBytes(input)).items().size());
        }
        @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "crud.uniform.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.uniform") @PostMapping("/bulk/uniform") public RestApiResponse<View> confirm(@RequestBody BulkConfirmationRequest request) { return view(0); }
        @BulkOperation(mode = BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "crud.items.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.items") @PostMapping("/bulk/items") public RestApiResponse<View> confirmItems(@RequestBody BulkConfirmationRequest request) { return view(0); }
        @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "crud.uniform-atomic.evaluation", atomicity = ActionCollectionAtomicity.ATOMIC)
        @Operation(operationId = "crud.uniform-atomic") @PostMapping("/bulk/uniform-atomic") public RestApiResponse<View> confirmAtomic(@RequestBody BulkConfirmationRequest request) { return view(0); }
        @BulkOperation(mode = BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "crud.items-atomic.evaluation", atomicity = ActionCollectionAtomicity.ATOMIC)
        @Operation(operationId = "crud.items-atomic") @PostMapping("/bulk/items-atomic") public RestApiResponse<View> confirmItemsAtomic(@RequestBody BulkConfirmationRequest request) { return view(0); }
        @Operation(operationId = "crud.command.evaluation") @PostMapping("/bulk/command/evaluation") public RestApiResponse<View> evaluateCommand(@RequestBody Command input) { return view(0); }
        @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "crud.command.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @WorkflowAction(id = "command", title = "Command fixture", scope = ActionScope.COLLECTION, atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.command") @PostMapping("/actions/command") public RestApiResponse<View> confirmCommand(@RequestBody BulkConfirmationRequest request) { return view(0); }
        private static RestApiResponse<View> view(int count) { return RestApiResponse.success(new View("fixture", count), null); }
    }
}
