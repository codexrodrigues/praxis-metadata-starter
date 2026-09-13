package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.condition.RequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalResourceOperationBindingTest {

    private static final OpenApiDocumentService OPEN_API_DOCUMENTS = new FixedGroupOpenApiDocumentService("bulk-operations");

    @Test
    void resolvesAnExplicitSinglePostOperationForTheExpectedResourceAndBuildsCanonicalSchemaReferences() {
        try (AnnotationConfigWebApplicationContext context = context(InventoryController.class)) {
            CanonicalOperationResolver resolver = resolver(context);

            CanonicalOperationRef operation = resolver.requireResourceOperation(
                    "inventory.items",
                    "inventory.items.bulk-evaluate",
                    "POST"
            );

            assertEquals("bulk-operations", operation.group());
            assertEquals("inventory.items.bulk-evaluate", operation.operationId());
            assertEquals("/inventory-items/bulk/evaluation", operation.path());
            assertEquals("POST", operation.method());

            FilteredSchemaReferenceResolver schemas = new FilteredSchemaReferenceResolver();
            CanonicalSchemaRef request = schemas.requestSchema(operation);
            CanonicalSchemaRef response = schemas.responseSchema(operation);

            assertEquals("request", request.schemaType());
            assertEquals("response", response.schemaType());
            assertTrue(request.url().contains("path=%2Finventory-items%2Fbulk%2Fevaluation"));
            assertTrue(request.url().contains("operation=post"));
            assertTrue(response.url().contains("schemaType=response"));
        }
    }

    @Test
    void distinguishesGlobalOperationIdsWhenResourcesReuseTheSameLocalActionName() {
        try (AnnotationConfigWebApplicationContext context = context(InventoryController.class, OrderController.class)) {
            CanonicalOperationResolver resolver = resolver(context);

            CanonicalOperationRef inventory = resolver.requireResourceOperation(
                    "inventory.items", "inventory.items.bulk-evaluate", "POST"
            );
            CanonicalOperationRef order = resolver.requireResourceOperation(
                    "sales.orders", "sales.orders.bulk-evaluate", "POST"
            );

            assertEquals("/inventory-items/bulk/evaluation", inventory.path());
            assertEquals("/orders/bulk/evaluation", order.path());
        }
    }

    @Test
    void rejectsAResolvedOperationOwnedByAnotherResource() {
        try (AnnotationConfigWebApplicationContext context = context(InventoryController.class, OrderController.class)) {
            CanonicalOperationResolver resolver = resolver(context);

            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "inventory.items", "sales.orders.bulk-evaluate", "POST"
            ));
        }
    }

    @Test
    void rejectsAnOperationIdRegisteredByMoreThanOneResource() {
        try (AnnotationConfigWebApplicationContext context = context(DuplicateOperationOneController.class, DuplicateOperationTwoController.class)) {
            CanonicalOperationResolver resolver = resolver(context);

            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "bulk.one", "bulk.shared-evaluate", "POST"
            ));
            assertThrows(IllegalStateException.class, () -> resolver.resolveByOperationId("bulk.shared-evaluate"));
        }
    }

    @Test
    void rejectsTheWrongHttpMethodAndAnUnknownOperation() {
        try (AnnotationConfigWebApplicationContext context = context(InventoryController.class)) {
            CanonicalOperationResolver resolver = resolver(context);

            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "inventory.items", "inventory.items.bulk-evaluate", "GET"
            ));
            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "inventory.items", "inventory.items.missing", "POST"
            ));
        }
    }

    @Test
    void rejectsFallbackOperationIdsWhenTheHandlerDoesNotDeclareOperation() {
        try (AnnotationConfigWebApplicationContext context = context(UnannotatedOperationController.class)) {
            CanonicalOperationResolver resolver = resolver(context);

            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "unannotated.operations", "evaluate", "POST"
            ));
        }
    }

    @Test
    void rejectsAHandlerThatPublishesMultiplePathsOrMethods() {
        try (AnnotationConfigWebApplicationContext context = context(MultiplePathsController.class, MultipleMethodsController.class)) {
            CanonicalOperationResolver resolver = resolver(context);

            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "multi.paths", "multi.paths.bulk-evaluate", "POST"
            ));
            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "multi.methods", "multi.methods.bulk-evaluate", "POST"
            ));
        }
    }

    @Test
    void rejectsOperationsHiddenByOperationOrHiddenAnnotations() {
        try (AnnotationConfigWebApplicationContext context = context(
                OperationHiddenController.class,
                HiddenClassController.class,
                HiddenMethodController.class
        )) {
            CanonicalOperationResolver resolver = resolver(context);

            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "hidden.operation", "hidden.operation.bulk-evaluate", "POST"
            ));
            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "hidden.class", "hidden.class.bulk-evaluate", "POST"
            ));
            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "hidden.method", "hidden.method.bulk-evaluate", "POST"
            ));
        }
    }

    @Test
    void rejectsMappingsWithParametersHeadersOrCustomConditions() throws Exception {
        try (AnnotationConfigWebApplicationContext context = context(
                ParameterConstrainedController.class,
                HeaderConstrainedController.class,
                CustomConditionController.class
        )) {
            RequestMappingHandlerMapping mappings = context.getBean(RequestMappingHandlerMapping.class);
            CustomConditionController customController = context.getBean(CustomConditionController.class);
            Method customMethod = CustomConditionController.class.getDeclaredMethod("evaluate");
            mappings.registerMapping(
                    RequestMappingInfo.paths("/custom-condition/bulk/evaluation")
                            .methods(RequestMethod.POST)
                            .customCondition(new TestRequestCondition())
                            .build(),
                    customController,
                    customMethod
            );
            CanonicalOperationResolver resolver = resolver(context);

            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "constrained.params", "constrained.params.bulk-evaluate", "POST"
            ));
            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "constrained.headers", "constrained.headers.bulk-evaluate", "POST"
            ));
            assertThrows(IllegalStateException.class, () -> resolver.requireResourceOperation(
                    "constrained.custom", "constrained.custom.bulk-evaluate", "POST"
            ));
        }
    }

    @Test
    void rejectsDifferentIdsSharingAnAddressThroughMediaTypeConditions() {
        try (AnnotationConfigWebApplicationContext context = context(MediaVariantsController.class)) {
            CanonicalOperationResolver resolver = resolver(context);
            for (String id : new String[]{"media.json", "media.xml"}) {
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> resolver.requireResourceOperation("media.variants", id, "POST"));
                assertTrue(failure.getMessage().contains("shared by another registered mapping"));
            }
        }
    }

    @Test
    void rejectsBlankBindingArgumentsAndMakesTheDefaultSpiContractExplicit() {
        try (AnnotationConfigWebApplicationContext context = context(InventoryController.class)) {
            CanonicalOperationResolver resolver = resolver(context);

            assertThrows(IllegalArgumentException.class, () -> resolver.requireResourceOperation(" ", "id", "POST"));
            assertThrows(IllegalArgumentException.class, () -> resolver.requireResourceOperation("inventory.items", " ", "POST"));
            assertThrows(IllegalArgumentException.class, () -> resolver.requireResourceOperation("inventory.items", "id", " "));
        }

        CanonicalOperationResolver substitutableResolver = new CanonicalOperationResolver() {
            @Override
            public String resolveGroup(String path) {
                return "test";
            }

            @Override
            public CanonicalOperationRef resolve(String path, String method) {
                return new CanonicalOperationRef("test", null, path, method);
            }

            @Override
            public CanonicalOperationRef resolve(
                    org.springframework.web.method.HandlerMethod handlerMethod,
                    RequestMappingInfo mappingInfo
            ) {
                return new CanonicalOperationRef("test", "test.operation", "/test", "POST");
            }

            @Override
            public Optional<CanonicalOperationRef> resolveByOperationId(String operationId) {
                return Optional.empty();
            }
        };

        assertThrows(UnsupportedOperationException.class, () -> substitutableResolver.requireResourceOperation(
                "test.resource", "test.operation", "POST"
        ));
    }

    private CanonicalOperationResolver resolver(AnnotationConfigWebApplicationContext context) {
        return new OpenApiCanonicalOperationResolver(
                OPEN_API_DOCUMENTS,
                context.getBean(RequestMappingHandlerMapping.class)
        );
    }

    private AnnotationConfigWebApplicationContext context(Class<?>... controllers) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebMvcConfiguration.class);
        context.register(controllers);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    static class WebMvcConfiguration {
    }

    @ApiResource(value = "/inventory-items", resourceKey = "inventory.items")
    static class InventoryController {

        @PostMapping(path = "/bulk/evaluation", consumes = "application/json")
        @Operation(operationId = "inventory.items.bulk-evaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/orders", resourceKey = "sales.orders")
    static class OrderController {

        @PostMapping("/bulk/evaluation")
        @Operation(operationId = "sales.orders.bulk-evaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/bulk-one", resourceKey = "bulk.one")
    static class DuplicateOperationOneController {

        @PostMapping("/bulk/evaluation")
        @Operation(operationId = "bulk.shared-evaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/bulk-two", resourceKey = "bulk.two")
    static class DuplicateOperationTwoController {

        @PostMapping("/bulk/evaluation")
        @Operation(operationId = "bulk.shared-evaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/unannotated", resourceKey = "unannotated.operations")
    static class UnannotatedOperationController {

        @PostMapping("/bulk/evaluation")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/multi-paths", resourceKey = "multi.paths")
    static class MultiplePathsController {

        @PostMapping(path = {"/bulk/evaluation", "/bulk/assessment"})
        @Operation(operationId = "multi.paths.bulk-evaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/multi-methods", resourceKey = "multi.methods")
    static class MultipleMethodsController {

        @RequestMapping(path = "/bulk/evaluation", method = {RequestMethod.POST, RequestMethod.PATCH})
        @Operation(operationId = "multi.methods.bulk-evaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/hidden-operation", resourceKey = "hidden.operation")
    static class OperationHiddenController {

        @PostMapping("/bulk/evaluation")
        @Operation(operationId = "hidden.operation.bulk-evaluate", hidden = true)
        public void evaluate() {
        }
    }

    @Hidden
    @ApiResource(value = "/hidden-class", resourceKey = "hidden.class")
    static class HiddenClassController {

        @PostMapping("/bulk/evaluation")
        @Operation(operationId = "hidden.class.bulk-evaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/hidden-method", resourceKey = "hidden.method")
    static class HiddenMethodController {

        @PostMapping("/bulk/evaluation")
        @Operation(operationId = "hidden.method.bulk-evaluate")
        @Hidden
        public void evaluate() {
        }
    }

    @ApiResource(value = "/parameter-constrained", resourceKey = "constrained.params")
    static class ParameterConstrainedController {

        @PostMapping(path = "/bulk/evaluation", params = "mode=bulk")
        @Operation(operationId = "constrained.params.bulk-evaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/header-constrained", resourceKey = "constrained.headers")
    static class HeaderConstrainedController {

        @PostMapping(path = "/bulk/evaluation", headers = "X-Bulk-Mode=true")
        @Operation(operationId = "constrained.headers.bulk-evaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/media-variants", resourceKey = "media.variants")
    static class MediaVariantsController {
        @PostMapping(path = "/evaluation", consumes = "application/json")
        @Operation(operationId = "media.json")
        public void json() {
        }

        @PostMapping(path = "/evaluation", consumes = "application/xml")
        @Operation(operationId = "media.xml")
        public void xml() {
        }
    }

    @ApiResource(value = "/custom-condition", resourceKey = "constrained.custom")
    static class CustomConditionController {

        @Operation(operationId = "constrained.custom.bulk-evaluate")
        public void evaluate() {
        }
    }

    private static final class TestRequestCondition implements RequestCondition<TestRequestCondition> {

        @Override
        public TestRequestCondition combine(TestRequestCondition other) {
            return this;
        }

        @Override
        public TestRequestCondition getMatchingCondition(HttpServletRequest request) {
            return this;
        }

        @Override
        public int compareTo(TestRequestCondition other, HttpServletRequest request) {
            return 0;
        }
    }

    private record FixedGroupOpenApiDocumentService(String group) implements OpenApiDocumentService {

        @Override
        public String resolveGroupFromPath(String path) {
            return group;
        }

        @Override
        public JsonNode getDocumentForGroup(String groupName) {
            throw new UnsupportedOperationException("Schema content is outside this binding test.");
        }

        @Override
        public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
            return "unused";
        }

        @Override
        public void clearCaches() {
            // No cache is needed by the real MVC binding registry test.
        }
    }
}
