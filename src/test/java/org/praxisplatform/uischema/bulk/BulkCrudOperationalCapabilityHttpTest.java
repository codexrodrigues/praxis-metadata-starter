package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
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
    @Autowired BulkCrudOperationalLifecyclePostgresTest.Store store;

    @Test
    void readyCrudAndCommandShareOneCaptureOnBothCapabilityRoutesAndServeEverySchemaRef() throws Exception {
        var internal = (org.springframework.web.client.RestTemplate) ReflectionTestUtils.getField(documents, "restTemplate");
        var original = List.copyOf(internal.getInterceptors());
        var fresh = new AtomicInteger();
        var interceptors = new java.util.ArrayList<>(original);
        interceptors.add((request, body, execution) -> {
            if ("no-cache, no-store".equals(request.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))) fresh.incrementAndGet();
            return execution.execute(request, body);
        });
        internal.setInterceptors(interceptors);
        try {
            withLocalRequest(() -> {
                Map<String, CanonicalOpenApiGroupSnapshot> snapshots = new java.util.HashMap<>();
                var canonicalizer = new SchemaCanonicalizer();
                for (String family : List.of("uniform", "items", "command")) {
                    var operation = operations.requireResourceOperation(RESOURCE, "crud." + family + ".evaluation", "POST");
                    org.slf4j.LoggerFactory.getLogger(getClass()).info("CRUD fixture evaluation reference: {}", operation);
                    var snapshot = snapshots.computeIfAbsent(operation.group(), group -> CanonicalOpenApiGroupSnapshot.capture(documents, group));
                    String pointer = family.equals("items") ? BulkCrudOperationalCompositionTest.ITEMS_POINTER
                            : BulkCrudOperationalCompositionTest.UNIFORM_POINTER;
                    JsonNode actual = snapshot.requireRequestSchema(operation).schema().at(pointer);
                    assertEquals(canonicalizer.canonicalize(BulkIdentityCodecs.integers().canonicalWireSchema()),
                            canonicalizer.canonicalize(actual), family + " published identity schema: " + actual);
                }
                for (String id : List.of("crud.uniform", "crud.items", "crud.command")) lifecycle.publish(identity(id), 0);
                return null;
            });
            int groups = operations.publishedOpenApiGroups(Set.of(operations.resolveGroup(PATH))).size();
            for (String endpoint : List.of(PATH + "/capabilities", PATH + "/7/capabilities")) {
                int before = fresh.get();
                probe.visits.set(0);
                var response = rest.getForEntity(url(endpoint), JsonNode.class);
                assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
                assertEquals(before + groups, fresh.get(), "one composition, including the action catalog and availability");
                assertEquals(2, probe.visits.get());
                JsonNode body = response.getBody();
                assertNotNull(body);
                for (String id : List.of("bulk-update", "bulk-update-items")) {
                    JsonNode operation = body.path("operations").path(id);
                    assertTrue(operation.path("supported").asBoolean());
                    assertEquals("COLLECTION", operation.path("scope").asText());
                    assertTrue(operation.path("availability").path("allowed").asBoolean());
                    JsonNode bulk = operation.path("bulk");
                    assertFalse(bulk.has("parametersPointer"));
                    assertEquals(id.equals("bulk-update") ? "UNIFORM_UPDATE" : "PER_ITEM_UPDATE", bulk.path("mode").asText());
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
            withLocalRequest(() -> lifecycle.suspend(identity("crud.uniform"), 1));
            var suspended = rest.getForEntity(url(PATH + "/capabilities"), JsonNode.class);
            assertEquals(200, suspended.getStatusCode().value());
            assertFalse(suspended.getBody().path("operations").has("bulk-update"));
            assertFalse(suspended.getBody().path("operations").has("bulk-update-items"));
            assertNull(suspended.getBody().path("actions").findValue("bulk"));
        } finally { internal.setInterceptors(original); }
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
        var request = new MockHttpServletRequest(); request.setLocalAddr("127.0.0.1"); request.setLocalPort(port); request.setServerPort(port);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { return work.get(); } finally { if (previous == null) RequestContextHolder.resetRequestAttributes(); else RequestContextHolder.setRequestAttributes(previous); }
    }
    private String url(String path) { return "http://localhost:" + port + path; }
    private static BulkOperationControlIdentity identity(String operation) { return BulkCrudOperationalLifecyclePostgresTest.identity(operation); }

    @TestConfiguration
    static class Configuration {
        @Bean(destroyMethod = "close") BulkCrudOperationalLifecyclePostgresTest.Store bulkStore() throws Exception {
            return new BulkCrudOperationalLifecyclePostgresTest.Store(List.of("crud.uniform", "crud.items", "crud.command"));
        }
        @Bean BulkExecutionInfrastructure bulkRuntime(BulkCrudOperationalLifecyclePostgresTest.Store store) { return store.runtime; }
        @Bean BulkControlPlaneInfrastructure bulkControl(BulkCrudOperationalLifecyclePostgresTest.Store store) { return store.control; }
        @Bean BulkOperationDescriptorProvider uniformProvider(BulkExecutionInfrastructure runtime) {
            return new BulkCrudOperationalCompositionTest.Provider("crud.uniform", "r1", BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, runtime);
        }
        @Bean BulkOperationDescriptorProvider itemsProvider(BulkExecutionInfrastructure runtime) {
            return new BulkCrudOperationalCompositionTest.Provider("crud.items", "r1", BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, runtime);
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
            // Observe only this fixture's two CRUD operations; retain the baseline fixture's policies elsewhere.
            if (!RESOURCE.equals(context.resourceKey())
                    || !("bulk-update".equals(context.operationId()) || "bulk-update-items".equals(context.operationId()))) {
                return delegate.evaluate(context);
            }
            BulkCrudOperationalLifecyclePostgresTest.assertUnlocked((CachedOpenApiDocumentService) documents);
            assertNull(context.resourceId()); assertNull(context.resourceState()); assertEquals("COLLECTION", context.scope());
            visits.incrementAndGet();
            String operation = context.operationId().equals("bulk-update") ? "crud.uniform" : "crud.items";
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
        @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "crud.uniform.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.uniform") @PostMapping("/bulk/uniform") public RestApiResponse<View> confirm(@RequestBody BulkConfirmationRequest request) { return view(0); }
        @BulkOperation(mode = BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "crud.items.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.items") @PostMapping("/bulk/items") public RestApiResponse<View> confirmItems(@RequestBody BulkConfirmationRequest request) { return view(0); }
        @Operation(operationId = "crud.command.evaluation") @PostMapping("/bulk/command/evaluation") public RestApiResponse<View> evaluateCommand(@RequestBody Command input) { return view(0); }
        @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "crud.command.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @WorkflowAction(id = "command", title = "Command fixture", scope = ActionScope.COLLECTION, atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.command") @PostMapping("/actions/command") public RestApiResponse<View> confirmCommand(@RequestBody BulkConfirmationRequest request) { return view(0); }
        private static RestApiResponse<View> view(int count) { return RestApiResponse.success(new View("fixture", count), null); }
    }
}
