package org.praxisplatform.uischema.action;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DefaultActionAvailabilityContextResolverTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void resolvesTenantPrincipalAuthoritiesAndStateFromCurrentRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant", "tenant-a");
        request.setUserPrincipal(new TestPrincipal("qa-user", List.of(new TestAuthority("employee:approve"))));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        DefaultActionAvailabilityContextResolver resolver = new DefaultActionAvailabilityContextResolver(
                (resourceKey, resourceId) -> Optional.of(ResourceStateSnapshot.of(
                        "INACTIVE",
                        Map.of("employeeId", resourceId)
                ))
        );

        ActionAvailabilityContext context = resolver.resolve("example.employees", "/employees", 42L);

        assertEquals("example.employees", context.resourceKey());
        assertEquals("/employees", context.resourcePath());
        assertEquals(42L, context.resourceId());
        assertEquals("tenant-a", context.tenant());
        assertNotNull(context.locale());
        assertEquals("qa-user", context.principal().getName());
        assertIterableEquals(List.of("employee:approve"), context.authorities());
        assertNotNull(context.resourceState());
        assertEquals("INACTIVE", context.resourceState().state());
    }

    @Test
    void degradesToNeutralAuthoritiesWhenAuthorityExtractionFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(new BrokenPrincipal("qa-user"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        DefaultActionAvailabilityContextResolver resolver = new DefaultActionAvailabilityContextResolver();

        ActionAvailabilityContext context = resolver.resolve("example.employees", "/employees", 42L);

        assertEquals("qa-user", context.principal().getName());
        assertEquals(Set.of(), context.authorities());
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
