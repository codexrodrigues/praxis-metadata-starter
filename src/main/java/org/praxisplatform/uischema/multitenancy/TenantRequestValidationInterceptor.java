package org.praxisplatform.uischema.multitenancy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.praxisplatform.uischema.customfields.ResourceTenantContextResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/**
 * {@link HandlerInterceptor} para validar precocemente os identificadores de tenant e realm nas
 * requisicoes HTTP antes da entrada em controladores ou servicos.
 */
public class TenantRequestValidationInterceptor implements HandlerInterceptor {

    public static final String DEFAULT_REALM_HEADER = "X-Tenant-Realm";

    private final ResourceTenantContextResolver tenantContextResolver;
    private final String realmHeaderName;
    private final ThreadLocal<TenantRealmContext.Scope> activeScope = new ThreadLocal<>();

    public TenantRequestValidationInterceptor(ResourceTenantContextResolver tenantContextResolver) {
        this(tenantContextResolver, DEFAULT_REALM_HEADER);
    }

    public TenantRequestValidationInterceptor(
            ResourceTenantContextResolver tenantContextResolver,
            String realmHeaderName
    ) {
        this.tenantContextResolver = Objects.requireNonNull(tenantContextResolver, "tenantContextResolver must not be null");
        this.realmHeaderName = realmHeaderName == null || realmHeaderName.isBlank()
                ? DEFAULT_REALM_HEADER
                : realmHeaderName.trim();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            tenantContextResolver.resolveTenant();
            String realm = request != null ? TenantRealmContext.normalize(request.getHeader(realmHeaderName)) : null;
            if (realm != null) {
                activeScope.set(TenantRealmContext.open(realm));
            }
            return true;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        TenantRealmContext.Scope scope = activeScope.get();
        if (scope != null) {
            activeScope.remove();
            scope.close();
        }
    }
}
