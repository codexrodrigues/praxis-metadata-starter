package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;

import java.util.Objects;

/**
 * Shared read-only OpenAPI document accessors that avoid defensive {@code deepCopy()}
 * overhead when reading from {@link CachedOpenApiDocumentService} directly or through a Spring proxy.
 * Callers MUST NOT mutate the returned {@link JsonNode}.
 */
public final class OpenApiDocumentReaders {

    private OpenApiDocumentReaders() {
    }

    public static JsonNode readOnlyGroupDocument(
            OpenApiDocumentService service,
            String groupName,
            boolean strict
    ) {
        Objects.requireNonNull(service, "openApiDocumentService");
        CachedOpenApiDocumentService target = unwrapDirectOrSpringProxyCachedDocumentService(service);
        if (target != null) {
            return strict
                    ? target.getReadOnlyDocumentForGroupStrict(groupName)
                    : target.getReadOnlyDocumentForGroup(groupName);
        }
        return strict
                ? service.getDocumentForGroupStrict(groupName)
                : service.getDocumentForGroup(groupName);
    }

    private static CachedOpenApiDocumentService unwrapDirectOrSpringProxyCachedDocumentService(
            OpenApiDocumentService service
    ) {
        if (service.getClass() == CachedOpenApiDocumentService.class) {
            return (CachedOpenApiDocumentService) service;
        }
        if (AopUtils.isAopProxy(service)
                && AopProxyUtils.ultimateTargetClass(service) == CachedOpenApiDocumentService.class) {
            Object singletonTarget = AopProxyUtils.getSingletonTarget(service);
            if (singletonTarget instanceof CachedOpenApiDocumentService cached) {
                return cached;
            }
            if (service instanceof CachedOpenApiDocumentService cached) {
                return cached;
            }
        }
        return null;
    }
}
