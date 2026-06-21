package org.praxisplatform.uischema.options.service;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceExecutionMode;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.options.service.jpa.JpaOptionSourceProvider;
import org.praxisplatform.uischema.options.service.jpa.JpaOptionSourceQueryExecutor;
import org.springframework.core.Ordered;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultOptionSourceProviderRegistryTest {

    @Test
    void resolvesProviderByDescriptorContextAndOperation() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        OptionSourceExecutionContext filterContext = context(descriptor, OptionSourceOperation.FILTER);
        OptionSourceExecutionContext byIdsContext = context(descriptor, OptionSourceOperation.BY_IDS);
        StubProvider filterProvider = new StubProvider(OptionSourceOperation.FILTER);
        StubProvider byIdsProvider = new StubProvider(OptionSourceOperation.BY_IDS);
        DefaultOptionSourceProviderRegistry registry = new DefaultOptionSourceProviderRegistry(List.of(filterProvider, byIdsProvider));

        assertSame(filterProvider, registry.resolve(descriptor, filterContext, OptionSourceOperation.FILTER));
        assertSame(byIdsProvider, registry.resolve(descriptor, byIdsContext, OptionSourceOperation.BY_IDS));
    }

    @Test
    void throwsProviderNotFoundWhenNoProviderSupportsOperation() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        OptionSourceExecutionContext context = context(descriptor, OptionSourceOperation.FILTER);
        DefaultOptionSourceProviderRegistry registry = new DefaultOptionSourceProviderRegistry(List.of());

        assertThrows(
                OptionSourceProviderNotFoundException.class,
                () -> registry.resolve(descriptor, context, OptionSourceOperation.FILTER)
        );
    }

    @Test
    void jpaFallbackDoesNotMaskProviderRequiredLightLookup() {
        OptionSourceDescriptor descriptor = descriptor("externalCatalog")
                .withExecutionMode(OptionSourceExecutionMode.PROVIDER_REQUIRED);
        OptionSourceExecutionContext context = context(descriptor, OptionSourceOperation.FILTER);
        DefaultOptionSourceProviderRegistry registry = new DefaultOptionSourceProviderRegistry(
                List.of(new JpaOptionSourceProvider(new JpaOptionSourceQueryExecutor()))
        );

        assertThrows(
                OptionSourceProviderNotFoundException.class,
                () -> registry.resolve(descriptor, context, OptionSourceOperation.FILTER)
        );
    }

    @Test
    void orderedProviderWinsWhenMultipleProvidersSupportOperation() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        OptionSourceExecutionContext context = context(descriptor, OptionSourceOperation.FILTER);
        OrderedStubProvider hostProvider = new OrderedStubProvider(OptionSourceOperation.FILTER, Ordered.HIGHEST_PRECEDENCE);
        OrderedStubProvider fallbackProvider = new OrderedStubProvider(OptionSourceOperation.FILTER, Ordered.LOWEST_PRECEDENCE);
        DefaultOptionSourceProviderRegistry registry = new DefaultOptionSourceProviderRegistry(List.of(fallbackProvider, hostProvider));

        assertSame(hostProvider, registry.resolve(descriptor, context, OptionSourceOperation.FILTER));
    }

    @Test
    void explicitBeanOrderWinsWhenProviderClassDoesNotImplementOrdered() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        OptionSourceExecutionContext context = context(descriptor, OptionSourceOperation.FILTER);
        StubProvider hostProvider = new StubProvider(OptionSourceOperation.FILTER);
        StubProvider fallbackProvider = new StubProvider(OptionSourceOperation.FILTER);
        Map<OptionSourceProvider, Integer> explicitOrders = new IdentityHashMap<>();
        explicitOrders.put(hostProvider, Ordered.HIGHEST_PRECEDENCE);
        explicitOrders.put(fallbackProvider, Ordered.LOWEST_PRECEDENCE);
        DefaultOptionSourceProviderRegistry registry = new DefaultOptionSourceProviderRegistry(
                List.of(fallbackProvider, hostProvider),
                explicitOrders
        );

        assertSame(hostProvider, registry.resolve(descriptor, context, OptionSourceOperation.FILTER));
    }

    @Test
    void throwsAmbiguousProviderWhenSameOrderSupportsSameOperation() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        OptionSourceExecutionContext context = context(descriptor, OptionSourceOperation.FILTER);
        OrderedStubProvider firstProvider = new OrderedStubProvider(OptionSourceOperation.FILTER, 100);
        OrderedStubProvider secondProvider = new OrderedStubProvider(OptionSourceOperation.FILTER, 100);
        DefaultOptionSourceProviderRegistry registry = new DefaultOptionSourceProviderRegistry(List.of(firstProvider, secondProvider));

        AmbiguousOptionSourceProviderException exception = assertThrows(
                AmbiguousOptionSourceProviderException.class,
                () -> registry.resolve(descriptor, context, OptionSourceOperation.FILTER)
        );
        assertFalse(exception.getMessage().contains("OrderedStubProvider"));
        assertFalse(exception.getMessage().contains("$"));
    }

    private static OptionSourceExecutionContext context(
            OptionSourceDescriptor descriptor,
            OptionSourceOperation operation
    ) {
        return new OptionSourceExecutionContext(
                descriptor.key(),
                descriptor.type(),
                descriptor.resourcePath(),
                operation,
                java.util.Map.of()
        );
    }

    private static OptionSourceDescriptor descriptor(String key) {
        return new OptionSourceDescriptor(
                key,
                OptionSourceType.LIGHT_LOOKUP,
                "/employees",
                key,
                "department.id",
                "department.nome",
                "department.id",
                List.of(),
                OptionSourcePolicy.defaults()
        );
    }

    private record StubProvider(OptionSourceOperation supportedOperation) implements OptionSourceProvider {
        @Override
        public boolean supports(
                OptionSourceDescriptor descriptor,
                OptionSourceExecutionContext context,
                OptionSourceOperation operation
        ) {
            return operation == supportedOperation && context.operation() == supportedOperation;
        }

        @Override
        public Page<OptionDTO<Object>> filter(OptionSourceExecutionRequest<?> request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request) {
            throw new UnsupportedOperationException();
        }
    }

    private record OrderedStubProvider(
            OptionSourceOperation supportedOperation,
            int order
    ) implements OptionSourceProvider, Ordered {
        @Override
        public boolean supports(
                OptionSourceDescriptor descriptor,
                OptionSourceExecutionContext context,
                OptionSourceOperation operation
        ) {
            return operation == supportedOperation && context.operation() == supportedOperation;
        }

        @Override
        public Page<OptionDTO<Object>> filter(OptionSourceExecutionRequest<?> request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
