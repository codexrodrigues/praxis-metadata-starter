package org.praxisplatform.uischema.customfields;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class P2CustomFieldContextTest {
    record Request(@CustomFields Map<String,Object> customFields) { }
    record Filter() implements GenericFilterDTO { }
    static class Entity {
        @jakarta.persistence.Id Long id = 1L;
        @jakarta.persistence.Version Long version = 1L;
        String status = "LOCKED";
        int statusReads;
        Entity related;
        @CustomFields Map<String,Object> customFields = Map.of("amount", 1, "audit", true);
        public String getStatus() { statusReads++; return status; }
    }
    static class Service extends AbstractBaseResourceService<Entity,Request,Long,Filter,Request,Request> {
        final ResourceMapper<Entity,Request,Request,Request,Long> mapper = mock(ResourceMapper.class);
        boolean denied;
        int hooks;
        Service(BaseCrudRepository<Entity,Long> repository) { super(repository, Entity.class); }
        @Override protected ResourceMapper<Entity,Request,Request,Request,Long> getResourceMapper() { return mapper; }
        @Override protected String resolveResourceKey() { return "p2.requests"; }
        @Override protected void beforeUpdate(Long id, Entity entity, Request dto) { hooks++; }
        @Override protected void assertEntityAccessAllowed(Entity entity) { if (denied) throw new SecurityException("Denied"); }
    }
    static CustomFieldDescriptor field(String key, boolean readOnly, Map<String,Object> rules) {
        return new CustomFieldDescriptor(key, key, "Request field", key.equals("audit") ? CustomFieldDataType.BOOLEAN : CustomFieldDataType.INTEGER,
                null, null, 0, readOnly, readOnly, false, false, false, null, null, null, null, null,
                null, null, null, List.of(), List.of(), null, null, Map.of(), rules);
    }
    static CustomFieldDescriptor guarded() {
        return field("amount", false, Map.of("jsonLogic", Map.of("!=", List.of(Map.of("var", "entity.status"), "LOCKED")),
                "message", "Locked records cannot change"));
    }
    static ResourceVersionUpdatePrecondition<Long> precondition(long version) {
        var etags = new ResourceVersionEtagService("p2-test-secret");
        return new ResourceVersionUpdatePrecondition<>(etags, etags.create("p2.requests", 1L, version), "p2.requests", 1L);
    }
    static Service service(Entity entity, AtomicInteger definitionReads, List<CustomFieldDescriptor> definitions) {
        var repository = (BaseCrudRepository<Entity,Long>) mock(BaseCrudRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenAnswer(call -> { entity.version++; return entity; });
        var service = new Service(repository);
        ReflectionTestUtils.setField(service, "customFieldValidator", new CustomFieldValidator((r,t,s) -> {
            assertEquals("p2.requests", r);
            assertEquals("request", s);
            definitionReads.incrementAndGet();
            return definitions;
        }));
        doAnswer(call -> { entity.customFields = ((Request)call.getArgument(1)).customFields(); return null; })
                .when(service.mapper).applyUpdate(any(), any());
        when(service.mapper.toResponse(entity)).thenAnswer(call -> new Request(entity.customFields));
        return service;
    }
    @Test void ordinaryAndVersionedUpdatesCannotBypassTheEntityConstraint() {
        for (boolean versioned : List.of(false, true)) {
            var entity = new Entity(); var reads = new AtomicInteger(); var service = service(entity, reads, List.of(guarded()));
            var dto = new Request(Map.of("amount", 8));
            assertThrows(CustomFieldValidationException.class, () -> {
                if (versioned) service.update(1L, dto, precondition(1)); else service.update(1L, dto);
            });
            assertEquals(0, service.hooks);
            verify(service.mapper, never()).applyUpdate(any(), any());
            verify((BaseCrudRepository<Entity,Long>)ReflectionTestUtils.getField(service, "repository"), never()).save(any());
            assertEquals(1, reads.get());
        }
    }
    @Test void permittedUpdateUsesOneSnapshotAndPreservesServerOwnedValues() {
        for (boolean versioned : List.of(false, true)) {
            var entity = new Entity(); entity.status = "OPEN";
            var reads = new AtomicInteger(); var service = service(entity, reads, List.of(guarded(), field("audit", true, Map.of())));
            var dto = new Request(Map.of("amount", 8));
            if (versioned) service.update(1L, dto, precondition(1)); else service.update(1L, dto);
            assertEquals(Map.of("amount",8,"audit",true), entity.customFields);
            assertEquals(1, reads.get()); assertEquals(1, service.hooks);
        }
    }
    @Test void authorizationAndStaleVersionPrecedeReadingServerFacts() {
        var entity = new Entity(); var reads = new AtomicInteger(); var service = service(entity, reads, List.of(guarded()));
        service.denied = true;
        assertThrows(SecurityException.class, () -> service.update(1L, new Request(Map.of("amount",8)), precondition(1)));
        assertEquals(0, entity.statusReads);
        service.denied = false;
        assertThrows(ResourceVersionPreconditionException.class, () -> service.update(1L, new Request(Map.of("amount",8)), precondition(9)));
        assertEquals(0, entity.statusReads); assertEquals(0, service.hooks);
    }
    @Test void standaloneValidationCannotTreatMissingEntityAsPermission() {
        var validator = new CustomFieldValidator((r,t,s) -> List.of(guarded()));
        assertThrows(IllegalStateException.class, () -> validator.validate(new Request(Map.of("amount",8)), "p2.requests", "a"));
    }
    @Test void temporalOperatorsIncludingNestedDefaultsAreRejectedAtAdmission() {
        for (var expression : List.of(
                Map.of(">", List.of(Map.of("now", List.of()), 0)),
                Map.of("daysSince", List.of("2026-01-01")),
                Map.of("var", List.of("value", Map.of("now", List.of()))))) {
            assertThrows(IllegalArgumentException.class, () -> field("amount", false, Map.of("jsonLogic", expression)));
        }
    }
    @Test void createHasExplicitNullEntityAndStandaloneCallsCannotAssumeCreate() {
        var descriptor = field("amount", false, Map.of("jsonLogic",
                Map.of("==", Arrays.asList(Map.of("var", "entity"), null))));
        var entity = new Entity(); var reads = new AtomicInteger(); var service = service(entity, reads, List.of(descriptor));
        var dto = new Request(Map.of("amount",8));
        when(service.mapper.newEntity(dto)).thenReturn(entity);
        assertDoesNotThrow(() -> service.create(dto));
        assertEquals(1, reads.get()); assertEquals(0, entity.statusReads);
        var validator = new CustomFieldValidator((r,t,s) -> List.of(descriptor));
        assertThrows(IllegalStateException.class, () -> validator.validate(dto,"p2.requests","a"));
    }
    @Test void preparationFreezesInputAndDefinitionsWithoutQueryingTheProviderAgain() {
        var reads = new AtomicInteger(); var values = new HashMap<String,Object>(); values.put("amount",8);
        var definitions = new ArrayList<>(List.of(field("amount",false,
                Map.of("jsonLogic",Map.of("<=",List.of(Map.of("var","value"),10))))));
        var validator = new CustomFieldValidator((r,t,s) -> { reads.incrementAndGet(); return definitions; });
        var prepared = validator.prepare(new Request(values),"p2.requests","a","request");
        values.put("amount",99); definitions.clear();
        assertDoesNotThrow(() -> prepared.validateForUpdate(new Entity()));
        assertEquals(1,reads.get());
        assertThrows(IllegalStateException.class, () -> prepared.validateForUpdate(null));
    }
    @Test void valueOnlyConstraintsDoNotTraverseUnrelatedServerState() {
        var entity = new Entity(); entity.related = entity;
        var reads = new AtomicInteger();
        var service = service(entity, reads, List.of(field("amount",false,
                Map.of("jsonLogic",Map.of("<=",List.of(Map.of("var","value"),10))))));
        assertDoesNotThrow(() -> service.update(1L,new Request(Map.of("amount",8))));
        assertEquals(0,entity.statusReads);
        assertEquals(1,reads.get()); assertEquals(1,service.hooks);
    }
}
