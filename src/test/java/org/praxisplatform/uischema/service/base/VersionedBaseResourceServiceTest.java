package org.praxisplatform.uischema.service.base;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Java inheritance/dispatch proof only: no Spring context or database is started. */
class VersionedBaseResourceServiceTest {

    @Test
    void inheritedUpdateRejectsMissingPreconditionThroughConcreteBaseAndInterfaceReferences() {
        BaseCrudRepository<Entity, Long> repository = mockRepository();
        ResourceMapper<Entity, Response, Write, Write, Long> mapper = mockMapper();
        VersionedService service = new VersionedService(repository, mapper);
        EntityManager entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        Entity existing = new Entity(7L, "Before", 4L);
        Write update = new Write("After");
        // A real load/save path exists so the RED result demonstrates the inherited write.
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);
        when(entityManager.contains(existing)).thenReturn(true);
        when(mapper.toResponse(existing)).thenReturn(new Response(7L, "After"));
        doAnswer(invocation -> {
            existing.name = update.name();
            return null;
        }).when(mapper).applyUpdate(existing, update);

        assertRequired(() -> service.update(7L, update));
        AbstractBaseResourceService<Entity, Response, Long, Filter, Write, Write> base = service;
        assertRequired(() -> base.update(7L, update));
        BaseCreateUpdateResourceService<Response, Long, Filter, Write, Write> ordinary = service;
        assertRequired(() -> ordinary.update(7L, update));
        VersionedCreateUpdateResourceService<Response, Long, Filter, Write, Write> versioned = service;
        assertRequired(() -> versioned.update(7L, update));

        assertEquals("Before", existing.name);
        assertEquals(4L, existing.version);
        assertEquals(List.of(), service.hooks);
        verifyNoInteractions(repository, mapper, entityManager);
    }

    @Test
    void explicitVersionedUpdateRemainsExecutableWithMatchingPrecondition() {
        BaseCrudRepository<Entity, Long> repository = mockRepository();
        ResourceMapper<Entity, Response, Write, Write, Long> mapper = mockMapper();
        VersionedService service = new VersionedService(repository, mapper);
        Entity existing = new Entity(7L, "Before", 4L);
        Write update = new Write("After");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        doAnswer(invocation -> {
            existing.name = update.name();
            return null;
        }).when(mapper).applyUpdate(existing, update);
        when(repository.save(existing)).thenAnswer(invocation -> {
            existing.version++;
            return existing;
        });
        when(mapper.toResponse(existing)).thenAnswer(invocation -> new Response(existing.id, existing.name));
        ResourceVersionEtagService etags = new ResourceVersionEtagService("unit-test-secret");
        ResourceVersionUpdatePrecondition<Long> precondition = new ResourceVersionUpdatePrecondition<>(
                etags, etags.create("test.versioned", 7L, 4L), "test.versioned", 7L);

        ResourceRepresentationResult<Response> result = service.update(7L, update, precondition);

        assertEquals(new Response(7L, "After"), result.body());
        assertEquals(5L, result.requirePersistedVersion());
        assertEquals(List.of("beforeUpdate", "afterUpdate"), service.hooks);
        verify(mapper).applyUpdate(existing, update);
        verify(repository).save(existing);
    }

    private static void assertRequired(Runnable update) {
        ResourceVersionPreconditionException exception = assertThrows(
                ResourceVersionPreconditionException.class, update::run);
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, exception.status());
        assertEquals("RESOURCE_VERSION_REQUIRED", exception.code());
    }

    @SuppressWarnings("unchecked")
    private static BaseCrudRepository<Entity, Long> mockRepository() {
        return mock(BaseCrudRepository.class);
    }

    @SuppressWarnings("unchecked")
    private static ResourceMapper<Entity, Response, Write, Write, Long> mockMapper() {
        return mock(ResourceMapper.class);
    }

    private static class VersionedService extends AbstractBaseResourceService<
            Entity, Response, Long, Filter, Write, Write>
            implements VersionedCreateUpdateResourceService<Response, Long, Filter, Write, Write> {

        private final ResourceMapper<Entity, Response, Write, Write, Long> mapper;
        private final List<String> hooks = new ArrayList<>();

        private VersionedService(BaseCrudRepository<Entity, Long> repository,
                                 ResourceMapper<Entity, Response, Write, Write, Long> mapper) {
            super(repository, Entity.class);
            this.mapper = mapper;
        }

        @Override
        protected ResourceMapper<Entity, Response, Write, Write, Long> getResourceMapper() {
            return mapper;
        }

        @Override
        public ResourceRepresentationResult<Response> update(
                Long id, Write dto, ResourceVersionUpdatePrecondition<Long> precondition) {
            Entity existing = findEntityById(id);
            precondition.requireMatch(existing.version);
            beforeUpdate(id, existing, dto);
            mapper.applyUpdate(existing, dto);
            Entity saved = getRepository().save(existing);
            afterUpdate(id, saved, dto);
            return toResourceRepresentation(saved);
        }

        @Override
        protected OptionalLong getEntityResourceVersion(Entity entity) {
            return OptionalLong.of(entity.version);
        }

        @Override
        protected void beforeUpdate(Long id, Entity entity, Write dto) {
            hooks.add("beforeUpdate");
        }

        @Override
        protected void afterUpdate(Long id, Entity entity, Write dto) {
            hooks.add("afterUpdate");
        }
    }

    private static class Entity {
        @Id
        private final Long id;
        private String name;
        private long version;

        private Entity(Long id, String name, long version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }
    }

    private record Response(Long id, String name) { }
    private record Write(String name) { }
    private static class Filter implements GenericFilterDTO { }
}
