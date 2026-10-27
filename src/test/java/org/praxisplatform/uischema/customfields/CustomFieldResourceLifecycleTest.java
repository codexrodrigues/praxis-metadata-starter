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
        boolean hookCalled;
        String key = "people";
        Service(BaseCrudRepository<Entity,Long> repository) { super(repository, Entity.class); }
        Service(BaseCrudRepository<Entity,Long> repository,
                org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<Entity> builder, String key) {
            super(repository, builder, Entity.class); this.key = key;
        }
        @Override protected ResourceMapper<Entity, Request, Request, Request, Long> getResourceMapper() { return mapper; }
        @Override protected String resolveResourceKey() { return key; }
        @Override protected void beforeCreate(Request dto, Entity entity) { hookCalled = true; }
        @Override protected void beforeUpdate(Long id, Entity entity, Request dto) { hookCalled = true; }
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
