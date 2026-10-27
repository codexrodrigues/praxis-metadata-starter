package org.praxisplatform.uischema.customfields;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestHeaderResourceTenantContextResolverTest {
    final RequestHeaderResourceTenantContextResolver resolver = new RequestHeaderResourceTenantContextResolver();
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }
    @Test void noRequestIsNeutral() { assertTrue(resolver.resolveTenant().isEmpty()); }
    @Test void supportsBothHeadersAndRejectsAmbiguity() {
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        request.addHeader("X-Tenant-ID", "agency:12");
        assertEquals("agency:12", resolver.resolveTenant().orElseThrow());
        request.addHeader("X-Tenant", "agency:13");
        assertThrows(IllegalArgumentException.class, resolver::resolveTenant);
    }
    @Test void validatesBothHeadersWithoutSilentlyFallingBack() {
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        request.addHeader("X-Tenant", "safe"); request.addHeader("X-Tenant-ID", "../unsafe");
        assertThrows(IllegalArgumentException.class, resolver::resolveTenant);
        for (String value : new String[]{"a".repeat(65), " spaced ", "a/b", "a|b"})
            assertThrows(IllegalArgumentException.class, () -> RequestHeaderResourceTenantContextResolver.normalize(value));
        assertNull(RequestHeaderResourceTenantContextResolver.normalize(""));
    }
}
