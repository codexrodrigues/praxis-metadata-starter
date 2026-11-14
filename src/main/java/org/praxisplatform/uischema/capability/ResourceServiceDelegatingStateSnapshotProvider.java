package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.support.DefaultConversionService;

import java.util.Optional;

/** Default service-backed snapshot lookup. Hosts may replace the provider as a whole. */
@Order(Ordered.LOWEST_PRECEDENCE)
public class ResourceServiceDelegatingStateSnapshotProvider implements ResourceStateSnapshotProvider {
    private final ObjectProvider<BaseResourceQueryService<?, ?, ?>> services;

    public ResourceServiceDelegatingStateSnapshotProvider(ObjectProvider<BaseResourceQueryService<?, ?, ?>> services) {
        this.services = services;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Optional<ResourceStateSnapshot> resolve(String resourceKey, Object resourceId) {
        if (resourceKey == null || resourceId == null) return Optional.empty();
        var candidates = services.orderedStream().filter(service -> resourceKey.equals(service.getResourceKey())).toList();
        if (candidates.isEmpty()) return Optional.empty();
        if (candidates.size() > 1) throw new IllegalStateException("Ambiguous resource state services: " + resourceKey);
        BaseResourceQueryService service = candidates.getFirst();
        Class<?> idType = ResolvableType.forClass(org.springframework.aop.support.AopUtils.getTargetClass(service))
                .as(BaseResourceQueryService.class).getGeneric(1).resolve();
        if (idType == null) throw new IllegalStateException("Resource state service must declare its identity type");
        Object id;
        try {
            id = idType.isInstance(resourceId) ? resourceId : CompositeIdSupport.isCompositeIdType(idType)
                    ? CompositeIdSupport.decode(resourceId.toString(), idType)
                    : DefaultConversionService.getSharedInstance().convert(resourceId, idType);
        } catch (org.springframework.core.convert.ConversionException | IllegalArgumentException invalidIdentity) {
            return Optional.empty();
        }
        Optional<Optional<ResourceStateSnapshot>> seeded =
                ResourceStateSnapshotScope.findReusableResourceSnapshot(resourceKey, id);
        if (seeded.isPresent()) {
            return seeded.get();
        }
        return service.findResourceStateSnapshot(id);
    }
}
