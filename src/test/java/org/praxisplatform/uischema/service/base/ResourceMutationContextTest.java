package org.praxisplatform.uischema.service.base;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.util.*;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.concurrency.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** Native transaction proof: origin cannot collapse into ordinary UPDATE or DELETE. */
class ResourceMutationContextTest {
    static EntityManagerFactory factory;
    EntityManager em;
    Probe service;
    @BeforeAll static void start() {
        factory = new Configuration().addAnnotatedClass(CoreResourceEvolutionIntegrationTest.Item.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:mutation-context;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
    }
    @AfterAll static void stop() { factory.close(); }
    @BeforeEach void setup() {
        em = factory.createEntityManager(); em.getTransaction().begin();
        var repository = new JpaRepositoryFactory(em).getRepository(CoreResourceEvolutionIntegrationTest.ItemRepository.class);
        service = new Probe(repository); ReflectionTestUtils.setField(service, "entityManager", em);
        for (long id=1;id<=2;id++) em.persist(new CoreResourceEvolutionIntegrationTest.Item(id,1,"old"));
        em.flush(); em.clear();
    }
    @AfterEach void stopTransaction() { if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }

    @Test void directCreateAndVersionedUpdateCarryNativeIdentity() {
        service.create("new"); assertEquals(ResourceMutationContext.create(), service.contexts.getFirst());
        var etags = new ResourceVersionEtagService("mutation-context-test"); var scope = new ResourceVersionScope("1|test|actor");
        service.update(1L,"updated",new ResourceVersionUpdatePrecondition<>(etags,etags.create(scope,"items",1L,0),scope,"items",1L));
        assertEquals(ResourceMutationContext.update(), service.contexts.getLast());
    }
    @Test void everyDeleteTargetIsAdmittedBeforeAnyTargetIsScheduledForDeletion() {
        service.rejectDelete = 2L;
        assertThrows(IllegalArgumentException.class, () -> service.deleteAllById(List.of(1L,2L)));
        assertEquals(2,service.contexts.size());
        assertSame(service.contexts.getFirst(),service.contexts.getLast());
        assertEquals(ResourceMutationContext.Kind.DELETE,service.contexts.getFirst().kind());
        assertTrue(service.contexts.getFirst().collection());
        assertNotNull(service.contexts.getFirst().referenceInstant());
        assertNotNull(em.find(CoreResourceEvolutionIntegrationTest.Item.class,1L));
        assertNotNull(em.find(CoreResourceEvolutionIntegrationTest.Item.class,2L));
        assertTrue(service.effects.isEmpty());
    }
    @Test void itemDeletionIsDistinctFromCollectionDeletion() {
        service.deleteById(1L); assertEquals(List.of(ResourceMutationContext.delete()),service.contexts);
        assertEquals(List.of(1L),service.effects);
    }
    @Test void impossibleContextsFailClosed() {
        assertThrows(IllegalArgumentException.class,()->new ResourceMutationContext(ResourceMutationContext.Kind.CREATE,
            ResourceMutationContext.Stage.CONFIRMATION,BulkMode.UNIFORM_UPDATE,false,java.time.Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,()->ResourceMutationContext.bulkEvaluation(BulkMode.DOMAIN_COMMAND,java.time.Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,()->ResourceMutationContext.update().requireBulk());
    }
    static class Probe extends CoreResourceEvolutionIntegrationTest.ItemService {
        List<ResourceMutationContext> contexts=new ArrayList<>(); List<Long> effects=new ArrayList<>(); Long rejectDelete;
        Probe(CoreResourceEvolutionIntegrationTest.ItemRepository repository) { super(repository); }
        @Override protected void validateCreate(ResourceMutationContext context,String request,CoreResourceEvolutionIntegrationTest.Item row) { contexts.add(context); }
        @Override protected com.fasterxml.jackson.databind.JsonNode validateUpdate(ResourceMutationContext context,Long id,CoreResourceEvolutionIntegrationTest.Item row,String request) { contexts.add(context); return null; }
        @Override protected void validateDelete(ResourceMutationContext context,Long id,CoreResourceEvolutionIntegrationTest.Item row) {
            contexts.add(context); if (Objects.equals(id,rejectDelete)) throw new IllegalArgumentException("dependency");
        }
        @Override protected void afterDelete(Long id,CoreResourceEvolutionIntegrationTest.Item row) { effects.add(id); }
    }
}
