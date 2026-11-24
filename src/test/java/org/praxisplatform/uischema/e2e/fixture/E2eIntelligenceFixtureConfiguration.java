package org.praxisplatform.uischema.e2e.fixture;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.praxisplatform.intelligence.contracts.IntelligenceContractVersion;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanRequest;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanSummary;
import org.praxisplatform.intelligence.contracts.epistemic.EpistemicKind;
import org.praxisplatform.intelligence.contracts.inference.InferenceGroupResult;
import org.praxisplatform.intelligence.contracts.inference.InferenceRequest;
import org.praxisplatform.intelligence.contracts.inference.InferenceResponse;
import org.praxisplatform.intelligence.contracts.inference.InferenceTestKind;
import org.praxisplatform.intelligence.contracts.jobs.IntelligenceJobReceipt;
import org.praxisplatform.intelligence.contracts.modelcard.ModelCardDescriptor;
import org.praxisplatform.intelligence.contracts.provenance.AnalyticalProvenance;
import org.praxisplatform.intelligence.contracts.provenance.SuppressionStatus;
import org.praxisplatform.intelligence.contracts.spi.ResourceIntelligenceExecutor;
import org.praxisplatform.intelligence.contracts.spi.TabularColumnDescriptor;
import org.praxisplatform.intelligence.contracts.spi.TabularFrameSource;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.annotation.AnalyticsDimensionBinding;
import org.praxisplatform.uischema.annotation.AnalyticsIntent;
import org.praxisplatform.uischema.annotation.AnalyticsMetricBinding;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.annotation.AnalyticsProjection;
import org.praxisplatform.uischema.annotation.ApiGroup;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.annotation.UiAnalytics;
import org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController;
import org.praxisplatform.uischema.service.base.ResourceFilterAccessScope;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.service.base.AbstractReadOnlyResourceService;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.service.jpa.JpaStatsTabularFrameSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Configuration
@Profile({"e2e-intelligence", "e2e-intelligence-unavailable"})
public class E2eIntelligenceFixtureConfiguration {

    @Bean
    @Profile("e2e-intelligence")
    public RecordingResourceIntelligenceExecutor recordingResourceIntelligenceExecutor() {
        return new RecordingResourceIntelligenceExecutor();
    }

    @Bean
    @Profile("e2e-intelligence")
    public KeysetBatchProbe keysetBatchProbe(
            jakarta.persistence.EntityManager entityManager,
            PlatformTransactionManager transactionManager
    ) {
        return new KeysetBatchProbe(entityManager, transactionManager);
    }

    public static final class KeysetBatchProbe {

        private final jakarta.persistence.EntityManager entityManager;
        private final TransactionTemplate readOnlyTemplate;

        KeysetBatchProbe(
                jakarta.persistence.EntityManager entityManager,
                PlatformTransactionManager transactionManager
        ) {
            this.entityManager = entityManager;
            this.readOnlyTemplate = new TransactionTemplate(transactionManager);
            this.readOnlyTemplate.setReadOnly(true);
        }

        public List<String> streamAllEmployeeKeysWithBatchSize(int batchSize) {
            JpaStatsTabularFrameSource<EmployeeEntity, Long> source = new JpaStatsTabularFrameSource<>(
                    "human-resources.intelligent-employees",
                    entityManager,
                    null,
                    EmployeeEntity.class,
                    (root, query, cb) -> cb.conjunction(),
                    List.of("id"),
                    null,
                    "employeeKey",
                    Map.of("salario", IntelligentEmployeeService.REGISTRY.resolve("salario").orElseThrow()),
                    100,
                    batchSize,
                    readOnlyTemplate
            );
            List<String> keys = new ArrayList<>();
            source.streamRows(row -> {
                if (!TransactionSynchronizationManager.isActualTransactionActive()
                        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                    throw new IllegalStateException("Expected active read-only transaction inside streamRows");
                }
                keys.add(String.valueOf(row.get("employeeKey")));
            });
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Expected read-only transaction to close after streamRows");
            }
            return List.copyOf(keys);
        }
    }

    public static final class RecordingResourceIntelligenceExecutor implements ResourceIntelligenceExecutor {

        private final AtomicReference<String> lastResourceKey = new AtomicReference<>();
        private final AtomicReference<InferenceRequest> lastInferenceRequest = new AtomicReference<>();
        private final AtomicReference<AnomalyScanRequest> lastAnomalyRequest = new AtomicReference<>();
        private final AtomicReference<List<TabularColumnDescriptor>> lastColumns = new AtomicReference<>(List.of());
        private final AtomicReference<List<Map<String, Object>>> lastRows = new AtomicReference<>(List.of());
        private final AtomicBoolean lastTransactionActiveInsideStream = new AtomicBoolean(false);
        private final AtomicBoolean lastTransactionReadOnlyInsideStream = new AtomicBoolean(false);
        private final AtomicBoolean lastTransactionActiveAfterStream = new AtomicBoolean(true);

        public void reset() {
            lastResourceKey.set(null);
            lastInferenceRequest.set(null);
            lastAnomalyRequest.set(null);
            lastColumns.set(List.of());
            lastRows.set(List.of());
            lastTransactionActiveInsideStream.set(false);
            lastTransactionReadOnlyInsideStream.set(false);
            lastTransactionActiveAfterStream.set(true);
        }

        public String lastResourceKey() {
            return lastResourceKey.get();
        }

        public InferenceRequest lastInferenceRequest() {
            return lastInferenceRequest.get();
        }

        public AnomalyScanRequest lastAnomalyRequest() {
            return lastAnomalyRequest.get();
        }

        public List<TabularColumnDescriptor> lastColumns() {
            return lastColumns.get();
        }

        public List<Map<String, Object>> lastRows() {
            return lastRows.get();
        }

        public boolean lastTransactionActiveInsideStream() {
            return lastTransactionActiveInsideStream.get();
        }

        public boolean lastTransactionReadOnlyInsideStream() {
            return lastTransactionReadOnlyInsideStream.get();
        }

        public boolean lastTransactionActiveAfterStream() {
            return lastTransactionActiveAfterStream.get();
        }

        @Override
        public InferenceResponse executeInference(
                String resourceKey,
                InferenceRequest request,
                TabularFrameSource frameSource
        ) {
            List<Map<String, Object>> collected = new ArrayList<>();
            AtomicBoolean activeInside = new AtomicBoolean(false);
            AtomicBoolean readOnlyInside = new AtomicBoolean(false);
            frameSource.streamRows(row -> {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    activeInside.set(true);
                    readOnlyInside.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                }
                collected.add(row);
            });
            lastTransactionActiveInsideStream.set(activeInside.get());
            lastTransactionReadOnlyInsideStream.set(readOnlyInside.get());
            lastTransactionActiveAfterStream.set(TransactionSynchronizationManager.isActualTransactionActive());
            lastResourceKey.set(resourceKey);
            lastInferenceRequest.set(request);
            lastColumns.set(frameSource.columns());
            lastRows.set(List.copyOf(collected));

            AnalyticalProvenance provenance = new AnalyticalProvenance(
                    IntelligenceContractVersion.V1,
                    "e2e-intelligence-stub",
                    "1.0.0",
                    "java-21",
                    null,
                    "e2e-inference-model-v1",
                    "1.0.0",
                    request.seed(),
                    request.asOfDate(),
                    null,
                    null,
                    EpistemicKind.CALIBRATED_PROBABILITY,
                    collected.size(),
                    0,
                    request.requestId(),
                    null,
                    Instant.now()
            );
            ModelCardDescriptor modelCard = new ModelCardDescriptor(
                    "e2e-inference-model-v1",
                    "1.0.0",
                    "E2E Inference Verification Model",
                    "workforce",
                    "non-parametric",
                    EpistemicKind.CALIBRATED_PROBABILITY,
                    "End-to-end verification of governed tabular streaming and provenance contracts.",
                    List.of("Automated employment decisions without human oversight"),
                    1,
                    List.of("Filtered resource rows obey ResourceFilterAccessScope"),
                    "Human validation required"
            );
            InferenceTestKind testKind = request.tests().getFirst();
            boolean suppressed = collected.size() < request.minBucketCount();
            InferenceGroupResult result = new InferenceGroupResult(
                    "ALL",
                    testKind,
                    1.25d,
                    0.042d,
                    0.042d,
                    0.35d,
                    "RANK_BISERIAL",
                    null,
                    null,
                    collected.size(),
                    0L,
                    BigDecimal.ZERO,
                    !suppressed,
                    false,
                    suppressed ? SuppressionStatus.SUPPRESSED_PRIMARY_K_ANONYMITY : SuppressionStatus.EXACT_RELEASE,
                    "Recorded " + collected.size() + " rows from " + frameSource.datasetKey()
            );
            return new InferenceResponse(IntelligenceContractVersion.V1, provenance, modelCard, List.of(result));
        }

        @Override
        public AnomalyScanResponse executeAnomalyScan(
                String resourceKey,
                AnomalyScanRequest request,
                TabularFrameSource frameSource
        ) {
            List<Map<String, Object>> collected = new ArrayList<>();
            frameSource.streamRows(collected::add);
            lastResourceKey.set(resourceKey);
            lastAnomalyRequest.set(request);
            lastColumns.set(frameSource.columns());
            lastRows.set(List.copyOf(collected));

            AnalyticalProvenance provenance = new AnalyticalProvenance(
                    IntelligenceContractVersion.V1,
                    "e2e-intelligence-stub",
                    "1.0.0",
                    "java-21",
                    null,
                    "e2e-anomaly-model-v1",
                    "1.0.0",
                    request.seed(),
                    request.asOfDate(),
                    null,
                    null,
                    EpistemicKind.CALIBRATED_PROBABILITY,
                    collected.size(),
                    0,
                    request.requestId(),
                    null,
                    Instant.now()
            );
            ModelCardDescriptor modelCard = new ModelCardDescriptor(
                    "e2e-anomaly-model-v1",
                    "1.0.0",
                    "E2E Anomaly Verification Model",
                    "workforce",
                    "robust-mad",
                    EpistemicKind.CALIBRATED_PROBABILITY,
                    "End-to-end verification of entity pseudonymization and anomaly contracts.",
                    List.of("Automated disciplinary action without audit"),
                    1,
                    List.of("Entity identifiers emitted as ENTITY_ID_TO_PSEUDONYMIZE"),
                    "Human audit required"
            );
            return new AnomalyScanResponse(
                    IntelligenceContractVersion.V1,
                    provenance,
                    modelCard,
                    List.of(),
                    new AnomalyScanSummary(collected.size(), 1, 0, 0, 0, 0, BigDecimal.ZERO, false)
            );
        }

        @Override
        public Optional<IntelligenceJobReceipt> getJob(String jobId) {
            return Optional.empty();
        }
    }

    public static class IntelligentEmployeeResponseDTO {
        @Schema(description = "Identificador unico do colaborador.")
        @UISchema(label = "ID")
        private Long id;

        @Schema(description = "Nome completo do colaborador.")
        @UISchema(label = "Nome")
        private String nome;

        @Schema(description = "Status funcional atual.")
        @UISchema(label = "Status")
        private EmployeeStatus status;

        @Schema(description = "Salario base mensal.")
        @UISchema(label = "Salario", numericFormat = NumericFormat.CURRENCY)
        private BigDecimal salario;

        @Schema(description = "Data de admissao.")
        @UISchema(label = "Admissao")
        private LocalDate admissionDate;

        @Schema(description = "Perfil de calculo salarial.")
        @UISchema(label = "Perfil Salarial")
        private String payrollProfile;

        @Schema(description = "Departamento de lotacao.")
        @UISchema(label = "Departamento")
        private String departmentNome;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getNome() {
            return nome;
        }

        public void setNome(String nome) {
            this.nome = nome;
        }

        public EmployeeStatus getStatus() {
            return status;
        }

        public void setStatus(EmployeeStatus status) {
            this.status = status;
        }

        public BigDecimal getSalario() {
            return salario;
        }

        public void setSalario(BigDecimal salario) {
            this.salario = salario;
        }

        public LocalDate getAdmissionDate() {
            return admissionDate;
        }

        public void setAdmissionDate(LocalDate admissionDate) {
            this.admissionDate = admissionDate;
        }

        public String getPayrollProfile() {
            return payrollProfile;
        }

        public void setPayrollProfile(String payrollProfile) {
            this.payrollProfile = payrollProfile;
        }

        public String getDepartmentNome() {
            return departmentNome;
        }

        public void setDepartmentNome(String departmentNome) {
            this.departmentNome = departmentNome;
        }
    }

    static class IntelligentEmployeeResourceMapper implements ResourceMapper<
            EmployeeEntity, IntelligentEmployeeResponseDTO, Void, Void, Long> {

        @Override
        public IntelligentEmployeeResponseDTO toResponse(EmployeeEntity entity) {
            IntelligentEmployeeResponseDTO dto = new IntelligentEmployeeResponseDTO();
            dto.setId(entity.getId());
            dto.setNome(entity.getNome());
            dto.setStatus(entity.getStatus());
            dto.setSalario(entity.getSalario());
            dto.setAdmissionDate(entity.getAdmissionDate());
            dto.setPayrollProfile(entity.getPayrollProfile());
            dto.setDepartmentNome(entity.getDepartment() != null ? entity.getDepartment().getNome() : null);
            return dto;
        }

        @Override
        public EmployeeEntity newEntity(Void dto) {
            throw new UnsupportedOperationException("Read-only analytical fixture");
        }

        @Override
        public void applyUpdate(EmployeeEntity entity, Void dto) {
            throw new UnsupportedOperationException("Read-only analytical fixture");
        }

        @Override
        public Long extractId(EmployeeEntity entity) {
            return entity.getId();
        }
    }

    @Service
    @Profile({"e2e-intelligence", "e2e-intelligence-unavailable"})
    static class IntelligentEmployeeService extends AbstractReadOnlyResourceService<
            EmployeeEntity, IntelligentEmployeeResponseDTO, Long, EmployeeFilterDTO> {

        private static final StatsFieldRegistry REGISTRY = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("status", "status")
                .labeledGroupByBucket("department", "department.id", "department.nome", Set.of(StatsMetric.COUNT))
                .temporalTimeSeriesField("admissionDate", "admissionDate")
                .categoricalGroupByBucket("payrollProfile", "payrollProfile")
                .numericHistogramMeasureField("salario", "salario")
                .add(StatsFieldDescriptor.groupByBucket("protectedProfile", "payrollProfile", Set.of(StatsMetric.COUNT))
                        .withMinBucketCount(2))
                .build();

        private final IntelligentEmployeeResourceMapper mapper = new IntelligentEmployeeResourceMapper();
        private final HttpServletRequest request;

        IntelligentEmployeeService(EmployeeRepository repository, HttpServletRequest request) {
            super(repository, EmployeeEntity.class);
            this.request = request;
        }

        @Override
        protected ResourceMapper<EmployeeEntity, IntelligentEmployeeResponseDTO, Void, Void, Long> getResourceMapper() {
            return mapper;
        }

        @Override
        public Optional<String> getDatasetVersion() {
            return Optional.of("e2e-intelligence");
        }

        @Override
        protected ResourceFilterAccessScope<EmployeeEntity> resolveResourceFilterAccessScope() {
            String principalName = request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName();
            if ("blocked-resource-user".equals(principalName)) {
                return ResourceFilterAccessScope.denied();
            }
            if ("hr-resource-user".equals(principalName)) {
                return ResourceFilterAccessScope.restricted((root, query, criteriaBuilder) ->
                        criteriaBuilder.equal(root.get("department").get("code"), "HR"));
            }
            return ResourceFilterAccessScope.unrestricted();
        }

        @Override
        public StatsSupportMode getGroupByStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsSupportMode getTimeSeriesStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsSupportMode getDistributionStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsSupportMode getComparisonStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsSupportMode getInferenceStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsSupportMode getAnomalyStatsSupportMode() {
            return StatsSupportMode.AUTO;
        }

        @Override
        public StatsFieldRegistry getStatsFieldRegistry() {
            return REGISTRY;
        }
    }

    @ApiResource(
            value = "/intelligent-employees",
            resourceKey = "human-resources.intelligent-employees",
            title = "Intelligent Employees Analytics",
            description = "Opt-in read-only resource exposing governed statistical inference and anomaly detection.",
            icon = "analytics",
            identity = @ResourceIdentity(keyField = "id", titleField = "nome")
    )
    @ApiGroup("human-resources")
    @Profile({"e2e-intelligence", "e2e-intelligence-unavailable"})
    @UiAnalytics(projections = {
            @AnalyticsProjection(
                    id = "employee-salary-inference",
                    intent = AnalyticsIntent.COMPARISON,
                    sourceOperation = AnalyticsOperation.INFERENCE,
                    primaryDimension = @AnalyticsDimensionBinding(field = "status"),
                    primaryMetrics = {@AnalyticsMetricBinding(field = "salario", aggregation = "median")}
            ),
            @AnalyticsProjection(
                    id = "employee-salary-anomalies",
                    intent = AnalyticsIntent.DISTRIBUTION,
                    sourceOperation = AnalyticsOperation.ANOMALIES,
                    primaryDimension = @AnalyticsDimensionBinding(field = "department"),
                    primaryMetrics = {@AnalyticsMetricBinding(field = "salario", aggregation = "sum")}
            )
    })
    static class IntelligentEmployeeController extends AbstractReadOnlyResourceController<
            IntelligentEmployeeResponseDTO, Long, EmployeeFilterDTO> {

        private final IntelligentEmployeeService service;

        IntelligentEmployeeController(IntelligentEmployeeService service) {
            this.service = service;
        }

        @Override
        protected IntelligentEmployeeService getService() {
            return service;
        }

        @Override
        protected Long getResponseId(IntelligentEmployeeResponseDTO dto) {
            return dto.getId();
        }
    }
}
