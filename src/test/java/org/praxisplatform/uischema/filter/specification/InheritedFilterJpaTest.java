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
        factory = new MetadataSources(registry)
                .addAnnotatedClass(CatalogItem.class)
                .addAnnotatedClass(FilterDepartment.class)
                .addAnnotatedClass(FilterDependent.class)
                .buildMetadata().buildSessionFactory();
        try (var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            var hr = new FilterDepartment(10L, "Recursos Humanos", "OPERACIONAL");
            var tech = new FilterDepartment(20L, "Engenharia", "ESTRATEGICO");
            em.persist(hr);
            em.persist(tech);

            var item1 = new CatalogItem(1L, "A", 1, "Alpha", true, java.time.LocalDate.of(2024, 1, 15), hr);
            var item2 = new CatalogItem(2L, "A", 2, "Alpha", true, java.time.LocalDate.of(2024, 2, 20), hr);
            item2.dependents.add(new FilterDependent(101L, "Maria Silva", item2));
            item2.dependents.add(new FilterDependent(102L, "Joao Silva", item2));
            var item3 = new CatalogItem(3L, "B", 2, "Alpha", true, java.time.LocalDate.of(2024, 3, 10), tech);
            item3.dependents.add(new FilterDependent(103L, "Pedro Souza", item3));
            var item4 = new CatalogItem(4L, "A", 2, "Beta", false, java.time.LocalDate.of(2024, 4, 5), tech);

            em.persist(item1);
            em.persist(item2);
            em.persist(item3);
            em.persist(item4);
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

    @Test void singleRelationsShareLeftJoinAndOmniboxUsesCorrelatedExistsWithoutDistinct() {
        var filter = new RelationalPerformanceFilter();
        filter.departmentName = "Recursos";
        filter.departmentType = "OPERACIONAL";
        filter.omnibox = "Silva";
        filter.referenceDateBetween = List.of(java.time.LocalDate.of(2024, 1, 1), java.time.LocalDate.of(2024, 3, 1));
        filter.referenceDateOn = java.time.LocalDate.of(2024, 2, 20);

        try (var em = factory.createEntityManager()) {
            var cb = em.getCriteriaBuilder();
            var query = cb.createQuery(Long.class);
            var root = query.from(CatalogItem.class);
            var spec = new GenericSpecificationsBuilder<CatalogItem>().buildSpecification(filter, Pageable.unpaged()).spec();
            query.select(root.get("id")).where(spec.toPredicate(root, query, cb)).orderBy(cb.asc(root.get("id")));

            // 1. Multiple @Filterable(relation = "department.*") + omnibox "department.name" must share a SINGLE LEFT JOIN!
            assertThat(root.getJoins()).hasSize(1);
            assertThat(root.getJoins().iterator().next().getAttribute().getName()).isEqualTo("department");
            // 2. Collection path ("dependents.fullName") must use correlated EXISTS subquery, NOT a root join and NOT SELECT DISTINCT!
            assertThat(query.isDistinct()).isFalse();
            // 3. Even though item 2 has TWO dependents matching "Silva", it is returned once without SELECT DISTINCT!
            assertThat(em.createQuery(query).getResultList()).containsExactly(2L);
        }
    }

    static List<Long> ids(GenericFilterDTO filter) {
        try (var em = factory.createEntityManager()) {
            var cb = em.getCriteriaBuilder(); var query = cb.createQuery(Long.class); var root = query.from(CatalogItem.class);
            var spec = new GenericSpecificationsBuilder<CatalogItem>().buildSpecification(filter, Pageable.unpaged()).spec();
            query.select(root.get("id")).where(spec.toPredicate(root, query, cb)).orderBy(cb.asc(root.get("id")));
            return em.createQuery(query).getResultList();
        }
    }

    static class RelationalPerformanceFilter implements GenericFilterDTO {
        @Filterable(operation = FilterOperation.LIKE, relation = "department.name")
        String departmentName;

        @Filterable(operation = FilterOperation.EQUAL, relation = "department.sectorType")
        String departmentType;

        @Filterable(
                operation = FilterOperation.LIKE,
                relations = {"id", "name", "department.name", "dependents.fullName"}
        )
        String omnibox;

        @Filterable(operation = FilterOperation.BETWEEN, relation = "referenceDate")
        List<java.time.LocalDate> referenceDateBetween;

        @Filterable(operation = FilterOperation.ON_DATE, relation = "referenceDate")
        java.time.LocalDate referenceDateOn;
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
    @Entity(name = "InheritedFilterDepartment") @Table(name = "inherited_filter_department")
    static class FilterDepartment {
        @Id Long id;
        String name;
        String sectorType;
        FilterDepartment() { }
        FilterDepartment(Long id, String name, String sectorType) {
            this.id = id; this.name = name; this.sectorType = sectorType;
        }
    }
    @Entity(name = "InheritedFilterDependent") @Table(name = "inherited_filter_dependent")
    static class FilterDependent {
        @Id Long id;
        String fullName;
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id")
        CatalogItem item;
        FilterDependent() { }
        FilterDependent(Long id, String fullName, CatalogItem item) {
            this.id = id; this.fullName = fullName; this.item = item;
        }
    }
    @Entity(name = "InheritedFilterCatalogItem") @Table(name = "inherited_filter_item")
    static class CatalogItem {
        @Id Long id;
        String origin;
        Integer orgCode;
        String name;
        Boolean enabled;
        java.time.LocalDate referenceDate;
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "department_id")
        FilterDepartment department;
        @OneToMany(mappedBy = "item", cascade = CascadeType.ALL)
        List<FilterDependent> dependents = new java.util.ArrayList<>();
        CatalogItem() { }
        CatalogItem(Long id, String origin, int orgCode, String name, boolean enabled,
                    java.time.LocalDate referenceDate, FilterDepartment department) {
            this.id = id; this.origin = origin; this.orgCode = orgCode; this.name = name; this.enabled = enabled;
            this.referenceDate = referenceDate; this.department = department;
        }
    }
}
