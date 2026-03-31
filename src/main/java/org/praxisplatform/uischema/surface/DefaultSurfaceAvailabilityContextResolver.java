package org.praxisplatform.uischema.surface;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.Principal;
import java.util.Locale;

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

    @Override
    public SurfaceAvailabilityContext resolve(SurfaceDefinition definition, Object resourceId) {
        return new SurfaceAvailabilityContext(
                definition.resourceKey(),
                definition.resourcePath(),
                resourceId,
                resolveTenant(),
                resolveLocale(),
                resolvePrincipal()
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
}
