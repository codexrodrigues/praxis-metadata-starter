package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.customfields.*;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class CustomFieldSchemaTenantIsolationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String PATH = "/api/hr/people";

    @Test void authenticatedResolverWithoutHeadersIsPrivateAndIsolatesConditionalRequests() {
        AtomicReference<String> authenticatedTenant = new AtomicReference<>("a");
        var controller = controller((r, t, role) -> List.of(field("field_" + t)),
                () -> Optional.of(authenticatedTenant.get()), false);
        var first = get(controller, "response", null, null);
        assertThat(first.getHeaders().getCacheControl()).contains("private").doesNotContain("public");
        authenticatedTenant.set("b");
        var second = get(controller, "response", first.getHeaders().getETag(), null);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getETag()).isNotEqualTo(first.getHeaders().getETag());
        assertThat(mapper.valueToTree(second.getBody()).at("/properties/customFields/properties/field_b").isMissingNode()).isFalse();
        var unchanged = get(controller, "response", second.getHeaders().getETag(), "spoofed");
        assertThat(unchanged.getStatusCode().value()).isEqualTo(304);
        assertThat(unchanged.getHeaders().getCacheControl()).contains("private");
        assertThat(unchanged.getHeaders().getVary()).contains("X-Tenant", "X-Tenant-ID");
    }

    @Test void emptyAuthenticatedContextDoesNotFallBackToUntrustedHeader() {
        List<String> observedTenants = new ArrayList<>();
        var controller = controller((r, t, role) -> { observedTenants.add(t); return List.of(); }, Optional::empty, false);
        get(controller, "response", null, "untrusted");
        assertThat(observedTenants).containsExactly((String) null);
    }

    @Test void projectionUsesRequestedSchemaRoleAndRefreshesDefinitionFingerprint() {
        AtomicReference<String> suffix = new AtomicReference<>("first");
        var controller = controller((r, t, role) -> List.of(field(role + "_" + suffix.get())), Optional::empty, false);
        var response = get(controller, "response", null, null);
        var request = get(controller, "request", null, null);
        assertThat(mapper.valueToTree(response.getBody()).at("/properties/customFields/properties/response_first").isMissingNode()).isFalse();
        assertThat(mapper.valueToTree(request.getBody()).at("/properties/customFields/properties/request_first").isMissingNode()).isFalse();
        suffix.set("second");
        var changed = get(controller, "response", response.getHeaders().getETag(), null);
        assertThat(changed.getStatusCode().value()).isEqualTo(200);
        assertThat(changed.getHeaders().getETag()).isNotEqualTo(response.getHeaders().getETag());
    }

    @Test void unusedFeaturePreservesBaselineSchemaIdentityAndHash() {
        var baseline = get(controller(null, null, false), "response", null, null);
        var enabled = get(controller(new NoOpCustomFieldDefinitionProvider(), Optional::empty, false), "response", null, null);
        assertThat(enabled.getBody()).isEqualTo(baseline.getBody());
        assertThat(enabled.getHeaders().getETag()).isEqualTo(baseline.getHeaders().getETag());
        assertThat(enabled.getHeaders().getCacheControl()).isEqualTo(baseline.getHeaders().getCacheControl());
    }

    @Test void absentDefinitionsRemoveContainerFromRequiredAlongWithProperty() {
        var response = get(controller(new NoOpCustomFieldDefinitionProvider(), Optional::empty, true), "response", null, null);
        var tree = mapper.valueToTree(response.getBody());
        assertThat(tree.at("/properties/customFields").isMissingNode()).isTrue();
        assertThat(tree.path("required").toString()).doesNotContain("customFields");
    }

    @Test void readOnlyRequiredFieldIsRequiredOnlyInResponseProjection() {
        var descriptor = new CustomFieldDescriptor("calculated", "Calculated", "Server value", CustomFieldDataType.STRING,
                null, null, 0, true, true, false, false, true, null, null, null, null, null,
                null, null, null, null, null, null, null, Map.of());
        var controller = controller((resource, tenant, role) -> List.of(descriptor), Optional::empty, false);
        var request = mapper.valueToTree(get(controller, "request", null, null).getBody())
                .at("/properties/customFields");
        var response = mapper.valueToTree(get(controller, "response", null, null).getBody())
                .at("/properties/customFields");
        assertThat(request.path("required").isMissingNode()).isTrue();
        assertThat(request.at("/properties/calculated/x-ui/required").booleanValue()).isFalse();
        assertThat(request.at("/properties/calculated/readOnly").booleanValue()).isTrue();
        assertThat(mapper.valueToTree(get(controller, "request", null, null).getBody()).has("required")).isFalse();
        assertThat(mapper.valueToTree(get(controller, "response", null, null).getBody()).path("required"))
                .isEqualTo(mapper.createArrayNode().add("customFields"));
        assertThat(response.path("required")).isEqualTo(mapper.createArrayNode().add("calculated"));
        assertThat(response.at("/properties/calculated/x-ui/required").booleanValue()).isTrue();
        assertThat(response.at("/properties/calculated/readOnly").booleanValue()).isTrue();
    }

    @Test void emptyDefinitionsDoNotMaterializePropertiesOnAnOrdinarySparseSchema() {
        Map<String, Object> schema = new LinkedHashMap<>(Map.of("type", "object", "description", "Unconstrained object"));
        Map<String, Object> baseline = new LinkedHashMap<>(schema);
        var enricher = new CustomFieldSchemaEnricher(new NoOpCustomFieldDefinitionProvider(), mapper);
        assertThat(enricher.enrich(schema, "hr.people", null, "response")).isEmpty();
        assertThat(schema).isEqualTo(baseline).doesNotContainKey("properties");
    }

    @Test void publicRequestRoleIsCaseInsensitiveIncludingProviderAndHash() {
        List<String> roles = new ArrayList<>();
        var controller = controller((r,t,role) -> { roles.add(role); return List.of(field("value")); }, Optional::empty, false);
        var lower = get(controller, "request", null, null);
        var upper = get(controller, "REQUEST", null, null);
        assertThat(upper.getBody()).isEqualTo(lower.getBody());
        assertThat(upper.getHeaders().getETag()).isEqualTo(lower.getHeaders().getETag());
        assertThat(upper.getHeaders().getFirst("X-Schema-Hash")).isEqualTo(lower.getHeaders().getFirst("X-Schema-Hash"));
        assertThat(get(controller, "REQUEST", lower.getHeaders().getETag(), null).getStatusCode().value()).isEqualTo(304);
        assertThat(roles).containsExactly("request", "request", "request");
    }

    static class Query implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO { }
    static class FilterNamedWrite { }
    static class GenericEndpoint<T> { public void execute(@org.springframework.web.bind.annotation.RequestBody T body) { } }
    static class QueryEndpoint extends GenericEndpoint<Query> { }
    static class WriteEndpoint extends GenericEndpoint<FilterNamedWrite> { }

    @Test void controllerSelectsFilterProjectionFromResolvedGenericBodyRatherThanRouteOrClassName() throws Exception {
        for (Object endpoint : List.of(new QueryEndpoint(), new WriteEndpoint())) {
            var mapping = org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            var handler = new org.springframework.web.method.HandlerMethod(endpoint, GenericEndpoint.class.getMethod("execute", Object.class));
            var route = org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths(PATH)
                    .methods(org.springframework.web.bind.annotation.RequestMethod.POST).build();
            org.mockito.Mockito.when(mapping.getHandlerMethods()).thenReturn(Map.of(route, handler));
            var descriptor = new CustomFieldDescriptor("size", "Size", null, CustomFieldDataType.INTEGER, null, null, 0,
                    true, true, true, false, true, 20, java.math.BigDecimal.TEN, java.math.BigDecimal.valueOf(50),
                    null, null, null, null, null, List.of(), List.of(), null,
                    org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.IN, Map.of());
            List<String> roles = new ArrayList<>();
            var controller = controller((r,t,role) -> { roles.add(role); return List.of(descriptor); }, Optional::empty, false);
            var support = new OpenApiDocsSupport();
            ReflectionTestUtils.setField(controller, "canonicalOperationResolver",
                    new OpenApiCanonicalOperationResolver(new TestOpenApiDocumentService(support), mapping));
            var tree = mapper.valueToTree(get(controller, "request", null, null).getBody());
            var field = tree.at("/properties/customFields/properties/size");
            if (endpoint instanceof QueryEndpoint) {
                assertThat(field.path("type")).isEqualTo(mapper.createArrayNode().add("array").add("null"));
                assertThat(field.at("/items/type").asText()).isEqualTo("integer");
                assertThat(field.has("minimum")).isFalse();
                assertThat(field.has("default")).isFalse();
                assertThat(field.has("readOnly")).isFalse();
            } else {
                assertThat(field.path("type").asText()).isEqualTo("integer");
                assertThat(field.path("readOnly").asBoolean()).isTrue();
                assertThat(field.has("minimum")).isTrue();
            }
            assertThat(roles).containsExactly("request");
        }
    }

    private ResponseEntity<Map<String, Object>> get(ApiDocsController controller, String role, String etag, String headerTenant) {
        return controller.getFilteredSchema(PATH, null, "post", false, role, null, null, etag, headerTenant, Locale.ENGLISH);
    }
    private ApiDocsController controller(CustomFieldDefinitionProvider definitions, ResourceTenantContextResolver tenants, boolean container) {
        var controller = new ApiDocsController();
        var support = new OpenApiDocsSupport();
        var documents = new TestOpenApiDocumentService(support);
        ObjectNode document = mapper.createObjectNode();
        ObjectNode operation = document.putObject("paths").putObject(PATH).putObject("post");
        operation.put("operationId", "createPerson");
        var ui = operation.putObject("x-ui");
        ui.put("responseSchema", "Person");
        ui.putObject("resource").put("resourceKey", "hr.people");
        operation.putObject("responses").putObject("200").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/Person");
        operation.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/Person");
        var person = document.putObject("components").putObject("schemas").putObject("Person");
        person.put("type", "object");
        person.putObject("properties").putObject("name").put("type", "string");
        if (container) {
            ((ObjectNode) person.path("properties")).putObject("customFields").put("type", "object");
            person.putArray("required").add("customFields");
        }
        documents.putDocument(documents.resolveGroupFromPath(PATH), document);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "openApiDocsSupport", support);
        ReflectionTestUtils.setField(controller, "openApiDocumentService", documents);
        ReflectionTestUtils.setField(controller, "canonicalOperationResolver", new OpenApiCanonicalOperationResolver(documents, null));
        ReflectionTestUtils.setField(controller, "schemaReferenceResolver", new FilteredSchemaReferenceResolver());
        ReflectionTestUtils.setField(controller, "canonicalCapabilityResolver", new OpenApiCanonicalCapabilityResolver(documents));
        if (definitions != null) ReflectionTestUtils.setField(controller, "customFieldSchemaEnricher", new CustomFieldSchemaEnricher(definitions, mapper));
        if (tenants != null) ReflectionTestUtils.setField(controller, "resourceTenantContextResolver", tenants);
        return controller;
    }
    private CustomFieldDescriptor field(String key) {
        return new CustomFieldDescriptor(key, key, "Field", CustomFieldDataType.STRING, null, null, 0,
                false, false, true, false, true, null, null, null, null, null, null, null, null,
                null, null, null, null, Map.of());
    }
}
