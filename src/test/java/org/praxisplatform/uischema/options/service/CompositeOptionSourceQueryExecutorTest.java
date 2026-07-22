package org.praxisplatform.uischema.options.service;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupCapabilities;
import org.praxisplatform.uischema.options.LookupFilteringDescriptor;
import org.praxisplatform.uischema.options.LookupSearchStrategyDefinition;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceExecutionMode;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeOptionSourceQueryExecutorTest {

    @Test
    void delegatesFilterToProviderResolvedForFilterOperation() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        CapturingProvider provider = new CapturingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        Page<OptionDTO<Object>> result = executor.filterOptions(
                null,
                TestEntity.class,
                null,
                Map.of("companyId", 10),
                descriptor,
                "ops",
                List.of(),
                null,
                PageRequest.of(0, 10),
                List.of(1L)
        );

        assertEquals(OptionSourceOperation.FILTER, provider.lastContext.operation());
        assertEquals("departments", provider.lastContext.sourceKey());
        assertEquals("ops", provider.lastRequest.search());
        assertEquals(Map.of("companyId", 10), provider.lastRequest.filterPayload());
        assertEquals(1, provider.lastRequest.includeIds().size());
        assertEquals("filtered", result.getContent().getFirst().label());
    }

    @Test
    void resolvesExplicitDocumentStrategyAndNormalizesItBeforeProviderResolution() {
        OptionSourceDescriptor descriptor = descriptorWithSearchStrategies("employees");
        CapturingProvider provider = new CapturingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        executor.filterOptions(
                null, TestEntity.class, null, null, descriptor, "123.456.789-00", "document",
                List.of(), null, PageRequest.of(0, 10), List.of()
        );

        assertEquals("document", provider.lastRequest.searchStrategy());
        assertEquals("12345678900", provider.lastRequest.search());
    }

    @Test
    void rejectsAmbiguousOrInvalidDocumentSearchBeforeProviderResolution() {
        OptionSourceDescriptor descriptor = descriptorWithSearchStrategies("employees");
        CountingProvider provider = new CountingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        assertThrows(IllegalArgumentException.class, () -> executor.filterOptions(
                null, TestEntity.class, null, null, descriptor, "Maria", null,
                List.of(), null, PageRequest.of(0, 10), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> executor.filterOptions(
                null, TestEntity.class, null, null, descriptor, "123.456.789-0", "document",
                List.of(), null, PageRequest.of(0, 10), List.of()
        ));
        assertEquals(0, provider.supportCalls);
    }

    @Test
    void delegatesByIdsToProviderResolvedForByIdsOperation() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        CapturingProvider provider = new CapturingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        List<OptionDTO<Object>> result = executor.byIdsOptions(null, TestEntity.class, descriptor, List.of(10L, 11L));

        assertEquals(OptionSourceOperation.BY_IDS, provider.lastContext.operation());
        assertEquals("departments", provider.lastContext.sourceKey());
        assertEquals(List.of(10L, 11L), provider.lastRequest.ids());
        assertEquals("byIds", result.getFirst().label());
    }

    @Test
    void delegatesContextualByIdsFilterPayloadToProvider() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        CapturingProvider provider = new CapturingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        List<OptionDTO<Object>> result = executor.byIdsOptions(
                null,
                TestEntity.class,
                null,
                Map.of("departmentId", 7L),
                descriptor,
                List.of(10L)
        );

        assertEquals(OptionSourceOperation.BY_IDS, provider.lastContext.operation());
        assertEquals(Map.of("departmentId", 7L), provider.lastRequest.filterPayload());
        assertEquals(List.of(10L), provider.lastRequest.ids());
        assertEquals("byIds", result.getFirst().label());
    }

    @Test
    void normalizesByIdsProviderResponseWithoutNullItemsAndPreservesRequestedOrder() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        FixedByIdsProvider provider = new FixedByIdsProvider(Arrays.asList(
                null,
                new OptionDTO<>(11L, "People", Map.of("rank", 2)),
                new OptionDTO<>(null, "Broken", null),
                new OptionDTO<>(10L, "Operations", Map.of("rank", 1))
        ));
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        List<OptionDTO<Object>> result = executor.byIdsOptions(
                null,
                TestEntity.class,
                descriptor,
                List.of(10L, 11L, 12L)
        );

        assertEquals(List.of(10L, 11L), result.stream().map(OptionDTO::id).toList());
        assertEquals(List.of("Operations", "People"), result.stream().map(OptionDTO::label).toList());
    }

    @Test
    void usesContextResolverBeforeProviderResolution() {
        OptionSourceDescriptor descriptor = descriptor("departments");
        CapturingProvider provider = new CapturingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider)),
                new OptionSourceRequestValidator(),
                (sourceDescriptor, operation) -> new OptionSourceExecutionContext(
                        sourceDescriptor.key(),
                        sourceDescriptor.type(),
                        sourceDescriptor.resourcePath(),
                        operation,
                        Map.of("tenant", "acme")
                )
        );

        executor.byIdsOptions(null, TestEntity.class, descriptor, List.of(10L));

        assertEquals("acme", provider.lastContext.attributes().get("tenant"));
        assertEquals("acme", provider.lastRequest.context().attributes().get("tenant"));
    }

    @Test
    void validatesStructuredFiltersBeforeResolvingProvider() {
        OptionSourceDescriptor descriptor = descriptor("external");
        CountingProvider provider = new CountingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        assertThrows(IllegalArgumentException.class, () -> executor.filterOptions(
                null,
                TestEntity.class,
                null,
                null,
                descriptor,
                null,
                List.of(new org.praxisplatform.uischema.options.LookupFilterRequest("status", "equals", "ACTIVE")),
                null,
                PageRequest.of(0, 10),
                List.of()
        ));
        assertEquals(0, provider.supportCalls);
    }

    @Test
    void validatesByIdsCapabilityBeforeResolvingProvider() {
        OptionSourceDescriptor descriptor = descriptorWithCapabilities(
                "filterOnly",
                new LookupCapabilities(true, false, false, false, false, false, false, false, false, false)
        );
        CountingProvider provider = new CountingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        assertThrows(OptionSourceCapabilityNotSupportedException.class,
                () -> executor.byIdsOptions(null, TestEntity.class, descriptor, List.of(1L)));
        assertEquals(0, provider.supportCalls);
    }

    @Test
    void validatesIncludeIdsPolicyBeforeResolvingProvider() {
        OptionSourceDescriptor descriptor = descriptorWithPolicy(
                "noIncludeIds",
                new OptionSourcePolicy(false, true, "contains", 0, 25, 100, false, false, "label")
        );
        CountingProvider provider = new CountingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        assertThrows(IllegalArgumentException.class, () -> executor.filterOptions(
                null,
                TestEntity.class,
                null,
                null,
                descriptor,
                null,
                List.of(),
                null,
                PageRequest.of(0, 10),
                List.of(1L)
        ));
        assertEquals(0, provider.supportCalls);
    }

    @Test
    void validatesMinimumSearchCharsBeforeResolvingProvider() {
        OptionSourceDescriptor descriptor = descriptorWithPolicy(
                "minimumSearch",
                new OptionSourcePolicy(false, true, "contains", 3, 25, 100, true, false, "label")
        );
        CountingProvider provider = new CountingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        assertThrows(IllegalArgumentException.class, () -> executor.filterOptions(
                null,
                TestEntity.class,
                null,
                null,
                descriptor,
                "ab",
                List.of(),
                null,
                PageRequest.of(0, 10),
                List.of()
        ));
        assertEquals(0, provider.supportCalls);
    }

    @Test
    void validatesMaxPageSizeBeforeResolvingProvider() {
        OptionSourceDescriptor descriptor = descriptorWithPolicy(
                "smallPages",
                new OptionSourcePolicy(false, true, "contains", 0, 10, 25, true, false, "label")
        );
        CountingProvider provider = new CountingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        assertThrows(IllegalArgumentException.class, () -> executor.filterOptions(
                null,
                TestEntity.class,
                null,
                null,
                descriptor,
                null,
                List.of(),
                null,
                PageRequest.of(0, 26),
                List.of()
        ));
        assertEquals(0, provider.supportCalls);
    }

    @Test
    void validatesDescriptorDependencyMapBeforeResolvingProvider() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "invalidDependencyMap",
                OptionSourceType.LIGHT_LOOKUP,
                "/employees",
                "departmentId",
                null,
                "department.nome",
                "department.id",
                List.of("departmentId"),
                Map.of("tenantId", "tenant.id"),
                OptionSourcePolicy.defaults()
        );
        CountingProvider provider = new CountingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        assertThrows(IllegalArgumentException.class, () -> executor.filterOptions(
                null,
                TestEntity.class,
                null,
                null,
                descriptor,
                null,
                List.of(),
                null,
                PageRequest.of(0, 10),
                List.of()
        ));
        assertEquals(0, provider.supportCalls);
    }

    @Test
    void validatesEntityLookupDependencyMapBeforeResolvingProvider() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "invalidEntityDependencyMap",
                OptionSourceType.RESOURCE_ENTITY,
                "/employees",
                "departmentId",
                null,
                "label",
                "id",
                List.of("departmentId"),
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        "external",
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        List.of("label"),
                        Map.of("tenantId", "tenant.id"),
                        null,
                        LookupCapabilities.defaults(),
                        null
                )
        );
        CountingProvider provider = new CountingProvider();
        CompositeOptionSourceQueryExecutor executor = new CompositeOptionSourceQueryExecutor(
                new DefaultOptionSourceProviderRegistry(List.of(provider))
        );

        assertThrows(IllegalArgumentException.class, () -> executor.filterOptions(
                null,
                TestEntity.class,
                null,
                null,
                descriptor,
                null,
                List.of(),
                null,
                PageRequest.of(0, 10),
                List.of()
        ));
        assertEquals(0, provider.supportCalls);
    }

    private static OptionSourceDescriptor descriptor(String key) {
        return descriptorWithPolicy(key, OptionSourcePolicy.defaults());
    }

    private static OptionSourceDescriptor descriptorWithPolicy(String key, OptionSourcePolicy policy) {
        return new OptionSourceDescriptor(
                key,
                OptionSourceType.LIGHT_LOOKUP,
                "/employees",
                key,
                "department.id",
                "department.nome",
                "department.id",
                List.of(),
                policy
        );
    }

    private static OptionSourceDescriptor descriptorWithSearchStrategies(String key) {
        return new OptionSourceDescriptor(
                key,
                OptionSourceType.LIGHT_LOOKUP,
                "/employees",
                key,
                "employee.id",
                "employee.name",
                "employee.id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                null,
                new LookupFilteringDescriptor(
                        List.of(), Map.of(), List.of(), null, List.of(), null,
                        List.of(
                                new LookupSearchStrategyDefinition("employee-code", "business-code", 1),
                                new LookupSearchStrategyDefinition("document", "normalized-document", 11),
                                new LookupSearchStrategyDefinition("name", "descriptive-text", 3)
                        )),
                OptionSourceExecutionMode.PROVIDER_REQUIRED
        );
    }

    private static OptionSourceDescriptor descriptorWithCapabilities(
            String key,
            LookupCapabilities capabilities
    ) {
        return new OptionSourceDescriptor(
                key,
                OptionSourceType.RESOURCE_ENTITY,
                "/employees",
                key,
                null,
                "label",
                "id",
                List.of(),
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        "external",
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        List.of("label"),
                        java.util.Map.of(),
                        null,
                        capabilities,
                        null
                )
        );
    }

    private static final class CapturingProvider implements OptionSourceProvider {
        private OptionSourceExecutionContext lastContext;
        private OptionSourceExecutionRequest<?> lastRequest;

        @Override
        public boolean supports(
                OptionSourceDescriptor descriptor,
                OptionSourceExecutionContext context,
                OptionSourceOperation operation
        ) {
            this.lastContext = context;
            return true;
        }

        @Override
        public Page<OptionDTO<Object>> filter(OptionSourceExecutionRequest<?> request) {
            this.lastRequest = request;
            return new PageImpl<>(List.of(new OptionDTO<>(1L, "filtered", null)));
        }

        @Override
        public List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request) {
            this.lastRequest = request;
            return List.of(new OptionDTO<>(10L, "byIds", null));
        }
    }

    private static final class CountingProvider implements OptionSourceProvider {
        private int supportCalls;

        @Override
        public boolean supports(
                OptionSourceDescriptor descriptor,
                OptionSourceExecutionContext context,
                OptionSourceOperation operation
        ) {
            supportCalls++;
            return true;
        }

        @Override
        public Page<OptionDTO<Object>> filter(OptionSourceExecutionRequest<?> request) {
            throw new AssertionError("Provider should not be called");
        }

        @Override
        public List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request) {
            throw new AssertionError("Provider should not be called");
        }
    }

    private record FixedByIdsProvider(List<OptionDTO<Object>> options) implements OptionSourceProvider {

        @Override
        public boolean supports(
                OptionSourceDescriptor descriptor,
                OptionSourceExecutionContext context,
                OptionSourceOperation operation
        ) {
            return true;
        }

        @Override
        public Page<OptionDTO<Object>> filter(OptionSourceExecutionRequest<?> request) {
            throw new AssertionError("Filter should not be called");
        }

        @Override
        public List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request) {
            return options;
        }
    }

    private static final class TestEntity {
    }
}
