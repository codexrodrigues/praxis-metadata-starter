package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.GenerationScopedGenericResponseService;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.service.GenericResponseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiGroupRegistrationE2ETest extends AbstractE2eH2Test {

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void openApiGroupsRegisterNewHierarchyAndInfrastructureEndpoints() throws Exception {
        assertFalse(context.getBean(SpringDocConfigProperties.class).getCache().isDisabled(),
                "Group isolation must work with Springdoc's default document cache enabled");
        assertInstanceOf(BeanFactoryPostProcessor.class, context.getBean("springdocBeanFactoryPostProcessor"));
        assertEquals(ConfigurableBeanFactory.SCOPE_PROTOTYPE,
                context.getBeanFactory().getBeanDefinition("openAPIBuilder").getScope());
        assertNotSame(context.getBean("openAPIBuilder"), context.getBean("openAPIBuilder"));
        assertInstanceOf(GenerationScopedGenericResponseService.class, context.getBean(GenericResponseService.class));

        ResponseEntity<String> employeeGroup = get("/v3/api-docs/employees");
        assertEquals(200, employeeGroup.getStatusCode().value());
        JsonNode employeeRoot = body(employeeGroup);
        assertTrue(employeeRoot.path("paths").has("/employees"));
        assertTrue(employeeRoot.path("paths").has("/employees/{id}"));
        assertFalse(employeeRoot.path("paths").has("/payroll-view/all"));
        assertAllResponseReferencesResolve(employeeRoot, "/employees/all", "matricula");

        ResponseEntity<String> payrollGroup = get("/v3/api-docs/payroll-view");
        assertEquals(200, payrollGroup.getStatusCode().value());
        JsonNode payrollRoot = body(payrollGroup);
        assertTrue(payrollRoot.path("paths").has("/payroll-view/all"));
        assertTrue(payrollRoot.path("paths").has("/payroll-view/{id}"));
        assertFalse(payrollRoot.path("paths").has("/employees"));
        assertAllResponseReferencesResolve(payrollRoot, "/payroll-view/all", "netAmount");

        ResponseEntity<String> aggregatedGroup = get("/v3/api-docs/human-resources");
        assertEquals(200, aggregatedGroup.getStatusCode().value());
        JsonNode aggregatedRoot = body(aggregatedGroup);
        assertTrue(aggregatedRoot.path("paths").has("/employees"));
        assertTrue(aggregatedRoot.path("paths").has("/departments"));
        assertTrue(aggregatedRoot.path("paths").has("/payroll-view/all"));
        assertFalse(aggregatedRoot.path("paths").has("/schemas/filtered"));

        ResponseEntity<String> applicationGroup = get("/v3/api-docs/application");
        assertEquals(200, applicationGroup.getStatusCode().value());
        JsonNode applicationRoot = body(applicationGroup);
        assertTrue(applicationRoot.path("paths").has("/employees"));
        assertFalse(applicationRoot.path("paths").has("/external-employees"));

        ResponseEntity<String> infraGroup = get("/v3/api-docs/praxis-metadata-infra");
        assertEquals(200, infraGroup.getStatusCode().value());
        JsonNode infraRoot = body(infraGroup);
        assertTrue(infraRoot.path("paths").has("/schemas/filtered"));
        assertTrue(infraRoot.path("paths").has("/schemas/catalog"));
        assertFalse(infraRoot.path("paths").has("/employees"));

        JsonNode cachedEmployeeRoot = body(get("/v3/api-docs/employees"));
        assertEquals(employeeRoot.path("paths"), cachedEmployeeRoot.path("paths"));
        assertEquals(employeeRoot.path("components"), cachedEmployeeRoot.path("components"));
    }

    private void assertAllResponseReferencesResolve(JsonNode document, String path, String itemProperty) {
        JsonNode content = document.path("paths").path(path).path("get")
                .path("responses").path("200").path("content");
        assertTrue(content.isObject() && !content.isEmpty(), "Missing successful response content for " + path);
        var pending = new ArrayDeque<JsonNode>();
        content.forEach(mediaType -> {
            JsonNode schema = mediaType.path("schema");
            assertTrue(schema.isObject(), "Missing response schema for " + path);
            pending.add(schema);
        });
        var references = new HashSet<String>();
        boolean itemShapePresent = false;
        while (!pending.isEmpty()) {
            JsonNode schema = pending.removeFirst();
            itemShapePresent |= schema.findValues("properties").stream()
                    .anyMatch(properties -> properties.has(itemProperty));
            for (String reference : schema.findValuesAsText("$ref")) {
                assertTrue(reference.startsWith("#/components/schemas/"), "Unexpected schema reference: " + reference);
                if (references.add(reference)) {
                    JsonNode target = document.at(reference.substring(1));
                    assertTrue(target.isObject(), "Unresolved schema reference for " + path + ": " + reference);
                    pending.add(target);
                }
            }
        }
        assertFalse(references.isEmpty(), "The collection response must retain its component references: " + path);
        assertTrue(itemShapePresent, "The collection response must reach its item shape: " + path);
    }
}
