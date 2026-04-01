package org.praxisplatform.uischema.openapi;

import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void resolveByOperationIdFindsMatchingHandler() throws Exception {
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

    static final class DummyController {

        @Operation(operationId = "listEmployees")
        @GetMapping("/api/employees")
        public void list() {
        }

        @GetMapping("/api/employees/{id}")
        public void details() {
        }
    }
}
