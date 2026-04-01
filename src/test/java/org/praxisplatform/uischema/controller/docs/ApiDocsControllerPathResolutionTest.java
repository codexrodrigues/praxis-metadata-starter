package org.praxisplatform.uischema.controller.docs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Testes para validar a resolucao automatica de grupos no ApiDocsController.
 */
@ExtendWith(MockitoExtension.class)
class ApiDocsControllerPathResolutionTest {

    @Mock
    private OpenApiGroupResolver openApiGroupResolver;

    private OpenApiDocsSupport openApiDocsSupport;

    @BeforeEach
    void setUp() {
        openApiDocsSupport = new OpenApiDocsSupport();
        ReflectionTestUtils.setField(openApiDocsSupport, "openApiGroupResolver", openApiGroupResolver);
    }

    @Test
    void testResolveGroupFromPath_WithOpenApiGroupResolver() {
        String path = "/api/human-resources/funcionarios/all";
        String expectedGroup = "human-resources";
        when(openApiGroupResolver.resolveGroup(path)).thenReturn(expectedGroup);

        String resolvedGroup = invokeResolveGroupFromPath(path);

        assertEquals(expectedGroup, resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_DecodesEncodedPathBeforeDelegating() {
        String encodedPath = "%2Fapi%2Fhuman-resources%2Ffuncionarios%2F%7Bid%7D%2Fprofile";
        String decodedPath = "/api/human-resources/funcionarios/{id}/profile";
        when(openApiGroupResolver.resolveGroup(decodedPath)).thenReturn("api-human-resources-funcionarios");

        String resolvedGroup = invokeResolveGroupFromPath(encodedPath);

        assertEquals("api-human-resources-funcionarios", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithPathDerivation() {
        String path = "/api/human-resources/eventos-folha/all";
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);

        String resolvedGroup = invokeResolveGroupFromPath(path);

        assertEquals("api-human-resources-eventos-folha", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithShortPath() {
        String path = "/api/funcionarios";
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);

        String resolvedGroup = invokeResolveGroupFromPath(path);

        assertEquals("api", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithVeryShortPath() {
        String path = "/funcionarios";
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);

        String resolvedGroup = invokeResolveGroupFromPath(path);

        assertEquals("funcionarios", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithEmptyPath() {
        String path = "";

        String resolvedGroup = invokeResolveGroupFromPath(path);

        assertEquals("application", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithNullPath() {
        String path = null;

        String resolvedGroup = invokeResolveGroupFromPath(path);

        assertEquals("application", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_ComplexPaths() {
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);

        assertEquals("api-human-resources-funcionarios",
                invokeResolveGroupFromPath("/api/human-resources/funcionarios/all"));

        assertEquals("api-human-resources-departamentos",
                invokeResolveGroupFromPath("/api/human-resources/departamentos/123"));

        assertEquals("api-human-resources-eventos-folha",
                invokeResolveGroupFromPath("/api/human-resources/eventos-folha/filter"));

        assertEquals("api-financial-accounts",
                invokeResolveGroupFromPath("/api/financial/accounts/summary"));

        assertEquals("api-inventory-products",
                invokeResolveGroupFromPath("/api/inventory/products/{id}/details"));

        assertEquals("integration-employees",
                invokeResolveGroupFromPath("/integration-employees/{id}/profile"));
    }

    private String invokeResolveGroupFromPath(String path) {
        return openApiDocsSupport.resolveGroupFromPath(path);
    }
}
