package org.praxisplatform.uischema.multitenancy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * {@link SecurityContextRepository} que isola atributos de sessao por realm/tenant ativo, evitando
 * colisao ou vazamento de sessoes entre realmos distintos na mesma aplicacao.
 *
 * <p>Requer {@code spring-security-web} no classpath (dependencia opcional do starter).</p>
 */
public class RealmScopedSecurityContextRepository implements SecurityContextRepository {

    public static final String DEFAULT_REALM = "default";
    public static final String DEFAULT_KEY_PREFIX = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY + "::";

    private final Function<HttpServletRequest, String> realmResolver;
    private final String sessionKeyPrefix;
    private final boolean allowSessionCreation;
    private final ConcurrentMap<String, HttpSessionSecurityContextRepository> delegates = new ConcurrentHashMap<>();

    public RealmScopedSecurityContextRepository() {
        this(defaultRealmResolver());
    }

    public RealmScopedSecurityContextRepository(Function<HttpServletRequest, String> realmResolver) {
        this(realmResolver, DEFAULT_KEY_PREFIX, true);
    }

    public RealmScopedSecurityContextRepository(
            Function<HttpServletRequest, String> realmResolver,
            String sessionKeyPrefix,
            boolean allowSessionCreation
    ) {
        this.realmResolver = Objects.requireNonNull(realmResolver, "realmResolver must not be null");
        this.sessionKeyPrefix = (sessionKeyPrefix == null || sessionKeyPrefix.isBlank())
                ? DEFAULT_KEY_PREFIX
                : sessionKeyPrefix;
        this.allowSessionCreation = allowSessionCreation;
    }

    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return delegateFor(requestResponseHolder.getRequest()).loadContext(requestResponseHolder);
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return delegateFor(request).loadDeferredContext(request);
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        delegateFor(request).saveContext(context, request, response);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return delegateFor(request).containsContext(request);
    }

    private HttpSessionSecurityContextRepository delegateFor(HttpServletRequest request) {
        String realm = resolveRealm(request);
        return delegates.computeIfAbsent(realm, key -> {
            HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
            repo.setSpringSecurityContextKey(sessionKeyPrefix + key);
            repo.setAllowSessionCreation(allowSessionCreation);
            return repo;
        });
    }

    private String resolveRealm(HttpServletRequest request) {
        String resolved = request != null ? realmResolver.apply(request) : null;
        if (resolved == null || resolved.isBlank()) {
            return TenantRealmContext.get().orElse(DEFAULT_REALM);
        }
        String normalized = TenantRealmContext.normalize(resolved);
        return normalized != null ? normalized : DEFAULT_REALM;
    }

    public static Function<HttpServletRequest, String> defaultRealmResolver() {
        return request -> {
            if (request == null) {
                return TenantRealmContext.get().orElse(DEFAULT_REALM);
            }
            String realmHeader = request.getHeader(TenantRequestValidationInterceptor.DEFAULT_REALM_HEADER);
            if (realmHeader != null && !realmHeader.isBlank()) {
                return realmHeader;
            }
            String tenant = request.getHeader("X-Tenant");
            if (tenant != null && !tenant.isBlank()) {
                return tenant;
            }
            String altTenant = request.getHeader("X-Tenant-ID");
            if (altTenant != null && !altTenant.isBlank()) {
                return altTenant;
            }
            return TenantRealmContext.get().orElse(DEFAULT_REALM);
        };
    }
}
