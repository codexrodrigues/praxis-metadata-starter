package org.praxisplatform.uischema.filter.specification;

import jakarta.persistence.*;
import java.util.List;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.springframework.data.domain.*;
import static org.assertj.core.api.Assertions.*;

/** Executes inherited criteria in SQL, including aliases, nulls and shadowing. */
class InheritedFilterJpaTest {
    static StandardServiceRegistry registry;
    static EntityManagerFactory factory;

    @BeforeAll static void start() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:inherited-filter;DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
        factory = new MetadataSources(registry).addAnnotatedClass(CatalogItem.class).buildMetadata().buildSessionFactory();
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new CatalogItem(1L, "A", 1, "Alpha", true));
            em.persist(new CatalogItem(2L, "A", 2, "Alpha", true));
            em.persist(new CatalogItem(3L, "B", 2, "Alpha", true));
            em.persist(new CatalogItem(4L, "A", 2, "Beta", false));
            em.getTransaction().commit();
        }
    }

    @AfterAll static void stop() {
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test void inheritedCriteriaConstrainRowsAcrossThreeLevels() {
        var filter = new CatalogFilter(); filter.origin = "A"; filter.organization = 2; filter.label = "Alpha";
        assertThat(ids(filter)).containsExactly(2L);
        filter.origin = "B";
        assertThat(ids(filter)).containsExactly(3L);
        filter.organization = 1;
        assertThat(ids(filter)).isEmpty();
    }

    @Test void nullCriteriaRemainOptionalAndFalseIsNotIgnored() {
        var filter = new CatalogFilter(); filter.enabled = false;
        assertThat(ids(filter)).containsExactly(4L);
        filter.enabled = null;
        assertThat(ids(filter)).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test void inheritedAliasesRetainDirectionForPagedAndUnpagedQueries() {
        var builder = new GenericSpecificationsBuilder<CatalogItem>();
        var filter = new CatalogFilter();
        for (Pageable requested : List.of(PageRequest.of(2, 10, Sort.by(Sort.Direction.DESC, "organization")),
                Pageable.unpaged(Sort.by(Sort.Direction.DESC, "organization")))) {
            var actual = builder.buildSpecification(filter, requested).pageable();
            assertThat(actual.isPaged()).isEqualTo(requested.isPaged());
            assertThat(actual.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "orgCode"));
            if (requested.isPaged()) assertThat(actual.getPageNumber()).isEqualTo(2);
        }
    }

    @Test void annotatedChildDeclarationOverridesParentCriterionAndSort() {
        var filter = new ShadowedFilter(); filter.criterion = "Beta";
        ((ParentWithCriterion) filter).criterion = "does-not-exist";
        assertThat(ids(filter)).containsExactly(4L);
        var page = new GenericSpecificationsBuilder<CatalogItem>()
                .buildSpecification(filter, PageRequest.of(0, 10, Sort.by("criterion"))).pageable();
        assertThat(page.getSort()).isEqualTo(Sort.by("name"));
    }

    @Test void unannotatedChildAndStaticFieldsDoNotExecuteHiddenParentCriteria() {
        var filter = new HiddenFilter(); filter.criterion = "unused";
        ((ParentWithCriterion) filter).criterion = "does-not-exist";
        assertThat(ids(filter)).containsExactly(1L, 2L, 3L, 4L);
    }

    static List<Long> ids(GenericFilterDTO filter) {
        try (var em = factory.createEntityManager()) {
            var cb = em.getCriteriaBuilder(); var query = cb.createQuery(Long.class); var root = query.from(CatalogItem.class);
            var spec = new GenericSpecificationsBuilder<CatalogItem>().buildSpecification(filter, Pageable.unpaged()).spec();
            query.select(root.get("id")).where(spec.toPredicate(root, query, cb)).orderBy(cb.asc(root.get("id")));
            return em.createQuery(query).getResultList();
        }
    }

    static class OriginFilter implements GenericFilterDTO {
        @Filterable(operation = FilterOperation.EQUAL, relation = "origin") String origin;
    }
    static class ScopedFilter extends OriginFilter {
        @Filterable(operation = FilterOperation.EQUAL, relation = "orgCode") Integer organization;
        @Filterable(operation = FilterOperation.EQUAL, relation = "name") String label;
    }
    static class CatalogFilter extends ScopedFilter {
        @Filterable(operation = FilterOperation.EQUAL, relation = "enabled") Boolean enabled;
        @Filterable(operation = FilterOperation.EQUAL, relation = "nonexistent") static String neverCriterion = "poison";
    }
    static class ParentWithCriterion implements GenericFilterDTO {
        @Filterable(operation = FilterOperation.EQUAL, relation = "origin") String criterion;
    }
    static class ShadowedFilter extends ParentWithCriterion {
        @Filterable(operation = FilterOperation.EQUAL, relation = "name") String criterion;
    }
    static class HiddenFilter extends ParentWithCriterion {
        String criterion;
        @Filterable(operation = FilterOperation.EQUAL, relation = "nonexistent") static String neverCriterion = "poison";
    }
    @Entity(name = "InheritedFilterCatalogItem") @Table(name = "inherited_filter_item")
    static class CatalogItem {
        @Id Long id;
        String origin;
        Integer orgCode;
        String name;
        Boolean enabled;
        CatalogItem() { }
        CatalogItem(Long id, String origin, int orgCode, String name, boolean enabled) {
            this.id = id; this.origin = origin; this.orgCode = orgCode; this.name = name; this.enabled = enabled;
        }
    }
}
