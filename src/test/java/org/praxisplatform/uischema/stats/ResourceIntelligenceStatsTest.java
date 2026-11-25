package org.praxisplatform.uischema.stats;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.intelligence.contracts.IntelligenceContractVersion;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyColumnMapping;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyMethod;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanRequest;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanSummary;
import org.praxisplatform.intelligence.contracts.epistemic.EpistemicKind;
import org.praxisplatform.intelligence.contracts.error.IntelligenceErrorCode;
import org.praxisplatform.intelligence.contracts.error.IntelligenceExecutionException;
import org.praxisplatform.intelligence.contracts.inference.InferenceRequest;
import org.praxisplatform.intelligence.contracts.inference.InferenceResponse;
import org.praxisplatform.intelligence.contracts.inference.InferenceTestKind;
import org.praxisplatform.intelligence.contracts.jobs.IntelligenceJobReceipt;
import org.praxisplatform.intelligence.contracts.modelcard.ModelCardDescriptor;
import org.praxisplatform.intelligence.contracts.provenance.AnalyticalProvenance;
import org.praxisplatform.intelligence.contracts.spi.ResourceIntelligenceExecutor;
import org.praxisplatform.intelligence.contracts.spi.TabularColumnDescriptor;
import org.praxisplatform.intelligence.contracts.spi.TabularFrameSource;
import org.praxisplatform.intelligence.contracts.spi.TabularLogicalType;
import org.praxisplatform.uischema.capability.ResourceStructuralCapabilities;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.service.base.ResourceFilterAccessScope;
import org.praxisplatform.uischema.governance.ResourceGovernanceInterceptor;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.praxisplatform.uischema.rest.response.CustomProblemDetail;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.AbstractBaseQueryResourceService;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.dto.AnomalyScanStatsRequest;
import org.praxisplatform.uischema.stats.dto.InferenceStatsRequest;
import org.praxisplatform.uischema.stats.service.jpa.JpaStatsTabularFrameSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;

import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceIntelligenceStatsTest {

    private static final StatsFieldRegistry REGISTRY = StatsFieldRegistry.builder()
            .categoricalGroupByBucket("cohortKey", "cohortKey")
            .categoricalGroupByBucket("groupKey", "groupKey")
            .categoricalGroupByBucket("unitKey", "unitKey")
            .categoricalGroupByBucket("variableOrRetroactive", "variableEligible")
            .numericHistogramMeasureField("expectedValue", "expectedAmount")
            .numericHistogramMeasureField("observedValue", "observedAmount")
            .numericHistogramMeasureField("residualValue", "residualAmount")
            .add(StatsFieldDescriptor.groupByBucket("protectedCohort", "cohortKey", Set.of(StatsMetric.COUNT))
                    .withMinBucketCount(5))
            .build();

    @Test
    void defaultQueryServiceRemainsDisabledAndThrowsUnsupportedOperation() {
        SampleService service = new SampleService(mockRepository(List.of()));
        ReflectionTestUtils.setField(service, "statsProperties", enabledProperties(100));
        ReflectionTestUtils.setField(service, "statsEligibility", new StatsEligibility());
        service.setResourceIntelligenceExecutor(recordingExecutor(new AtomicReference<>(), new AtomicReference<>()));

        ResourceStructuralCapabilities capabilities = service.getStructuralCapabilities();
        assertFalse(capabilities.statsInference());
        assertFalse(capabilities.statsAnomalies());
        assertFalse(capabilities.stats().inference());
        assertFalse(capabilities.stats().anomalies());

        assertThrows(UnsupportedOperationException.class, () -> service.inferenceStats(validInferenceRequest("expectedValue")));
        assertThrows(UnsupportedOperationException.class, () -> service.anomalyScanStats(validAnomalyRequest(null)));
    }

    @Test
    void optedInServiceWithoutExecutorPublishesFalseCapabilityAndThrowsServiceUnavailableMappedTo503() {
        SampleService service = new SampleService(mockRepository(List.of()));
        service.inferenceMode = StatsSupportMode.AUTO;
        service.anomalyMode = StatsSupportMode.AUTO;
        ReflectionTestUtils.setField(service, "statsProperties", enabledProperties(100));
        ReflectionTestUtils.setField(service, "statsEligibility", new StatsEligibility());

        ResourceStructuralCapabilities capabilities = service.getStructuralCapabilities();
        assertFalse(capabilities.statsInference());
        assertFalse(capabilities.statsAnomalies());

        IntelligenceExecutionException ex = assertThrows(
                IntelligenceExecutionException.class,
                () -> service.inferenceStats(validInferenceRequest("expectedValue"))
        );
        assertEquals(IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE, ex.errorCode());

        IntelligenceExecutionException anomalyEx = assertThrows(
                IntelligenceExecutionException.class,
                () -> service.anomalyScanStats(validAnomalyRequest(null))
        );
        assertEquals(IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE, anomalyEx.errorCode());

        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/sample-payroll/stats/inference");
        ResponseEntity<RestApiResponse<Object>> response = handler.handleIntelligenceExecution(
                new IntelligenceExecutionException(
                        IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE,
                        "Analytics engine unreachable",
                        "corr-503"
                ),
                new ServletWebRequest(servletRequest)
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("corr-503", response.getHeaders().getFirst("X-Correlation-ID"));
        assertNotNull(response.getBody());
        CustomProblemDetail detail = response.getBody().getErrors().getFirst();
        assertEquals("INTELLIGENCE_ENGINE_UNAVAILABLE", detail.getProperties().get("errorCode"));
        assertEquals("corr-503", detail.getProperties().get("correlationId"));

        MockHttpServletRequest inboundRequest = new MockHttpServletRequest("POST", "/sample-payroll/stats/inference");
        inboundRequest.addHeader("X-Correlation-ID", "inbound-corr-99");
        ResponseEntity<RestApiResponse<Object>> fallbackCorr = handler.handleIntelligenceExecution(
                new IntelligenceExecutionException(
                        IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE,
                        "Analytics engine unreachable"
                ),
                new ServletWebRequest(inboundRequest)
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, fallbackCorr.getStatusCode());
        assertEquals("inbound-corr-99", fallbackCorr.getHeaders().getFirst("X-Correlation-ID"));
        assertEquals(
                "inbound-corr-99",
                fallbackCorr.getBody().getErrors().getFirst().getProperties().get("correlationId")
        );
    }

    @Test
    void eligibilityValidatesInferenceAndAnomalyColumnsAndRejectsGovernanceLeaksOrEntityKeyReuse() {
        StatsEligibility eligibility = new StatsEligibility();

        StatsEligibility.ResolvedInferenceFields resolvedInference = eligibility.validateInference(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(),
                        null,
                        null,
                        List.of(InferenceTestKind.WILCOXON_SIGNED_RANK),
                        "protectedCohort",
                        "expectedValue",
                        "observedValue",
                        null,
                        true,
                        0.05d,
                        null,
                        2,
                        42L,
                        null
                ),
                REGISTRY,
                3
        );
        assertEquals(Set.of("protectedCohort", "expectedValue", "observedValue"),
                resolvedInference.projectedFields().keySet());
        // protectedCohort has minBucketCount=5 which dominates both default(3) and requested(2)
        assertEquals(5, resolvedInference.effectiveMinBucketCount());

        // Reject unregistered field or governance-stripped field
        StatsFieldRegistry restricted = withoutFields(REGISTRY, "observedValue");
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateInference(
                validInferenceRequest("observedValue"),
                restricted,
                3
        ));
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateInference(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        List.of(InferenceTestKind.WELCH_T),
                        "cohortKey", null, null, null,
                        null, null, null, null, null, null
                ),
                REGISTRY,
                1
        ));
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateInference(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        List.of(InferenceTestKind.WELCH_T),
                        null, "expectedValue", null, null,
                        null, null, null, 0, null, null
                ),
                REGISTRY,
                1
        ));

        StatsEligibility.ResolvedAnomalyColumns resolvedAnomalies = eligibility.validateAnomalyScan(
                validAnomalyRequest(null),
                REGISTRY,
                3
        );
        assertEquals("opaqueEntityKey", resolvedAnomalies.entityKeyColumn());
        assertTrue(resolvedAnomalies.projectedFields().containsKey("cohortKey"));
        assertTrue(resolvedAnomalies.projectedFields().containsKey("residualValue"));
        assertEquals(3, resolvedAnomalies.effectiveMinBucketCount());

        // Reject reusing entityKeyColumn as an analytical column
        AnomalyColumnMapping unsafeMapping = new AnomalyColumnMapping(
                "cohortKey",
                "cohortKey",
                "groupKey",
                "expectedValue",
                "expectedValue",
                "observedValue",
                "residualValue",
                "variableOrRetroactive",
                "unitKey"
        );
        IllegalArgumentException reuseEx = assertThrows(IllegalArgumentException.class, () ->
                eligibility.validateAnomalyScan(validAnomalyRequest(unsafeMapping), REGISTRY, 3));
        assertTrue(reuseEx.getMessage().contains("entityKeyColumn"));

        // Reject missing column required by enabled method
        assertThrows(IllegalArgumentException.class, () ->
                eligibility.validateAnomalyScan(
                        validAnomalyRequest(null),
                        withoutFields(REGISTRY, "residualValue"),
                        3
                ));

        // Per-test field requirements and duplicate/null rejection
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateInference(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        List.of(InferenceTestKind.WELCH_T),
                        "cohortKey", null, "observedValue", null,
                        null, null, null, null, null, null
                ),
                REGISTRY,
                1
        ));
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateInference(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        List.of(InferenceTestKind.WILCOXON_SIGNED_RANK),
                        "cohortKey", null, "observedValue", null,
                        null, null, null, null, null, null
                ),
                REGISTRY,
                1
        ));
        StatsEligibility.ResolvedInferenceFields residualOnly = eligibility.validateInference(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        List.of(InferenceTestKind.WILCOXON_SIGNED_RANK),
                        "cohortKey", null, null, "residualValue",
                        null, null, null, null, null, null
                ),
                REGISTRY,
                1
        );
        assertEquals(Set.of("cohortKey", "residualValue"), residualOnly.projectedFields().keySet());

        assertThrows(IllegalArgumentException.class, () -> eligibility.validateInference(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        List.of(InferenceTestKind.BENFORD_NIGRINI_MAD),
                        null, null, "observedValue", null,
                        false, null, null, null, null, null
                ),
                REGISTRY,
                1
        ));
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateInference(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        List.of(InferenceTestKind.WELCH_T, InferenceTestKind.WELCH_T),
                        null, "expectedValue", "observedValue", null,
                        null, null, null, null, null, null
                ),
                REGISTRY,
                1
        ));
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateInference(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        java.util.Arrays.asList(InferenceTestKind.WELCH_T, null),
                        null, "expectedValue", "observedValue", null,
                        null, null, null, null, null, null
                ),
                REGISTRY,
                1
        ));
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateAnomalyScan(
                new AnomalyScanStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        Set.of(AnomalyMethod.NIGRINI_BENFORD),
                        null, 0.05d, null, 3.5d, 15, 20,
                        false, 500, null, 50, 1L, null
                ),
                REGISTRY,
                1
        ));
    }

    @Test
    void datasetKeySpoofingIsRejectedByInferenceAndAnomalyRequests() {
        InferenceStatsRequest<SampleFilterDTO> spoofedInference = new InferenceStatsRequest<>(
                new SampleFilterDTO(), null, "foreign.dataset",
                List.of(InferenceTestKind.WELCH_T),
                null, "expectedValue", "observedValue", null,
                null, null, null, null, null, null
        );
        assertThrows(IllegalArgumentException.class, () ->
                spoofedInference.toInferenceRequest("workforce.sample-payroll", 3));

        InferenceStatsRequest<SampleFilterDTO> matchingInference = new InferenceStatsRequest<>(
                new SampleFilterDTO(), null, "workforce.sample-payroll",
                List.of(InferenceTestKind.WELCH_T),
                null, "expectedValue", "observedValue", null,
                null, null, null, null, null, null
        );
        assertEquals("workforce.sample-payroll",
                matchingInference.toInferenceRequest("workforce.sample-payroll", 3).datasetKey());

        AnomalyScanStatsRequest<SampleFilterDTO> spoofedAnomaly = new AnomalyScanStatsRequest<>(
                new SampleFilterDTO(), null, "foreign.dataset",
                Set.of(AnomalyMethod.ROBUST_MAD_ZSCORE),
                null, 0.05d, null, 3.5d, 15, 20,
                false, 500, null, 50, 1L, null
        );
        assertThrows(IllegalArgumentException.class, () ->
                spoofedAnomaly.toAnomalyScanRequest("workforce.sample-payroll", 3));
    }

    @Test
    void compositeIdComponentsEncodeConsistentlyAndUtilDateSerializesAsIso8601WithShortReadOnlyTx() {
        assertEquals("10%2501:a%3Ab", CompositeIdSupport.encodeComponents(List.of("10%01", "a:b")));

        Instant expectedInstant = Instant.parse("2024-03-04T12:15:00Z");
        SamplePayrollItem entity = item(7L, "A", true, "100.00", "110.00", "10.00");
        entity.eventTimestamp = Date.from(expectedInstant);
        BaseCrudRepository<SamplePayrollItem, Long> repo = mockRepository(List.of(entity));

        List<Boolean> seenReadOnlyFlags = new ArrayList<>();
        List<Boolean> committedBeforeReturn = new ArrayList<>();
        AbstractPlatformTransactionManager recordingTxManager = new AbstractPlatformTransactionManager() {
            private boolean committed = false;

            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                committed = false;
                seenReadOnlyFlags.add(definition.isReadOnly());
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                committed = true;
                committedBeforeReturn.add(committed);
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                committed = false;
            }
        };
        TransactionTemplate readOnlyTx = new TransactionTemplate(recordingTxManager);
        readOnlyTx.setReadOnly(true);

        StatsFieldRegistry dateRegistry = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("eventTimestamp", "eventTimestamp")
                .build();
        JpaStatsTabularFrameSource<SamplePayrollItem, Long> source = new JpaStatsTabularFrameSource<>(
                "workforce.sample-payroll",
                null,
                repo,
                SamplePayrollItem.class,
                (root, query, cb) -> cb.conjunction(),
                List.of("id"),
                SamplePayrollItem::getId,
                "opaqueEntityKey",
                Map.of("eventTimestamp", dateRegistry.resolve("eventTimestamp").orElseThrow()),
                100,
                readOnlyTx
        );

        assertEquals(TabularLogicalType.TIMESTAMP, source.columns().get(1).logicalType());
        List<Map<String, Object>> streamed = new ArrayList<>();
        source.streamRows(streamed::add);
        assertEquals(1, streamed.size());
        assertEquals(expectedInstant.toString(), streamed.getFirst().get("eventTimestamp"));
        assertTrue(seenReadOnlyFlags.stream().allMatch(Boolean::booleanValue));
        assertFalse(committedBeforeReturn.isEmpty());
    }

    @Test
    void statsCapabilityRetainsFieldsForIntelligenceOnlyResourcesAndDisablesEmptyRegistry() {
        StatsCapability intelligenceOnly = StatsCapability.from(
                REGISTRY,
                StatsSupportMode.DISABLED,
                StatsSupportMode.DISABLED,
                StatsSupportMode.DISABLED,
                StatsSupportMode.AUTO,
                StatsSupportMode.DISABLED,
                2,
                500,
                366,
                3,
                10_000
        );
        assertTrue(intelligenceOnly.inference());
        assertFalse(intelligenceOnly.anomalies());
        assertEquals(REGISTRY.descriptors().size(), intelligenceOnly.fields().size());
        assertTrue(intelligenceOnly.fields().stream().noneMatch(StatsFieldCapability::groupByEligible));

        StatsCapability emptyRegistry = StatsCapability.from(
                StatsFieldRegistry.empty(),
                StatsSupportMode.DISABLED,
                StatsSupportMode.DISABLED,
                StatsSupportMode.DISABLED,
                StatsSupportMode.AUTO,
                StatsSupportMode.AUTO,
                2,
                500,
                366,
                3,
                10_000
        );
        assertFalse(emptyRegistry.inference());
        assertFalse(emptyRegistry.anomalies());
        assertTrue(emptyRegistry.fields().isEmpty());
    }

    @Test
    void jpaTabularFrameSourceStreamsPseudonymizedColumnsHonorsMaxRowsAndGovernanceInterceptor() {
        List<SamplePayrollItem> rows = List.of(
                item(1L, "A", true, "7000.00", "7350.00", "350.00"),
                item(2L, "A", false, "5000.00", "5100.00", "100.00")
        );
        BaseCrudRepository<SamplePayrollItem, Long> repo = mockRepository(rows);
        SampleService service = new SampleService(repo);
        service.inferenceMode = StatsSupportMode.AUTO;
        service.anomalyMode = StatsSupportMode.AUTO;
        AtomicReference<AnomalyScanRequest> capturedRequest = new AtomicReference<>();
        List<Map<String, Object>> streamedRows = new ArrayList<>();
        AtomicReference<List<TabularColumnDescriptor>> capturedColumns = new AtomicReference<>();

        service.setResourceIntelligenceExecutor(new ResourceIntelligenceExecutor() {
            @Override
            public InferenceResponse executeInference(String resourceKey, InferenceRequest request, TabularFrameSource frameSource) {
                return stubInferenceResponse(request);
            }

            @Override
            public AnomalyScanResponse executeAnomalyScan(String resourceKey, AnomalyScanRequest request, TabularFrameSource frameSource) {
                capturedRequest.set(request);
                capturedColumns.set(frameSource.columns());
                frameSource.streamRows(streamedRows::add);
                return stubAnomalyResponse(request, streamedRows.size());
            }

            @Override
            public Optional<IntelligenceJobReceipt> getJob(String jobId) {
                return Optional.empty();
            }
        });
        ReflectionTestUtils.setField(service, "statsProperties", enabledProperties(10));
        ReflectionTestUtils.setField(service, "statsEligibility", new StatsEligibility());
        service.accessScope = ResourceFilterAccessScope.restricted((root, query, cb) -> cb.equal(root.get("ownerId"), 99L));

        ResourceStructuralCapabilities caps = service.getStructuralCapabilities();
        assertTrue(caps.statsInference());
        assertTrue(caps.statsAnomalies());
        assertTrue(caps.stats().inference());
        assertTrue(caps.stats().anomalies());

        AnomalyScanResponse response = service.anomalyScanStats(new AnomalyScanStatsRequest<>(
                new SampleFilterDTO(),
                "req-anom-1",
                null,
                Set.of(AnomalyMethod.ROBUST_MAD_ZSCORE),
                null,
                0.05d,
                BigDecimal.valueOf(100),
                3.5d,
                15,
                20,
                false,
                500,
                2,
                50,
                7L,
                null
        ));
        assertNotNull(response);
        assertEquals("workforce.sample-payroll", capturedRequest.get().datasetKey());
        assertEquals(3, capturedRequest.get().minBucketCount());
        assertEquals(
                new TabularColumnDescriptor("opaqueEntityKey", TabularLogicalType.ENTITY_ID_TO_PSEUDONYMIZE),
                capturedColumns.get().getFirst()
        );
        assertTrue(capturedColumns.get().contains(
                new TabularColumnDescriptor("variableOrRetroactive", TabularLogicalType.BOOLEAN)));
        assertTrue(capturedColumns.get().contains(
                new TabularColumnDescriptor("observedValue", TabularLogicalType.DECIMAL)));
        assertEquals(2, streamedRows.size());
        assertEquals("1", streamedRows.getFirst().get("opaqueEntityKey"));
        assertEquals(new BigDecimal("7350.00"), streamedRows.getFirst().get("observedValue"));

        // Verify governance filterStatsRegistry removes protected fields before inference validation
        service.setResourceGovernanceInterceptors(List.of(new ResourceGovernanceInterceptor() {
            @Override
            public StatsFieldRegistry filterStatsRegistry(BaseResourceQueryService<?, ?, ?> s, StatsFieldRegistry registry) {
                return withoutFields(registry, "protectedCohort");
            }
        }));
        assertThrows(IllegalArgumentException.class, () -> service.inferenceStats(
                new InferenceStatsRequest<>(
                        new SampleFilterDTO(), null, null,
                        List.of(InferenceTestKind.WELCH_T),
                        "protectedCohort", "expectedValue", "observedValue", null,
                        null, null, null, null, null, null
                )
        ));

        // Verify Repository specification passed to findAll comes from resolveResourceFilterQuery + accessScope
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<SamplePayrollItem>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(repo).findAll(specCaptor.capture(), any(Pageable.class));
        assertNotNull(specCaptor.getValue());

        // Verify maxRows overflow raises PAYLOAD_TOO_LARGE mapped to 413
        JpaStatsTabularFrameSource<SamplePayrollItem, Long> tightSource = new JpaStatsTabularFrameSource<>(
                "workforce.sample-payroll",
                null,
                repo,
                SamplePayrollItem.class,
                (root, query, cb) -> cb.conjunction(),
                List.of("id"),
                SamplePayrollItem::getId,
                "opaqueEntityKey",
                Map.of("observedValue", REGISTRY.resolve("observedValue").orElseThrow()),
                1
        );
        IntelligenceExecutionException payloadEx = assertThrows(
                IntelligenceExecutionException.class,
                () -> tightSource.streamRows(row -> { })
        );
        assertEquals(IntelligenceErrorCode.PAYLOAD_TOO_LARGE, payloadEx.errorCode());
        ResponseEntity<RestApiResponse<Object>> status413 = new GlobalExceptionHandler().handleIntelligenceExecution(
                payloadEx,
                new ServletWebRequest(new MockHttpServletRequest("POST", "/sample-payroll/stats/anomalies"))
        );
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, status413.getStatusCode());
    }

    @Test
    void anomalyScanStatsRequestAutoEnablesBenfordOnlyWhenMethodIncludesNigriniAndOmitted() {
        AnomalyScanStatsRequest<SampleFilterDTO> autoEnabled = new AnomalyScanStatsRequest<>(
                null,
                "benford-auto",
                "workforce.sample-payroll",
                Set.of(AnomalyMethod.NIGRINI_BENFORD),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        assertTrue(autoEnabled.toAnomalyScanRequest("workforce.sample-payroll", 10).enableBenford());

        AnomalyScanStatsRequest<SampleFilterDTO> explicitlyDisabled = new AnomalyScanStatsRequest<>(
                null,
                "benford-off",
                "workforce.sample-payroll",
                Set.of(AnomalyMethod.NIGRINI_BENFORD),
                null,
                null,
                null,
                null,
                null,
                null,
                Boolean.FALSE,
                null,
                null,
                null,
                null,
                null
        );
        assertFalse(explicitlyDisabled.toAnomalyScanRequest("workforce.sample-payroll", 10).enableBenford());

        AnomalyScanStatsRequest<SampleFilterDTO> nonBenfordDefault = new AnomalyScanStatsRequest<>(
                null,
                "benford-none",
                "workforce.sample-payroll",
                Set.of(AnomalyMethod.ROBUST_MAD_ZSCORE),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        assertFalse(nonBenfordDefault.toAnomalyScanRequest("workforce.sample-payroll", 10).enableBenford());
    }

    private static StatsProperties enabledProperties(int maxInMemoryRows) {
        return new StatsProperties(
                true,
                20,
                100,
                StatsSupportMode.AUTO,
                1_000,
                366,
                2,
                500,
                366,
                3,
                maxInMemoryRows
        );
    }

    private static InferenceStatsRequest<SampleFilterDTO> validInferenceRequest(String observedField) {
        return new InferenceStatsRequest<>(
                new SampleFilterDTO(),
                "req-inf-1",
                null,
                List.of(InferenceTestKind.WILCOXON_SIGNED_RANK),
                "cohortKey",
                "expectedValue",
                observedField,
                null,
                false,
                0.05d,
                null,
                3,
                1L,
                null
        );
    }

    private static AnomalyScanStatsRequest<SampleFilterDTO> validAnomalyRequest(AnomalyColumnMapping mapping) {
        return new AnomalyScanStatsRequest<>(
                new SampleFilterDTO(),
                "req-anom-1",
                null,
                Set.of(AnomalyMethod.ROBUST_MAD_ZSCORE),
                mapping,
                0.05d,
                null,
                3.5d,
                15,
                20,
                false,
                500,
                null,
                50,
                1L,
                null
        );
    }

    @SuppressWarnings("unchecked")
    private static BaseCrudRepository<SamplePayrollItem, Long> mockRepository(List<SamplePayrollItem> rows) {
        BaseCrudRepository<SamplePayrollItem, Long> repo = mock(BaseCrudRepository.class);
        when(repo.count(any(Specification.class))).thenReturn((long) rows.size());
        when(repo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows));
        return repo;
    }

    private static SamplePayrollItem item(
            Long id,
            String cohort,
            boolean variable,
            String expected,
            String observed,
            String residual
    ) {
        SamplePayrollItem entity = new SamplePayrollItem();
        entity.id = id;
        entity.cohortKey = cohort;
        entity.groupKey = "G1";
        entity.unitKey = "U1";
        entity.variableEligible = variable;
        entity.expectedAmount = new BigDecimal(expected);
        entity.observedAmount = new BigDecimal(observed);
        entity.residualAmount = new BigDecimal(residual);
        entity.ownerId = 99L;
        return entity;
    }

    private static ResourceIntelligenceExecutor recordingExecutor(
            AtomicReference<InferenceRequest> infRef,
            AtomicReference<AnomalyScanRequest> anomRef
    ) {
        return new ResourceIntelligenceExecutor() {
            @Override
            public InferenceResponse executeInference(String resourceKey, InferenceRequest request, TabularFrameSource frameSource) {
                if (infRef != null) {
                    infRef.set(request);
                }
                return stubInferenceResponse(request);
            }

            @Override
            public AnomalyScanResponse executeAnomalyScan(String resourceKey, AnomalyScanRequest request, TabularFrameSource frameSource) {
                if (anomRef != null) {
                    anomRef.set(request);
                }
                return stubAnomalyResponse(request, 0);
            }

            @Override
            public Optional<IntelligenceJobReceipt> getJob(String jobId) {
                return Optional.empty();
            }
        };
    }

    private static StatsFieldRegistry withoutFields(StatsFieldRegistry registry, String... excludedFields) {
        Set<String> excluded = Set.of(excludedFields);
        return StatsFieldRegistry.of(
                registry.descriptors().stream()
                        .filter(descriptor -> !excluded.contains(descriptor.field()))
                        .toList()
        );
    }

    private static InferenceResponse stubInferenceResponse(InferenceRequest request) {
        AnalyticalProvenance provenance = new AnalyticalProvenance(
                IntelligenceContractVersion.V1,
                "stub-engine",
                "1.0.0",
                "java-21",
                null,
                "stub-inference-v1",
                "1.0.0",
                request.seed(),
                request.asOfDate(),
                null,
                null,
                EpistemicKind.CALIBRATED_PROBABILITY,
                0L,
                0,
                request.requestId(),
                null,
                Instant.now()
        );
        ModelCardDescriptor modelCard = new ModelCardDescriptor(
                "stub-inference-v1",
                "1.0.0",
                "Stub Inference Model",
                "workforce",
                "non-parametric",
                EpistemicKind.CALIBRATED_PROBABILITY,
                "Test validation",
                List.of(),
                5,
                List.of("Independent observations"),
                "Human review required"
        );
        return new InferenceResponse(IntelligenceContractVersion.V1, provenance, modelCard, List.of());
    }

    private static AnomalyScanResponse stubAnomalyResponse(AnomalyScanRequest request, long rowsScanned) {
        AnalyticalProvenance provenance = new AnalyticalProvenance(
                IntelligenceContractVersion.V1,
                "stub-engine",
                "1.0.0",
                "java-21",
                null,
                "stub-anomaly-v1",
                "1.0.0",
                request.seed(),
                request.asOfDate(),
                null,
                null,
                EpistemicKind.CALIBRATED_PROBABILITY,
                rowsScanned,
                0,
                request.requestId(),
                null,
                Instant.now()
        );
        ModelCardDescriptor modelCard = new ModelCardDescriptor(
                "stub-anomaly-v1",
                "1.0.0",
                "Stub Anomaly Model",
                "workforce",
                "robust-mad",
                EpistemicKind.CALIBRATED_PROBABILITY,
                "Test validation",
                List.of(),
                5,
                List.of("Minimum cohort sample"),
                "Human review required"
        );
        return new AnomalyScanResponse(
                IntelligenceContractVersion.V1,
                provenance,
                modelCard,
                List.of(),
                new AnomalyScanSummary(rowsScanned, 0, 0, 0, 0, 0, BigDecimal.ZERO, false)
        );
    }

    static class SampleService extends AbstractBaseQueryResourceService<
            SamplePayrollItem, SampleResponse, Long, SampleFilterDTO> {
        StatsSupportMode inferenceMode = StatsSupportMode.DISABLED;
        StatsSupportMode anomalyMode = StatsSupportMode.DISABLED;
        ResourceFilterAccessScope<SamplePayrollItem> accessScope = ResourceFilterAccessScope.unrestricted();

        SampleService(BaseCrudRepository<SamplePayrollItem, Long> repository) {
            super(repository, SamplePayrollItem.class);
        }

        @Override
        protected String resolveResourceKey() {
            return "workforce.sample-payroll";
        }

        @Override
        protected ResourceMapper<SamplePayrollItem, SampleResponse, Void, Void, Long> getResourceMapper() {
            return new ResourceMapper<>() {
                @Override
                public SampleResponse toResponse(SamplePayrollItem entity) {
                    return new SampleResponse(entity.id);
                }

                @Override
                public SamplePayrollItem newEntity(Void dto) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void applyUpdate(SamplePayrollItem entity, Void dto) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Long extractId(SamplePayrollItem entity) {
                    return entity.id;
                }
            };
        }

        @Override
        protected ResourceFilterAccessScope<SamplePayrollItem> resolveResourceFilterAccessScope() {
            return accessScope;
        }

        @Override
        public StatsSupportMode getInferenceStatsSupportMode() {
            return inferenceMode;
        }

        @Override
        public StatsSupportMode getAnomalyStatsSupportMode() {
            return anomalyMode;
        }

        @Override
        public StatsFieldRegistry getStatsFieldRegistry() {
            return REGISTRY;
        }
    }

    static class SamplePayrollItem {
        @Id
        Long id;
        String cohortKey;
        String groupKey;
        String unitKey;
        Boolean variableEligible;
        BigDecimal expectedAmount;
        BigDecimal observedAmount;
        BigDecimal residualAmount;
        Date eventTimestamp;
        Long ownerId;

        Long getId() {
            return id;
        }
    }

    record SampleResponse(Long id) { }

    static class SampleFilterDTO implements GenericFilterDTO { }
}
