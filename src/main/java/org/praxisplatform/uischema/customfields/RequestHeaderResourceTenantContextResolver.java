package org.praxisplatform.uischema.customfields;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Header adapter; tenant authorization remains the host's responsibility. */
public final class RequestHeaderResourceTenantContextResolver implements ResourceTenantContextResolver {
    private static final Pattern SAFE_TENANT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    public Optional<String> resolveTenant() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        String primary = normalize(attributes.getRequest().getHeader("X-Tenant"));
        String alternate = normalize(attributes.getRequest().getHeader("X-Tenant-ID"));
        if (primary != null && alternate != null && !primary.equals(alternate)) {
            throw new IllegalArgumentException("Conflicting tenant headers");
        }
        return Optional.ofNullable(primary != null ? primary : alternate);
    }

    public static String normalize(String tenant) {
        if (tenant == null || tenant.isBlank()) return null;
        if (!SAFE_TENANT.matcher(tenant).matches()) {
            throw new IllegalArgumentException("Invalid tenant identifier");
        }
        return tenant;
    }
}
