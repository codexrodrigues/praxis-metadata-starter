package org.praxisplatform.uischema.customfields;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class CustomFieldResourceLifecycleTest {
    record Request(@CustomFields Map<String,Object> customFields) {}
    record Filter(@CustomFields Map<String,Object> customFields) implements GenericFilterDTO {}
    static class Entity { @jakarta.persistence.Id Long id; @CustomFields Map<String,Object> customFields; }
    static class Service extends AbstractBaseResourceService<Entity, Request, Long, Filter, Request, Request> {
        final ResourceMapper<Entity, Request, Request, Request, Long> mapper = mock(ResourceMapper.class);
        final BaseCrudRepository<Entity,Long> repository;
        boolean hookCalled;
        int validationCalls;
        java.util.function.Consumer<Entity> updateHook = entity -> {};
        String key = "people";
        Service(BaseCrudRepository<Entity,Long> repository) { super(repository, Entity.class); this.repository = repository; }
        Service(BaseCrudRepository<Entity,Long> repository,
                org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<Entity> builder, String key) {
            super(repository, builder, Entity.class); this.key = key; this.repository = repository;
        }
        @Override protected ResourceMapper<Entity, Request, Request, Request, Long> getResourceMapper() { return mapper; }
        @Override protected String resolveResourceKey() { return key; }
        @Override protected void beforeCreate(Request dto, Entity entity) { hookCalled = true; }
        @Override protected void beforeUpdate(Long id, Entity entity, Request dto) { hookCalled = true; updateHook.accept(entity); }
        @Override protected Set<String> validateCustomFields(Object dto, String schemaType) {
            validationCalls++;
            return super.validateCustomFields(dto, schemaType);
        }
    }
    static class GetterEntity extends Entity {
        @CustomFields public Map<String,Object> getCustomFields() { return customFields; }
        public void setCustomFields(Map<String,Object> values) { customFields = values; }
    }
    static class FieldAndGetterEntity extends Entity {
        @CustomFields public Map<String,Object> getCustomFields() { return customFields; }
    }
    static class GetterOnlyEntity {
        @jakarta.persistence.Id Long id;
        Map<String,Object> storage;
        @CustomFields public Map<String,Object> getCustomFields() { return storage; }
    }
    static class GetterOnlyService extends AbstractBaseResourceService<GetterOnlyEntity, Request, Long, Filter, Request, Request> {
        final ResourceMapper<GetterOnlyEntity, Request, Request, Request, Long> mapper = mock(ResourceMapper.class);
        GetterOnlyService(BaseCrudRepository<GetterOnlyEntity,Long> repository) { super(repository,GetterOnlyEntity.class); }
        @Override protected ResourceMapper<GetterOnlyEntity,Request,Request,Request,Long> getResourceMapper() { return mapper; }
    }
    private static CustomFieldDescriptor definition(String key, CustomFieldDataType type, boolean readOnly, boolean required) {
        return new CustomFieldDescriptor(key,null,null,type,null,null,0,required,readOnly,false,false,false,
                null,null,null,null,null,null,null,null,null,null,null,null,null);
    }
    private static CustomFieldDescriptor audit() { return definition("auditFlag",CustomFieldDataType.BOOLEAN,true,true); }
    private static CustomFieldDescriptor note() { return definition("note",CustomFieldDataType.STRING,false,false); }
    private static Service updateService(Entity existing, CustomFieldDefinitionProvider definitions) {
        BaseCrudRepository<Entity,Long> repository = mock(BaseCrudRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);
        Service service = new Service(repository);
        ReflectionTestUtils.setField(service,"customFieldValidator",new CustomFieldValidator(definitions));
        doAnswer(call -> {
            ((Entity)call.getArgument(0)).customFields = ((Request)call.getArgument(1)).customFields();
            return null;
        }).when(service.mapper).applyUpdate(any(),any());
        when(service.mapper.toResponse(existing)).thenAnswer(call -> new Request(existing.customFields));
        return service;
    }
    @Test void deniesInvalidCreateAndUpdateBeforeHooksMapperAndRepository() {
        BaseCrudRepository<Entity,Long> repository = mock(BaseCrudRepository.class);
        Service service = new Service(repository);
        CustomFieldDefinitionProvider definitions = (resource,tenant,schema) -> {
            assertEquals("people",resource); assertEquals("org-a",tenant); assertEquals("request",schema);
            return List.of(CustomFieldFixtures.field("age", CustomFieldDataType.INTEGER));
        };
        ReflectionTestUtils.setField(service,"customFieldValidator",new CustomFieldValidator(definitions));
        ReflectionTestUtils.setField(service,"resourceTenantContextResolver",(ResourceTenantContextResolver)() -> Optional.of("org-a"));
        assertThrows(CustomFieldValidationException.class,() -> service.create(new Request(Map.of("age", "wrong"))));
        assertThrows(CustomFieldValidationException.class,() -> service.update(1L,new Request(Map.of("age", "wrong"))));
        assertFalse(service.hookCalled);
        verifyNoInteractions(repository,service.mapper);
    }
    @Test void twoServicesSharingBuilderKeepTheirResourceAndTenantContexts() {
        var builder = new org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<Entity>();
        BaseCrudRepository<Entity,Long> repository = mock(BaseCrudRepository.class);
        when(repository.findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.jpa.domain.Specification.class),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class))).thenReturn(org.springframework.data.domain.Page.empty());
        var definitions = mock(CustomFieldDefinitionProvider.class);
        when(definitions.findDefinitions(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("request")))
                .thenReturn(List.of(CustomFieldFixtures.field("age",CustomFieldDataType.INTEGER)));
        var people = new Service(repository,builder,"people");
        var workers = new Service(repository,builder,"workers");
        people.configureCustomFieldFiltering(definitions,() -> Optional.of("tenant-a"));
        workers.configureCustomFieldFiltering(definitions,() -> Optional.of("tenant-b"));
        var page = org.springframework.data.domain.PageRequest.of(0,10,org.springframework.data.domain.Sort.by("id"));
        people.filter(new Filter(Map.of("age",3)),page,List.of());
        workers.filter(new Filter(Map.of("age",4)),page,List.of());
        verify(definitions).findDefinitions("people","tenant-a","request");
        verify(definitions).findDefinitions("workers","tenant-b","request");
    }
    @Test void preservesRequiredReadOnlyValuesWhenMapperReplacesTheContainer() {
        Entity existing = new Entity(); existing.customFields = Map.of("auditFlag",true,"note","old");
        var service = updateService(existing,(r,t,s) -> List.of(audit(),note()));
        var result = service.update(1L,new Request(Map.of("note","changed")));
        assertEquals(Map.of("auditFlag",true,"note","changed"),result.customFields());
        assertTrue(service.hookCalled);
        assertEquals(1,service.validationCalls);
    }
    @Test void rejectsEveryReadOnlyAssignmentBeforeLoadingOrRunningHooks() {
        BaseCrudRepository<Entity,Long> repository = mock(BaseCrudRepository.class);
        Service service = new Service(repository);
        ReflectionTestUtils.setField(service,"customFieldValidator",new CustomFieldValidator((r,t,s) -> List.of(audit(),note())));
        Map<String,Object> explicitNull = new LinkedHashMap<>(); explicitNull.put("auditFlag",null);
        for (Map<String,Object> input : List.of(Map.<String,Object>of("auditFlag",true),explicitNull)) {
            assertThrows(CustomFieldValidationException.class,() -> service.create(new Request(input)));
            assertThrows(CustomFieldValidationException.class,() -> service.update(1L,new Request(input)));
        }
        assertFalse(service.hookCalled);
        verifyNoInteractions(repository,service.mapper);
    }
    @Test void hookChangesAndRemovalsBecomeTheServerOwnedSnapshot() {
        Entity changed = new Entity(); changed.customFields = new LinkedHashMap<>(Map.of("auditFlag",true));
        var service = updateService(changed,(r,t,s) -> List.of(audit(),note()));
        service.updateHook = entity -> entity.customFields.put("auditFlag",false);
        assertEquals(Map.of("auditFlag",false,"note","changed"),service.update(1L,new Request(Map.of("note","changed"))).customFields());

        Entity removed = new Entity(); removed.customFields = new LinkedHashMap<>(Map.of("auditFlag",true));
        var removalService = updateService(removed,(r,t,s) -> List.of(audit(),note()));
        removalService.updateHook = entity -> entity.customFields.remove("auditFlag");
        doAnswer(call -> { removed.customFields = Map.of("auditFlag",true,"note","changed"); return null; })
                .when(removalService.mapper).applyUpdate(any(),any());
        assertEquals(Map.of("note","changed"),removalService.update(1L,new Request(Map.of("note","changed"))).customFields());
    }
    @Test void nullAndImmutableContainersAndGetterSettersRetainReadOnlyValues() {
        for (Entity entity : List.of(new Entity(),new GetterEntity(),new FieldAndGetterEntity())) {
            for (Map<String,Object> input : Arrays.asList(Map.<String,Object>of(),null)) {
                entity.customFields = Map.of("auditFlag",true);
                var service = updateService(entity,(r,t,s) -> List.of(audit(),note()));
                assertEquals(Map.of("auditFlag",true),service.update(1L,new Request(input)).customFields());
            }
        }
        Entity initiallyNull = new Entity();
        var service = updateService(initiallyNull,(r,t,s) -> List.of(audit(),note()));
        assertNull(service.update(1L,new Request(null)).customFields());
    }
    @Test void getterOnlyMutableContainerCanBeRestoredButImmutableContainerFailsBeforeSave() {
        List<Map<String,Object>> containers = Arrays.asList(new LinkedHashMap<>(Map.of("note","changed")),Map.of("note","changed"),null);
        for (int index = 0; index < containers.size(); index++) {
            GetterOnlyEntity entity = new GetterOnlyEntity(); entity.storage = Map.of("auditFlag",true);
            BaseCrudRepository<GetterOnlyEntity,Long> repository = mock(BaseCrudRepository.class);
            when(repository.findById(1L)).thenReturn(Optional.of(entity));
            when(repository.save(entity)).thenReturn(entity);
            var service = new GetterOnlyService(repository);
            ReflectionTestUtils.setField(service,"customFieldValidator",new CustomFieldValidator((r,t,s) -> List.of(audit(),note())));
            doAnswer(call -> { entity.storage = ((Request)call.getArgument(1)).customFields(); return null; })
                    .when(service.mapper).applyUpdate(any(),any());
            when(service.mapper.toResponse(entity)).thenAnswer(call -> new Request(entity.storage));
            var request = new Request(containers.get(index));
            if (index == 0) {
                assertEquals(Map.of("auditFlag",true,"note","changed"),service.update(1L,request).customFields());
            } else {
                assertThrows(IllegalStateException.class,() -> service.update(1L,request));
                verify(repository,never()).save(any());
            }
        }
    }
    @Test void combinesAnnotatedBackingFieldAndGetterAcrossTheClassHierarchy() {
        var property = CustomFieldSupport.property(FieldAndGetterEntity.class).orElseThrow();
        assertNotNull(property.field());
        assertNotNull(property.accessor());
    }
    @Test void nestedReadOnlyValuesAreDetachedBeforeAnInPlaceMapperMutation() {
        Entity existing = new Entity();
        int[] numbers = {1,2};
        Map<String,Object> nested = new LinkedHashMap<>(Map.of("numbers",numbers,"labels",new ArrayList<>(List.of("original"))));
        existing.customFields = new LinkedHashMap<>(Map.of("history",new ArrayList<>(List.of(nested))));
        var service = updateService(existing,(r,t,s) -> List.of(definition("history",CustomFieldDataType.ARRAY,true,true),note()));
        doAnswer(call -> {
            numbers[0] = 99;
            ((List<String>)nested.get("labels")).clear();
            existing.customFields.clear();
            existing.customFields.put("note","changed");
            return null;
        }).when(service.mapper).applyUpdate(any(),any());
        Map<String,Object> result = service.update(1L,new Request(Map.of("note","changed"))).customFields();
        var restored = (Map<String,Object>)((List<?>)result.get("history")).getFirst();
        assertArrayEquals(new int[]{1,2},(int[])restored.get("numbers"));
        assertEquals(List.of("original"),restored.get("labels"));
        assertEquals("changed",result.get("note"));
    }
    @Test void validationAndPreservationUseOneDefinitionAndTenantSnapshot() {
        var definitions = mock(CustomFieldDefinitionProvider.class);
        when(definitions.findDefinitions("people","tenant-a","request"))
                .thenReturn(List.of(audit(),note()),List.of(note()));
        Entity existing = new Entity(); existing.customFields = Map.of("auditFlag",true);
        var service = updateService(existing,definitions);
        var tenant = mock(ResourceTenantContextResolver.class);
        when(tenant.resolveTenant()).thenReturn(Optional.of("tenant-a"),Optional.of("tenant-b"));
        ReflectionTestUtils.setField(service,"resourceTenantContextResolver",tenant);
        assertEquals(Map.of("auditFlag",true,"note","changed"),service.update(1L,new Request(Map.of("note","changed"))).customFields());
        verify(tenant,times(1)).resolveTenant();
        verify(definitions,times(1)).findDefinitions("people","tenant-a","request");
        verifyNoMoreInteractions(definitions,tenant);
    }
    @Test void noDefinitionsAndOtherTenantsDoNotInheritReadOnlyKeys() {
        Entity first = new Entity(); first.customFields = Map.of("auditFlag",true);
        Entity second = new Entity(); second.customFields = Map.of("auditFlag",false);
        CustomFieldDefinitionProvider definitions = (r,t,s) -> "tenant-a".equals(t) ? List.of(audit(),note()) : List.of();
        var a = updateService(first,definitions);
        var b = updateService(second,definitions);
        ReflectionTestUtils.setField(a,"resourceTenantContextResolver",(ResourceTenantContextResolver)() -> Optional.of("tenant-a"));
        ReflectionTestUtils.setField(b,"resourceTenantContextResolver",(ResourceTenantContextResolver)() -> Optional.of("tenant-b"));
        assertEquals(Map.of("auditFlag",true),a.update(1L,new Request(Map.of())).customFields());
        assertEquals(Map.of(),b.update(1L,new Request(Map.of())).customFields());
    }
    @Test void mapsStructuredFieldErrorsThroughCanonicalHandler() {
        var handler = new org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = handler.handleCustomFieldValidation(new CustomFieldValidationException(List.of(
                new org.springframework.validation.FieldError("payload","customFields.age","Invalid age"))),
                new org.springframework.web.context.request.ServletWebRequest(request));
        assertEquals(400,response.getStatusCode().value());
        assertEquals("customFields.age",response.getBody().getErrors().getFirst().getTarget());
    }
}
