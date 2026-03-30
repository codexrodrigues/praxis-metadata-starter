package org.praxisplatform.uischema.openapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class OpenApiCanonicalOperationResolverTest {

    @Mock
    private OpenApiDocumentService openApiDocumentService;

    private OpenApiCanonicalOperationResolver resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resolver = new OpenApiCanonicalOperationResolver(openApiDocumentService, null);
    }

    @Test
    void resolveNormalizesPathMethodAndUsesResolvedGroup() {
        when(openApiDocumentService.resolveGroupFromPath("/api/human-resources/employees/")).thenReturn("hr");

        CanonicalOperationRef ref = resolver.resolve("/api/human-resources/employees/", "post");

        assertEquals("hr", ref.group());
        assertEquals("/api/human-resources/employees", ref.path());
        assertEquals("POST", ref.method());
    }
}
