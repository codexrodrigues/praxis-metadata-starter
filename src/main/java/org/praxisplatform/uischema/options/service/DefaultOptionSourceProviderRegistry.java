package org.praxisplatform.uischema.options.service;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default provider registry backed by Spring-discovered providers.
 */
public class DefaultOptionSourceProviderRegistry implements OptionSourceProviderRegistry {

    private final List<OptionSourceProvider> providers;
    private final Map<OptionSourceProvider, Integer> providerOrders;

    public DefaultOptionSourceProviderRegistry(List<OptionSourceProvider> providers) {
        this(providers, Map.of());
    }

    public DefaultOptionSourceProviderRegistry(
            List<OptionSourceProvider> providers,
            Map<OptionSourceProvider, Integer> providerOrders
    ) {
        this.providerOrders = new IdentityHashMap<>();
        if (providerOrders != null) {
            this.providerOrders.putAll(providerOrders);
        }
        this.providers = providers == null
                ? List.of()
                : providers.stream()
                .sorted((left, right) -> {
                    int leftOrder = orderOf(left);
                    int rightOrder = orderOf(right);
                    if (leftOrder != rightOrder) {
                        return Integer.compare(leftOrder, rightOrder);
                    }
                    return AnnotationAwareOrderComparator.INSTANCE.compare(left, right);
                })
                .toList();
    }

    @Override
    public OptionSourceProvider resolve(
            OptionSourceDescriptor descriptor,
            OptionSourceExecutionContext context,
            OptionSourceOperation operation
    ) {
        OptionSourceProvider resolved = null;
        int resolvedOrder = Ordered.LOWEST_PRECEDENCE;
        for (OptionSourceProvider provider : providers) {
            if (!provider.supports(descriptor, context, operation)) {
                continue;
            }
            int providerOrder = orderOf(provider);
            if (resolved == null) {
                resolved = provider;
                resolvedOrder = providerOrder;
                continue;
            }
            if (providerOrder == resolvedOrder) {
                throw new AmbiguousOptionSourceProviderException(descriptor, operation, resolved, provider);
            }
            return resolved;
        }
        if (resolved != null) {
            return resolved;
        }
        throw new OptionSourceProviderNotFoundException(descriptor, operation);
    }

    private int orderOf(OptionSourceProvider provider) {
        Integer explicitOrder = providerOrders.get(provider);
        if (explicitOrder != null) {
            return explicitOrder;
        }
        if (provider instanceof Ordered ordered) {
            return ordered.getOrder();
        }
        Order order = provider.getClass().getAnnotation(Order.class);
        return order == null ? Ordered.LOWEST_PRECEDENCE : order.value();
    }
}
