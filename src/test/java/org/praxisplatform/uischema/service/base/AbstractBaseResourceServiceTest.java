package org.praxisplatform.uischema.service.base;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.OptionLabel;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void readOnlyServiceRejectsCommandMethods() {
        BaseCrudRepository<TestEntity, Long> repository = mockRepository();
        TestReadOnlyService service = new TestReadOnlyService(repository);

        assertThrows(UnsupportedOperationException.class, () -> service.create(null));
        assertThrows(UnsupportedOperationException.class, () -> service.update(1L, null));
        assertThrows(UnsupportedOperationException.class, () -> service.deleteById(1L));
        assertThrows(UnsupportedOperationException.class, () -> service.deleteAllById(List.of(1L)));
    }

    @SuppressWarnings("unchecked")
    private BaseCrudRepository<TestEntity, Long> mockRepository() {
        return mock(BaseCrudRepository.class);
    }

    private static TestEntity entity(Long id, String name) {
        TestEntity entity = new TestEntity();
        entity.setId(id);
        entity.setName(name);
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

        private static final ResourceMapper<TestEntity, TestResponseDTO, TestCreateDTO, TestUpdateDTO> RESOURCE_MAPPER =
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
                    public Object extractId(TestEntity entity) {
                        return entity.getId();
                    }
                };

        private TestService(BaseCrudRepository<TestEntity, Long> repository) {
            super(repository, new GenericSpecificationsBuilder<>(), TestEntity.class);
        }

        @Override
        protected ResourceMapper<TestEntity, TestResponseDTO, TestCreateDTO, TestUpdateDTO> getResourceMapper() {
            return RESOURCE_MAPPER;
        }
    }

    private static final class TestReadOnlyService extends AbstractReadOnlyResourceService<
            TestEntity,
            TestResponseDTO,
            Long,
            TestFilterDTO
            > {

        private static final ResourceMapper<TestEntity, TestResponseDTO, Void, Void> RESOURCE_MAPPER =
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
                    public Object extractId(TestEntity entity) {
                        return entity.getId();
                    }
                };

        private TestReadOnlyService(BaseCrudRepository<TestEntity, Long> repository) {
            super(repository, new GenericSpecificationsBuilder<>(), TestEntity.class);
        }

        @Override
        protected ResourceMapper<TestEntity, TestResponseDTO, Void, Void> getResourceMapper() {
            return RESOURCE_MAPPER;
        }
    }

    private static final class TestEntity {
        @Id
        private Long id;

        @OptionLabel
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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
