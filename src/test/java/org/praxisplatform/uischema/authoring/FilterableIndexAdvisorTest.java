package org.praxisplatform.uischema.authoring;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterableIndexAdvisorTest {

    @Entity
    @Table(name = "departments")
    static class DepartmentEntity {
        @Id
        private Long id;

        @Column(name = "name")
        private String name;
    }

    @Entity
    @Table(name = "employee_dependents")
    static class DependentEntity {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "employee_id")
        private EmployeeEntity employee;

        @Column(name = "full_name")
        private String fullName;
    }

    @Entity
    @Table(name = "secret_identities")
    static class SecretIdentityEntity {
        @Id
        private Long id;

        @OneToOne
        @JoinColumn(name = "employee_id")
        private EmployeeEntity employee;

        @Column(name = "codename")
        private String codename;
    }

    @Embeddable
    static class AddressEmbeddable {
        @Column(name = "zip_code")
        private String zipCode;
    }

    @Embeddable
    static class ContactEmbeddable {
        @Embedded
        private AddressEmbeddable address;
    }

    @Entity
    @Table(
            name = "employees",
            indexes = {
                    @Index(name = "idx_employees_status_hire", columnList = "status, hire_date DESC")
            }
    )
    static class EmployeeEntity {
        @Id
        private Long id;

        @Column(name = "full_name")
        private String fullName;

        @Column(name = "tax_id", unique = true)
        private String taxId;

        @Column(name = "badge_number")
        private Integer badgeNumber;

        @Column(name = "status")
        private String status;

        @Column(name = "hire_date")
        private LocalDate hireDate;

        @Column(name = "salary")
        private BigDecimal salary;

        @ManyToOne
        @JoinColumn(name = "department_id")
        private DepartmentEntity department;

        @OneToMany(mappedBy = "employee")
        private List<DependentEntity> dependents;

        @OneToOne(mappedBy = "employee")
        private SecretIdentityEntity secretIdentity;

        @Embedded
        @AttributeOverride(name = "address.zipCode", column = @Column(name = "postal_code"))
        private ContactEmbeddable contact;
    }

    static class EmployeeFilterDTO implements GenericFilterDTO {

        @Filterable(
                operation = FilterOperation.LIKE,
                relations = {"fullName", "taxId", "badgeNumber", "department.name", "dependents.fullName"}
        )
        private String buscaAmpla;

        @Filterable(operation = FilterOperation.EQUAL)
        private String status;

        @Filterable(operation = FilterOperation.BETWEEN, relation = "hireDate")
        private List<LocalDate> hireDateBetween;

        @Filterable(operation = FilterOperation.IN, relation = "department.id")
        private List<Long> departmentIds;
    }

    static class AdvancedEdgeCasesFilterDTO extends EmployeeFilterDTO {
        // Redeclared without @Filterable: shadows superclass status filter
        private String status;

        @Filterable(operation = FilterOperation.ENDS_WITH, relation = "secretIdentity.codename")
        private String codenameSuffix;

        @Filterable(operation = FilterOperation.STARTS_WITH, relation = "contact.address.zipCode")
        private String postalCodePrefix;
    }

    record RecordEmployeeFilterDTO(
            @Filterable(operation = FilterOperation.EQUAL) String status
    ) implements GenericFilterDTO {
    }

    @Test
    void derivesBtreeAndGinTrigramRecommendationsAcrossEntityAssociationsAndStats() {
        StatsFieldRegistry stats = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("status", "status")
                .temporalTimeSeriesField("hireDate", "hireDate")
                .numericMeasureField("salary", "salary")
                .build();

        FilterableIndexAdvisor advisor = new FilterableIndexAdvisor();
        FilterableIndexAdviceReport report = advisor.analyze(EmployeeEntity.class, EmployeeFilterDTO.class, stats);

        assertTrue(report.unresolvedPropertyPaths().isEmpty(), "All filterable paths should resolve cleanly");

        // 4 String columns in buscaAmpla should produce GIN_TRGM recommendations
        List<FilterableIndexRecommendation> ginRecs = report.ginTrigramRecommendations();
        assertEquals(4, ginRecs.size());
        assertEquals(
                List.of("full_name", "tax_id", "name", "full_name"),
                ginRecs.stream().map(FilterableIndexRecommendation::columnName).toList()
        );

        // Numeric badgeNumber in buscaAmpla must NOT get GIN_TRGM; it gets BTREE
        FilterableIndexRecommendation badgeRec = report.recommendations().stream()
                .filter(r -> "badge_number".equals(r.columnName()))
                .findFirst()
                .orElseThrow();
        assertEquals(FilterableIndexKind.BTREE, badgeRec.indexKind());
        assertFalse(badgeRec.coveredByJpaMetadata());

        // status is covered by leading column of idx_employees_status_hire
        FilterableIndexRecommendation statusRec = report.recommendations().stream()
                .filter(r -> "employees".equals(r.tableName()) && "status".equals(r.columnName()))
                .findFirst()
                .orElseThrow();
        assertTrue(statusRec.coveredByJpaMetadata(), "status should be recognized as covered by @Table(indexes)");

        // hire_date is second column in composite index, so not leading -> uncovered for standalone range scan
        FilterableIndexRecommendation hireDateRec = report.recommendations().stream()
                .filter(r -> "employees".equals(r.tableName()) && "hire_date".equals(r.columnName()))
                .findFirst()
                .orElseThrow();
        assertEquals(FilterableIndexKind.BTREE_RANGE, hireDateRec.indexKind());
        assertFalse(hireDateRec.coveredByJpaMetadata());

        // FK join column department_id on employees and EXISTS correlation FK employee_id on employee_dependents
        assertTrue(report.recommendations().stream()
                .anyMatch(r -> "employees".equals(r.tableName())
                        && "department_id".equals(r.columnName())
                        && r.indexKind() == FilterableIndexKind.BTREE));
        assertTrue(report.recommendations().stream()
                .anyMatch(r -> "employee_dependents".equals(r.tableName())
                        && "employee_id".equals(r.columnName())
                        && r.indexKind() == FilterableIndexKind.BTREE));

        String sql = report.renderPostgresMigrationSql();
        assertTrue(sql.contains("CREATE EXTENSION IF NOT EXISTS pg_trgm;"));
        assertTrue(sql.contains("USING gin (LOWER(full_name) gin_trgm_ops);"));
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_employees_department_id ON employees (department_id);"));
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_employee_dependents_employee_id ON employee_dependents (employee_id);"));

        String jpaSnippet = report.renderMissingJpaIndexesSnippet();
        assertTrue(jpaSnippet.contains("@Index(name = \"idx_employees_department_id\", columnList = \"department_id\")"));
        assertFalse(jpaSnippet.contains("columnList = \"status\""));
    }

    @Test
    void handlesInverseOneToOneNestedEmbeddedOverridesEndsWithGinAndSubclassShadowing() {
        FilterableIndexAdvisor advisor = new FilterableIndexAdvisor();
        FilterableIndexAdviceReport report = advisor.analyze(EmployeeEntity.class, AdvancedEdgeCasesFilterDTO.class);

        assertTrue(report.unresolvedPropertyPaths().isEmpty(), () -> "Unresolved paths: " + report.unresolvedPropertyPaths());

        // 1. Subclass redeclared 'status' without @Filterable -> must NOT appear in recommendations
        assertFalse(
                report.recommendations().stream().anyMatch(r -> "status".equals(r.columnName())),
                "Shadowed field without @Filterable must not inherit superclass @Filterable"
        );

        // 2. Inverse @OneToOne(mappedBy = "employee") must register FK on secret_identities.employee_id, NOT on employees
        assertTrue(
                report.recommendations().stream().anyMatch(r ->
                        "secret_identities".equals(r.tableName())
                                && "employee_id".equals(r.columnName())
                                && r.indexKind() == FilterableIndexKind.BTREE),
                "Inverse @OneToOne FK must be recommended on the owning table (secret_identities.employee_id)"
        );
        assertFalse(
                report.recommendations().stream().anyMatch(r ->
                        "employees".equals(r.tableName()) && "secret_identity_id".equals(r.columnName())),
                "Inverse @OneToOne must not invent a non-existent FK column on the inverse table"
        );

        // 3. ENDS_WITH on textual column requires GIN_TRGM (leading wildcard '%suffix' cannot use B-Tree)
        assertTrue(
                report.ginTrigramRecommendations().stream().anyMatch(r ->
                        "secret_identities".equals(r.tableName()) && "codename".equals(r.columnName())),
                "ENDS_WITH on String must recommend GIN_TRGM"
        );

        // 4. Nested @Embedded with @AttributeOverride("address.zipCode" -> "postal_code") + STARTS_WITH -> BTREE_PATTERN
        FilterableIndexRecommendation zipRec = report.recommendations().stream()
                .filter(r -> "employees".equals(r.tableName()) && "postal_code".equals(r.columnName()))
                .findFirst()
                .orElseThrow();
        assertEquals(FilterableIndexKind.BTREE_PATTERN, zipRec.indexKind());
        assertEquals(
                "CREATE INDEX IF NOT EXISTS idx_employees_postal_code ON employees (LOWER(postal_code) varchar_pattern_ops);",
                zipRec.postgresDdl()
        );

        // 5. Record FilterDTO support
        FilterableIndexAdviceReport recordReport = advisor.analyze(EmployeeEntity.class, RecordEmployeeFilterDTO.class);
        assertEquals(1, recordReport.recommendations().size());
        assertEquals("status", recordReport.recommendations().get(0).columnName());
        assertEquals(Set.of("status"), Set.copyOf(recordReport.recommendations().get(0).sourceFilterProperties()));
    }

    @Embeddable
    static class CompositeTenureId {
        @Column(name = "numfunc")
        private Long numfunc;

        @Column(name = "numero")
        private Integer numero;
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Formula {
        String value();
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Immutable {
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Subselect {
        String value();
    }

    @Entity
    @Immutable
    @Subselect("select codigo, nome from vw_setores_correntes")
    static class SectorViewEntity {
        @Id
        private String codigo;

        @Column(name = "nome")
        private String nome;
    }

    @Entity
    @Table(name = "tenures")
    static class TenureEntity {
        @jakarta.persistence.EmbeddedId
        private CompositeTenureId id;

        @Column(name = "categoria")
        private String categoria;

        @Formula("case when categoria is null then 'INATIVO' else 'VIGENTE' end")
        private String situacaoLookup;

        @ManyToOne
        @JoinColumn(name = "setor_id")
        private SectorViewEntity setor;
    }

    static class TenureFilterDTO implements GenericFilterDTO {
        @Filterable(operation = FilterOperation.EQUAL, relation = "id.numfunc")
        private Long numfunc;

        @Filterable(operation = FilterOperation.EQUAL, relation = "id.numero")
        private Integer numero;

        @Filterable(operation = FilterOperation.EQUAL)
        private String situacaoLookup;

        @Filterable(operation = FilterOperation.EQUAL, relation = "setor.nome")
        private String setorNome;

        @Filterable(operation = FilterOperation.EQUAL, relation = "setor.inexistente")
        private String setorInvalido;
    }

    @Entity
    @Table(name = "assignments")
    static class AssignmentEntity {
        @Id
        private Long id;

        @Embedded
        @jakarta.persistence.AttributeOverrides({
                @AttributeOverride(name = "numfunc", column = @Column(name = "titular_numfunc")),
                @AttributeOverride(name = "numero", column = @Column(name = "titular_numvinc"))
        })
        private CompositeTenureId titularId;

        @ManyToOne
        @jakarta.persistence.JoinColumns({
                @JoinColumn(name = "titular_numfunc", referencedColumnName = "numfunc", insertable = false, updatable = false),
                @JoinColumn(name = "titular_numvinc", referencedColumnName = "numero", insertable = false, updatable = false)
        })
        private TenureEntity titular;
    }

    static class AssignmentFilterDTO implements GenericFilterDTO {
        @Filterable(operation = FilterOperation.EQUAL)
        private CompositeTenureId titularId;

        @Filterable(operation = FilterOperation.IN, relation = "titular.categoria")
        private List<String> categoriasTitular;
    }

    @Entity
    @Table(
            name = "broken_catalog",
            indexes = {
                    @Index(name = "idx_broken_catalog_code", columnList = "code"),
                    @Index(name = "idx_broken_catalog_ghost", columnList = "collection_fk_only")
            }
    )
    static class BrokenIndexedEntity {
        @Id
        @Column(name = "code")
        private String code;

        @org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn(priority = 2)
        @Column(name = "secondary_sort")
        private Integer secondarySort;

        @org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn(priority = 1)
        @Column(name = "sort_order")
        private Integer sortOrder;
    }

    static class BrokenIndexedFilterDTO implements GenericFilterDTO {
        @Filterable(operation = FilterOperation.EQUAL)
        private String code;
    }

    @Test
    void resolvesCompositeJoinColumnsAndEmbeddedValueObjectLeafFilters() {
        FilterableIndexAdvisor advisor = new FilterableIndexAdvisor();
        FilterableIndexAdviceReport report = advisor.analyze(AssignmentEntity.class, AssignmentFilterDTO.class);

        assertTrue(report.unresolvedPropertyPaths().isEmpty(), () -> "Unresolved paths: " + report.unresolvedPropertyPaths());
        assertTrue(
                report.recommendations().stream().anyMatch(r ->
                        "assignments".equals(r.tableName())
                                && "titular_numfunc".equals(r.columnName())
                                && r.indexKind() == FilterableIndexKind.BTREE),
                "Both @Embedded leaf filter and @JoinColumns association should resolve leading column titular_numfunc"
        );
        assertFalse(
                report.recommendations().stream().anyMatch(r -> "titular_id".equals(r.columnName())),
                "Must not synthesize titular_id when @JoinColumns is declared"
        );

        FilterableIndexAdviceReport tenureReport = advisor.analyze(TenureEntity.class, TenureFilterDTO.class);
        assertEquals(List.of("setor.inexistente"), tenureReport.unresolvedPropertyPaths());
        assertFalse(
                tenureReport.recommendations().stream().anyMatch(r -> "situacao_lookup".equals(r.columnName())),
                "Virtual @Formula fields must resolve cleanly without recommending a physical @Index"
        );
        assertFalse(
                tenureReport.recommendations().stream().anyMatch(r -> "SectorViewEntity".equals(r.targetEntityName())),
                "@Immutable/@Subselect view entities must not emit physical @Index recommendations"
        );
        assertTrue(
                tenureReport.recommendations().stream().anyMatch(r -> "tenures".equals(r.tableName()) && "setor_id".equals(r.columnName())),
                "Foreign key column on the physical owning table (tenures.setor_id) must still be recommended"
        );
        FilterableIndexRecommendation numfuncRec = tenureReport.recommendations().stream()
                .filter(r -> "numfunc".equals(r.columnName()))
                .findFirst()
                .orElseThrow();
        assertTrue(numfuncRec.coveredByJpaMetadata(), "Leading column of @EmbeddedId PK (numfunc) must be recognized as covered");
        FilterableIndexRecommendation numeroRec = tenureReport.recommendations().stream()
                .filter(r -> "numero".equals(r.columnName()))
                .findFirst()
                .orElseThrow();
        assertFalse(numeroRec.coveredByJpaMetadata(), "Second column of @EmbeddedId PK (numero) is not leading and should be recommended for standalone equality filter");

        FilterableIndexAdviceReport brokenIndexReport = advisor.analyze(BrokenIndexedEntity.class, BrokenIndexedFilterDTO.class);
        assertEquals(
                List.of("@Table(indexes) -> BrokenIndexedEntity.collection_fk_only"),
                brokenIndexReport.unresolvedPropertyPaths(),
                "Non-existent physical columns referenced in @Table(indexes) must be flagged as unresolved"
        );
        assertEquals(
                List.of("sort_order", "secondary_sort"),
                brokenIndexReport.unindexedBtreeRecommendations().stream()
                        .map(FilterableIndexRecommendation::columnName)
                        .toList(),
                "@DefaultSortColumn on entity must be recommended for B-Tree indexing in ascending priority order"
        );
    }
}
