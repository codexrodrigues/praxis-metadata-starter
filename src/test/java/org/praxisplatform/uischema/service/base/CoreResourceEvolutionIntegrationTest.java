package org.praxisplatform.uischema.service.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.annotation.*;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.exporting.*;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.stats.*;
import org.praxisplatform.uischema.util.CursorEncoder;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real H2/JPA proof for the common mutable/read-only core, without a host dependency. */
class CoreResourceEvolutionIntegrationTest {
    static EntityManagerFactory factory;
    static final List<String> sql = new java.util.concurrent.CopyOnWriteArrayList<>();
    EntityManager em;
    ItemRepository repository;
    ItemService service;
    CompositeService composite;

    @BeforeAll static void start() {
        factory = new Configuration().addAnnotatedClass(Item.class).addAnnotatedClass(CompositeItem.class).addAnnotatedClass(IdClassItem.class).addAnnotatedClass(OneIdItem.class).addAnnotatedClass(CollectionSortItem.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:p1core;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setStatementInspector((StatementInspector) statement -> { sql.add(statement); return statement; })
                .buildSessionFactory();
    }
    @AfterAll static void stop() { factory.close(); }
    @BeforeEach void setup() {
        em = factory.createEntityManager(); em.getTransaction().begin();
        repository = new JpaRepositoryFactory(em).getRepository(ItemRepository.class);
        service = new ItemService(repository);
        ReflectionTestUtils.setField(service, "entityManager", em);
        ReflectionTestUtils.setField(service, "collectionExportExecutor", new DefaultCollectionExportExecutor(List.of(
                new JsonCollectionExportEngine(new ObjectMapper().findAndRegisterModules()), new CsvCollectionExportEngine(), new ExcelCollectionExportEngine())));
        composite = new CompositeService(new JpaRepositoryFactory(em).getRepository(CompositeRepository.class));
        ReflectionTestUtils.setField(composite, "entityManager", em);
        for (long i = 1; i <= 5; i++) em.persist(new Item(i, i == 5 ? 2 : 1, i == 4 ? null : "Name " + i));
        for (int partition = 1; partition <= 2; partition++) for (int sequence = 1; sequence <= 3; sequence++)
            { em.persist(new CompositeItem(new ItemId(partition, sequence)));
              var item = new IdClassItem(); item.partitionId = partition; item.sequence = sequence; em.persist(item); }
        for (int i = 1; i <= 3; i++) { var row = new OneIdItem(); row.pk = new OneId(i); em.persist(row); }
        em.flush(); em.clear(); sql.clear();
    }
    @AfterEach void rollback() { if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }

    @Test void cursorUsesStableIdentityInsteadOfOffsetAndSurvivesAnchorDeletion() {
        var first = service.filterByCursor(new Filter(), Sort.by("id"), null, null, 2);
        assertEquals(List.of(1L, 2L), ids(first.content()));
        assertTrue(CursorEncoder.BASE64_URL.decode(first.next()).matches("[0-9a-f]{24}:2"));
        assertNull(first.prev());
        em.remove(em.find(Item.class, 1L)); em.remove(em.find(Item.class, 2L)); em.flush();
        var second = service.filterByCursor(new Filter(), Sort.by("id"), first.next(), null, 2);
        assertEquals(List.of(3L, 4L), ids(second.content()));
        assertNull(second.next());
        assertTrue(sql.stream().anyMatch(statement -> statement.contains(">?")));
        assertTrue(sql.stream().noneMatch(statement -> statement.toLowerCase().contains("offset")));
    }

    @Test void cursorSupportsBackwardTraversalDescendingAndNullSortValues() {
        Sort sort = Sort.by(Sort.Order.desc("name").nullsLast());
        var first = service.filterByCursor(new Filter(), sort, null, null, 2);
        assertEquals(List.of(3L, 2L), ids(first.content()));
        var second = service.filterByCursor(new Filter(), sort, first.next(), null, 2);
        assertEquals(List.of(1L, 4L), ids(second.content()));
        assertNull(second.next()); assertNotNull(second.prev());
        var previous = service.filterByCursor(new Filter(), sort, null, second.prev(), 2);
        assertEquals(ids(first.content()), ids(previous.content()));
        assertNull(previous.prev()); assertNotNull(previous.next());
        assertEquals(3, service.locate(new Filter(), sort, 4L).orElseThrow());
    }

    @Test void compositeCursorExpandsPublicIdAndUsesLexicographicPredicate() {
        assertEquals(List.of("pk.partitionId", "pk.sequence"), composite.getIdentityPaths());
        var first = composite.filterByCursor(new Filter(), Sort.by("id"), null, null, 2);
        assertEquals(List.of("1:1", "1:2"), first.content());
        assertTrue(CursorEncoder.BASE64_URL.decode(first.next()).matches("[0-9a-f]{24}:1:2"));
        var second = composite.filterByCursor(new Filter(), Sort.by("id"), first.next(), null, 2);
        assertEquals(List.of("1:3", "2:1"), second.content());
        assertEquals(List.of("1:1", "1:2"), composite.filterByCursor(new Filter(), Sort.by("id"), null, second.prev(), 2).content());
        assertEquals(4, composite.locate(new Filter(), Sort.by("id"), new ItemId(2, 2)).orElseThrow());
        assertEquals(List.of("2:3", "2:2"), composite.filterByCursor(new Filter(), Sort.by(Sort.Direction.DESC, "id"), null, null, 2).content());
    }

    @Test void locateCountsInSqlWithoutLoadingCollectionAndObeysFilterAndScope() {
        assertEquals(2, service.locate(new Filter(), Sort.by("id"), 3L).orElseThrow());
        assertTrue(sql.stream().anyMatch(statement -> statement.toLowerCase().contains("count(")));
        assertEquals(3, sql.stream().filter(statement -> statement.toLowerCase().startsWith("select")).count());
        assertEquals(403, statusOf(() -> service.locate(new Filter(), Sort.by("id"), 5L)));
        Filter filter = new Filter(); filter.name = "Name 1";
        assertThrows(EntityNotFoundException.class, () -> service.locate(filter, Sort.by("id"), 3L));
    }

    @Test void scopedCollectionsAndItemAuthorizationKeepNotFoundDistinctFromForbidden() {
        assertEquals(List.of(1L, 2L, 3L, 4L), ids(service.findAll()));
        assertEquals(404, statusOf(() -> { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }));
        assertThrows(EntityNotFoundException.class, () -> service.findById(99L));
        assertThrows(EntityNotFoundException.class, () -> service.findAllById(List.of(1L, 99L)));
        assertEquals(403, statusOf(() -> service.findById(5L)));
        assertEquals(403, statusOf(() -> service.findAllById(List.of(1L, 5L))));
        assertEquals(403, statusOf(() -> service.update(5L, "Denied", precondition(5, 0))));
        assertEquals(403, statusOf(() -> service.deleteById(5L)));
        assertEquals(403, statusOf(() -> service.deleteAllById(List.of(1L, 5L))));
        assertThrows(IllegalArgumentException.class, () -> service.deleteAllById(Arrays.asList(1L, null)));
        assertNotNull(em.find(Item.class, 1L));
        assertThrows(EntityNotFoundException.class, () -> service.deleteById(99L));
        assertThrows(EntityNotFoundException.class, () -> service.deleteAllById(List.of(1L, 99L)));
        service.tenant = -1;
        assertTrue(service.findAll().isEmpty());
    }

    @Test void collectionExportAndPinnedIdsApplyTheItemGuardEvenWithoutASqlTenantScope() {
        service.guardOnly = true;
        assertEquals(403, statusOf(() -> service.exportCollection(request(CollectionExportScope.ALL, new Filter(), null, null, 10, null))));
        Filter filter = new Filter(); filter.name = "Name 1";
        assertEquals(403, statusOf(() -> service.filter(filter, PageRequest.of(0, 5), List.of(5L))));
        assertEquals(403, statusOf(() -> service.filter(new Filter(), PageRequest.of(0, 10), List.of())));
    }

    @Test void unauthorizedVersionedWritesNeverAcquireTheTargetRowLock() {
        assertEquals(403, statusOf(() -> service.update(5L, "denied", precondition(5,0))));
        assertTrue(sql.stream().noneMatch(statement -> statement.toLowerCase().contains("for update")), sql.toString());
        assertTrue(sql.stream().noneMatch(statement -> statement.toLowerCase().startsWith("update")), sql.toString());
    }

    @Test void itemAccessIsRecheckedAfterTheLockedRefresh() {
        var mocked = org.mockito.Mockito.mock(EntityManager.class);
        var session = org.mockito.Mockito.mock(org.hibernate.Session.class);
        org.mockito.Mockito.when(mocked.isJoinedToTransaction()).thenReturn(true);
        org.mockito.Mockito.when(mocked.unwrap(org.hibernate.Session.class)).thenReturn(session);
        var entity = new Item(1,1,"permitted-before-refresh"); entity.revision = 0L;
        org.mockito.Mockito.when(mocked.find(Item.class,1L)).thenReturn(entity);
        org.mockito.Mockito.doAnswer(call -> { entity.tenant = 2; return null; }).when(mocked).refresh(entity,LockModeType.PESSIMISTIC_WRITE);
        ReflectionTestUtils.setField(service,"entityManager",mocked);
        assertEquals(403,statusOf(() -> service.update(1L,"denied-after-refresh",precondition(1,0))));
        var order = org.mockito.Mockito.inOrder(mocked);
        order.verify(mocked).find(Item.class,1L);
        order.verify(mocked).refresh(entity,LockModeType.PESSIMISTIC_WRITE);
    }

    @Test void everyResponsePathEnrichesOneBatchIncludingReadOnlyAndExport() throws Exception {
        assertTrue(service.findById(1L).body().enriched);
        assertEquals(List.of(1), service.batches); service.batches.clear();
        assertTrue(service.findAll().stream().allMatch(row -> row.enriched));
        assertEquals(List.of(4), service.batches); service.batches.clear();
        assertEquals(List.of(2L, 1L), ids(service.findAllById(List.of(2L, 1L))));
        assertEquals(List.of(2), service.batches); service.batches.clear();
        assertTrue(service.filter(new Filter(), PageRequest.of(0, 2), List.of()).stream().allMatch(row -> row.enriched));
        assertEquals(List.of(2), service.batches); service.batches.clear();
        service.filterByCursor(new Filter(), Sort.by("id"), null, null, 2);
        assertEquals(List.of(2), service.batches); service.batches.clear();
        service.exportCollection(request(CollectionExportScope.ALL, null, null, null, 10, List.of()));
        assertEquals(List.of(4), service.batches);
        ReadOnlyService readOnly = new ReadOnlyService(repository);
        assertTrue(readOnly.findById(1L).body().enriched);
    }

    @Test void exportResolvesAllFiveScopesSelectionExclusionsFilterAndPage() throws Exception {
        Filter filter = new Filter(); filter.name = "Name 1";
        assertEquals(List.of(1L), exportedIds(service.exportCollection(request(CollectionExportScope.FILTERED, filter, null, null, 10, List.of()))));
        assertEquals(List.of(1L, 2L, 3L, 4L), exportedIds(service.exportCollection(request(CollectionExportScope.ALL, filter, null, null, 10, List.of()))));
        var selection = new CollectionExportSelection("explicit", "id", List.of("2", "5"), false, List.of());
        assertEquals(List.of(2L), exportedIds(service.exportCollection(request(CollectionExportScope.SELECTED, filter, selection, null, 10, List.of()))));
        assertEquals(List.of(2L), exportedIds(service.exportCollection(request(CollectionExportScope.AUTO, filter, selection, null, 10, List.of()))));
        assertEquals(List.of(1L), exportedIds(service.exportCollection(request(CollectionExportScope.AUTO, filter, null, null, 10, List.of()))));
        var allMatching = new CollectionExportSelection("query", "id", List.of(), true, List.of("2", "5"));
        assertEquals(List.of(1L, 3L, 4L), exportedIds(service.exportCollection(request(CollectionExportScope.SELECTED, null, allMatching, null, 10, List.of()))));
        assertEquals(List.of(3L, 4L), exportedIds(service.exportCollection(request(CollectionExportScope.CURRENT_PAGE, null, null,
                new CollectionExportPage(1, null, 2, 4L), 10, List.of()))));
    }

    @Test void exportsAreBoundedAndGovernanceCannotBeOverriddenOrSpoofed() throws Exception {
        assertEquals(List.of("id", "name", "date", "amount", "active"), service.defaultExportFields().stream().map(CollectionExportField::key).toList());
        assertEquals("dd/MM/yyyy", service.defaultExportFields().get(2).presentation().format());
        assertNull(service.defaultExportFields().get(3).presentation().currency());
        assertEquals("Enabled", service.defaultExportFields().get(4).presentation().trueLabel());
        service.overrideFields = true;
        assertFalse(service.governedExportFields().stream().anyMatch(field -> field.key().equals("secret") || field.key().equals("tagged")));
        var forbidden = List.of(new CollectionExportField("secret", "Harmless", true, true, "string", "secret"));
        assertThrows(IllegalArgumentException.class, () -> service.exportCollection(request(CollectionExportScope.ALL, null, null, null, 10, forbidden)));
        var limited = service.exportCollection(request(CollectionExportScope.ALL, null, null, null, 2, List.of()));
        assertEquals(2, limited.rowCount()); assertEquals(true, limited.metadata().get("truncated"));
        assertTrue(limited.warnings().stream().anyMatch(warning -> warning.contains("row limit")));
        assertThrows(IllegalArgumentException.class, () -> service.exportCollection(request(CollectionExportScope.ALL, null, null, null, 0, List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.exportCollection(request(CollectionExportScope.CURRENT_PAGE, null, null, null, 10, List.of())));
        service.blankExportPath = true;
        assertEquals(List.of(1L, 2L, 3L, 4L), exportedIds(service.exportCollection(request(CollectionExportScope.ALL, null, null, null, 10, List.of()))));
    }

    @Test void compositeSelectionSupportsWireIdsAndAccessSpecifications() {
        assertEquals(List.of(new ItemId(1, 2), new ItemId(2, 1)), composite.getRepository()
                .findAll(composite.selectionSpecification(List.of("1:2", "2:1")), Sort.by("pk.partitionId", "pk.sequence"))
                .stream().map(entity -> entity.pk).toList());
    }

    @Test void inheritedVersionedUpdateCapturesFinalHookRevisionAndPreservesCustomFields() {
        var result = service.update(1L, "Updated", precondition(1, 0));
        assertEquals("Updated!", result.body().name);
        assertTrue(result.requirePersistedVersion() >= 1);
        assertEquals(result.requirePersistedVersion(), em.find(Item.class, 1L).revision);
        assertTrue(result.body().enriched);
        assertTrue(sql.stream().anyMatch(statement -> statement.toLowerCase().contains("for update")));
        var stale = assertThrows(ResourceVersionPreconditionException.class, () -> service.update(1L, "stale", precondition(1, 0)));
        assertEquals(412, stale.status().value());
        assertThrows(ResourceVersionPreconditionException.class, () -> service.update(1L, "no precondition"));
        assertThrows(ResourceVersionPreconditionException.class, () -> service.update(1L, "no precondition", null));
        assertThrows(EntityNotFoundException.class, () -> service.update(99L, "missing", precondition(99, 0)));
    }

    @Test void versionedMutationAndDeleteRunAccessAndLifecycleHooks() {
        var result = service.rename(1L, precondition(1, 0));
        assertEquals("Command", result.body().name); assertEquals(1, result.requirePersistedVersion());
        assertThrows(ResourceVersionPreconditionException.class, () -> service.rename(1L, precondition(1, 0)));
        assertEquals(403, statusOf(() -> service.rename(5L, precondition(5, 0))));
        service.deleteAllById(List.of(2L, 3L));
        assertEquals(List.of(2L, 3L), service.deleted);
        assertNull(em.find(Item.class, 2L)); assertNull(em.find(Item.class, 3L));
    }

    @Test void stateSnapshotAndStatsDefaultsFollowServiceDeclarations() {
        assertEquals("OPEN", service.findResourceStateSnapshot(1L).orElseThrow().state());
        assertEquals(403, statusOf(() -> service.findResourceStateSnapshot(5L)));
        assertEquals(StatsSupportMode.DISABLED, service.getGroupByStatsSupportMode());
        service.stats = StatsFieldRegistry.builder().categoricalGroupByBucket("name", "name").build();
        assertEquals(StatsSupportMode.AUTO, service.getGroupByStatsSupportMode());
        assertEquals(StatsSupportMode.AUTO, service.getTimeSeriesStatsSupportMode());
        assertEquals(StatsSupportMode.AUTO, service.getDistributionStatsSupportMode());
        assertEquals(StatsSupportMode.AUTO, service.getComparisonStatsSupportMode());
    }

    @Test void invalidCursorsAndAmbiguousBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.filterByCursor(new Filter(), Sort.by("id"), "bad", null, 2));
        assertThrows(IllegalArgumentException.class, () -> service.filterByCursor(new Filter(), Sort.by("id"), "one", "two", 2));
        assertThrows(IllegalArgumentException.class, () -> service.filterByCursor(new Filter(), Sort.by("id"), null, null, 0));
    }

    @Test void unpagedFilterExpandsThePublicIdentityAlias() {
        assertEquals(List.of(1L, 2L, 3L, 4L), ids(service.filter(new Filter(), Pageable.unpaged(Sort.by("id")), List.of()).getContent()));
        assertEquals(List.of(3L, 1L, 2L, 4L), ids(service.filter(new Filter(), Pageable.unpaged(Sort.by("id")), List.of(3L, 5L)).getContent()));
    }

    @Test void aPreconditionForAnotherItemCannotAuthorizeAnUpdate() {
        assertThrows(ResourceVersionPreconditionException.class, () -> service.update(2L, "Wrong item", precondition(1, 0)));
        assertEquals("Name 2", em.find(Item.class, 2L).name);
    }

    @Test void idClassSelectionIncludesOnlyScopedIdsAndExpandsIdentitySort() {
        var target = new IdClassService(new JpaRepositoryFactory(em).getRepository(IdClassRepository.class));
        ReflectionTestUtils.setField(target, "entityManager", em);
        assertEquals(List.of("partitionId", "sequence"), target.getIdentityPaths());
        var result = target.filter(new Filter(), PageRequest.of(0, 1, Sort.by("id")), List.of(new ItemId(1, 3), new ItemId(2, 1)));
        assertEquals(List.of("1:3", "1:1"), result.getContent());
        assertEquals(List.of("1:1", "1:2"), target.filterByCursor(new Filter(), Sort.by("id"), null, null, 2).content());
    }

    @Test void embeddedIdentityWithOneComponentStillUsesTheComponentValue() {
        var target = new OneIdService(new JpaRepositoryFactory(em).getRepository(OneIdRepository.class));
        ReflectionTestUtils.setField(target, "entityManager", em);
        assertEquals(1, target.locate(new Filter(), Sort.by("id"), new OneId(2)).orElseThrow());
        assertEquals(1, target.getRepository().count(target.selectionSpecification(List.of("2"))));
    }

    @Test void collectionValuedSortIsRejectedRatherThanReturningDuplicatedResourceRows() {
        var cb = em.getCriteriaBuilder(); var query = cb.createQuery(CollectionSortItem.class);
        var root = query.from(CollectionSortItem.class);
        assertThrows(IllegalArgumentException.class, () -> ResourceKeysetSupport.orders(cb, root, Sort.by("labels")));
    }

    @Test void ignoreCaseForNumericCursorSortIsRejectedOnTheFirstPage() {
        assertThrows(IllegalArgumentException.class, () -> service.filterByCursor(new Filter(), Sort.by(Sort.Order.asc("id").ignoreCase()), null, null, 2));
    }

    @Test void versionIntrospectionReadsTheManagedImplementationBehindAJpaProxy() {
        Item reference = em.getReference(Item.class, 1L);
        assertInstanceOf(org.hibernate.proxy.HibernateProxy.class, reference);
        assertEquals(0, service.getEntityResourceVersion(reference).orElseThrow());
        assertEquals(1L, ResourceKeysetSupport.read(Map.of("related", reference), "related.pk"));
        assertEquals(2, service.locate(new Filter(), Sort.by("id"), 3L).orElseThrow());
        em.clear(); em.getReference(Item.class, 3L);
        assertEquals(2, service.locate(new Filter(), Sort.by("id"), 3L).orElseThrow());
    }

    @Test void existingJpaReferencePreservesAccessHookProjectionAndVersionedWrite() {
        em.getReference(Item.class, 1L);
        assertEquals("Name 1", service.findById(1L).body().name);
        var result = service.update(1L, "Via proxy", precondition(1, 0));
        assertEquals(1L, result.body().id);
        assertEquals("Via proxy!", result.body().name);
        assertTrue(result.requirePersistedVersion() > 0);
    }

    @Test void concurrentUpdatesAgainstOneRevisionAllowExactlyOneWriter() throws Exception {
        try (var seed = factory.createEntityManager()) {
            seed.getTransaction().begin(); seed.persist(new Item(100, 1, "Concurrent")); seed.getTransaction().commit();
        }
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int index = 0; index < 2; index++) results.add(workers.submit(() -> {
                try (var writer = factory.createEntityManager()) {
                    writer.getTransaction().begin();
                    ItemService target = new ItemService(new JpaRepositoryFactory(writer).getRepository(ItemRepository.class));
                    ReflectionTestUtils.setField(target, "entityManager", writer);
                    assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    try {
                        target.update(100L, "Winner", precondition(100, 0));
                        writer.getTransaction().commit(); return 200;
                    } catch (ResourceVersionPreconditionException stale) {
                        writer.getTransaction().rollback(); return stale.status().value();
                    }
                }
            }));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var result : results) statuses.add(result.get(20, java.util.concurrent.TimeUnit.SECONDS));
            statuses.sort(Integer::compareTo); assertEquals(List.of(200, 412), statuses);
        } finally {
            try (var cleanup = factory.createEntityManager()) {
                cleanup.getTransaction().begin(); cleanup.remove(cleanup.find(Item.class, 100L)); cleanup.getTransaction().commit();
            }
        }
    }

    @Test void dirtyPersistenceContextIsRejectedBeforeVersionedReadOrAutoflush() {
        Item entity = em.find(Item.class, 1L); entity.name = "Unapproved"; sql.clear();
        assertThrows(IllegalStateException.class, () -> service.update(1L, "Requested", precondition(1, 0)));
        assertTrue(sql.isEmpty());
        em.clear(); assertEquals("Name 1", em.find(Item.class, 1L).name);
    }

    @Test void previousAndCurrentMutationReceivesAnIndependentPreviousScalarSnapshot() {
        var result = service.renameWithPrevious(1L, precondition(1, 0));
        assertEquals("Name 1 -> changed", result.body().name);
        assertEquals(1, result.requirePersistedVersion());
    }

    static ResourceVersionUpdatePrecondition<Long> precondition(long id, long version) {
        var etags = new ResourceVersionEtagService("p1-test-secret");
        return new ResourceVersionUpdatePrecondition<>(etags, etags.create("items", id, version), "items", id);
    }
    static int statusOf(Runnable run) { return assertThrows(ResponseStatusException.class, run::run).getStatusCode().value(); }
    static List<Long> ids(List<Response> rows) { return rows.stream().map(row -> row.id).toList(); }
    static List<Long> exportedIds(CollectionExportResult result) throws Exception {
        List<Long> ids = new ArrayList<>(); new ObjectMapper().readTree(result.content()).forEach(row -> ids.add(row.get("id").asLong())); return ids;
    }
    static CollectionExportRequest<Filter> request(CollectionExportScope scope, Filter filter, CollectionExportSelection selection,
            CollectionExportPage page, int max, List<CollectionExportField> fields) {
        return new CollectionExportRequest<>(null, null, null, CollectionExportFormat.JSON, scope, selection, fields,
                filter, "id,asc", page, null, true, false, max, null, null);
    }
    @Entity(name = "P1CollectionSortItem") public static class CollectionSortItem {
        @Id public Long id;
        @ElementCollection public List<String> labels;
    }
    @Embeddable public static class OneId implements Serializable {
        public int number;
        public OneId() { }
        OneId(int number) { this.number = number; }
        @Override public boolean equals(Object other) { return other instanceof OneId id && number == id.number; }
        @Override public int hashCode() { return number; }
    }
    @Entity(name = "P1OneIdItem") public static class OneIdItem { @EmbeddedId public OneId pk; }
    public interface OneIdRepository extends BaseCrudRepository<OneIdItem, OneId> { }
    static class OneIdService extends AbstractReadOnlyResourceService<OneIdItem, String, OneId, Filter> {
        OneIdService(OneIdRepository repository) { super(repository, OneIdItem.class); }
        @Override public boolean supportsCursorPagination() { return true; }
        protected ResourceMapper<OneIdItem, String, Object, Object, OneId> getResourceMapper() { return new ResourceMapper<>() {
            public String toResponse(OneIdItem entity) { return Integer.toString(entity.pk.number); }
            public OneIdItem newEntity(Object dto) { throw new UnsupportedOperationException(); }
            public void applyUpdate(OneIdItem entity, Object dto) { throw new UnsupportedOperationException(); }
            public OneId extractId(OneIdItem entity) { return entity.pk; }
        }; }
    }
    public interface IdClassRepository extends BaseCrudRepository<IdClassItem, ItemId> { }
    @Entity(name = "P1IdClassItem") @Table(name = "p1_idclass_item") @IdClass(ItemId.class)
    public static class IdClassItem {
        @Id public int partitionId;
        @Id public int sequence;
    }
    static class IdClassService extends AbstractReadOnlyResourceService<IdClassItem, String, ItemId, Filter> {
        IdClassService(IdClassRepository repository) { super(repository, IdClassItem.class); }
        @Override public boolean supportsCursorPagination() { return true; }
        @Override protected ResourceFilterAccessScope<IdClassItem> resolveResourceFilterAccessScope() {
            return ResourceFilterAccessScope.restricted((root, query, cb) -> cb.equal(root.get("partitionId"), 1));
        }
        protected ResourceMapper<IdClassItem, String, Object, Object, ItemId> getResourceMapper() { return new ResourceMapper<>() {
            public String toResponse(IdClassItem entity) { return entity.partitionId + ":" + entity.sequence; }
            public IdClassItem newEntity(Object dto) { throw new UnsupportedOperationException(); }
            public void applyUpdate(IdClassItem entity, Object dto) { throw new UnsupportedOperationException(); }
            public ItemId extractId(IdClassItem entity) { return new ItemId(entity.partitionId, entity.sequence); }
        }; }
    }
    public interface ItemRepository extends BaseCrudRepository<Item, Long> { }
    public interface CompositeRepository extends BaseCrudRepository<CompositeItem, ItemId> { }
    public static class Filter implements GenericFilterDTO { @Filterable(relation = "name") public String name; }
    @Entity(name = "P1Item") @Table(name = "p1_item") public static class Item {
        @Id public Long pk;
        public int tenant;
        public String name;
        @Version public Long revision;
        public Item() { }
        Item(long pk, int tenant, String name) { this.pk = pk; this.tenant = tenant; this.name = name; }
    }
    @Embeddable public static class ItemId implements Serializable {
        public int partitionId;
        public int sequence;
        public ItemId() { }
        ItemId(int partitionId, int sequence) { this.partitionId = partitionId; this.sequence = sequence; }
        @Override public boolean equals(Object other) { return other instanceof ItemId id && partitionId == id.partitionId && sequence == id.sequence; }
        @Override public int hashCode() { return Objects.hash(partitionId, sequence); }
    }
    @Entity(name = "P1CompositeItem") @Table(name = "p1_composite_item") public static class CompositeItem {
        @EmbeddedId public ItemId pk;
        public CompositeItem() { }
        CompositeItem(ItemId pk) { this.pk = pk; }
    }
    public static class Response {
        @UISchema(label = "Key", order = 1) public Long id;
        @UISchema(label = "Name", order = 2) public String name;
        @UISchema(label = "Date", order = 3) public LocalDate date;
        @UISchema(label = "Amount", order = 4, controlType = FieldControlType.CURRENCY_INPUT) public BigDecimal amount;
        @UISchema(label = "Active", order = 5, labelTrue = "Enabled", labelFalse = "Disabled") public boolean active;
        @UISchema(tableHidden = true) public String secondary;
        @UISchema @DomainGovernance(kind = DomainGovernanceKind.PRIVACY, classification = DomainClassification.RESTRICTED,
                dataCategory = DomainDataCategory.PERSONAL, exportable = false) public String secret;
        @UISchema @DomainGovernance(kind = DomainGovernanceKind.SECURITY, classification = DomainClassification.RESTRICTED,
                dataCategory = DomainDataCategory.PERSONAL, complianceTags = "NON_EXPORTABLE") public String tagged;
        public boolean enriched;
    }
    static final ResourceMapper<Item, Response, String, String, Long> MAPPER = new ResourceMapper<>() {
        public Response toResponse(Item entity) { Response dto = new Response(); dto.id = entity.pk; dto.name = entity.name; return dto; }
        public Item newEntity(String dto) { return new Item(10, 1, dto); }
        public void applyUpdate(Item entity, String dto) { entity.name = dto; }
        public Long extractId(Item entity) { return entity.pk; }
    };
    public static class ItemService extends AbstractBaseResourceService<Item, Response, Long, Filter, String, String>
            implements VersionedCreateUpdateResourceService<Response, Long, Filter, String, String> {
        int tenant = 1;
        boolean overrideFields;
        boolean blankExportPath;
        boolean guardOnly;
        StatsFieldRegistry stats = StatsFieldRegistry.empty();
        List<Integer> batches = new ArrayList<>();
        List<Long> deleted = new ArrayList<>();
        ItemService(ItemRepository repository) { super(repository, Item.class); }
        protected ResourceMapper<Item, Response, String, String, Long> getResourceMapper() { return MAPPER; }
        @Override protected String resolveResourceKey() { return "items"; }
        @Override public Sort getDefaultSort() { return Sort.by("pk"); }
        @Override public boolean supportsCursorPagination() { return true; }
        @Override protected ResourceFilterAccessScope<Item> resolveResourceFilterAccessScope() {
            if (guardOnly) return ResourceFilterAccessScope.unrestricted();
            return ResourceFilterAccessScope.restricted((root, query, cb) -> cb.equal(root.get("tenant"), tenant));
        }
        @Override protected void assertEntityAccessAllowed(Item entity) {
            if (entity.tenant != tenant) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        @Override protected void enrichResponses(List<Response> responses) { batches.add(responses.size()); responses.forEach(row -> row.enriched = true); }
        @Override protected void afterUpdate(Long id, Item entity, String dto) { entity.name += "!"; }
        @Override protected void beforeDelete(Long id, Item entity) { deleted.add(id); }
        @Override protected List<CollectionExportField> exportFields() {
            if (blankExportPath) return List.of(new CollectionExportField("id", "Key", true, true, "number", ""));
            if (!overrideFields) return super.exportFields();
            List<CollectionExportField> fields = new ArrayList<>(defaultExportFields());
            for (String key : List.of("secret", "tagged")) fields.add(new CollectionExportField(key, key, true, true, "string", key));
            return fields;
        }
        @Override protected Optional<ResourceStateSnapshot> toResourceStateSnapshot(Item entity) { return Optional.of(ResourceStateSnapshot.of("OPEN")); }
        @Override public StatsFieldRegistry getStatsFieldRegistry() { return stats; }
        ResourceRepresentationResult<Response> rename(Long id, ResourceVersionUpdatePrecondition<Long> precondition) {
            return executeVersionedMutation(id, precondition, entity -> entity.name = "Command");
        }
        ResourceRepresentationResult<Response> renameWithPrevious(Long id, ResourceVersionUpdatePrecondition<Long> precondition) {
            return executeVersionedMutation(id, precondition, (previous, current) -> {
                current.name = "changed";
                assertEquals("Name 1", previous.name);
                current.name = previous.name + " -> " + current.name;
            });
        }
    }
    static class ReadOnlyService extends AbstractReadOnlyResourceService<Item, Response, Long, Filter> {
        ReadOnlyService(ItemRepository repository) { super(repository, Item.class); }
        protected ResourceMapper<Item, Response, ?, ?, Long> getResourceMapper() { return MAPPER; }
        @Override protected void enrichResponses(List<Response> responses) { responses.forEach(row -> row.enriched = true); }
    }
    static class CompositeService extends AbstractReadOnlyResourceService<CompositeItem, String, ItemId, Filter> {
        CompositeService(CompositeRepository repository) { super(repository, CompositeItem.class); }
        @Override public boolean supportsCursorPagination() { return true; }
        protected ResourceMapper<CompositeItem, String, Object, Object, ItemId> getResourceMapper() { return new ResourceMapper<>() {
            public String toResponse(CompositeItem entity) { return entity.pk.partitionId + ":" + entity.pk.sequence; }
            public CompositeItem newEntity(Object dto) { throw new UnsupportedOperationException(); }
            public void applyUpdate(CompositeItem entity, Object dto) { throw new UnsupportedOperationException(); }
            public ItemId extractId(CompositeItem entity) { return entity.pk; }
        }; }
    }
}
