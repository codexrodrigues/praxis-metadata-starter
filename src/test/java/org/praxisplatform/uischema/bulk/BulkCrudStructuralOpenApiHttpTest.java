package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.controller.base.AbstractCreateUpdateResourceController;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.e2e.fixture.E2eFixtureApplication;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.id.SchemaIdBuilder;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Served Springdoc/filtered schemas and real MVC inheritance, not readiness or domain execution. */
@SpringBootTest(classes = E2eFixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e-h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({BulkCrudStructuralOpenApiHttpTest.InheritedController.class,
        BulkCrudStructuralOpenApiHttpTest.OverrideController.class, BulkCrudStructuralOpenApiHttpTest.MapperConfiguration.class})
class BulkCrudStructuralOpenApiHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate rest;
    @Autowired BulkResourceOperationBindings bindings;
    @Autowired CanonicalOperationResolver operations;
    @Autowired OpenApiDocumentService documents;
    @Autowired OpenApiDocsSupport docsSupport;
    @Autowired ActionDefinitionRegistry actions;
    @Autowired SchemaReferenceResolver references;
    @Autowired CanonicalCapabilityResolver capabilities;

    @Test
    void inheritedAndUnannotatedOverridePutUseTheResourceDeclaredIdentityAndRealUpdateSchema() throws Exception {
        ReflectionTestUtils.setField(docsSupport, "openApiInternalBaseUrl", url(""));
        assertEquals(PropertyNamingStrategies.SNAKE_CASE, mapper.getPropertyNamingStrategy());
        assertTrue(bindings.diagnostics().isEmpty(), bindings.diagnostics().toString());
        var compiler = new BulkOperationStructuralCompiler(bindings, operations, documents, actions, mapper, references, capabilities);
        var descriptors = compiler.compileAll().stream().filter(value -> value.resourceKey().startsWith("crud-http.")).toList();
        assertEquals(3, descriptors.size());
        for (var descriptor : descriptors) {
            assertEquals(7, descriptor.operations().size());
            assertThrows(IllegalStateException.class, descriptor::action);
            var update = descriptor.update();
            assertEquals(Update.class.getName(), update.requestJavaType());
            assertEquals(Set.of("display_name"), update.editableFields().writableFields(BulkMode.UNIFORM_UPDATE));
            assertEquals(Set.of("display_name", "principal"), update.editableFields().writableFields(BulkMode.PER_ITEM_UPDATE));
            assertEquals(Set.of("display_name"), update.editableFields().clearableFields(descriptor.mode()));
            assertFalse(bindings.requiresBodylessLifecycle(update.operation().operationId()));
            var document = mapper.readTree(rest.getForObject(url("/v3/api-docs/" + update.operation().group()), String.class));
            assertEquals(update.operation().operationId(), document.path("paths").path(update.operation().path()).path("put").path("operationId").asText());
            assertHttpSchema(update.operation(), update.filteredRequest().reference());
            for (var operation : descriptor.operations()) {
                assertNotNull(operation.filteredResponse(), operation.role().name());
                assertHttpSchema(operation.reference(), operation.filteredResponse().reference());
                if (operation.requestSchema().isPresent()) {
                    assertNotNull(operation.filteredRequest());
                    assertHttpSchema(operation.reference(), operation.filteredRequest().reference());
                } else assertNull(operation.filteredRequest());
            }
        }
        var inherited = bindings.handlerFor("crud-http.inherited.update").orElseThrow();
        assertEquals(AbstractCreateUpdateResourceController.class, inherited.getMethod().getDeclaringClass());
        var overridden = bindings.handlerFor("crud-http.override.update").orElseThrow();
        assertEquals(OverrideController.class, overridden.getMethod().getDeclaringClass());
        assertNull(overridden.getMethod().getDeclaredAnnotation(BulkResourceOperation.class));
        assertNull(overridden.getMethod().getDeclaredAnnotation(Operation.class));
        assertTrue(actions.findByResourceKey("crud-http.inherited").isEmpty());
        assertTrue(actions.findByResourceKey("crud-http.override").isEmpty());
        var response = rest.exchange(url("/crud-http-override/7"), HttpMethod.PUT,
                new HttpEntity<>(mapper.writeValueAsString(new Update("kept", false, 7, 0L)), jsonHeaders()), String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("kept", mapper.readTree(response.getBody()).at("/data/display_name").asText());
        assertFalse(mapper.readTree(response.getBody()).at("/data/principal").asBoolean());
    }

    private void assertHttpSchema(org.praxisplatform.uischema.openapi.CanonicalOperationRef operation, CanonicalSchemaRef ref) throws Exception {
        var response = rest.getRestTemplate().getForEntity(URI.create(url(ref.url())), String.class);
        assertEquals(200, response.getStatusCode().value(), ref.url());
        var schema = mapper.readTree(response.getBody());
        var resource = schema.at("/x-ui/resource");
        assertEquals(ref.schemaId(), SchemaIdBuilder.build(operation.path(), operation.method().toLowerCase(java.util.Locale.ROOT),
                ref.schemaType(), false, resource.path("idField").asText(), resource.path("readOnly").asBoolean()));
        ObjectNode structural = schema.deepCopy();
        if (structural.path("x-ui") instanceof ObjectNode ui) ui.remove("operationExamples");
        String hash = SchemaHashUtil.sha256Hex(new SchemaCanonicalizer().canonicalize(structural));
        assertEquals(hash, response.getHeaders().getFirst("X-Schema-Hash"));
        assertEquals("\"" + hash + "\"", response.getHeaders().getETag());
        var headers = new HttpHeaders(); headers.setIfNoneMatch(response.getHeaders().getETag());
        var cached = rest.getRestTemplate().exchange(URI.create(url(ref.url())), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(304, cached.getStatusCode().value());
        assertEquals(hash, cached.getHeaders().getFirst("X-Schema-Hash"));
    }
    private static HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }
    private String url(String path) { return "http://localhost:" + port + path; }

    @TestConfiguration
    static class MapperConfiguration {
        @Bean ObjectMapper customMapper() {
            return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
    }
    public record Update(@BulkEditable(allowClear = true) @Schema(nullable = true) String displayName,
            @BulkEditable(modes = BulkMode.PER_ITEM_UPDATE) Boolean principal, Integer identity, Long revision) { }
    public record Filter(String name) implements GenericFilterDTO { }
    public record Evaluation(List<String> changes) { }
    public record Confirmation(String proposalId) { }
    public record View(String proposalId) { }

    public abstract static class ResourceController extends AbstractCreateUpdateResourceController<Update, Integer, Filter, Update, Update> {
        private final BaseCreateUpdateResourceService<Update, Integer, Filter, Update, Update> service;
        @SuppressWarnings("unchecked") protected ResourceController() {
            service = mock(BaseCreateUpdateResourceService.class);
            when(service.getIdFieldName()).thenReturn("identity");
            when(service.update(anyInt(), any(Update.class))).thenAnswer(call -> call.getArgument(1));
        }
        @Override protected BaseCreateUpdateResourceService<Update, Integer, Filter, Update, Update> getService() { return service; }
        @Override protected Integer getResponseId(Update dto) { return dto.identity(); }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL) @GetMapping("/bulk/proposal") public View proposal() { return new View("p"); }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS) @GetMapping("/bulk/proposal-results") public View proposals() { return new View("p"); }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION) @GetMapping("/bulk/execution") public View execution() { return new View("p"); }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS) @GetMapping("/bulk/results") public View results() { return new View("p"); }
        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL) @PostMapping("/bulk/cancel") public View cancel() { return new View("p"); }
    }
    @ApiResource(value = "/crud-http-inherited", resourceKey = "crud-http.inherited") @ApiGroup("crud-http-inherited")
    @BulkResourceOperations(proposalOperationId = "crud-http.inherited.proposal", proposalResultsOperationId = "crud-http.inherited.proposals",
            executionOperationId = "crud-http.inherited.execution", executionResultsOperationId = "crud-http.inherited.results", cancelOperationId = "crud-http.inherited.cancel",
            updateSourceOperationId = "crud-http.inherited.update", protectedUpdateFields = {"identity", "revision"})
    public static class InheritedController extends ResourceController {
        @Operation(operationId = "crud-http.inherited.evaluate") @PostMapping("/bulk/update/evaluation") public View evaluate(@RequestBody Evaluation body) { return new View("p"); }
        @Operation(operationId = "crud-http.inherited.evaluate-items") @PostMapping("/bulk/update-items/evaluation") public View evaluateItems(@RequestBody Evaluation body) { return new View("p"); }
        @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "crud-http.inherited.evaluate", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud-http.inherited.confirm") @PostMapping("/bulk/update") public View confirm(@RequestBody Confirmation body) { return new View(body.proposalId()); }
        @BulkOperation(mode = BulkMode.PER_ITEM_UPDATE, evaluationOperationId = "crud-http.inherited.evaluate-items", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud-http.inherited.confirm-items") @PostMapping("/bulk/update-items") public View confirmItems(@RequestBody Confirmation body) { return new View(body.proposalId()); }
    }
    @ApiResource(value = "/crud-http-override", resourceKey = "crud-http.override") @ApiGroup("crud-http-override")
    @BulkResourceOperations(proposalOperationId = "crud-http.override.proposal", proposalResultsOperationId = "crud-http.override.proposals",
            executionOperationId = "crud-http.override.execution", executionResultsOperationId = "crud-http.override.results", cancelOperationId = "crud-http.override.cancel",
            updateSourceOperationId = "crud-http.override.update", protectedUpdateFields = {"identity", "revision"})
    public static class OverrideController extends ResourceController {
        // A host override retains the base marker and MVC mapping without repeating metadata.
        @Override public ResponseEntity<RestApiResponse<Update>> update(@PathVariable Integer id, @RequestBody Update dto) {
            return ResponseEntity.ok(RestApiResponse.success(dto, null));
        }
        @Operation(operationId = "crud-http.override.evaluate") @PostMapping("/bulk/update/evaluation") public View evaluate(@RequestBody Evaluation body) { return new View("p"); }
        @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "crud-http.override.evaluate", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud-http.override.confirm") @PostMapping("/bulk/update") public View confirm(@RequestBody Confirmation body) { return new View(body.proposalId()); }
    }
}
