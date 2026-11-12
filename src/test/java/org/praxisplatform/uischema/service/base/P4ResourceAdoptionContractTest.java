package org.praxisplatform.uischema.service.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class P4ResourceAdoptionContractTest {
  @Test @SuppressWarnings("unchecked") void mapperDomainHookRunsBeforeSaveAndForDetachedBulkCandidates() throws Exception {
    BaseCrudRepository<Item,Long> repository=mock(BaseCrudRepository.class);
    var persisted=new Item();persisted.id=7L;persisted.name="old";persisted.version=4;
    when(repository.findById(7L)).thenReturn(Optional.of(persisted));
    when(repository.save(persisted)).thenAnswer(i->{persisted.version++;return persisted;});
    var service=new Items(repository);
    var etags=new ResourceVersionEtagService("p4-canonical-test-secret-at-least-32-characters");
    var scope=new ResourceVersionScope("tenant=1");
    var condition=new ResourceVersionUpdatePrecondition<>(etags,etags.create(scope,"p4.items",7L,4),scope,"p4.items",7L);
    var result=service.update(7L,new Write("  next  "),condition);
    assertEquals("NEXT",result.body().name());assertEquals(5,result.requirePersistedVersion());
    assertEquals(List.of("before","mapped:NEXT","after"),service.hooks);
    service.hooks.clear();
    var json=new ObjectMapper().findAndRegisterModules();
    try(var validation=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      var adapter=service.bulkDomainAdapter(json,validation.getValidator(),etags,()->scope);
      var schema=new io.swagger.v3.oas.models.media.ObjectSchema();
      schema.addProperty("name",new io.swagger.v3.oas.models.media.StringSchema());
      var fields=BulkEditableFields.compile(json,json.constructType(Write.class),json.readTree("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"),io.swagger.v3.oas.models.SpecVersion.V30,Set.of());
      adapter.evaluate(7L,etags.create(scope,"p4.items",7L,5),List.of(BulkFieldChange.set("name",json.valueToTree(" candidate "))),org.praxisplatform.uischema.service.base.ResourceMutationContext.bulkEvaluation(BulkMode.UNIFORM_UPDATE,java.time.Instant.EPOCH),fields);
      assertEquals("NEXT",persisted.name);assertEquals(5,persisted.version);
      assertEquals(List.of("before","mapped:CANDIDATE"),service.hooks);
      verify(repository,times(1)).save(any());
      assertThrows(IllegalArgumentException.class,()->adapter.evaluate(7L,etags.create(scope,"p4.items",7L,5),
          List.of(BulkFieldChange.set("name",json.valueToTree("deny"))),org.praxisplatform.uischema.service.base.ResourceMutationContext.bulkEvaluation(BulkMode.UNIFORM_UPDATE,java.time.Instant.EPOCH),fields));
      assertEquals("NEXT",persisted.name);verify(repository,times(1)).save(any());
    }
  }
  @Test @SuppressWarnings({"unchecked","rawtypes"}) void operationScopeIsAppliedToQueryAndExplicitSelectionsBeforeLimit() {
    BaseCrudRepository<Item,Long> repository=mock(BaseCrudRepository.class);
    var item=new Item();item.id=7L;item.name="allowed";
    when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(item)));
    var service=new Items(repository);
    assertEquals(List.of(7L),service.selectBulkIdentities(new Filter(),null,List.of(),1));
    assertEquals(List.of(7L),service.selectBulkIdentities(null,List.of(7L),List.of(),1));
    var captured=org.mockito.ArgumentCaptor.forClass(org.springframework.data.jpa.domain.Specification.class);
    verify(repository,times(2)).findAll(captured.capture(),any(org.springframework.data.domain.Pageable.class));
    for(var specification:captured.getAllValues()) {
      var root=mock(jakarta.persistence.criteria.Root.class,RETURNS_DEEP_STUBS);
      var builder=mock(jakarta.persistence.criteria.CriteriaBuilder.class);
      specification.toPredicate(root,mock(jakarta.persistence.criteria.CriteriaQuery.class),builder);
      verify(builder).equal(root.get("name"),"allowed");
    }
  }
  static class Item { @Id Long id;String name;@Version long version;public Item(){} }
  record Read(Long id,String name){}
  record Write(@org.praxisplatform.uischema.annotation.BulkEditable(modes={BulkMode.UNIFORM_UPDATE}) String name){}
  static class Filter implements GenericFilterDTO{}
  static class Items extends AbstractBaseResourceService<Item,Read,Long,Filter,Write,Write>
      implements VersionedCreateUpdateResourceService<Read,Long,Filter,Write,Write> {
    final List<String> hooks=new ArrayList<>();
    final ResourceMapper<Item,Read,Write,Write,Long> mapper=new ResourceMapper<>() {
      public Read toResponse(Item e){return new Read(e.id,e.name);}
      public Item newEntity(Write dto){var e=new Item();e.name=dto.name();return e;}
      public void applyUpdate(Item e,Write dto){e.name=dto.name();}
      public Long extractId(Item e){return e.id;}
    };
    Items(BaseCrudRepository<Item,Long> repository){super(repository,Item.class);}
    protected ResourceMapper<Item,Read,Write,Write,Long> getResourceMapper(){return mapper;}
    protected String resolveResourceKey(){return "p4.items";}
    protected ResourceFilterAccessScope<Item> resolveBulkResourceFilterAccessScope(){
      return ResourceFilterAccessScope.restricted((root,query,builder)->builder.equal(root.get("name"),"allowed"));
    }
    protected void beforeUpdate(Long id,Item e,Write dto){hooks.add("before");}
    protected void applyResourceUpdate(Long id,Item e,Write dto){
      super.applyResourceUpdate(id,e,dto);e.name=e.name.trim().toUpperCase(java.util.Locale.ROOT);
      if("DENY".equals(e.name))throw new IllegalArgumentException("Domain rejection");hooks.add("mapped:"+e.name);
    }
    protected void afterUpdate(Long id,Item e,Write dto){hooks.add("after");}
  }
}
