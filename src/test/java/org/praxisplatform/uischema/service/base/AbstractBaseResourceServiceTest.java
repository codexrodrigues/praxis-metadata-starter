package org.praxisplatform.uischema.service.base;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.OptionLabel;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractBaseResourceServiceTest {

    @Test
    void findByIdMapsResponseDto() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestService service = new TestService(repository);

        when(repository.findById(7L)).thenReturn(Optional.of(entity(7L, "Alice")));

        TestResponseDTO response = service.findById(7L);

        assertEquals(7L, response.id());
        assertEquals("Alice", response.name());
    }

    @Test
    void findAllMapsResponsesAndUsesDefaultSort() {
        BaseCrudRepository<SortableEntity, Long> repository = mockRepository();
        SortableService service = new SortableService(repository);

        when(repository.findAll(any(Sort.class))).thenReturn(List.of(sortableEntity(1L, "Alpha")));

        List<TestResponseDTO> response = service.findAll();

        assertEquals(List.of(new TestResponseDTO(1L, "Alpha")), response);
        verify(repository).findAll(service.getDefaultSort());
    }

    @Test
    void findAllByIdPreservesRequestedOrder() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestService service = new TestService(repository);

        when(repository.findAllById(List.of(2L, 1L, 3L)))
                .thenReturn(List.of(entity(3L, "Third"), entity(1L, "First"), entity(2L, "Second")));

        List<TestResponseDTO> response = service.findAllById(List.of(2L, 1L, 3L));

        assertEquals(List.of(2L, 1L, 3L), response.stream().map(TestResponseDTO::id).toList());
        assertEquals(List.of("Second", "First", "Third"), response.stream().map(TestResponseDTO::name).toList());
    }

    @Test
    void filterMapsContentAndPreservesIncludeIdsOnFirstPage() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestService service = new TestService(repository);

        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(entity(2L, "Second"), entity(3L, "Third")),
                        PageRequest.of(0, 2),
                        2
                ));
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(entity(1L, "First")));

        Page<TestResponseDTO> page = service.filter(new TestFilterDTO(), PageRequest.of(0, 2), List.of(1L, 3L));

        assertEquals(List.of(1L, 3L, 2L), page.getContent().stream().map(TestResponseDTO::id).toList());
        assertEquals(List.of("First", "Third", "Second"), page.getContent().stream().map(TestResponseDTO::name).toList());
        assertEquals(2, page.getTotalElements());
    }

    @Test
    void createReturnsSavedProjectionWithoutEntityManager() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestService service = new TestService(repository);

        when(repository.save(any(TestEntity.class))).thenAnswer(invocation -> {
            TestEntity entity = invocation.getArgument(0);
            entity.setId(15L);
            return entity;
        });

        BaseResourceCommandService.SavedResult<Long, TestResponseDTO> result =
                service.create(new TestCreateDTO("Created"));

        assertEquals(15L, result.id());
        assertEquals("Created", result.body().name());
    }

    @Test
    void createRefreshesManagedEntityWhenEntityManagerIsAvailable() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestService service = new TestService(repository);
        EntityManager entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        when(repository.save(any(TestEntity.class))).thenAnswer(invocation -> {
            TestEntity entity = invocation.getArgument(0);
            entity.setId(22L);
            return entity;
        });
        when(entityManager.contains(any(TestEntity.class))).thenReturn(true);

        service.create(new TestCreateDTO("Managed"));

        verify(entityManager).flush();
        verify(entityManager).refresh(any(TestEntity.class));
    }

    @Test
    void updateLoadsEntityAppliesMapperAndReturnsProjection() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestService service = new TestService(repository);

        TestEntity existing = entity(9L, "Before");
        when(repository.findById(9L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        TestResponseDTO response = service.update(9L, new TestUpdateDTO("After"));

        assertEquals(9L, response.id());
        assertEquals("After", response.name());
        assertEquals("After", existing.getName());
    }

    @Test
    void filterOptionsUsesResourceOptionProjection() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestService service = new TestService(repository);

        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(entity(1L, "A"), entity(2L, "B")),
                        PageRequest.of(0, 2),
                        2
                ));

        Page<OptionDTO<Long>> page = service.filterOptions(new TestFilterDTO(), PageRequest.of(0, 2));

        assertEquals(List.of(1L, 2L), page.getContent().stream().map(OptionDTO::id).toList());
        assertEquals(List.of("A", "B"), page.getContent().stream().map(OptionDTO::label).toList());
    }

    @Test
    void optionMapperUsesAnnotatedGetterBeforeFallbacks() {
        BaseCrudRepository<GetterLabeledEntity, Long> repository = mockRepository();
        GetterLabeledService service = new GetterLabeledService(repository);

        OptionDTO<Long> option = service.toOption(getterLabeledEntity(30L, "Getter Label", "Ignored fallback"));

        assertEquals(30L, option.id());
        assertEquals("Getter Label", option.label());
    }

    @Test
    void readOnlyServiceIsQueryOnlyAndDoesNotImplementCommandBoundary() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestReadOnlyService service = new TestReadOnlyService(repository);

        assertFalse(BaseResourceCommandService.class.isAssignableFrom(service.getClass()));
    }

    @Test
    void getIdFieldNameFindsInheritedJpaId() {
        BaseCrudRepository<InheritedIdEntity, Long> repository = mockRepository();
        InheritedIdService service = new InheritedIdService(repository);

        assertEquals("id", service.getIdFieldName());
    }

    @Test
    void getDefaultSortCombinesAnnotatedFieldsByPriority() {
        BaseCrudRepository<SortableEntity, Long> repository = mockRepository();
        SortableService service = new SortableService(repository);

        assertEquals(
                List.of(
                        Sort.Order.desc("department"),
                        Sort.Order.asc("name")
                ),
                service.getDefaultSort().toList()
        );
    }

    @Test
    void resolveOptionSourceFallsBackToSharedRegistryBeanWhenServiceDoesNotOverrideRegistry() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestReadOnlyService service = new TestReadOnlyService(repository);
        OptionSourceRegistry sharedRegistry = OptionSourceRegistry.builder()
                .add(TestEntity.class, new OptionSourceDescriptor(
                        "departmentLookup",
                        OptionSourceType.RESOURCE_ENTITY,
                        "/test-entities",
                        null,
                        null,
                        null,
                        null,
                        List.of("departmentId"),
                        OptionSourcePolicy.defaults()
                ))
                .build();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("optionSourceRegistry", sharedRegistry);
        ReflectionTestUtils.setField(service, "optionSourceRegistryProvider", beanFactory.getBeanProvider(OptionSourceRegistry.class));

        assertEquals("departmentLookup", service.resolveOptionSource("departmentLookup").key());
    }

    @SuppressWarnings("unchecked")
    private <E> BaseCrudRepository<E, Long> mockRepository() {
        return mock(BaseCrudRepository.class);
    }

    private static TestEntity entity(Long id, String name) {
        TestEntity entity = new TestEntity();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }

    private static GetterLabeledEntity getterLabeledEntity(Long id, String label, String name) {
        GetterLabeledEntity entity = new GetterLabeledEntity();
        entity.setId(id);
        entity.setLabel(label);
        entity.setName(name);
        return entity;
    }

    private static SortableEntity sortableEntity(Long id, String name) {
        SortableEntity entity = new SortableEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDepartment("Engineering");
        return entity;
    }

    private static final class TestService extends AbstractBaseResourceService<
            TestEntity,
            TestResponseDTO,
            Long,
            TestFilterDTO,
            TestCreateDTO,
            TestUpdateDTO
            > {

        private static final ResourceMapper<TestEntity, TestResponseDTO, TestCreateDTO, TestUpdateDTO, Long> RESOURCE_MAPPER =
                new ResourceMapper<>() {
                    @Override
                    public TestResponseDTO toResponse(TestEntity entity) {
                        return new TestResponseDTO(entity.getId(), entity.getName());
                    }

                    @Override
                    public TestEntity newEntity(TestCreateDTO dto) {
                        TestEntity entity = new TestEntity();
                        entity.setName(dto.name());
                        return entity;
                    }

                    @Override
                    public void applyUpdate(TestEntity entity, TestUpdateDTO dto) {
                        entity.setName(dto.name());
                    }

                    @Override
                    public Long extractId(TestEntity entity) {
                        return entity.getId();
                    }
                };

        private TestService(BaseCrudRepository<TestEntity, Long> repository) {
            super(repository, new GenericSpecificationsBuilder<>(), TestEntity.class);
        }

        @Override
        protected ResourceMapper<TestEntity, TestResponseDTO, TestCreateDTO, TestUpdateDTO, Long> getResourceMapper() {
            return RESOURCE_MAPPER;
        }
    }

    private static final class TestReadOnlyService extends AbstractReadOnlyResourceService<
            TestEntity,
            TestResponseDTO,
            Long,
            TestFilterDTO
            > {

        private static final ResourceMapper<TestEntity, TestResponseDTO, Void, Void, Long> RESOURCE_MAPPER =
                new ResourceMapper<>() {
                    @Override
                    public TestResponseDTO toResponse(TestEntity entity) {
                        return new TestResponseDTO(entity.getId(), entity.getName());
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
                        return entity.getId();
                    }
                };

        private TestReadOnlyService(BaseCrudRepository<TestEntity, Long> repository) {
            super(repository, new GenericSpecificationsBuilder<>(), TestEntity.class);
        }

        @Override
        protected ResourceMapper<TestEntity, TestResponseDTO, Void, Void, Long> getResourceMapper() {
            return RESOURCE_MAPPER;
        }
    }

    private static final class GetterLabeledService extends AbstractReadOnlyResourceService<
            GetterLabeledEntity,
            TestResponseDTO,
            Long,
            TestFilterDTO
            > {

        private static final ResourceMapper<GetterLabeledEntity, TestResponseDTO, Void, Void, Long> RESOURCE_MAPPER =
                new ResourceMapper<>() {
                    @Override
                    public TestResponseDTO toResponse(GetterLabeledEntity entity) {
                        return new TestResponseDTO(entity.getId(), entity.getName());
                    }

                    @Override
                    public GetterLabeledEntity newEntity(Void dto) {
                        return new GetterLabeledEntity();
                    }

                    @Override
                    public void applyUpdate(GetterLabeledEntity entity, Void dto) {
                    }

                    @Override
                    public Long extractId(GetterLabeledEntity entity) {
                        return entity.getId();
                    }
                };

        private GetterLabeledService(BaseCrudRepository<GetterLabeledEntity, Long> repository) {
            super(repository, new GenericSpecificationsBuilder<>(), GetterLabeledEntity.class);
        }

        @Override
        protected ResourceMapper<GetterLabeledEntity, TestResponseDTO, Void, Void, Long> getResourceMapper() {
            return RESOURCE_MAPPER;
        }

        private OptionDTO<Long> toOption(GetterLabeledEntity entity) {
            return getOptionMapper().toOption(entity);
        }
    }

    private static final class InheritedIdService extends AbstractReadOnlyResourceService<
            InheritedIdEntity,
            TestResponseDTO,
            Long,
            TestFilterDTO
            > {

        private static final ResourceMapper<InheritedIdEntity, TestResponseDTO, Void, Void, Long> RESOURCE_MAPPER =
                new ResourceMapper<>() {
                    @Override
                    public TestResponseDTO toResponse(InheritedIdEntity entity) {
                        return new TestResponseDTO(entity.getId(), entity.getName());
                    }

                    @Override
                    public InheritedIdEntity newEntity(Void dto) {
                        return new InheritedIdEntity();
                    }

                    @Override
                    public void applyUpdate(InheritedIdEntity entity, Void dto) {
                    }

                    @Override
                    public Long extractId(InheritedIdEntity entity) {
                        return entity.getId();
                    }
                };

        private InheritedIdService(BaseCrudRepository<InheritedIdEntity, Long> repository) {
            super(repository, new GenericSpecificationsBuilder<>(), InheritedIdEntity.class);
        }

        @Override
        protected ResourceMapper<InheritedIdEntity, TestResponseDTO, Void, Void, Long> getResourceMapper() {
            return RESOURCE_MAPPER;
        }
    }

    private static final class SortableService extends AbstractReadOnlyResourceService<
            SortableEntity,
            TestResponseDTO,
            Long,
            TestFilterDTO
            > {

        private static final ResourceMapper<SortableEntity, TestResponseDTO, Void, Void, Long> RESOURCE_MAPPER =
                new ResourceMapper<>() {
                    @Override
                    public TestResponseDTO toResponse(SortableEntity entity) {
                        return new TestResponseDTO(entity.getId(), entity.getName());
                    }

                    @Override
                    public SortableEntity newEntity(Void dto) {
                        return new SortableEntity();
                    }

                    @Override
                    public void applyUpdate(SortableEntity entity, Void dto) {
                    }

                    @Override
                    public Long extractId(SortableEntity entity) {
                        return entity.getId();
                    }
                };

        private SortableService(BaseCrudRepository<SortableEntity, Long> repository) {
            super(repository, new GenericSpecificationsBuilder<>(), SortableEntity.class);
        }

        @Override
        protected ResourceMapper<SortableEntity, TestResponseDTO, Void, Void, Long> getResourceMapper() {
            return RESOURCE_MAPPER;
        }
    }

    private static class BaseIdEntity {
        @Id
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }

    private static final class TestEntity extends BaseIdEntity {
        @OptionLabel
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static final class GetterLabeledEntity extends BaseIdEntity {
        private String label;
        private String name;

        @OptionLabel
        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static final class InheritedIdEntity extends BaseIdEntity {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static final class SortableEntity extends BaseIdEntity {
        @DefaultSortColumn(priority = 2)
        private String name;

        @DefaultSortColumn(priority = 1, ascending = false)
        private String department;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDepartment() {
            return department;
        }

        public void setDepartment(String department) {
            this.department = department;
        }
    }

    private record TestResponseDTO(Long id, String name) {
    }

    private record TestCreateDTO(String name) {
    }

    private record TestUpdateDTO(String name) {
    }

    private static final class TestFilterDTO implements GenericFilterDTO {
    }
}
