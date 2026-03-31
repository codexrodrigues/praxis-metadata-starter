package org.praxisplatform.uischema.surface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DefaultSurfaceAvailabilityContextResolverTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        Locale.setDefault(Locale.ROOT);
    }

    @Test
    void resolvesTenantPrincipalAuthoritiesAndStateFromCurrentRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant", "tenant-a");
        request.setUserPrincipal(new TestPrincipal("qa-user", List.of(new TestAuthority("employee:profile:update"))));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        DefaultSurfaceAvailabilityContextResolver resolver = new DefaultSurfaceAvailabilityContextResolver(
                (resourceKey, resourceId) -> Optional.of(ResourceStateSnapshot.of(
                        "ACTIVE",
                        Map.of("employeeId", resourceId)
                ))
        );

        SurfaceAvailabilityContext context = resolver.resolve("example.employees", "/employees", 42L);

        assertEquals("example.employees", context.resourceKey());
        assertEquals("/employees", context.resourcePath());
        assertEquals(42L, context.resourceId());
        assertEquals("tenant-a", context.tenant());
        assertNotNull(context.locale());
        assertEquals("qa-user", context.principal().getName());
        assertIterableEquals(List.of("employee:profile:update"), context.authorities());
        assertNotNull(context.resourceState());
        assertEquals("ACTIVE", context.resourceState().state());
    }

    @Test
    void degradesToNeutralAuthoritiesWhenAuthorityExtractionFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(new BrokenPrincipal("qa-user"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        DefaultSurfaceAvailabilityContextResolver resolver = new DefaultSurfaceAvailabilityContextResolver();

        SurfaceAvailabilityContext context = resolver.resolve("example.employees", "/employees", 42L);

        assertEquals("qa-user", context.principal().getName());
        assertEquals(java.util.Set.of(), context.authorities());
    }

    private record TestAuthority(String authority) {
        public String getAuthority() {
            return authority;
        }
    }

    private record TestPrincipal(String name, List<TestAuthority> authorities) implements java.security.Principal {
        @Override
        public String getName() {
            return name;
        }

        public List<TestAuthority> getAuthorities() {
            return authorities;
        }
    }

    private static final class BrokenPrincipal implements java.security.Principal {

        private final String name;

        private BrokenPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        public List<BrokenAuthority> getAuthorities() {
            return List.of(new BrokenAuthority());
        }
    }

    private static final class BrokenAuthority {
        public String getAuthority() {
            throw new IllegalStateException("broken");
        }
    }
}
