package org.praxisplatform.uischema.options.service.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.annotation.DomainLineageTransformation;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceReference;
import org.praxisplatform.uischema.annotation.ResourceState;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupCapabilities;
import org.praxisplatform.uischema.options.LookupDetailDescriptor;
import org.praxisplatform.uischema.options.LookupDisplayDescriptor;
import org.praxisplatform.uischema.options.LookupDisplayFieldDescriptor;
import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.LookupFilteringDescriptor;
import org.praxisplatform.uischema.options.LookupSearchStrategyDefinition;
import org.praxisplatform.uischema.options.LookupSelectionPolicy;
import org.praxisplatform.uischema.options.LookupSortOption;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JpaEntityLookupGapJ1J2J3J4IntegrationTest {

    @Entity(name = "GapTestDepartment")
    static class DepartmentEntity {
        @Id
        Long id;
        String code;
        String name;

        DepartmentEntity() {}

        DepartmentEntity(Long id, String code, String name) {
            this.id = id;
            this.code = code;
            this.name = name;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getName() { return name; }
    }

    @Entity(name = "GapTestEmployee")
    static class EmployeeEntity {
        @Id
        Long id;

        String fullName;

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_DATA)
        String personalName;

        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID)
        String taxId;

        @DomainGovernance(preset = DomainGovernancePreset.SECURITY_CREDENTIAL)
        String apiSecret;

        EmployeeEntity() {}

        EmployeeEntity(Long id, String fullName, String taxId, String apiSecret) {
            this.id = id;
            this.fullName = fullName;
            this.personalName = fullName;
            this.taxId = taxId;
            this.apiSecret = apiSecret;
        }

        public Long getId() { return id; }
        public String getFullName() { return fullName; }
        public String getPersonalName() { return personalName; }
        public String getTaxId() { return taxId; }
        public String getApiSecret() { return apiSecret; }
    }

    @Entity(name = "GapTestAssignment")
    static class AssignmentEntity {
        @Id
        Long id;
        String internalCode;
        String title;
        String status;

        @ManyToOne
        @JoinColumn(name = "employee_id")
        EmployeeEntity employee;

        @ManyToOne
        @JoinColumn(name = "department_id")
        DepartmentEntity primaryDepartment;

        @ManyToOne
        @JoinColumn(name = "assigned_department_id")
        DepartmentEntity assignedDepartment;

        AssignmentEntity() {}

        AssignmentEntity(Long id, String internalCode, String title, String status,
                         EmployeeEntity employee, DepartmentEntity primaryDepartment,
                         DepartmentEntity assignedDepartment) {
            this.id = id;
            this.internalCode = internalCode;
            this.title = title;
            this.status = status;
            this.employee = employee;
            this.primaryDepartment = primaryDepartment;
            this.assignedDepartment = assignedDepartment;
        }

        public Long getId() { return id; }
        public String getInternalCode() { return internalCode; }
        public String getTitle() { return title; }
        public String getStatus() { return status; }
        public EmployeeEntity getEmployee() { return employee; }
        public DepartmentEntity getPrimaryDepartment() { return primaryDepartment; }
        public DepartmentEntity getAssignedDepartment() { return assignedDepartment; }
    }

    @ResourceIdentity(
            keyField = "id",
            titleField = "employeeName",
            subtitleField = "businessCode",
            metadataFields = {"departmentName", "assignedDepartmentName"}
    )
    static class AssignmentLookupDto {
        private Long id;

        @UISchema(label = "Code", icon = "badge", preset = UISchemaPreset.ENTERPRISE_CODE)
        @DomainLineage(sourceClass = AssignmentEntity.class, sourcePaths = {"internalCode"}, transformation = DomainLineageTransformation.ALIAS)
        private String businessCode;

        private Long employeeId;

        @UISchema(label = "Employee", icon = "person")
        @ResourceReference(resourceKey = "workforce.employees", valueField = "employeeId", surfaceId = "detail")
        @DomainLineage(sourceClass = EmployeeEntity.class, sourcePaths = {"fullName"})
        private String employeeName;

        private Long departmentId;

        @UISchema(label = "Primary Department", icon = "apartment")
        @ResourceReference(resourceKey = "workforce.departments", valueField = "departmentId", surfaceId = "detail")
        @DomainLineage(sourceClass = DepartmentEntity.class, sourcePaths = {"name"})
        private String departmentName;

        private Long assignedDepartmentId;

        @UISchema(label = "Assigned Department", icon = "swap_horiz")
        @ResourceReference(resourceKey = "workforce.departments", valueField = "assignedDepartmentId", surfaceId = "detail")
        @DomainLineage(sourceClass = Void.class, sourceResourceKey = "workforce.departments", sourcePaths = {"name"})
        private String assignedDepartmentName;

        @ResourceState
        @UISchema(label = "Status", icon = "verified")
        @DomainLineage(sourceClass = AssignmentEntity.class, sourcePaths = {"status"})
        private String operationalStatus;
    }

    static class AssignmentFilterDto implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "primaryDepartment.code")
        @UISchema(label = "Department Code")
        private String primaryDeptCode;

        @Filterable(operation = Filterable.FilterOperation.LIKE, relation = "title")
        @UISchema(label = "Title")
        private String titleSearch;
    }

    @Entity(name = "GapTestUnannotatedEmployee")
    static class UnannotatedEmployeeEntity {
        @Id
        Long id;
        String code;
        String fullName;
        String taxId;
        String apiSecret;
        String tacticalNote;
        String sensitiveTag;
        String restrictedCode;

        UnannotatedEmployeeEntity() {}

        UnannotatedEmployeeEntity(
                Long id,
                String code,
                String fullName,
                String taxId,
                String apiSecret,
                String tacticalNote,
                String sensitiveTag,
                String restrictedCode
        ) {
            this.id = id;
            this.code = code;
            this.fullName = fullName;
            this.taxId = taxId;
            this.apiSecret = apiSecret;
            this.tacticalNote = tacticalNote;
            this.sensitiveTag = sensitiveTag;
            this.restrictedCode = restrictedCode;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getFullName() { return fullName; }
        public String getTaxId() { return taxId; }
        public String getApiSecret() { return apiSecret; }
        public String getTacticalNote() { return tacticalNote; }
        public String getSensitiveTag() { return sensitiveTag; }
        public String getRestrictedCode() { return restrictedCode; }
    }

    @ResourceIdentity(
            keyField = "id",
            titleField = "fullName",
            subtitleField = "code",
            metadataFields = {"documentNumber", "apiSecret", "tacticalNote", "sensitiveTag", "restrictedCode"}
    )
    static class GovernedUnannotatedEmployeeDto {
        private Long id;

        @UISchema(label = "Code", preset = UISchemaPreset.ENTERPRISE_CODE)
        private String code;

        @UISchema(label = "Name")
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_DATA)
        private String fullName;

        @UISchema(label = "Tax ID")
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID)
        @DomainLineage(sourceClass = UnannotatedEmployeeEntity.class, sourcePaths = {"taxId"}, transformation = DomainLineageTransformation.ALIAS)
        private String documentNumber;

        @UISchema(label = "API Secret")
        @DomainGovernance(preset = DomainGovernancePreset.SECURITY_CREDENTIAL)
        private String apiSecret;

        @UISchema(label = "Tactical Note")
        @DomainGovernance(preset = DomainGovernancePreset.CONFIDENTIAL_OPERATIONAL)
        private String tacticalNote;

        @UISchema(label = "Sensitive Tag")
        @DomainGovernance(dataCategory = DomainDataCategory.SENSITIVE_PERSONAL, exportable = true)
        private String sensitiveTag;

        @UISchema(label = "Restricted Code")
        @DomainGovernance(classification = DomainClassification.RESTRICTED)
        private String restrictedCode;
    }

    static StandardServiceRegistry registry;
    static EntityManagerFactory factory;
    final JpaOptionSourceQueryExecutor executor = new JpaOptionSourceQueryExecutor();

    @BeforeAll
    static void start() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:entity-lookup-j1-j4;DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .build();
        factory = new MetadataSources(registry)
                .addAnnotatedClass(DepartmentEntity.class)
                .addAnnotatedClass(EmployeeEntity.class)
                .addAnnotatedClass(AssignmentEntity.class)
                .addAnnotatedClass(UnannotatedEmployeeEntity.class)
                .buildMetadata()
                .buildSessionFactory();

        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            var eng = new DepartmentEntity(10L, "ENG", "Engineering");
            var ops = new DepartmentEntity(20L, "OPS", "Operations");
            var fin = new DepartmentEntity(30L, "FIN", "Finance");
            em.persist(eng);
            em.persist(ops);
            em.persist(fin);

            var ana = new EmployeeEntity(100L, "Ana Beatriz", "12345678900", "top-secret-ana");
            var bruno = new EmployeeEntity(200L, "Bruno Costa", "98765432100", "top-secret-bruno");
            em.persist(ana);
            em.persist(bruno);

            em.persist(new AssignmentEntity(1L, "ASG-001", "Platform Architect", "ACTIVE", ana, eng, ops));
            em.persist(new AssignmentEntity(2L, "ASG-002", "Budget 100%_val\\x Lead", "ACTIVE", bruno, fin, eng));
            em.persist(new AssignmentEntity(3L, "ASG-003", "Budget 100A_valBx Lead", "ACTIVE", bruno, ops, fin));

            em.persist(new UnannotatedEmployeeEntity(
                    300L, "EMP-300", "Helena Rocha", "55566677788",
                    "secret-key-300", "Tactical-XYZ", "Health-Note", "RES-300"
            ));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void stop() {
        if (factory != null) {
            factory.close();
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesLineageJoinsPreservesWireKeysAndExecutesStructuredFilterWithRelationAlias() {
        LookupFilteringDescriptor filtering = LookupFilteringDescriptor
                .builderFromFilterDto(AssignmentFilterDto.class)
                .sortOptions(List.of(
                        new LookupSortOption("code", "internalCode", "asc", "Code"),
                        new LookupSortOption("description", "employeeName", "asc", "Employee")
                ))
                .defaultSort("description")
                .searchStrategies(List.of(
                        new LookupSearchStrategyDefinition("text", "descriptive-text", 2),
                        new LookupSearchStrategyDefinition("code", "business-code", 1)
                ))
                .defaultSearchStrategy("text")
                .build();

        EntityLookupDescriptor lookup = EntityLookupDescriptor
                .directoryBuilderFromDto("workforce.assignments", AssignmentLookupDto.class, AssignmentEntity.class)
                .filtering(filtering)
                .build();

        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "assignments",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/assignments",
                "assignmentId",
                "id",
                "employeeName",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );

        try (EntityManager em = factory.createEntityManager()) {
            Page<OptionDTO<Object>> page = executor.filterOptions(
                    em,
                    AssignmentEntity.class,
                    null,
                    null,
                    descriptor,
                    "Ana Operations",
                    null,
                    List.of(new LookupFilterRequest("primaryDeptCode", "equals", "ENG")),
                    "description",
                    PageRequest.of(0, 10),
                    List.of()
            );

            assertThat(page.getContent()).hasSize(1);
            OptionDTO<Object> item = page.getContent().getFirst();
            assertThat(item.id()).isEqualTo(1L);
            assertThat(item.label()).isEqualTo("Ana Beatriz");
            assertThat(item.extra())
                    .containsEntry("code", "ASG-001")
                    .containsEntry("description", "Ana Beatriz - Engineering - Operations")
                    .containsEntry("status", "ACTIVE");

            List<Map<String, Object>> richFields = (List<Map<String, Object>>) item.extra().get("richFields");
            assertThat(richFields)
                    .extracting(f -> (String) f.get("key"))
                    .containsExactly("businessCode", "employeeName", "departmentName", "assignedDepartmentName", "operationalStatus");
            assertThat(richFields.get(3)).containsEntry("value", "Operations");

            List<OptionDTO<Object>> byIds = executor.byIdsOptions(em, AssignmentEntity.class, descriptor, List.of(2L, 1L));
            assertThat(byIds).extracting(OptionDTO::id).containsExactly(2L, 1L);
            assertThat(byIds.get(0).label()).isEqualTo("Bruno Costa");
        }
    }

    @Test
    void enforcesLikeWildcardEscapingTokenCapAndNonTokenizationOfDocumentDigitsAndExact() {
        EntityLookupDescriptor lookup = EntityLookupDescriptor
                .directoryBuilder("workforce.assignments", "internalCode", "title", "primaryDepartment.name")
                .searchPaths("title", "primaryDepartment.name")
                .filtering(new LookupFilteringDescriptor(
                        LookupFilteringDescriptor.fromFilterDto(AssignmentFilterDto.class).availableFilters(),
                        Map.of(),
                        List.of(new LookupSortOption("title", "title", "asc", "Title")),
                        "title",
                        List.of(),
                        null,
                        List.of(
                                new LookupSearchStrategyDefinition("text", "descriptive-text", 2),
                                new LookupSearchStrategyDefinition("code", "business-code", 1, "digits")
                        ),
                        null
                ))
                .build();

        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "assignments",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/assignments",
                "assignmentId",
                "id",
                "title",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );

        try (EntityManager em = factory.createEntityManager()) {
            // Multi-strategy without defaultSearchStrategy still fails closed (422 semantics)
            assertThatThrownBy(() -> executor.filterOptions(
                    em, AssignmentEntity.class, null, null, descriptor,
                    "Platform", null, List.of(), null, PageRequest.of(0, 10), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);

            // Omnibox LIKE wildcard escaping: "100%_val\x" matches only row 2, NOT row 3 ("100A_valBx")
            Page<OptionDTO<Object>> escapedOmnibox = executor.filterOptions(
                    em, AssignmentEntity.class, null, null, descriptor,
                    "100%_val\\x", "text", List.of(), null, PageRequest.of(0, 10), List.of());
            assertThat(escapedOmnibox.getContent()).extracting(OptionDTO::id).containsExactly(2L);

            // Structured filter LIKE wildcard escaping
            Page<OptionDTO<Object>> escapedStructured = executor.filterOptions(
                    em, AssignmentEntity.class, null, null, descriptor,
                    null, null, List.of(new LookupFilterRequest("titleSearch", "contains", "100%_val\\x")),
                    null, PageRequest.of(0, 10), List.of());
            assertThat(escapedStructured.getContent()).extracting(OptionDTO::id).containsExactly(2L);

            // >8 tokens bounded cleanly without error (9 tokens, all matching row 1 or cross-column)
            Page<OptionDTO<Object>> boundedTokens = executor.filterOptions(
                    em, AssignmentEntity.class, null, null, descriptor,
                    "Platform Architect Engineering t4 t5 t6 t7 t8 t9", "text",
                    List.of(), null, PageRequest.of(0, 10), List.of());
            assertThat(boundedTokens.getContent()).isEmpty();
        }

        // Document strategy must NOT tokenize whitespace into ANDed tokens
        OptionSourceDescriptor employeeDocDescriptor = new OptionSourceDescriptor(
                "employeeDoc",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/employees",
                "employeeId",
                "id",
                "fullName",
                "id",
                List.of(),
                Map.of(),
                new OptionSourcePolicy(false, true, "contains", 1, 20, 50, true, false, "label"),
                EntityLookupDescriptor.directoryBuilder("workforce.employees", "taxId", "fullName")
                        .searchPaths("taxId")
                        .filtering(new LookupFilteringDescriptor(
                                List.of(), Map.of(), List.of(), null, List.of(), null,
                                List.of(new LookupSearchStrategyDefinition("doc", "normalized-document", 11)),
                                "doc"
                        ))
                        .build()
        );

        try (EntityManager em = factory.createEntityManager()) {
            Page<OptionDTO<Object>> docSearch = executor.filterOptions(
                    em, EmployeeEntity.class, null, null, employeeDocDescriptor,
                    "123 456 789 00", "doc", List.of(), null, PageRequest.of(0, 10), List.of());
            assertThat(docSearch.getContent()).extracting(OptionDTO::id).containsExactly(100L);
        }

        // searchMode="exact" must NOT tokenize whitespace into separate tokens
        OptionSourceDescriptor exactDescriptor = new OptionSourceDescriptor(
                "exactEmployees",
                OptionSourceType.LIGHT_LOOKUP,
                "/api/employees",
                "employeeId",
                "id",
                "fullName",
                "id",
                List.of(),
                Map.of(),
                new OptionSourcePolicy(false, true, "exact", 1, 20, 50, true, false, "label")
        );

        try (EntityManager em = factory.createEntityManager()) {
            Page<OptionDTO<Object>> exactHit = executor.filterOptions(
                    em, EmployeeEntity.class, null, null, exactDescriptor,
                    "Ana Beatriz", null, List.of(), null, PageRequest.of(0, 10), List.of());
            assertThat(exactHit.getContent()).extracting(OptionDTO::id).containsExactly(100L);

            Page<OptionDTO<Object>> exactMiss = executor.filterOptions(
                    em, EmployeeEntity.class, null, null, exactDescriptor,
                    "Ana", null, List.of(), null, PageRequest.of(0, 10), List.of());
            assertThat(exactMiss.getContent()).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesDeclarativeDomainGovernanceMaskingWhenOpaIsInactiveAndKeepsLightLookupExtraNull() {
        OptionSourceDescriptor employeeLookup = new OptionSourceDescriptor(
                "employees",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/employees",
                "employeeId",
                "id",
                "personalName",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        "workforce.employees",
                        "taxId",
                        List.of("taxId", "apiSecret"),
                        null,
                        null,
                        null,
                        List.of("personalName"),
                        Map.of(),
                        new LookupSelectionPolicy(null, null, List.of(), List.of(), true, null, null),
                        LookupCapabilities.standardDirectory(),
                        LookupDetailDescriptor.forSurface("workforce.employees", "detail", "detail"),
                        new LookupDisplayDescriptor(
                                "directory", "form", "comfortable", "inline", "list", "personalName",
                                List.of(
                                        new LookupDisplayFieldDescriptor("personalName", "personalName", "Name", "person", "text", "neutral", null),
                                        new LookupDisplayFieldDescriptor("taxId", "taxId", "Tax ID", "badge", "text", "neutral", null),
                                        new LookupDisplayFieldDescriptor("apiSecret", "apiSecret", "API Secret", "lock", "text", "neutral", null)
                                ),
                                List.of(), List.of(), null, false, true, true, false, false, true, Map.of(), 0
                        ),
                        null
                )
        );

        OptionSourceDescriptor lightTaxLookup = new OptionSourceDescriptor(
                "taxOnly",
                OptionSourceType.LIGHT_LOOKUP,
                "/api/employees",
                "employeeId",
                "taxId",
                "taxId",
                "id",
                List.of(),
                OptionSourcePolicy.defaults()
        );

        try (EntityManager em = factory.createEntityManager()) {
            Page<OptionDTO<Object>> page = executor.filterOptions(
                    em, EmployeeEntity.class, null, null, employeeLookup,
                    "Ana", null, List.of(), null, PageRequest.of(0, 10), List.of());
            assertThat(page.getContent()).hasSize(1);
            OptionDTO<Object> ana = page.getContent().getFirst();
            assertThat(ana.label()).isEqualTo("Ana Beatriz");
            assertThat(ana.extra()).containsEntry("code", "***");
            assertThat(ana.extra()).containsEntry("description", "***");
            List<Map<String, Object>> richFields = (List<Map<String, Object>>) ana.extra().get("richFields");
            assertThat(richFields).hasSize(2);
            assertThat(richFields.get(0)).containsEntry("key", "personalName").containsEntry("value", "***");
            assertThat(richFields.get(1)).containsEntry("key", "taxId").containsEntry("value", "***");
            // apiSecret (SECURITY_CREDENTIAL -> DENY) is omitted completely

            Page<OptionDTO<Object>> lightPage = executor.filterOptions(
                    em, EmployeeEntity.class, null, null, lightTaxLookup,
                    null, null, List.of(), null, PageRequest.of(0, 10), List.of());
            assertThat(lightPage.getContent()).hasSize(2);
            assertThat(lightPage.getContent()).allSatisfy(opt -> {
                assertThat(opt.label()).isEqualTo("***");
                assertThat(opt.extra()).isNull();
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void enforcesDtoOnlyGovernanceAndMasksSummarizeSensitiveAndRestrictedLabelsViaJpaExecutor() {
        EntityLookupDescriptor lookup = EntityLookupDescriptor
                .directoryBuilderFromDto("workforce.unannotated", GovernedUnannotatedEmployeeDto.class, UnannotatedEmployeeEntity.class)
                .build();

        assertThat(lookup.sourceDtoClass()).isEqualTo(GovernedUnannotatedEmployeeDto.class);
        assertThat(lookup.toMetadataMap()).doesNotContainKey("sourceDtoClass");

        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "unannotatedEmployees",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/unannotated-employees",
                "employeeId",
                "id",
                "fullName",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );
        assertThat(descriptor.toMetadataMap()).doesNotContainKey("sourceDtoClass");

        OptionSourceDescriptor tacticalLabelDescriptor = new OptionSourceDescriptor(
                "unannotatedTactical",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/unannotated-employees",
                "employeeId",
                "id",
                "tacticalNote",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );

        OptionSourceDescriptor sensitiveLabelDescriptor = new OptionSourceDescriptor(
                "unannotatedSensitive",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/unannotated-employees",
                "employeeId",
                "id",
                "sensitiveTag",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );

        OptionSourceDescriptor restrictedLabelDescriptor = new OptionSourceDescriptor(
                "unannotatedRestricted",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/unannotated-employees",
                "employeeId",
                "id",
                "restrictedCode",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                lookup
        );

        try (EntityManager em = factory.createEntityManager()) {
            Page<OptionDTO<Object>> page = executor.filterOptions(
                    em, UnannotatedEmployeeEntity.class, null, null, descriptor,
                    "Helena", null, List.of(), null, PageRequest.of(0, 10), List.of());
            assertThat(page.getContent()).hasSize(1);
            OptionDTO<Object> helena = page.getContent().getFirst();
            assertThat(helena.id()).isEqualTo(300L);
            assertThat(helena.label()).isEqualTo("Helena Rocha");
            assertThat(helena.extra())
                    .containsEntry("code", "EMP-300")
                    .containsEntry("description", "*** - *** - *** - ***");

            List<Map<String, Object>> richFields = (List<Map<String, Object>>) helena.extra().get("richFields");
            assertThat(richFields)
                    .extracting(f -> (String) f.get("key"))
                    .containsExactly("code", "fullName", "documentNumber", "tacticalNote", "sensitiveTag");
            assertThat(richFields.get(0)).containsEntry("value", "EMP-300");
            assertThat(richFields.get(1)).containsEntry("value", "***");
            assertThat(richFields.get(2)).containsEntry("value", "***");
            assertThat(richFields.get(3)).containsEntry("value", "***");
            assertThat(richFields.get(4)).containsEntry("value", "***");

            List<OptionDTO<Object>> byIds = executor.byIdsOptions(
                    em, UnannotatedEmployeeEntity.class, descriptor, List.of(300L));
            assertThat(byIds).hasSize(1);
            assertThat(byIds.getFirst().extra()).containsEntry("description", "*** - *** - *** - ***");

            OptionDTO<Object> tacticalOpt = executor.filterOptions(
                    em, UnannotatedEmployeeEntity.class, null, null, tacticalLabelDescriptor,
                    null, null, List.of(), null, PageRequest.of(0, 10), List.of()).getContent().getFirst();
            assertThat(tacticalOpt.label()).isEqualTo("***");

            OptionDTO<Object> sensitiveOpt = executor.filterOptions(
                    em, UnannotatedEmployeeEntity.class, null, null, sensitiveLabelDescriptor,
                    null, null, List.of(), null, PageRequest.of(0, 10), List.of()).getContent().getFirst();
            assertThat(sensitiveOpt.label()).isEqualTo("***");

            OptionDTO<Object> restrictedOpt = executor.filterOptions(
                    em, UnannotatedEmployeeEntity.class, null, null, restrictedLabelDescriptor,
                    null, null, List.of(), null, PageRequest.of(0, 10), List.of()).getContent().getFirst();
            assertThat(restrictedOpt.label()).isEqualTo("300");
        }
    }
}
