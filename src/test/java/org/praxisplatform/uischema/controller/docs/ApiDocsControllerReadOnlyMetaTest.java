package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiDocsControllerReadOnlyMetaTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ApiDocsController controller;
    private TestOpenApiDocumentService openApiDocumentService;

    @BeforeEach
    void setUp() {
        controller = new ApiDocsController();
        OpenApiDocsSupport openApiDocsSupport = new OpenApiDocsSupport();
        openApiDocumentService = new TestOpenApiDocumentService(openApiDocsSupport);

        org.springframework.test.util.ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "openApiDocsSupport", openApiDocsSupport);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "openApiDocumentService", openApiDocumentService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller,
                "canonicalOperationResolver",
                new OpenApiCanonicalOperationResolver(openApiDocumentService, null)
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller,
                "schemaReferenceResolver",
                new FilteredSchemaReferenceResolver()
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller,
                "canonicalCapabilityResolver",
                new OpenApiCanonicalCapabilityResolver(openApiDocumentService)
        );
        OptionSourceRegistry optionSourceRegistry = OptionSourceRegistry.builder()
                .add(ReadOnlyDemoEntity.class, new OptionSourceDescriptor(
                        "payrollProfile",
                        OptionSourceType.DISTINCT_DIMENSION,
                        "/api/ro-demo",
                        null,
                        null,
                        "payrollProfileLabel",
                        "payrollProfileCode",
                        List.of("universo"),
                        Map.of("universo", "empresa.universo"),
                        new OptionSourcePolicy(true, true, "contains", 1, 25, 100, true, false, "label")
                ))
                .add(SupplierLookupEntity.class, new OptionSourceDescriptor(
                        "supplier",
                        OptionSourceType.RESOURCE_ENTITY,
                        "/api/procurement/suppliers",
                        "supplierId",
                        "id",
                        "legalName",
                        "id",
                        List.of("companyId"),
                        Map.of("companyId", "companyId"),
                        new OptionSourcePolicy(true, true, "contains", 0, 25, 100, true, false, "label")
                ))
                .build();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("optionSourceRegistry", optionSourceRegistry);
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller,
                "optionSourceRegistryProvider",
                beanFactory.getBeanProvider(OptionSourceRegistry.class)
        );

        String group = "api-ro-demo-all"; // derived from path below
        JsonNode doc = buildReadOnlyOpenApiDocument();
        openApiDocumentService.putDocument(group, doc);
    }

    private JsonNode buildReadOnlyOpenApiDocument() {
        // paths
        ObjectNode root = mapper.createObjectNode();
        ObjectNode paths = root.putObject("paths");
        // GET byId and all, POST filter, POST filter/cursor -> Read-only shape
        paths.putObject("/api/ro-demo/{id}").putObject("get").putObject("x-ui");
        paths.putObject("/api/ro-demo/option-sources/{sourceKey}/options/by-ids").putObject("get").putObject("x-ui");
        paths.putObject("/api/ro-demo/option-sources/{sourceKey}/options/filter").putObject("post").putObject("x-ui");
        ObjectNode allGet = paths.putObject("/api/ro-demo/all").putObject("get");
        ObjectNode xui = allGet.putObject("x-ui");
        xui.put("responseSchema", "DemoDTO");
        paths.putObject("/api/ro-demo/filter").putObject("post").putObject("x-ui");
        paths.putObject("/api/ro-demo/filter/cursor").putObject("post").putObject("x-ui");

        // components.schemas with DemoDTO that uses demoId as id field
        ObjectNode components = root.putObject("components");
        ObjectNode schemas = components.putObject("schemas");
        ObjectNode demo = schemas.putObject("DemoDTO");
        demo.put("type", "object");
        ObjectNode props = demo.putObject("properties");
        props.putObject("demoId").put("type", "integer");
        props.putObject("name").put("type", "string");
        props.putObject("payrollProfile").put("type", "string");
        ObjectNode supplierId = props.putObject("supplierId");
        supplierId.put("type", "integer");
        supplierId.putObject("x-ui")
                .put("endpoint", "/api/procurement/suppliers/option-sources/supplier/options/filter");

        return root;
    }

    @Test
    void xUiResource_includesReadOnlyCapabilitiesAndIdFieldHeuristics() {
        String path = "/api/ro-demo/all";
        ResponseEntity<Map<String, Object>> r = controller.getFilteredSchema(
                path,
                "get",
                false,
                "response",
                null, // idField
                true, // readOnly flag
                null,
                null,
                Locale.ENGLISH
        );
        assertEquals(200, r.getStatusCodeValue());
        Map<String, Object> body = r.getBody();
        assertNotNull(body);

        @SuppressWarnings("unchecked")
        Map<String, Object> xui = (Map<String, Object>) body.get("x-ui");
        assertNotNull(xui);
        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) xui.get("resource");
        assertNotNull(resource);
        assertEquals(Boolean.TRUE, resource.get("readOnly"));
        assertEquals("demoId", resource.get("idField"));

        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) resource.get("capabilities");
        assertNotNull(caps);
        assertEquals(Boolean.FALSE, caps.get("create"));
        assertEquals(Boolean.FALSE, caps.get("update"));
        assertEquals(Boolean.FALSE, caps.get("delete"));
        assertEquals(Boolean.TRUE, caps.get("byId"));
        assertEquals(Boolean.TRUE, caps.get("all"));
        assertEquals(Boolean.TRUE, caps.get("filter"));
        assertEquals(Boolean.TRUE, caps.get("cursor"));
        assertEquals(Boolean.TRUE, caps.get("optionSources"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) body.get("properties");
        assertNotNull(properties);

        @SuppressWarnings("unchecked")
        Map<String, Object> payrollProfile = (Map<String, Object>) properties.get("payrollProfile");
        assertNotNull(payrollProfile);

        @SuppressWarnings("unchecked")
        Map<String, Object> fieldXUi = (Map<String, Object>) payrollProfile.get("x-ui");
        assertNotNull(fieldXUi);

        @SuppressWarnings("unchecked")
        Map<String, Object> optionSource = (Map<String, Object>) fieldXUi.get("optionSource");
        assertNotNull(optionSource);
        assertEquals("payrollProfile", optionSource.get("key"));
        assertEquals("DISTINCT_DIMENSION", optionSource.get("type"));
        assertEquals(Boolean.TRUE, optionSource.get("excludeSelfField"));
        assertEquals(java.util.List.of("universo"), optionSource.get("dependsOn"));
        assertEquals(Map.of("universo", "empresa.universo"), optionSource.get("dependencyFilterMap"));
        assertEquals("/api/ro-demo", optionSource.get("resourcePath"));
        assertEquals("contains", optionSource.get("searchMode"));
        assertEquals(25, optionSource.get("pageSize"));
        assertEquals(Boolean.TRUE, optionSource.get("includeIds"));

        @SuppressWarnings("unchecked")
        Map<String, Object> supplierId = (Map<String, Object>) properties.get("supplierId");
        assertNotNull(supplierId);
        @SuppressWarnings("unchecked")
        Map<String, Object> supplierXUi = (Map<String, Object>) supplierId.get("x-ui");
        assertNotNull(supplierXUi);
        @SuppressWarnings("unchecked")
        Map<String, Object> supplierOptionSource = (Map<String, Object>) supplierXUi.get("optionSource");
        assertNotNull(supplierOptionSource);
        assertEquals("supplier", supplierOptionSource.get("key"));
        assertEquals("RESOURCE_ENTITY", supplierOptionSource.get("type"));
        assertEquals("/api/procurement/suppliers", supplierOptionSource.get("resourcePath"));
    }

    static final class ReadOnlyDemoEntity {
    }

    static final class SupplierLookupEntity {
    }
}
