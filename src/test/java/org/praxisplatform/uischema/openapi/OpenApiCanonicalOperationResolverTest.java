package org.praxisplatform.uischema.openapi;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Hidden;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.ConsumesRequestCondition;
import org.springframework.web.servlet.mvc.condition.HeadersRequestCondition;
import org.springframework.web.servlet.mvc.condition.ParamsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.ProducesRequestCondition;
import org.springframework.web.servlet.mvc.condition.RequestCondition;
import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiCanonicalOperationResolverTest {

    @Mock
    private OpenApiDocumentService openApiDocumentService;

    @Mock
    private RequestMappingHandlerMapping handlerMapping;

    private OpenApiCanonicalOperationResolver resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resolver = new OpenApiCanonicalOperationResolver(openApiDocumentService, handlerMapping);
    }

    @Test
    void resolveNormalizesPathMethodAndUsesResolvedGroup() {
        when(openApiDocumentService.resolveGroupFromPath("/api/human-resources/employees")).thenReturn("hr");

        CanonicalOperationRef ref = resolver.resolve("/api/human-resources/employees/", "post");

        assertEquals("hr", ref.group());
        assertEquals("/api/human-resources/employees", ref.path());
        assertEquals("POST", ref.method());
        assertNull(ref.operationId());
    }

    @Test
    void resolveDecodesEncodedPathBeforeResolvingGroup() {
        when(openApiDocumentService.resolveGroupFromPath("/api/human-resources/funcionarios/{id}/profile"))
                .thenReturn("api-human-resources-funcionarios");

        CanonicalOperationRef ref = resolver.resolve(
                "%2Fapi%2Fhuman-resources%2Ffuncionarios%2F%7Bid%7D%2Fprofile",
                "patch"
        );

        assertEquals("api-human-resources-funcionarios", ref.group());
        assertEquals("/api/human-resources/funcionarios/{id}/profile", ref.path());
        assertEquals("PATCH", ref.method());
    }

    @Test
    void resolveFromHandlerMethodPrefersShortestPatternAndOperationAnnotation() throws Exception {
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), DummyController.class.getMethod("list"));
        RequestMappingInfo mappingInfo = RequestMappingInfo
                .paths("/api/employees/{id}", "/api/employees")
                .methods(RequestMethod.GET)
                .build();
        when(openApiDocumentService.resolveGroupFromPath("/api/employees")).thenReturn("employees");

        CanonicalOperationRef ref = resolver.resolve(handlerMethod, mappingInfo);

        assertEquals("employees", ref.group());
        assertEquals("listEmployees", ref.operationId());
        assertEquals("/api/employees", ref.path());
        assertEquals("GET", ref.method());
    }

    @Test
    void resolveByOperationIdKeepsUniqueLegacyMethodNameValid() throws Exception {
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), DummyController.class.getMethod("details"));
        RequestMappingInfo mappingInfo = RequestMappingInfo
                .paths("/api/employees/{id}", "/api/employees/details/{id}")
                .methods(RequestMethod.GET)
                .build();
        LinkedHashMap<RequestMappingInfo, HandlerMethod> mappings = new LinkedHashMap<>();
        mappings.put(mappingInfo, handlerMethod);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);
        when(openApiDocumentService.resolveGroupFromPath("/api/employees/{id}")).thenReturn("employees");

        Optional<CanonicalOperationRef> resolved = resolver.resolveByOperationId("details");

        assertTrue(resolved.isPresent());
        assertEquals("details", resolved.get().operationId());
        assertEquals("/api/employees/{id}", resolved.get().path());
    }

    @Test
    void resolveByOperationIdReturnsEmptyWhenNoHandlerMappingExists() {
        OpenApiCanonicalOperationResolver resolverWithoutMapping =
                new OpenApiCanonicalOperationResolver(openApiDocumentService, null);

        Optional<CanonicalOperationRef> resolved = resolverWithoutMapping.resolveByOperationId("missing");

        assertTrue(resolved.isEmpty());
    }

    @Test
    void resolveByOperationIdReturnsEmptyWhenNoRegisteredHandlerUsesTheId() throws Exception {
        HandlerMethod handlerMethod = new HandlerMethod(new DummyController(), DummyController.class.getMethod("list"));
        RequestMappingInfo mappingInfo = RequestMappingInfo
                .paths("/api/employees")
                .methods(RequestMethod.GET)
                .build();
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mappingInfo, handlerMethod));

        assertTrue(resolver.resolveByOperationId("missing").isEmpty());
    }

    @Test
    void resolveByOperationIdRejectsDuplicateExplicitIds() throws Exception {
        DuplicateOperationController controller = new DuplicateOperationController();
        Map<RequestMappingInfo, HandlerMethod> mappings = new LinkedHashMap<>();
        mappings.put(
                RequestMappingInfo.paths("/api/one").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, DuplicateOperationController.class.getMethod("first"))
        );
        mappings.put(
                RequestMappingInfo.paths("/api/two").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, DuplicateOperationController.class.getMethod("second"))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);

        assertThrows(IllegalStateException.class, () -> resolver.resolveByOperationId("duplicate-operation"));
    }

    @Test
    void resolveByOperationIdRejectsExplicitIdCollidingWithLegacyMethodName() throws Exception {
        ExplicitAndLegacyCollisionController controller = new ExplicitAndLegacyCollisionController();
        Map<RequestMappingInfo, HandlerMethod> mappings = new LinkedHashMap<>();
        mappings.put(
                RequestMappingInfo.paths("/api/explicit").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, ExplicitAndLegacyCollisionController.class.getMethod("explicit"))
        );
        mappings.put(
                RequestMappingInfo.paths("/api/legacy").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, ExplicitAndLegacyCollisionController.class.getMethod("effectiveOperation"))
        );
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);

        assertThrows(IllegalStateException.class, () -> resolver.resolveByOperationId("effectiveOperation"));
    }

    @Test
    void resolveByOperationIdRejectsOneHandlerRegisteredUnderTwoMappings() throws Exception {
        RepeatedMappingController controller = new RepeatedMappingController();
        HandlerMethod handler = new HandlerMethod(controller, RepeatedMappingController.class.getMethod("evaluate"));
        Map<RequestMappingInfo, HandlerMethod> mappings = new LinkedHashMap<>();
        mappings.put(RequestMappingInfo.paths("/api/one").methods(RequestMethod.POST).build(), handler);
        mappings.put(RequestMappingInfo.paths("/api/two").methods(RequestMethod.POST).build(), handler);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);

        assertThrows(IllegalStateException.class, () -> resolver.resolveByOperationId("repeated-operation"));
    }

    @Test
    void strictResolutionDefaultFailsClosedForCustomResolvers() {
        CanonicalOperationResolver customResolver = new CanonicalOperationResolver() {
            @Override
            public String resolveGroup(String path) {
                return "custom";
            }

            @Override
            public CanonicalOperationRef resolve(String path, String method) {
                return new CanonicalOperationRef("custom", null, path, method);
            }

            @Override
            public CanonicalOperationRef resolve(HandlerMethod handlerMethod, RequestMappingInfo mappingInfo) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<CanonicalOperationRef> resolveByOperationId(String operationId) {
                return Optional.empty();
            }
        };

        assertThrows(
                UnsupportedOperationException.class,
                () -> customResolver.requireResourceOperation("inventory.products", "productsEvaluate", "POST")
        );
    }

    @Test
    void strictResolutionRejectsBlankArgumentsAndUnsupportedHttpMethod() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.requireResourceOperation("", "productsEvaluate", "POST")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.requireResourceOperation("inventory.products", "", "POST")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.requireResourceOperation("inventory.products", "productsEvaluate", "")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.requireResourceOperation("inventory.products", "productsEvaluate", "BREW")
        );
    }

    @Test
    void strictResolutionRejectsWhenMvcHandlerMappingIsUnavailable() {
        OpenApiCanonicalOperationResolver resolverWithoutMapping =
                new OpenApiCanonicalOperationResolver(openApiDocumentService, null);

        assertThrows(
                IllegalStateException.class,
                () -> resolverWithoutMapping.requireResourceOperation("inventory.products", "productsEvaluate", "POST")
        );
    }

    @Test
    void strictResolutionRejectsAmbiguousRoutingAndUnrepresentableConditions() throws Exception {
        HandlerMethod handler = bindingHandler("evaluate");

        assertStrictBindingRejected(
                RequestMappingInfo.paths("/api/products/evaluation").build(),
                handler
        );
        assertStrictBindingRejected(
                RequestMappingInfo.paths("/api/products/evaluation").methods(RequestMethod.POST).params("mode=bulk").build(),
                handler
        );
        assertStrictBindingRejected(
                RequestMappingInfo.paths("/api/products/evaluation").methods(RequestMethod.POST).headers("X-Mode=bulk").build(),
                handler
        );
        assertStrictBindingRejected(
                RequestMappingInfo.paths("/api/products/evaluation")
                        .methods(RequestMethod.POST)
                        .customCondition(new TestCustomCondition())
                        .build(),
                handler
        );
    }

    @Test
    void strictResolutionRejectsHiddenAndResourceLessOperations() throws Exception {
        HiddenBindingController hiddenController = new HiddenBindingController();
        assertStrictBindingRejected(
                RequestMappingInfo.paths("/api/products/evaluation").methods(RequestMethod.POST).build(),
                new HandlerMethod(hiddenController, HiddenBindingController.class.getMethod("evaluate"))
        );

        ResourceLessController resourceLessController = new ResourceLessController();
        assertStrictBindingRejected(
                RequestMappingInfo.paths("/api/products/evaluation").methods(RequestMethod.POST).build(),
                new HandlerMethod(resourceLessController, ResourceLessController.class.getMethod("evaluate"))
        );
    }

    @Test
    void strictResolutionRejectsWhitespaceOperationIdAndPathThatWouldBeNormalized() throws Exception {
        WhitespaceOperationController whitespaceController = new WhitespaceOperationController();
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/products/evaluation").methods(RequestMethod.POST).build(),
                new HandlerMethod(whitespaceController, WhitespaceOperationController.class.getMethod("productsEvaluate"))
        ));

        assertThrows(
                IllegalStateException.class,
                () -> resolver.requireResourceOperation("inventory.products", "productsEvaluate", "POST")
        );

        assertStrictBindingRejected(
                nonCanonicalPathMapping("/api/products//evaluation"),
                bindingHandler("evaluate")
        );
    }

    @Test
    void strictResolutionDoesNotFetchAnOpenApiDocument() throws Exception {
        RequestMappingInfo mapping = RequestMappingInfo.paths("/api/products/evaluation")
                .methods(RequestMethod.POST)
                .build();
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mapping, bindingHandler("evaluate")));
        when(openApiDocumentService.resolveGroupFromPath("/api/products/evaluation")).thenReturn("inventory");

        CanonicalOperationRef resolved = resolver.requireResourceOperation(
                "inventory.products", "productsEvaluate", "POST"
        );

        assertEquals("productsEvaluate", resolved.operationId());
        verify(openApiDocumentService, never()).getDocumentForGroup(anyString());
    }

    private void assertStrictBindingRejected(RequestMappingInfo mapping, HandlerMethod handler) {
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mapping, handler));

        assertThrows(
                IllegalStateException.class,
                () -> resolver.requireResourceOperation("inventory.products", "productsEvaluate", "POST")
        );
    }

    private HandlerMethod bindingHandler(String methodName) throws Exception {
        BindingController controller = new BindingController();
        return new HandlerMethod(controller, BindingController.class.getMethod(methodName));
    }

    private RequestMappingInfo nonCanonicalPathMapping(String path) {
        return new RequestMappingInfo(
                new PatternsRequestCondition(path),
                new RequestMethodsRequestCondition(RequestMethod.POST),
                new ParamsRequestCondition(),
                new HeadersRequestCondition(),
                new ConsumesRequestCondition(),
                new ProducesRequestCondition(),
                null
        );
    }

    static final class DummyController {

        @Operation(operationId = "listEmployees")
        @GetMapping("/api/employees")
        public void list() {
        }

        @GetMapping("/api/employees/{id}")
        public void details() {
        }
    }

    static final class DuplicateOperationController {

        @Operation(operationId = "duplicate-operation")
        public void first() {
        }

        @Operation(operationId = "duplicate-operation")
        public void second() {
        }
    }

    static final class ExplicitAndLegacyCollisionController {

        @Operation(operationId = "effectiveOperation")
        public void explicit() {
        }

        public void effectiveOperation() {
        }
    }

    static final class RepeatedMappingController {

        @Operation(operationId = "repeated-operation")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/api/products", resourceKey = "inventory.products")
    static final class BindingController {

        @Operation(operationId = "productsEvaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/api/products", resourceKey = "inventory.products")
    static final class HiddenBindingController {

        @Hidden
        @Operation(operationId = "productsEvaluate")
        public void evaluate() {
        }
    }

    static final class ResourceLessController {

        @Operation(operationId = "productsEvaluate")
        public void evaluate() {
        }
    }

    @ApiResource(value = "/api/products", resourceKey = "inventory.products")
    static final class WhitespaceOperationController {

        @Operation(operationId = "   ")
        public void productsEvaluate() {
        }
    }

    static final class TestCustomCondition implements RequestCondition<TestCustomCondition> {

        @Override
        public TestCustomCondition combine(TestCustomCondition other) {
            return this;
        }

        @Override
        public TestCustomCondition getMatchingCondition(HttpServletRequest request) {
            return this;
        }

        @Override
        public int compareTo(TestCustomCondition other, HttpServletRequest request) {
            return 0;
        }
    }
}
