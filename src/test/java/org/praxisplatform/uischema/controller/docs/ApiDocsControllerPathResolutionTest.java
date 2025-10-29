package org.praxisplatform.uischema.controller.docs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Testes para validar a resolução automática de grupos no ApiDocsController.
 */
@ExtendWith(MockitoExtension.class)
class ApiDocsControllerPathResolutionTest {

    @Mock
    private OpenApiGroupResolver openApiGroupResolver;

    @InjectMocks
    private ApiDocsController apiDocsController;

    @BeforeEach
    void setUp() {
        // Injetar o mock via reflection no campo privado
        ReflectionTestUtils.setField(apiDocsController, "openApiGroupResolver", openApiGroupResolver);
    }

    @Test
    void testResolveGroupFromPath_WithOpenApiGroupResolver() {
        // Given
        String path = "/api/human-resources/funcionarios/all";
        String expectedGroup = "human-resources";
        when(openApiGroupResolver.resolveGroup(path)).thenReturn(expectedGroup);

        // When
        String resolvedGroup = invokeResolveGroupFromPath(path);

        // Then
        assertEquals(expectedGroup, resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithPathDerivation() {
        // Given
        String path = "/api/human-resources/eventos-folha/all";
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);

        // When
        String resolvedGroup = invokeResolveGroupFromPath(path);

        // Then
        assertEquals("api-human-resources-eventos-folha", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithShortPath() {
        // Given
        String path = "/api/funcionarios";
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);

        // When
        String resolvedGroup = invokeResolveGroupFromPath(path);

        // Then
        assertEquals("api", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithVeryShortPath() {
        // Given
        String path = "/funcionarios";
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);

        // When
        String resolvedGroup = invokeResolveGroupFromPath(path);

        // Then
        assertEquals("funcionarios", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithEmptyPath() {
        // Given
        String path = "";
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);

        // When
        String resolvedGroup = invokeResolveGroupFromPath(path);

        // Then
        assertEquals("application", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_WithNullPath() {
        // Given
        String path = null;

        // When
        String resolvedGroup = invokeResolveGroupFromPath(path);

        // Then
        assertEquals("application", resolvedGroup);
    }

    @Test
    void testResolveGroupFromPath_ComplexPaths() {
        // Given - diferentes cenários de paths reais
        when(openApiGroupResolver.resolveGroup(anyString())).thenReturn(null);

        // Test cases com expected results
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
    }

    /**
     * Invoca o método privado resolveGroupFromPath via reflection para testes.
     */
    private String invokeResolveGroupFromPath(String path) {
        try {
            return (String) ReflectionTestUtils.invokeMethod(apiDocsController, "resolveGroupFromPath", path);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao invocar método privado", e);
        }
    }
}
