package org.praxisplatform.uischema.configuration;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractReadOnlyResourceService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OpenApiUiSchemaAutoConfigurationOptionSourceRegistryTest {

    @Test
    void resolveRegistryForAggregationHonorsConcreteOverrideWithoutCallingSharedFallback() {
        OpenApiUiSchemaAutoConfiguration configuration = new OpenApiUiSchemaAutoConfiguration();
        ConcreteOverrideOptionSourceService service = new ConcreteOverrideOptionSourceService();

        OptionSourceRegistry registry = (OptionSourceRegistry) ReflectionTestUtils.invokeMethod(
                configuration,
                "resolveRegistryForAggregation",
                service
        );

        assertEquals("payrollProfile", registry.resolve(TestEntity.class, "payrollProfile").orElseThrow().key());
    }

    @Test
    void resolveRegistryForAggregationIgnoresProxyStyleSubclassWhenThereIsNoRealOverride() {
        OpenApiUiSchemaAutoConfiguration configuration = new OpenApiUiSchemaAutoConfiguration();
        ProxyLikeReadOnlyService service = new ProxyLikeReadOnlyService();

        OptionSourceRegistry registry = (OptionSourceRegistry) ReflectionTestUtils.invokeMethod(
                configuration,
                "resolveRegistryForAggregation",
                service
        );

        assertTrue(registry.isEmpty());
    }

    static class ConcreteOverrideOptionSourceService extends AbstractReadOnlyResourceService<
            TestEntity,
            TestResponseDTO,
            Long,
            TestFilterDTO
            > {

        private static final OptionSourceRegistry CONCRETE_OVERRIDE_REGISTRY = OptionSourceRegistry.builder()
                .add(TestEntity.class, new OptionSourceDescriptor(
                        "payrollProfile",
                        OptionSourceType.DISTINCT_DIMENSION,
                        "/employees",
                        "payrollProfile",
                        "payrollProfile",
                        "payrollProfileLabel",
                        "payrollProfile",
                        List.of("departmentId"),
                        new OptionSourcePolicy(true, true, "contains", 0, 25, 100, true, false, "label")
                ))
                .build();

        private static final ResourceMapper<TestEntity, TestResponseDTO, Void, Void, Long> RESOURCE_MAPPER =
                new ResourceMapper<>() {
                    @Override
                    public TestResponseDTO toResponse(TestEntity entity) {
                        return new TestResponseDTO(entity.id, entity.name);
                    }

                    @Override
                    public TestEntity newEntity(Void dto) {
                        return new TestEntity();
                    }

                    @Override
                    public void applyUpdate(TestEntity entity, Void dto) {
                    }

                    @Override
                    public Long extractId(TestEntity entity) {
                        return entity.id;
                    }
                };

        ConcreteOverrideOptionSourceService() {
            super(mockRepository(), new GenericSpecificationsBuilder<>(), TestEntity.class);
        }

        @Override
        protected ResourceMapper<TestEntity, TestResponseDTO, Void, Void, Long> getResourceMapper() {
            return RESOURCE_MAPPER;
        }

        @Override
        public OptionSourceRegistry getOptionSourceRegistry() {
            return CONCRETE_OVERRIDE_REGISTRY;
        }
    }

    static final class ProxyLikeReadOnlyService extends BaseReadOnlyService {
    }

    static class BaseReadOnlyService extends AbstractReadOnlyResourceService<
            TestEntity,
            TestResponseDTO,
            Long,
            TestFilterDTO
            > {

        private static final ResourceMapper<TestEntity, TestResponseDTO, Void, Void, Long> RESOURCE_MAPPER =
                new ResourceMapper<>() {
                    @Override
                    public TestResponseDTO toResponse(TestEntity entity) {
                        return new TestResponseDTO(entity.id, entity.name);
                    }

                    @Override
                    public TestEntity newEntity(Void dto) {
                        return new TestEntity();
                    }

                    @Override
                    public void applyUpdate(TestEntity entity, Void dto) {
                    }

                    @Override
                    public Long extractId(TestEntity entity) {
                        return entity.id;
                    }
                };

        BaseReadOnlyService() {
            super(mockRepository(), new GenericSpecificationsBuilder<>(), TestEntity.class);
        }

        @Override
        protected ResourceMapper<TestEntity, TestResponseDTO, Void, Void, Long> getResourceMapper() {
            return RESOURCE_MAPPER;
        }
    }

    static final class TestEntity {
        private Long id;
        private String name;
    }

    record TestResponseDTO(Long id, String name) {
    }

    static final class TestFilterDTO implements GenericFilterDTO {
    }

    @SuppressWarnings("unchecked")
    private static BaseCrudRepository<TestEntity, Long> mockRepository() {
        return mock(BaseCrudRepository.class);
    }
}
