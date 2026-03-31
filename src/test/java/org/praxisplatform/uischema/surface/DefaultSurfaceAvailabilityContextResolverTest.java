package org.praxisplatform.uischema.surface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DefaultSurfaceAvailabilityContextResolverTest {

    private final DefaultSurfaceAvailabilityContextResolver resolver = new DefaultSurfaceAvailabilityContextResolver();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        Locale.setDefault(Locale.ROOT);
    }

    @Test
    void resolvesTenantAndPrincipalFromCurrentRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant", "tenant-a");
        request.setUserPrincipal(() -> "qa-user");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        SurfaceAvailabilityContext context = resolver.resolve(definition(), 42L);

        assertEquals("example.employees", context.resourceKey());
        assertEquals("/employees", context.resourcePath());
        assertEquals(42L, context.resourceId());
        assertEquals("tenant-a", context.tenant());
        assertNotNull(context.locale());
        assertEquals("qa-user", context.principal().getName());
    }

    private SurfaceDefinition definition() {
        return new SurfaceDefinition(
                "detail",
                "example.employees",
                "/employees",
                "example",
                SurfaceKind.VIEW,
                SurfaceScope.ITEM,
                "Detalhar",
                "",
                "detail",
                "response",
                new CanonicalOperationRef("example", "getEmployee", "/employees/{id}", "GET"),
                new CanonicalSchemaRef("schema-id", "response", "/schemas/filtered?path=/employees/{id}"),
                10,
                List.of()
        );
    }
}
