package org.praxisplatform.uischema.e2e.fixture;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.praxisplatform.uischema.surface.ResourceStateSnapshot;
import org.praxisplatform.uischema.surface.ResourceStateSnapshotProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class EmployeeSurfaceStateSnapshotProvider implements ResourceStateSnapshotProvider {

    private final EmployeeRepository employeeRepository;

    EmployeeSurfaceStateSnapshotProvider(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    @Override
    public Optional<ResourceStateSnapshot> resolve(String resourceKey, Object resourceId) {
        if (!"human-resources.employees".equals(resourceKey) || !(resourceId instanceof Long id)) {
            return Optional.empty();
        }
        return employeeRepository.findById(id)
                .map(employee -> ResourceStateSnapshot.of(
                        employee.getStatus().name(),
                        java.util.Map.of("employeeId", employee.getId())
                ));
    }
}

@Component
class TestPrincipalHeaderFilter extends OncePerRequestFilter {

    private static final String PRINCIPAL_HEADER = "X-Test-Principal";
    private static final String AUTHORITIES_HEADER = "X-Test-Authorities";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String principalName = request.getHeader(PRINCIPAL_HEADER);
        String authoritiesHeader = request.getHeader(AUTHORITIES_HEADER);
        if (!StringUtils.hasText(principalName) && !StringUtils.hasText(authoritiesHeader)) {
            filterChain.doFilter(request, response);
            return;
        }

        TestAuthoritiesPrincipal principal = new TestAuthoritiesPrincipal(
                StringUtils.hasText(principalName) ? principalName.trim() : "test-user",
                parseAuthorities(authoritiesHeader)
        );

        HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }

            @Override
            public boolean isUserInRole(String role) {
                return principal.hasAuthority(role);
            }
        };

        filterChain.doFilter(wrapped, response);
    }

    private List<TestGrantedAuthority> parseAuthorities(String authoritiesHeader) {
        if (!StringUtils.hasText(authoritiesHeader)) {
            return List.of();
        }
        Set<String> values = Arrays.stream(authoritiesHeader.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return values.stream().map(TestGrantedAuthority::new).toList();
    }

    private record TestGrantedAuthority(String authority) {

        public String getAuthority() {
            return authority;
        }
    }

    private static final class TestAuthoritiesPrincipal implements Principal {

        private final String name;
        private final List<TestGrantedAuthority> authorities;

        private TestAuthoritiesPrincipal(String name, List<TestGrantedAuthority> authorities) {
            this.name = name;
            this.authorities = List.copyOf(authorities);
        }

        @Override
        public String getName() {
            return name;
        }

        public Collection<TestGrantedAuthority> getAuthorities() {
            return authorities;
        }

        public boolean hasAuthority(String authority) {
            return authorities.stream().anyMatch(current -> current.authority().equals(authority));
        }
    }
}
