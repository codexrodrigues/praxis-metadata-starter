package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.e2e.fixture.E2eFixtureApplication;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.id.SchemaIdBuilder;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP proof of the filtered variants; does not publish READY or claim domain mutation. */
@SpringBootTest(classes = E2eFixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e-h2")
@Import(BulkActionSchemaProjectionHttpTest.ProjectionController.class)
class BulkActionSchemaProjectionHttpTest {
    private static final String RESOURCE = "bulk-projection.items";
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired OpenApiDocsSupport docsSupport;
    @Autowired OpenApiDocumentService documents;
    @Autowired CanonicalOperationResolver operations;
    @Autowired ActionDefinitionRegistry actions;
    @Autowired BulkResourceOperationBindings bindings;
    @Autowired SchemaReferenceResolver references;
    @Autowired CanonicalCapabilityResolver capabilities;

    @Test
    void allSevenRolesServeTheirOwnUiVariantIdentityAndStructuralHashOverHttp() throws Exception {
        ReflectionTestUtils.setField(docsSupport, "openApiInternalBaseUrl", url(""));
        var compiler = new BulkOperationStructuralCompiler(bindings, operations, documents, actions,
                mapper.getTypeFactory(), references, capabilities);
        var descriptor = compiler.compileAll().stream().filter(value -> RESOURCE.equals(value.resourceKey())).findFirst().orElseThrow();
        assertEquals(7, descriptor.operations().size());
        List<Boolean> readOnlyValues = new ArrayList<>();
        for (var role : descriptor.operations()) {
            assertNotNull(role.filteredResponse(), role.role().name());
            CanonicalSchemaRef response = role.role() == BulkOperationStructuralDescriptor.Role.CONFIRMATION
                    ? descriptor.action().responseSchema() : role.filteredResponse().reference();
            assertEquals(response, role.filteredResponse().reference(), "confirmation reuses the captured catalog ref");
            JsonNode served = assertHttpVariant(role, response);
            readOnlyValues.add(served.at("/x-ui/resource/readOnly").asBoolean());
            assertFalse(served.path("properties").has("data"), "RestApiResponse is a raw transport wrapper, not the UI DTO");
            assertTrue(served.path("properties").has(role.role() == BulkOperationStructuralDescriptor.Role.EVALUATION
                    || role.role() == BulkOperationStructuralDescriptor.Role.PROPOSAL
                    || role.role() == BulkOperationStructuralDescriptor.Role.PROPOSAL_RESULTS ? "proposalId" : "executionId"));
            if (role.requestSchema().isPresent()) {
                CanonicalSchemaRef request = role.role() == BulkOperationStructuralDescriptor.Role.CONFIRMATION
                        ? descriptor.action().requestSchema() : role.filteredRequest().reference();
                assertEquals(request, role.filteredRequest().reference());
                assertHttpVariant(role, request);
            } else assertNull(role.filteredRequest());
            if (role.role() != BulkOperationStructuralDescriptor.Role.CONFIRMATION) {
                var defaultRef = references.resolve(role.reference(), "response");
                var defaultResponse = rest.getRestTemplate().getForEntity(URI.create(url(defaultRef.url())), String.class);
                assertEquals(served, mapper.readTree(defaultResponse.getBody()), "default endpoint projection remains shape-equivalent");
            }
        }
        assertTrue(readOnlyValues.contains(true));
        assertTrue(readOnlyValues.contains(false));
    }

    @Test
    void ordinaryResourceDefaultsAndExplicitOverridesKeepTheExistingEndpointShape() throws Exception {
        ReflectionTestUtils.setField(docsSupport, "openApiInternalBaseUrl", url(""));
        var operation = operations.resolve("/bulk-projection-items/{id}", "get");
        var defaultRef = references.resolve(operation, "response");
        var explicit = references.resolve(operation.path(), operation.method(), "response", false, null, null, "resourceCode", false);
        var defaultResponse = rest.getRestTemplate().getForEntity(URI.create(url(defaultRef.url())), String.class);
        var explicitResponse = rest.getRestTemplate().getForEntity(URI.create(url(explicit.url())), String.class);
        assertEquals(200, defaultResponse.getStatusCode().value());
        assertEquals(mapper.readTree(defaultResponse.getBody()), mapper.readTree(explicitResponse.getBody()));
        assertEquals(defaultResponse.getHeaders().getETag(), explicitResponse.getHeaders().getETag());
        JsonNode schema = mapper.readTree(defaultResponse.getBody());
        assertEquals("resourceCode", schema.at("/x-ui/resource/idField").asText());
        assertFalse(schema.at("/x-ui/resource/readOnly").asBoolean());
        assertTrue(schema.at("/x-ui/resource/idFieldValid").asBoolean());
        var overridden = references.resolve(operation.path(), operation.method(), "response", false, null, null, "externalId", true);
        var overriddenResponse = rest.getRestTemplate().getForEntity(URI.create(url(overridden.url())), String.class);
        JsonNode altered = mapper.readTree(overriddenResponse.getBody());
        assertEquals(schema.path("properties"), altered.path("properties"));
        assertEquals("externalId", altered.at("/x-ui/resource/idField").asText());
        assertTrue(altered.at("/x-ui/resource/readOnly").asBoolean());
        assertFalse(altered.at("/x-ui/resource/idFieldValid").asBoolean());
        assertNotEquals(defaultResponse.getHeaders().getETag(), overriddenResponse.getHeaders().getETag());
        var plusId = references.resolve(operation.path(), operation.method(), "response", false, null, null, "external+id", false);
        var plusResponse = rest.getRestTemplate().getForEntity(URI.create(url(plusId.url())), String.class);
        JsonNode plusBody = mapper.readTree(plusResponse.getBody());
        assertEquals("external+id", plusBody.at("/x-ui/resource/idField").asText(),
                "a literal plus in a URI query parameter must survive servlet decoding");
        assertFalse(plusBody.at("/x-ui/resource/idFieldValid").asBoolean());
        assertEquals(plusId.schemaId(), SchemaIdBuilder.build(operation.path(), operation.method().toLowerCase(java.util.Locale.ROOT), "response", false,
                "external+id", false));
        String plusHash = plusResponse.getHeaders().getFirst("X-Schema-Hash");
        assertNotNull(plusHash);
        var plus304 = rest.getRestTemplate().exchange(URI.create(url(plusId.url())), HttpMethod.GET,
                new HttpEntity<>(headersWithEtag(plusResponse.getHeaders().getETag())), String.class);
        assertEquals(304, plus304.getStatusCode().value());
        assertEquals(plusHash, plus304.getHeaders().getFirst("X-Schema-Hash"));
    }

    private JsonNode assertHttpVariant(BulkOperationStructuralDescriptor.Operation operation, CanonicalSchemaRef ref) throws Exception {
        var response = rest.getRestTemplate().getForEntity(URI.create(url(ref.url())), String.class);
        assertEquals(200, response.getStatusCode().value(), ref.url());
        JsonNode body = mapper.readTree(response.getBody());
        JsonNode resource = body.at("/x-ui/resource");
        assertEquals(ref.schemaId(), SchemaIdBuilder.build(operation.reference().path(),
                operation.reference().method().toLowerCase(java.util.Locale.ROOT), ref.schemaType(), false,
                resource.path("idField").asText(), resource.path("readOnly").asBoolean()));
        assertEquals(ref, references.resolve(operation.reference().path(), operation.reference().method(),
                ref.schemaType(), false, null, null, resource.path("idField").asText(), resource.path("readOnly").asBoolean()));
        ObjectNode structural = body.deepCopy();
        if (structural.path("x-ui") instanceof ObjectNode ui) ui.remove("operationExamples");
        String hash = SchemaHashUtil.sha256Hex(new SchemaCanonicalizer().canonicalize(structural));
        assertEquals(hash, response.getHeaders().getFirst("X-Schema-Hash"));
        assertEquals("\"" + hash + "\"", response.getHeaders().getETag());
        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(response.getHeaders().getETag());
        var cached = rest.getRestTemplate().exchange(URI.create(url(ref.url())), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(304, cached.getStatusCode().value());
        assertEquals(hash, cached.getHeaders().getFirst("X-Schema-Hash"));
        return body;
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private HttpHeaders headersWithEtag(String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);
        return headers;
    }

    @ApiResource(value = "/bulk-projection-items", resourceKey = RESOURCE)
    @ApiGroup("bulk-projection")
    @BulkResourceOperations(proposalOperationId = "projection.proposal", proposalResultsOperationId = "projection.proposal-results",
            executionOperationId = "projection.execution", executionResultsOperationId = "projection.results", cancelOperationId = "projection.cancel")
    public static class ProjectionController extends org.praxisplatform.uischema.controller.base.AbstractCollectionCommandResourceController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/bulk/proposals/{proposalId}") public RestApiResponse<ProposalView> proposal() { return null; }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/bulk/proposals/{proposalId}/results") public RestApiResponse<ProposalView> proposalResults() { return null; }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/bulk/executions/{executionId}") public RestApiResponse<ExecutionView> execution() { return null; }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/bulk/executions/{executionId}/results") public RestApiResponse<ExecutionView> results() { return null; }
        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @PostMapping("/bulk/executions/{executionId}/cancel") public RestApiResponse<ExecutionView> cancel() { return null; }
        @Operation(operationId = "projection.evaluate")
        @PostMapping("/actions/approve/evaluation") public RestApiResponse<ProposalView> evaluate(@RequestBody Evaluation request) { return null; }
        @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "projection.evaluate", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @WorkflowAction(id = "approve", title = "Approve", scope = ActionScope.COLLECTION, atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "projection.confirm")
        @PostMapping("/actions/approve") public RestApiResponse<ExecutionView> confirm(@RequestBody BulkConfirmationRequest request) { return null; }
        @GetMapping("/{id}") public ResourceView byId() { return null; }
        @PatchMapping("/{id}") public ResourceView update(@RequestBody ResourceView resource) { return null; }
    }
    public record ProposalView(String proposalId, String status) { }
    public record ExecutionView(String executionId, String status) { }
    public record Evaluation(List<String> targetIds, Parameters parameters) { }
    public record Parameters(String reason) { }
    public record ResourceView(@jakarta.validation.constraints.NotNull String resourceCode) { }
}
