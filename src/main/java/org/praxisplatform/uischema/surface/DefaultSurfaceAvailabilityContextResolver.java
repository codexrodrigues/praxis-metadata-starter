package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.NoOpResourceStateSnapshotProvider;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.capability.ResourceStateSnapshotProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolver baseline do contexto de availability.
 *
 * <p>
 * O contexto atual usa sinais canonicamente disponiveis no starter: `resourceKey`, `resourcePath`,
 * `resourceId`, locale da request, principal autenticado e header `X-Tenant` quando presente.
 * Ainda nao existe semantica estrutural por tenant nesta fase; o valor serve apenas para discovery
 * contextual e futuras regras de availability.
 * </p>
 */
public class DefaultSurfaceAvailabilityContextResolver implements SurfaceAvailabilityContextResolver {

    private static final String TENANT_HEADER = "X-Tenant";
    private final ResourceStateSnapshotProvider resourceStateSnapshotProvider;

    public DefaultSurfaceAvailabilityContextResolver() {
        this(new NoOpResourceStateSnapshotProvider());
    }

    public DefaultSurfaceAvailabilityContextResolver(ResourceStateSnapshotProvider resourceStateSnapshotProvider) {
        this.resourceStateSnapshotProvider = resourceStateSnapshotProvider;
    }

    @Override
    public SurfaceAvailabilityContext resolve(String resourceKey, String resourcePath, Object resourceId) {
        Principal principal = resolvePrincipal();
        return new SurfaceAvailabilityContext(
                resourceKey,
                resourcePath,
                resourceId,
                resolveTenant(),
                resolveLocale(),
                principal,
                resolveAuthorities(principal),
                resolveResourceState(resourceKey, resourceId)
        );
    }

    private String resolveTenant() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletRequestAttributes) {
            String tenant = servletRequestAttributes.getRequest().getHeader(TENANT_HEADER);
            return StringUtils.hasText(tenant) ? tenant.trim() : null;
        }
        return null;
    }

    private Locale resolveLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null ? locale : Locale.ROOT;
    }

    private Principal resolvePrincipal() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest().getUserPrincipal();
        }
        return null;
    }

    private Set<String> resolveAuthorities(Principal principal) {
        if (principal == null) {
            return Set.of();
        }
        LinkedHashSet<String> authorities = new LinkedHashSet<>();
        extractAuthorities(principal, authorities);
        return Set.copyOf(authorities);
    }

    private void extractAuthorities(Object source, Collection<String> sink) {
        if (source == null || sink == null) {
            return;
        }
        try {
            Method method = findNoArgMethod(source.getClass(), "getAuthorities");
            if (method == null) {
                return;
            }
            Object value = method.invoke(source);
            if (value instanceof Iterable<?> iterable) {
                for (Object authority : iterable) {
                    String resolved = resolveAuthorityValue(authority);
                    if (StringUtils.hasText(resolved)) {
                        sink.add(resolved.trim());
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Principal sem authorities expostas por metodo; contexto segue neutro.
        }
    }

    private String resolveAuthorityValue(Object authority) {
        if (authority == null) {
            return null;
        }
        if (authority instanceof String stringAuthority) {
            return stringAuthority;
        }
        try {
            Method method = findNoArgMethod(authority.getClass(), "getAuthority");
            if (method == null) {
                return null;
            }
            Object value = method.invoke(authority);
            return value instanceof String stringValue ? stringValue : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private ResourceStateSnapshot resolveResourceState(String resourceKey, Object resourceId) {
        if (!StringUtils.hasText(resourceKey) || resourceId == null) {
            return null;
        }
        return resourceStateSnapshotProvider.resolve(resourceKey, resourceId).orElse(null);
    }

    private Method findNoArgMethod(Class<?> type, String methodName) {
        try {
            Method method = type.getMethod(methodName);
            return makeAccessible(method);
        } catch (NoSuchMethodException ex) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                return makeAccessible(method);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
    }

    private Method makeAccessible(Method method) {
        if (method == null) {
            return null;
        }
        try {
            method.trySetAccessible();
            return method;
        } catch (SecurityException | InaccessibleObjectException ignored) {
            return null;
        }
    }
}
