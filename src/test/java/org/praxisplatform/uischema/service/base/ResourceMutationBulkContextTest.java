package org.praxisplatform.uischema.service.base;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

class ResourceMutationBulkContextTest {
    static EntityManagerFactory factory;
    EntityManager em; Probe service;
    final ObjectMapper json=new ObjectMapper();
    final ResourceVersionEtagService etags=new ResourceVersionEtagService("native-context-bulk-test");
    final ResourceVersionScope scope=new ResourceVersionScope("tenant=1");
    @BeforeAll static void start() {
        factory=new Configuration().addAnnotatedClass(CoreResourceEvolutionIntegrationTest.Item.class)
            .setProperty("hibernate.connection.driver_class","org.h2.Driver")
            .setProperty("hibernate.connection.url","jdbc:h2:mem:mutation-bulk;DB_CLOSE_DELAY=-1")
            .setProperty("hibernate.hbm2ddl.auto","create-drop").buildSessionFactory();
    }
    @AfterAll static void closeFactory(){factory.close();}
    @BeforeEach void setup(){
        em=factory.createEntityManager();em.getTransaction().begin();
        em.persist(new CoreResourceEvolutionIntegrationTest.Item(1,1,"old")); em.flush();em.clear();
        service=new Probe(new JpaRepositoryFactory(em).getRepository(CoreResourceEvolutionIntegrationTest.ItemRepository.class));
        ReflectionTestUtils.setField(service,"entityManager",em);
    }
    @AfterEach void cleanup(){if(em.getTransaction().isActive())em.getTransaction().rollback();em.close();}
    String version(){return etags.create(scope,"items",1L,0);}
    BulkEditableFields fields() throws Exception {
        return BulkEditableFields.compile(json,json.constructType(Write.class),json.readTree("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"),
            io.swagger.v3.oas.models.SpecVersion.V30,Set.of());
    }
    @TestFactory Stream<DynamicTest> bothModesCarryProposalAndConfirmationWithoutWritingDuringEvaluation(){
        return Stream.of(BulkMode.UNIFORM_UPDATE,BulkMode.PER_ITEM_UPDATE).flatMap(mode->Stream.of(ResourceMutationContext.Stage.EVALUATION,ResourceMutationContext.Stage.CONFIRMATION)
            .map(stage->DynamicTest.dynamicTest(mode+"/"+stage,()->{
                try(var validator=jakarta.validation.Validation.buildDefaultValidatorFactory()){
                    var adapter=service.bulkDomainAdapter(json,validator.getValidator(),etags,()->scope);
                    var context=stage==ResourceMutationContext.Stage.EVALUATION ? ResourceMutationContext.bulkEvaluation(mode,Instant.EPOCH)
                        : ResourceMutationContext.bulkConfirmation(mode,Instant.EPOCH);
                    var candidate=adapter.evaluate(1L,version(),List.of(BulkFieldChange.set("name",json.valueToTree("new"))),context,fields());
                    assertEquals(context,service.contexts.getLast()); assertEquals("old",em.find(CoreResourceEvolutionIntegrationTest.Item.class,1L).name);
                    assertEquals("1",candidate.facts().path("admission").path("policy").asText()); assertEquals(0,service.effects);
                }
            })));
    }
    @Test void confirmationReusesTheLockedVersionAndExecutesItsOwnContext() throws Exception {
        try(var validator=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var adapter=service.bulkDomainAdapter(json,validator.getValidator(),etags,()->scope);
            var context=ResourceMutationContext.bulkConfirmation(BulkMode.PER_ITEM_UPDATE,Instant.EPOCH);
            adapter.update(1L,version(),json.valueToTree(new Write("new")),context);
            assertEquals(List.of(context),service.contexts); assertEquals("new",em.find(CoreResourceEvolutionIntegrationTest.Item.class,1L).name);
            assertEquals(1,service.effects);
            assertThrows(ResourceVersionPreconditionException.class,()->adapter.update(1L,version(),json.valueToTree(new Write("other")),context));
            assertEquals(1,service.effects);
        }
    }
    @Test void changedPolicyProducesDifferentProtectedEvidenceFromTheSameAdmission() throws Exception {
        try(var validator=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var adapter=service.bulkDomainAdapter(json,validator.getValidator(),etags,()->scope);
            var context=ResourceMutationContext.bulkEvaluation(BulkMode.UNIFORM_UPDATE,Instant.EPOCH);
            var changes=List.of(BulkFieldChange.set("name",json.valueToTree("new")));
            var first=adapter.evaluate(1L,version(),changes,context,fields()); service.policy=2;
            var second=adapter.evaluate(1L,version(),changes,context,fields());
            assertNotEquals(first.facts(),second.facts()); assertEquals(0,service.effects);
        }
    }
    record Write(@BulkEditable(modes={BulkMode.UNIFORM_UPDATE,BulkMode.PER_ITEM_UPDATE}) String name){}
    static class Probe extends AbstractBaseResourceService<CoreResourceEvolutionIntegrationTest.Item,CoreResourceEvolutionIntegrationTest.Response,Long,CoreResourceEvolutionIntegrationTest.Filter,Write,Write>
        implements VersionedCreateUpdateResourceService<CoreResourceEvolutionIntegrationTest.Response,Long,CoreResourceEvolutionIntegrationTest.Filter,Write,Write>{
        List<ResourceMutationContext> contexts=new ArrayList<>();int effects;int policy=1;
        Probe(CoreResourceEvolutionIntegrationTest.ItemRepository repository){super(repository,CoreResourceEvolutionIntegrationTest.Item.class);}
        protected String resolveResourceKey(){return "items";}
        protected ResourceMapper<CoreResourceEvolutionIntegrationTest.Item,CoreResourceEvolutionIntegrationTest.Response,Write,Write,Long> getResourceMapper(){return new ResourceMapper<>(){
            public CoreResourceEvolutionIntegrationTest.Response toResponse(CoreResourceEvolutionIntegrationTest.Item row){var dto=new CoreResourceEvolutionIntegrationTest.Response();dto.id=row.pk;dto.name=row.name;return dto;}
            public CoreResourceEvolutionIntegrationTest.Item newEntity(Write dto){return new CoreResourceEvolutionIntegrationTest.Item(10,1,dto.name());}
            public void applyUpdate(CoreResourceEvolutionIntegrationTest.Item row,Write dto){row.name=dto.name();}
            public Long extractId(CoreResourceEvolutionIntegrationTest.Item row){return row.pk;}
        };}
        protected JsonNode validateUpdate(ResourceMutationContext context,Long id,CoreResourceEvolutionIntegrationTest.Item current,Write dto){
            assertFalse(getEntityManager().unwrap(org.hibernate.Session.class).isDirty()); contexts.add(context);
            return new ObjectMapper().createObjectNode().put("policy",Integer.toString(policy));
        }
        protected void afterUpdate(Long id,CoreResourceEvolutionIntegrationTest.Item row,Write dto){effects++;}
    }
}
