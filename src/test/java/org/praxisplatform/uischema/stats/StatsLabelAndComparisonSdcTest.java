package org.praxisplatform.uischema.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.analytics.ResolvedVerifiedAnalyticalRecipe;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeDescriptor;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeResolver;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.UiOption;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.option.PraxisUiOptionEnum;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractReadOnlyResourceService;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.service.StatsQueryExecutor;
import org.praxisplatform.uischema.stats.service.jpa.JpaStatsQueryExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.praxisplatform.uischema.stats.dto.ComparisonBucket;
import org.praxisplatform.uischema.stats.dto.ComparisonPeriodRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsDimensionRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies metadata-driven human bucket label enrichment ({@code optionItems}, {@link PraxisUiOptionEnum},
 * boolean {@code labelTrue}/{@code labelFalse}), preservation of {@link StatsFieldDescriptor#labeledGroupByBucket},
 * {@code comparisonStats} complementary suppression with SDC partition guard + truncation metadata,
 * and {@link VerifiedAnalyticalRecipeDescriptor.KpiHint} serialization.
 */
@SpringBootTest(
        classes = StatsLabelAndComparisonSdcTest.TestApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:statslabelsdc;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false",
                "praxis.stats.enabled=true",
                "praxis.stats.default-min-bucket-count=5"
        }
)
class StatsLabelAndComparisonSdcTest {

    @Autowired
    private SampleWorkforceService service;

    @Autowired
    private SampleWorkforceRepository repository;

    @Autowired
    private SampleDepartmentRepository departmentRepository;

    @BeforeEach
    void seedOnce() {
        if (repository.count() > 0) {
            return;
        }
        SampleDepartment eng = new SampleDepartment();
        eng.setName("Engenharia de Plataforma");
        eng = departmentRepository.save(eng);

        SampleDepartment ops = new SampleDepartment();
        ops.setName("Operacoes Corporativas");
        ops = departmentRepository.save(ops);

        LocalDate today = LocalDate.now();
        LocalDate currentDay = today.minusDays(2);
        LocalDate previousDay = today.minusDays(9);

        // Cohort "labels": sufficient counts >= 5 per category for 1D/2D label enrichment assertions
        for (int i = 0; i < 6; i++) {
            save("labels", "PERM", SampleWorkStatus.ACTIVE, true, eng, currentDay);
            save("labels", "TEMP", SampleWorkStatus.SUSPENDED, false, ops, currentDay);
        }

        // Cohort "cmp-absorb": with minBucketCount=5:
        // PERM: current=6, previous=6
        // CONT: current=5, previous=5 (smallest visible partner; absorbed when S1+S2 sum=2 < 5)
        // TEMP: current=1, previous=1 (violates k=5)
        // INTERN: current=1, previous=1 (violates k=5)
        // Absorbing CONT yields 3 suppressed buckets, sum = 1 + 1 + 5 = 7 >= 3 + 5 - 1 = 7 -> total=13, pool=7.
        for (int i = 0; i < 6; i++) {
            save("cmp-absorb", "PERM", SampleWorkStatus.ACTIVE, true, eng, currentDay);
            save("cmp-absorb", "PERM", SampleWorkStatus.ACTIVE, true, eng, previousDay);
        }
        for (int i = 0; i < 5; i++) {
            save("cmp-absorb", "CONT", SampleWorkStatus.ACTIVE, true, eng, currentDay);
            save("cmp-absorb", "CONT", SampleWorkStatus.ACTIVE, true, eng, previousDay);
        }
        save("cmp-absorb", "TEMP", SampleWorkStatus.SUSPENDED, false, ops, currentDay);
        save("cmp-absorb", "TEMP", SampleWorkStatus.SUSPENDED, false, ops, previousDay);
        save("cmp-absorb", "INTERN", SampleWorkStatus.RETIRED, false, ops, currentDay);
        save("cmp-absorb", "INTERN", SampleWorkStatus.RETIRED, false, ops, previousDay);

        // Cohort "cmp-pool-hidden":
        // PERM: current=10, previous=10
        // TEMP: current=2, previous=2
        // INTERN: current=3, previous=3
        // Small sum = 5 meets effectiveMinBucketCount=5 so PERM stays visible,
        // YET 5 < suppressedActive(2) + k(5) - 1 = 6 -> SDC partition guard hides pool & total!
        for (int i = 0; i < 10; i++) {
            save("cmp-pool-hidden", "PERM", SampleWorkStatus.ACTIVE, true, eng, currentDay);
            save("cmp-pool-hidden", "PERM", SampleWorkStatus.ACTIVE, true, eng, previousDay);
        }
        for (int i = 0; i < 2; i++) {
            save("cmp-pool-hidden", "TEMP", SampleWorkStatus.SUSPENDED, false, ops, currentDay);
            save("cmp-pool-hidden", "TEMP", SampleWorkStatus.SUSPENDED, false, ops, previousDay);
        }
        for (int i = 0; i < 3; i++) {
            save("cmp-pool-hidden", "INTERN", SampleWorkStatus.RETIRED, false, ops, currentDay);
            save("cmp-pool-hidden", "INTERN", SampleWorkStatus.RETIRED, false, ops, previousDay);
        }
    }

    private void save(
            String cohort,
            String contractCode,
            SampleWorkStatus status,
            boolean activeFlag,
            SampleDepartment department,
            LocalDate effectiveDate
    ) {
        SampleWorkforceEntity entity = new SampleWorkforceEntity();
        entity.setCohort(cohort);
        entity.setContractCode(contractCode);
        entity.setStatus(status);
        entity.setActiveFlag(activeFlag);
        entity.setDepartment(department);
        entity.setEffectiveDate(effectiveDate);
        repository.save(entity);
    }

    @Test
    void enrichesOptionItemsOptionEnumAndBooleanLabelsWhilePreservingLabeledGroupBy() {
        SampleWorkforceFilter filter = new SampleWorkforceFilter();
        filter.setCohort("labels");

        // 1) @UiOption optionItems enrichment (1D)
        GroupByStatsResponse contractStats = service.groupByStats(new GroupByStatsRequest<>(
                filter, "contractCode", new StatsMetricRequest(StatsMetric.COUNT, null), 10, StatsBucketOrder.KEY_ASC
        ));
        Map<Object, String> contractLabels = contractStats.buckets().stream()
                .collect(Collectors.toMap(GroupByBucket::key, GroupByBucket::label));
        assertEquals("Permanente", contractLabels.get("PERM"));
        assertEquals("Temporario", contractLabels.get("TEMP"));

        // 2) @UISchema(optionEnum = SampleWorkStatus.class) PraxisUiOptionEnum enrichment
        GroupByStatsResponse statusStats = service.groupByStats(new GroupByStatsRequest<>(
                filter, "status", new StatsMetricRequest(StatsMetric.COUNT, null), 10, StatsBucketOrder.KEY_ASC
        ));
        Map<Object, String> statusLabels = statusStats.buckets().stream()
                .collect(Collectors.toMap(b -> String.valueOf(b.key()), GroupByBucket::label));
        assertEquals("Em Exercicio", statusLabels.get("ACTIVE"));
        assertEquals("Suspenso", statusLabels.get("SUSPENDED"));

        // 3) Boolean @UISchema(labelTrue/labelFalse) enrichment
        GroupByStatsResponse activeFlagStats = service.groupByStats(new GroupByStatsRequest<>(
                filter, "activeFlag", new StatsMetricRequest(StatsMetric.COUNT, null), 10, StatsBucketOrder.KEY_ASC
        ));
        Map<Object, String> flagLabels = activeFlagStats.buckets().stream()
                .collect(Collectors.toMap(b -> String.valueOf(b.key()), GroupByBucket::label));
        assertEquals("Ativo no Quadro", flagLabels.get("true"));
        assertEquals("Fora do Quadro", flagLabels.get("false"));

        // 4) 2D composite bucket label enrichment
        GroupByStatsResponse multiDim = service.groupByStats(new GroupByStatsRequest<>(
                filter,
                null,
                new StatsMetricRequest(StatsMetric.COUNT, null),
                10,
                StatsBucketOrder.KEY_ASC,
                null,
                List.of(
                        new StatsDimensionRequest("contractCode"),
                        new StatsDimensionRequest("status")
                ),
                null,
                null,
                null,
                null,
                null,
                null
        ));
        GroupByBucket permActive = multiDim.buckets().stream()
                .filter(b -> "PERM".equals(String.valueOf(b.keys().get(0))))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("Permanente", "Em Exercicio"), permActive.labels());
        assertEquals("Permanente | Em Exercicio", permActive.label());

        // 5) labeledGroupByBucket (department.id -> department.name) keeps relational label
        GroupByStatsResponse deptStats = service.groupByStats(new GroupByStatsRequest<>(
                filter, "department", new StatsMetricRequest(StatsMetric.COUNT, null), 10, StatsBucketOrder.KEY_ASC
        ));
        assertTrue(deptStats.buckets().stream().anyMatch(b -> "Engenharia de Plataforma".equals(b.label())));
        assertTrue(deptStats.buckets().stream().anyMatch(b -> "Operacoes Corporativas".equals(b.label())));

        // 6) Truncation flag when limit < visible buckets
        GroupByStatsResponse limited = service.groupByStats(new GroupByStatsRequest<>(
                filter, "contractCode", new StatsMetricRequest(StatsMetric.COUNT, null), 1, StatsBucketOrder.KEY_ASC
        ));
        assertEquals(1, limited.buckets().size());
        assertEquals(Boolean.TRUE, limited.truncated());
    }

    @Test
    void enforcesComparisonComplementarySuppressionSdcGuardAndHumanLabels() {
        ComparisonPeriodRequest period = new ComparisonPeriodRequest(
                ComparisonPeriodPreset.LAST_7_DAYS, null, null, "UTC", ComparisonPeriodMode.PREVIOUS_ALIGNED
        );

        SampleWorkforceFilter absorbFilter = new SampleWorkforceFilter();
        absorbFilter.setCohort("cmp-absorb");
        ComparisonStatsResponse absorbResp = service.comparisonStats(new ComparisonStatsRequest<>(
                absorbFilter,
                "contractCode",
                "effectiveDate",
                List.of(new StatsMetricRequest(StatsMetric.COUNT, null)),
                period,
                10,
                StatsBucketOrder.VALUE_DESC
        ));

        assertEquals(Boolean.TRUE, absorbResp.suppressed());
        assertEquals(3, absorbResp.suppressedBucketCount());
        assertEquals(5, absorbResp.minBucketCount());
        assertEquals(13L, absorbResp.populationTotalCount());
        assertEquals(7L, absorbResp.suppressedPoolCount());
        assertEquals(Boolean.FALSE, absorbResp.truncated());
        assertEquals(1, absorbResp.buckets().size());
        ComparisonBucket visibleBucket = absorbResp.buckets().get(0);
        assertEquals("PERM", visibleBucket.key());
        assertEquals("Permanente", visibleBucket.label());

        // Partition guard hides populationTotalCount / suppressedPoolCount when pool sum == k + suppressedCount - 2
        SampleWorkforceFilter hiddenPoolFilter = new SampleWorkforceFilter();
        hiddenPoolFilter.setCohort("cmp-pool-hidden");
        ComparisonStatsResponse hiddenPoolResp = service.comparisonStats(new ComparisonStatsRequest<>(
                hiddenPoolFilter,
                "contractCode",
                "effectiveDate",
                List.of(new StatsMetricRequest(StatsMetric.COUNT, null)),
                period,
                10,
                StatsBucketOrder.VALUE_DESC
        ));

        assertEquals(2, hiddenPoolResp.suppressedBucketCount());
        assertEquals(1, hiddenPoolResp.buckets().size());
        assertNull(hiddenPoolResp.populationTotalCount());
        assertNull(hiddenPoolResp.suppressedPoolCount());

        // Truncated comparison response when limit=1 and 2 buckets survive
        SampleWorkforceFilter labelFilter = new SampleWorkforceFilter();
        labelFilter.setCohort("labels");
        ComparisonStatsResponse truncatedResp = service.comparisonStats(new ComparisonStatsRequest<>(
                labelFilter,
                "contractCode",
                "effectiveDate",
                List.of(new StatsMetricRequest(StatsMetric.COUNT, null)),
                period,
                1,
                StatsBucketOrder.KEY_ASC
        ));
        assertEquals(1, truncatedResp.buckets().size());
        assertEquals(Boolean.TRUE, truncatedResp.truncated());
    }

    @Test
    void serializesVerifiedAnalyticalRecipeKpiHintIntoMetadataMap() {
        VerifiedAnalyticalRecipeResolver resolver = new VerifiedAnalyticalRecipeResolver(
                null, new ObjectMapper(), null, List.of()
        );
        SampleRecipeController controller = new SampleRecipeController(service);
        List<ResolvedVerifiedAnalyticalRecipe> recipes = resolver.resolveByController(
                SampleRecipeController.class, controller
        );
        assertEquals(1, recipes.size());

        ResolvedVerifiedAnalyticalRecipe resolved = recipes.get(0);
        assertNotNull(resolved.kpiHint());
        assertEquals("HIGHER_IS_BETTER", resolved.kpiHint().polarity());

        Map<String, Object> metadataMap = resolved.toMetadataMap();
        assertTrue(metadataMap.containsKey("kpiHint"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kpiHintMap = (Map<String, Object>) metadataMap.get("kpiHint");
        assertEquals("HIGHER_IS_BETTER", kpiHintMap.get("polarity"));
        assertEquals("vinculo ativo", kpiHintMap.get("unitSingular"));
        assertEquals("vinculos ativos", kpiHintMap.get("unitPlural"));
        assertEquals("GROUP_BY_RATIO", kpiHintMap.get("suggestedDerivationKind"));
        assertEquals("BULLET", kpiHintMap.get("suggestedVisualizationKind"));
        assertEquals(new BigDecimal("85"), kpiHintMap.get("referenceTarget"));
        assertEquals(1, ((List<?>) kpiHintMap.get("suggestedThresholds")).size());
    }

    enum SampleWorkStatus implements PraxisUiOptionEnum {
        ACTIVE("Em Exercicio"),
        SUSPENDED("Suspenso"),
        RETIRED("Aposentado");

        private final String label;

        SampleWorkStatus(String label) {
            this.label = label;
        }

        @Override
        public String label() {
            return label;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = SampleWorkforceEntity.class)
    @EnableJpaRepositories(basePackageClasses = SampleWorkforceRepository.class, considerNestedRepositories = true)
    @Import({SampleWorkforceMapper.class, SampleWorkforceService.class})
    static class TestApplication {
        @Bean
        @ConditionalOnMissingBean
        StatsProperties statsProperties() {
            return new StatsProperties(true, 20, 100, StatsSupportMode.AUTO, 1_000, 366, 2, 500, 366, 5);
        }

        @Bean
        @ConditionalOnMissingBean
        StatsEligibility statsEligibility() {
            return new StatsEligibility();
        }

        @Bean
        @ConditionalOnMissingBean
        StatsQueryExecutor statsQueryExecutor() {
            return new JpaStatsQueryExecutor();
        }
    }

    @Entity
    @Table(name = "sdc_sample_department")
    static class SampleDepartment {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private String name;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Entity
    @Table(name = "sdc_sample_workforce")
    static class SampleWorkforceEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private String cohort;

        @Column(nullable = false)
        private String contractCode;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private SampleWorkStatus status;

        @Column(nullable = false)
        private Boolean activeFlag;

        @ManyToOne(fetch = FetchType.EAGER, optional = false)
        @JoinColumn(name = "department_id")
        private SampleDepartment department;

        @Column(nullable = false)
        private LocalDate effectiveDate;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getCohort() { return cohort; }
        public void setCohort(String cohort) { this.cohort = cohort; }
        public String getContractCode() { return contractCode; }
        public void setContractCode(String contractCode) { this.contractCode = contractCode; }
        public SampleWorkStatus getStatus() { return status; }
        public void setStatus(SampleWorkStatus status) { this.status = status; }
        public Boolean getActiveFlag() { return activeFlag; }
        public void setActiveFlag(Boolean activeFlag) { this.activeFlag = activeFlag; }
        public SampleDepartment getDepartment() { return department; }
        public void setDepartment(SampleDepartment department) { this.department = department; }
        public LocalDate getEffectiveDate() { return effectiveDate; }
        public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
    }

    static class SampleWorkforceResponseDTO {
        private Long id;

        @UISchema(
                label = "Regime Contratual",
                controlType = FieldControlType.SELECT,
                optionItems = {
                        @UiOption(value = "PERM", label = "Permanente"),
                        @UiOption(value = "CONT", label = "Contrato Especial"),
                        @UiOption(value = "TEMP", label = "Temporario"),
                        @UiOption(value = "INTERN", label = "Estagio")
                }
        )
        private String contractCode;

        @UISchema(
                label = "Situacao",
                controlType = FieldControlType.SELECT,
                optionEnum = SampleWorkStatus.class
        )
        private SampleWorkStatus status;

        @UISchema(
                label = "Ativo no Quadro",
                labelTrue = "Ativo no Quadro",
                labelFalse = "Fora do Quadro"
        )
        private Boolean activeFlag;

        private Long departmentId;
        private String departmentName;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getContractCode() { return contractCode; }
        public void setContractCode(String contractCode) { this.contractCode = contractCode; }
        public SampleWorkStatus getStatus() { return status; }
        public void setStatus(SampleWorkStatus status) { this.status = status; }
        public Boolean getActiveFlag() { return activeFlag; }
        public void setActiveFlag(Boolean activeFlag) { this.activeFlag = activeFlag; }
        public Long getDepartmentId() { return departmentId; }
        public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
        public String getDepartmentName() { return departmentName; }
        public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
    }

    static class SampleWorkforceFilter implements GenericFilterDTO {
        @Filterable
        private String cohort;

        public String getCohort() { return cohort; }
        public void setCohort(String cohort) { this.cohort = cohort; }
    }

    interface SampleDepartmentRepository extends BaseCrudRepository<SampleDepartment, Long> {
    }

    interface SampleWorkforceRepository extends BaseCrudRepository<SampleWorkforceEntity, Long> {
    }

    @Component
    static class SampleWorkforceMapper implements ResourceMapper<SampleWorkforceEntity, SampleWorkforceResponseDTO, Void, Void, Long> {
        @Override
        public SampleWorkforceResponseDTO toResponse(SampleWorkforceEntity entity) {
            SampleWorkforceResponseDTO dto = new SampleWorkforceResponseDTO();
            dto.setId(entity.getId());
            dto.setContractCode(entity.getContractCode());
            dto.setStatus(entity.getStatus());
            dto.setActiveFlag(entity.getActiveFlag());
            if (entity.getDepartment() != null) {
                dto.setDepartmentId(entity.getDepartment().getId());
                dto.setDepartmentName(entity.getDepartment().getName());
            }
            return dto;
        }

        @Override
        public SampleWorkforceEntity newEntity(Void dto) {
            throw new UnsupportedOperationException("Read-only fixture");
        }

        @Override
        public void applyUpdate(SampleWorkforceEntity entity, Void dto) {
            throw new UnsupportedOperationException("Read-only fixture");
        }

        @Override
        public Long extractId(SampleWorkforceEntity entity) {
            return entity.getId();
        }
    }

    @Service
    static class SampleWorkforceService extends AbstractReadOnlyResourceService<
            SampleWorkforceEntity,
            SampleWorkforceResponseDTO,
            Long,
            SampleWorkforceFilter> {

        private static final StatsFieldRegistry REGISTRY = StatsFieldRegistry.builder()
                .add(StatsFieldDescriptor.groupByBucket("contractCode", "contractCode", Set.of(StatsMetric.COUNT)))
                .add(StatsFieldDescriptor.groupByBucket("status", "status", Set.of(StatsMetric.COUNT)))
                .add(StatsFieldDescriptor.groupByBucket("activeFlag", "activeFlag", Set.of(StatsMetric.COUNT)))
                .add(StatsFieldDescriptor.labeledGroupByBucket("department", "department.id", "department.name", Set.of(StatsMetric.COUNT)))
                .add(StatsFieldDescriptor.timeSeriesField("effectiveDate", "effectiveDate"))
                .build();

        private final SampleWorkforceMapper mapper;

        SampleWorkforceService(SampleWorkforceRepository repository, SampleWorkforceMapper mapper) {
            super(repository, SampleWorkforceEntity.class);
            this.mapper = mapper;
        }

        @Override
        protected ResourceMapper<SampleWorkforceEntity, SampleWorkforceResponseDTO, Void, Void, Long> getResourceMapper() {
            return mapper;
        }

        @Override
        public StatsSupportMode getGroupByStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsSupportMode getComparisonStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsSupportMode getTimeSeriesStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsFieldRegistry getStatsFieldRegistry() {
            return REGISTRY;
        }
    }

    @ApiResource(value = "/api/sample/workforce", resourceKey = "sample.workforce")
    static class SampleRecipeController extends AbstractResourceQueryController<SampleWorkforceResponseDTO, Long, SampleWorkforceFilter> {
        private final BaseResourceQueryService<SampleWorkforceResponseDTO, Long, SampleWorkforceFilter> service;

        SampleRecipeController(BaseResourceQueryService<SampleWorkforceResponseDTO, Long, SampleWorkforceFilter> service) {
            this.service = service;
        }

        @Override
        protected BaseResourceQueryService<SampleWorkforceResponseDTO, Long, SampleWorkforceFilter> getService() {
            return service;
        }

        @Override
        protected Long getResponseId(SampleWorkforceResponseDTO dto) {
            return dto.getId();
        }

        @Override
        protected List<VerifiedAnalyticalRecipeDescriptor<SampleWorkforceFilter>> defineAnalyticalRecipes() {
            return List.of(
                    VerifiedAnalyticalRecipeDescriptor.<SampleWorkforceFilter>builder(
                                    "active-contract-ratio",
                                    "Taxa de exercicio ativo"
                            )
                            .operation(AnalyticsOperation.GROUP_BY)
                            .field("contractCode")
                            .metric(StatsMetric.COUNT, null)
                            .kpiHint(new VerifiedAnalyticalRecipeDescriptor.KpiHint(
                                    "HIGHER_IS_BETTER",
                                    "vinculo ativo",
                                    "vinculos ativos",
                                    "GROUP_BY_RATIO",
                                    "BULLET",
                                    new BigDecimal("85"),
                                    List.of(Map.of("max", 70, "tone", "warning"))
                            ))
                            .build()
            );
        }
    }
}
