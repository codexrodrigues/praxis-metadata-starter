package org.praxisplatform.uischema.stats;

import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.praxisplatform.intelligence.contracts.IntelligenceContractVersion;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualCandidate;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualDirection;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualFeasibilityStatus;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualLeverDelta;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualLeverSpec;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualLeverType;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualRequest;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualResponse;
import org.praxisplatform.intelligence.contracts.epistemic.EpistemicKind;
import org.praxisplatform.intelligence.contracts.error.IntelligenceErrorCode;
import org.praxisplatform.intelligence.contracts.error.IntelligenceExecutionException;
import org.praxisplatform.intelligence.contracts.inference.InferenceRequest;
import org.praxisplatform.intelligence.contracts.inference.InferenceResponse;
import org.praxisplatform.intelligence.contracts.jobs.IntelligenceJobReceipt;
import org.praxisplatform.intelligence.contracts.jobs.IntelligenceJobStatus;
import org.praxisplatform.intelligence.contracts.modelcard.ModelCardDescriptor;
import org.praxisplatform.intelligence.contracts.provenance.AnalyticalProvenance;
import org.praxisplatform.intelligence.contracts.provenance.SuppressionStatus;
import org.praxisplatform.intelligence.contracts.simulation.AnchorRevalidationStatus;
import org.praxisplatform.intelligence.contracts.simulation.AnchorScenarioPoint;
import org.praxisplatform.intelligence.contracts.simulation.DependenceMethod;
import org.praxisplatform.intelligence.contracts.simulation.FactorDistributionKind;
import org.praxisplatform.intelligence.contracts.simulation.FanChartPoint;
import org.praxisplatform.intelligence.contracts.simulation.SamplingStrategy;
import org.praxisplatform.intelligence.contracts.simulation.SimulationModelPoint;
import org.praxisplatform.intelligence.contracts.simulation.SimulationThresholdSpec;
import org.praxisplatform.intelligence.contracts.simulation.StochasticFactorSpec;
import org.praxisplatform.intelligence.contracts.simulation.StochasticSimulationRequest;
import org.praxisplatform.intelligence.contracts.simulation.StochasticSimulationResponse;
import org.praxisplatform.intelligence.contracts.simulation.ThresholdBreachOutcome;
import org.praxisplatform.intelligence.contracts.simulation.ThresholdDirection;
import org.praxisplatform.intelligence.contracts.simulation.TornadoSensitivityDriver;
import org.praxisplatform.intelligence.contracts.spi.AnchorScenarioRevalidator;
import org.praxisplatform.intelligence.contracts.spi.CounterfactualRuleValidator;
import org.praxisplatform.intelligence.contracts.spi.CounterfactualRuleVerdict;
import org.praxisplatform.intelligence.contracts.spi.CounterfactualSearchOrchestrator;
import org.praxisplatform.intelligence.contracts.spi.ResourceIntelligenceExecutor;
import org.praxisplatform.intelligence.contracts.spi.StochasticRevalidationPolicy;
import org.praxisplatform.intelligence.contracts.spi.StochasticSimulationOrchestrator;
import org.praxisplatform.intelligence.contracts.spi.TabularFrameSource;
import org.praxisplatform.uischema.capability.ResourceStructuralCapabilities;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.praxisplatform.uischema.rest.response.CustomProblemDetail;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.security.PraxisMetadataRequestMatchers;
import org.praxisplatform.uischema.service.base.AbstractBaseQueryResourceService;
import org.praxisplatform.uischema.service.base.ResourceFilterAccessScope;
import org.praxisplatform.uischema.stats.dto.CounterfactualStatsRequest;
import org.praxisplatform.uischema.stats.dto.StochasticSimulationStatsRequest;
import org.slf4j.MDC;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPatternParser;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceSimulationAndCounterfactualStatsTest {

    @Test
    void requestDtosApplyCanonicalDefaultsAndEnforceDatasetAndRequiredFieldInvariants() {
        StochasticSimulationStatsRequest<SampleFilter> simReq = new StochasticSimulationStatsRequest<>(
                new SampleFilter(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(sampleModelPoint("pt-1", "src-a", 10, "1000.00")),
                List.of(sampleFactor("wage_index")),
                List.of(),
                List.of(),
                null,
                null,
                2
        );

        StochasticSimulationRequest contractSim = simReq.toStochasticSimulationRequest("workforce.budgets", 5, null);
        assertEquals("workforce.budgets", contractSim.datasetKey());
        assertEquals(10_000, contractSim.scenarioCount());
        assertEquals(12, contractSim.horizonPeriods());
        assertEquals(42L, contractSim.seed());
        assertEquals(SamplingStrategy.QUASI_MONTE_CARLO_SOBOL, contractSim.samplingStrategy());
        assertEquals(DependenceMethod.IMAN_CONOVER, contractSim.dependenceMethod());
        assertEquals(5, contractSim.minBucketCount());

        StochasticSimulationStatsRequest<SampleFilter> mismatchedSim = new StochasticSimulationStatsRequest<>(
                new SampleFilter(), null, "other.dataset", 500, 6, 42L, null,
                SamplingStrategy.QUASI_MONTE_CARLO_SOBOL, DependenceMethod.IMAN_CONOVER,
                List.of(sampleModelPoint("pt-1", "src-a", 10, "1000.00")),
                List.of(sampleFactor("wage_index")), List.of(), List.of(), null, null, 5
        );
        assertThrows(IllegalArgumentException.class,
                () -> mismatchedSim.toStochasticSimulationRequest("workforce.budgets", 5, null));

        StochasticSimulationStatsRequest<SampleFilter> emptyPointsSim = new StochasticSimulationStatsRequest<>(
                new SampleFilter(), null, null, 500, 6, 42L, null,
                SamplingStrategy.QUASI_MONTE_CARLO_SOBOL, DependenceMethod.IMAN_CONOVER,
                List.of(), List.of(sampleFactor("wage_index")), List.of(), List.of(), null, null, 5
        );
        assertThrows(IllegalArgumentException.class,
                () -> emptyPointsSim.toStochasticSimulationRequest("workforce.budgets", 5, List.of()));

        CounterfactualStatsRequest<SampleFilter> cfReq = new CounterfactualStatsRequest<>(
                new SampleFilter(),
                null,
                null,
                "fiscal_ratio",
                CounterfactualDirection.DECREASE_TO_TARGET,
                new BigDecimal("0.55"),
                new BigDecimal("0.49"),
                null,
                List.of(sampleLever("headcount_delta")),
                null,
                null,
                null,
                null
        );
        CounterfactualRequest contractCf = cfReq.toCounterfactualRequest("workforce.budgets");
        assertEquals("workforce.budgets", contractCf.datasetKey());
        assertEquals(5, contractCf.maxCandidates());
        assertEquals(3, contractCf.maxChangedLevers());
        assertEquals(42L, contractCf.seed());

        CounterfactualStatsRequest<SampleFilter> missingDirection = new CounterfactualStatsRequest<>(
                new SampleFilter(), null, null, "fiscal_ratio", null,
                new BigDecimal("0.55"), new BigDecimal("0.49"), null,
                List.of(sampleLever("headcount_delta")), null, null, null, null
        );
        assertThrows(IllegalArgumentException.class,
                () -> missingDirection.toCounterfactualRequest("workforce.budgets"));
    }

    @Test
    void emptyRegistrySimulationAndCounterfactualPublishStructuralCapabilitiesAndEnforceFailClosedGates() {
        SampleSimulationService service = new SampleSimulationService(mockRepository());
        service.simulationMode = StatsSupportMode.AUTO;
        service.counterfactualMode = StatsSupportMode.AUTO;
        ReflectionTestUtils.setField(service, "statsProperties",
                new StatsProperties(true, 20, 100, StatsSupportMode.AUTO, 1000, 366, 2, 500, 366, 5, 1000));

        // Without executor/orchestrator, capabilities remain false and calls fail-closed with 503 code
        ResourceStructuralCapabilities withoutEngine = service.getStructuralCapabilities();
        assertFalse(withoutEngine.statsSimulation());
        assertFalse(withoutEngine.statsCounterfactual());
        assertFalse(withoutEngine.stats().simulation());
        assertFalse(withoutEngine.stats().counterfactual());

        IntelligenceExecutionException noEngineSim = assertThrows(
                IntelligenceExecutionException.class,
                () -> service.stochasticSimulationStats(validSimRequest())
        );
        assertEquals(IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE, noEngineSim.errorCode());

        // Wire executor without validator: simulation is structurally enabled even with EMPTY StatsFieldRegistry,
        // whereas counterfactual stays false until a validator or orchestrator is configured.
        AtomicReference<StochasticSimulationRequest> capturedSim = new AtomicReference<>();
        AtomicReference<CounterfactualRequest> capturedCf = new AtomicReference<>();
        service.setResourceIntelligenceExecutor(new ResourceIntelligenceExecutor() {
            @Override
            public org.praxisplatform.intelligence.contracts.inference.InferenceResponse executeInference(
                    String rk, InferenceRequest req, TabularFrameSource src) {
                throw new UnsupportedOperationException();
            }

            @Override
            public org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse executeAnomalyScan(
                    String rk, org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanRequest req,
                    TabularFrameSource src) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StochasticSimulationResponse executeStochasticSimulation(
                    String rk, StochasticSimulationRequest req) {
                capturedSim.set(req);
                return stubSimulationResponse(req, List.of(
                        new AnchorScenarioPoint(
                                "P50",
                                0.50d,
                                10,
                                new BigDecimal("1000.00"),
                                Map.of("wage_index", 0.05d),
                                null,
                                null,
                                AnchorRevalidationStatus.NOT_REVALIDATED
                        ),
                        new AnchorScenarioPoint(
                                "P90",
                                0.90d,
                                90,
                                new BigDecimal("1200.00"),
                                Map.of("wage_index", 0.12d),
                                null,
                                null,
                                AnchorRevalidationStatus.NOT_REVALIDATED
                        )
                ));
            }

            @Override
            public CounterfactualResponse executeCounterfactualSearch(String rk, CounterfactualRequest req) {
                capturedCf.set(req);
                return stubCounterfactualResponse(req, List.of(
                        new CounterfactualCandidate(
                                "cand-rejected-by-upstream",
                                1,
                                List.of(new CounterfactualLeverDelta(
                                        "headcount_delta", 10.0d, 5.0d, -0.5d, new BigDecimal("-2.0"))),
                                new BigDecimal("0.49"),
                                new BigDecimal("-0.06"),
                                1.0d,
                                CounterfactualFeasibilityStatus.REJECTED_BY_RULES_ENGINE,
                                List.of("Upstream rejection")
                        ),
                        new CounterfactualCandidate(
                                "cand-illegal",
                                2,
                                List.of(new CounterfactualLeverDelta(
                                        "headcount_delta", 10.0d, -5.0d, -1.5d, new BigDecimal("-8.0"))),
                                new BigDecimal("0.47"),
                                new BigDecimal("-0.08"),
                                1.5d,
                                CounterfactualFeasibilityStatus.CANDIDATE_UNVERIFIED,
                                List.of()
                        ),
                        new CounterfactualCandidate(
                                "cand-legal",
                                3,
                                List.of(new CounterfactualLeverDelta(
                                        "headcount_delta", 10.0d, 7.0d, -0.3d, new BigDecimal("-6.0"))),
                                new BigDecimal("0.49"),
                                new BigDecimal("-0.06"),
                                0.6d,
                                CounterfactualFeasibilityStatus.CANDIDATE_UNVERIFIED,
                                List.of()
                        )
                ));
            }

            @Override
            public Optional<IntelligenceJobReceipt> getJob(String jobId) {
                return Optional.empty();
            }
        });

        ResourceStructuralCapabilities withExecutorOnly = service.getStructuralCapabilities();
        assertTrue(withExecutorOnly.statsSimulation());
        assertFalse(withExecutorOnly.statsCounterfactual());
        assertTrue(withExecutorOnly.stats().simulation());
        assertFalse(withExecutorOnly.stats().counterfactual());

        IntelligenceExecutionException noValidatorCf = assertThrows(
                IntelligenceExecutionException.class,
                () -> service.counterfactualStats(validCfRequest())
        );
        assertEquals(IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE, noValidatorCf.errorCode());
    }

    @Test
    void inlineAnchorRevalidationAndFailClosedCounterfactualValidatorFilterIllegalCandidates() {
        SampleSimulationService service = new SampleSimulationService(mockRepository());
        service.simulationMode = StatsSupportMode.AUTO;
        service.counterfactualMode = StatsSupportMode.AUTO;
        service.resolvedModelPoints = List.of(sampleModelPoint("cohort-a", "EARMARKED_70", 8, "5000.00"));
        service.anchorRevalidator = (req, anchor) -> "P50".equals(anchor.anchorLabel())
                ? new BigDecimal("1005.00")   // ~0.5% drift <= 2% tolerance -> WITHIN_TOLERANCE
                : new BigDecimal("1300.00");  // ~7.7% drift > 2% tolerance -> DRIFT_EXCEEDED
        ReflectionTestUtils.setField(service, "statsProperties",
                new StatsProperties(true, 20, 100, StatsSupportMode.AUTO, 1000, 366, 2, 500, 366, 5, 1000));

        AtomicReference<StochasticSimulationRequest> capturedSim = new AtomicReference<>();
        service.setResourceIntelligenceExecutor(stubExecutor(capturedSim, req -> stubCounterfactualResponse(req, List.of(
                new CounterfactualCandidate(
                        "cand-upstream-rejected",
                        1,
                        List.of(new CounterfactualLeverDelta(
                                "headcount_delta", 10.0d, 4.0d, -0.6d, new BigDecimal("-6.0"))),
                        new BigDecimal("0.49"),
                        new BigDecimal("-0.06"),
                        0.4d,
                        CounterfactualFeasibilityStatus.REJECTED_BY_RULES_ENGINE,
                        List.of()
                ),
                new CounterfactualCandidate(
                        "cand-rule-denied",
                        2,
                        List.of(new CounterfactualLeverDelta(
                                "headcount_delta", 10.0d, 1.0d, -0.9d, new BigDecimal("-9.0"))),
                        new BigDecimal("0.46"),
                        new BigDecimal("-0.09"),
                        0.9d,
                        CounterfactualFeasibilityStatus.CANDIDATE_UNVERIFIED,
                        List.of()
                ),
                new CounterfactualCandidate(
                        "cand-rule-approved",
                        3,
                        List.of(new CounterfactualLeverDelta(
                                "headcount_delta", 10.0d, 8.0d, -0.2d, new BigDecimal("-6.0"))),
                        new BigDecimal("0.49"),
                        new BigDecimal("-0.06"),
                        0.2d,
                        CounterfactualFeasibilityStatus.CANDIDATE_UNVERIFIED,
                        List.of()
                )
        ))));

        service.setCounterfactualRuleValidator((req, candidate) -> {
            double toVal = ((Number) candidate.leverDeltas().getFirst().toValue()).doubleValue();
            if (toVal < 5.0d) {
                return CounterfactualRuleVerdict.reject(List.of("MIN_STAFFING_FLOOR"), "Violates minimum staffing floor");
            }
            return CounterfactualRuleVerdict.allow("Compliant with deterministic rule set");
        });

        ResourceStructuralCapabilities caps = service.getStructuralCapabilities();
        assertTrue(caps.statsSimulation());
        assertTrue(caps.statsCounterfactual());

        StochasticSimulationResponse simRes = service.stochasticSimulationStats(validSimRequestWithNoPoints());
        assertEquals(1, capturedSim.get().modelPoints().size());
        assertEquals(5, capturedSim.get().minBucketCount());
        assertEquals(AnchorRevalidationStatus.REVALIDATED_WITHIN_TOLERANCE,
                simRes.anchorScenarios().get(0).revalidationStatus());
        assertEquals(new BigDecimal("1005.00"), simRes.anchorScenarios().get(0).exactRevalidatedValue());
        assertEquals(AnchorRevalidationStatus.REVALIDATED_DRIFT_EXCEEDED,
                simRes.anchorScenarios().get(1).revalidationStatus());

        // Async submission with revalidator fails closed if no StochasticSimulationOrchestrator is registered
        assertThrows(UnsupportedOperationException.class,
                () -> service.submitStochasticSimulationJob(validSimRequestWithNoPoints()));

        // Counterfactual must keep ONLY cand-rule-approved promoted to VALIDATED_BY_RULES_ENGINE with rank=1
        CounterfactualResponse cfRes = service.counterfactualStats(validCfRequest());
        assertTrue(cfRes.converged());
        assertEquals(1, cfRes.candidates().size());
        assertEquals("cand-rule-approved", cfRes.candidates().getFirst().candidateId());
        assertEquals(1, cfRes.candidates().getFirst().rank());
        assertEquals(CounterfactualFeasibilityStatus.VALIDATED_BY_RULES_ENGINE,
                cfRes.candidates().getFirst().feasibilityStatus());
        assertEquals(2, cfRes.rejectedCount());

        // Async counterfactual requires CounterfactualSearchOrchestrator fail-closed
        IntelligenceExecutionException noAsyncCf = assertThrows(
                IntelligenceExecutionException.class,
                () -> service.submitCounterfactualJob(validCfRequest())
        );
        assertEquals(IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE, noAsyncCf.errorCode());
    }

    @Test
    void orchestratorsHandleSyncAsyncAndFailClosedRejectionOfUnverifiedOrchestratorOutputs() {
        SampleSimulationService service = new SampleSimulationService(mockRepository());
        service.simulationMode = StatsSupportMode.AUTO;
        service.counterfactualMode = StatsSupportMode.AUTO;
        ReflectionTestUtils.setField(service, "statsProperties",
                new StatsProperties(true, 20, 100, StatsSupportMode.AUTO, 1000, 366, 2, 500, 366, 5, 1000));

        service.setStochasticSimulationOrchestrator(new StochasticSimulationOrchestrator() {
            @Override
            public StochasticSimulationResponse executeStochasticSimulation(
                    String rk, StochasticSimulationRequest req, AnchorScenarioRevalidator revalidator) {
                return stubSimulationResponse(req, List.of());
            }

            @Override
            public IntelligenceJobReceipt submitStochasticSimulationJob(
                    String rk, StochasticSimulationRequest req, AnchorScenarioRevalidator revalidator) {
                Instant now = Instant.now();
                return new IntelligenceJobReceipt(
                        "sim-job-1", "STOCHASTIC_SIMULATION", IntelligenceJobStatus.QUEUED, 0,
                        req.requestId(), now, now, null, null, null);
            }
        });

        AtomicReference<CounterfactualFeasibilityStatus> returnedStatus =
                new AtomicReference<>(CounterfactualFeasibilityStatus.CANDIDATE_UNVERIFIED);
        service.setCounterfactualSearchOrchestrator(new CounterfactualSearchOrchestrator() {
            @Override
            public CounterfactualResponse executeCounterfactualSearch(
                    String rk, CounterfactualRequest req, CounterfactualRuleValidator validator) {
                return stubCounterfactualResponse(req, List.of(
                        new CounterfactualCandidate(
                                "cand-orch",
                                1,
                                List.of(new CounterfactualLeverDelta(
                                        "headcount_delta", 10.0d, 8.0d, -0.2d, new BigDecimal("-6.0"))),
                                new BigDecimal("0.49"),
                                new BigDecimal("-0.06"),
                                0.2d,
                                returnedStatus.get(),
                                List.of()
                        )
                ));
            }

            @Override
            public IntelligenceJobReceipt submitCounterfactualJob(
                    String rk, CounterfactualRequest req, CounterfactualRuleValidator validator) {
                Instant now = Instant.now();
                return new IntelligenceJobReceipt(
                        "cf-job-1", "COUNTERFACTUAL", IntelligenceJobStatus.QUEUED, 0,
                        req.requestId(), now, now, null, null, null);
            }
        });

        assertEquals("sim-job-1", service.submitStochasticSimulationJob(validSimRequest()).jobId());
        assertEquals("cf-job-1", service.submitCounterfactualJob(validCfRequest()).jobId());

        // Even if a faulty CounterfactualSearchOrchestrator returns CANDIDATE_UNVERIFIED, service fails closed
        IntelligenceExecutionException unverifiedGuard = assertThrows(
                IntelligenceExecutionException.class,
                () -> service.counterfactualStats(validCfRequest())
        );
        assertEquals(IntelligenceErrorCode.CONTRACT_VERSION_MISMATCH, unverifiedGuard.errorCode());

        returnedStatus.set(CounterfactualFeasibilityStatus.VALIDATED_BY_RULES_ENGINE);
        CounterfactualResponse validOrch = service.counterfactualStats(validCfRequest());
        assertEquals(1, validOrch.candidates().size());
        assertEquals(CounterfactualFeasibilityStatus.VALIDATED_BY_RULES_ENGINE,
                validOrch.candidates().getFirst().feasibilityStatus());
    }

    @Test
    void globalExceptionHandlerTranslatesSimulationAndCounterfactualErrorCodesCleanly() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/workforce-budgets/stats/simulation");
        request.addHeader("X-Correlation-ID", "sim-corr-422");

        ResponseEntity<RestApiResponse<Object>> drift = handler.handleIntelligenceExecution(
                new IntelligenceExecutionException(
                        IntelligenceErrorCode.DRIFT_CIRCUIT_OPEN,
                        "Anchor drift exceeded tolerance"
                ),
                new ServletWebRequest(request)
        );
        assertEquals(HttpStatus.valueOf(IntelligenceErrorCode.DRIFT_CIRCUIT_OPEN.httpStatus()), drift.getStatusCode());
        assertEquals("sim-corr-422", drift.getHeaders().getFirst("X-Correlation-ID"));
        CustomProblemDetail detail = drift.getBody().getErrors().getFirst();
        assertEquals("DRIFT_CIRCUIT_OPEN", detail.getProperties().get("errorCode"));
        assertEquals("Anchor drift circuit open", detail.getTitle());

        ResponseEntity<RestApiResponse<Object>> tooLarge = handler.handleIntelligenceExecution(
                new IntelligenceExecutionException(IntelligenceErrorCode.PAYLOAD_TOO_LARGE, "Too many rows"),
                new ServletWebRequest(request)
        );
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge.getStatusCode());
        assertEquals("https://example.com/probs/validation-error",
                tooLarge.getBody().getErrors().getFirst().getType().toString());
        assertEquals("Payload too large", tooLarge.getBody().getErrors().getFirst().getTitle());

        ObjectMapper mapper = new ObjectMapper();
        try {
            mapper.readValue("""
                    {
                      "factorKey": "wage_index",
                      "distribution": "NORMAL",
                      "parameters": {},
                      "lowerBound": 10.0,
                      "upperBound": 5.0
                    }
                    """, StochasticFactorSpec.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jsonEx) {
            HttpInputMessage inputMsg = new MockHttpInputMessage("{}".getBytes(StandardCharsets.UTF_8));
            ResponseEntity<RestApiResponse<Object>> badJson = handler.handleHttpMessageNotReadable(
                    new HttpMessageNotReadableException("JSON parse error", jsonEx, inputMsg),
                    new ServletWebRequest(request)
            );
            assertEquals(HttpStatus.BAD_REQUEST, badJson.getStatusCode());
            assertTrue(badJson.getBody().getMessage().contains("lowerBound"),
                    "Expected intelligence contract constructor invariant message, got: "
                            + badJson.getBody().getMessage());
        }
    }

    @Test
    void hardeningEnforcesDeniedScopeNullElementsMinBucketServerPointsZeroCliffAndMatchers() {
        SampleSimulationService service = new SampleSimulationService(mockRepository());
        service.simulationMode = StatsSupportMode.AUTO;
        service.counterfactualMode = StatsSupportMode.AUTO;
        ReflectionTestUtils.setField(service, "statsProperties",
                new StatsProperties(true, 20, 100, StatsSupportMode.AUTO, 1000, 366, 2, 500, 366, 5, 1000));

        // 1. DENIED filter access scope must fail closed with 403 across all 4 Onda 2 service methods
        service.filterAccessScope = ResourceFilterAccessScope.denied();
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.stochasticSimulationStats(validSimRequest())).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.submitStochasticSimulationJob(validSimRequest())).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.counterfactualStats(validCfRequest())).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.submitCounterfactualJob(validCfRequest())).getStatusCode());
        service.filterAccessScope = ResourceFilterAccessScope.unrestricted();

        // 2. Null entries in lists & minBucketCount < 1 fail with IllegalArgumentException (400), never 500 NPE
        StochasticSimulationStatsRequest<SampleFilter> nullFactorSim = new StochasticSimulationStatsRequest<>(
                new SampleFilter(), "req-null-1", null, 500, 6, 42L, null,
                SamplingStrategy.QUASI_MONTE_CARLO_SOBOL, DependenceMethod.IMAN_CONOVER,
                List.of(sampleModelPoint("pt-1", "src-a", 10, "1000.00")),
                Arrays.asList((StochasticFactorSpec) null), List.of(), List.of(), null, null, 2
        );
        assertThrows(IllegalArgumentException.class,
                () -> nullFactorSim.toStochasticSimulationRequest("workforce.budgets", 5, null));

        CounterfactualStatsRequest<SampleFilter> nullLeverCf = new CounterfactualStatsRequest<>(
                new SampleFilter(), "req-null-cf", null, "fiscal_ratio",
                CounterfactualDirection.DECREASE_TO_TARGET, new BigDecimal("0.55"), new BigDecimal("0.49"), null,
                Arrays.asList((CounterfactualLeverSpec) null), 5, 2, 42L, null
        );
        assertThrows(IllegalArgumentException.class,
                () -> nullLeverCf.toCounterfactualRequest("workforce.budgets"));

        StochasticSimulationStatsRequest<SampleFilter> zeroBucketReq = new StochasticSimulationStatsRequest<>(
                new SampleFilter(), "req-zero-bucket", null, 500, 6, 42L, null,
                SamplingStrategy.QUASI_MONTE_CARLO_SOBOL, DependenceMethod.IMAN_CONOVER,
                List.of(sampleModelPoint("pt-1", "src-a", 10, "1000.00")),
                List.of(sampleFactor("wage_index")), List.of(), List.of(), null, null, 0
        );
        assertThrows(IllegalArgumentException.class, () -> service.stochasticSimulationStats(zeroBucketReq));
        assertThrows(IllegalArgumentException.class, () -> service.submitStochasticSimulationJob(zeroBucketReq));

        // 3. Authoritative non-null empty server-derived modelPoints yields 422 without falling back to client points
        service.resolvedModelPoints = List.of();
        MDC.put("X-Correlation-ID", "mdc-corr-422");
        try {
            StochasticSimulationStatsRequest<SampleFilter> noReqIdWithClientPoints = new StochasticSimulationStatsRequest<>(
                    new SampleFilter(), null, null, 500, 6, 42L, null,
                    SamplingStrategy.QUASI_MONTE_CARLO_SOBOL, DependenceMethod.IMAN_CONOVER,
                    List.of(sampleModelPoint("pt-client", "src-client", 10, "1000.00")),
                    List.of(sampleFactor("wage_index")), List.of(), List.of(), null, null, 2
            );
            IntelligenceExecutionException emptyScopeEx = assertThrows(
                    IntelligenceExecutionException.class,
                    () -> service.stochasticSimulationStats(noReqIdWithClientPoints));
            assertEquals(IntelligenceErrorCode.INSUFFICIENT_SAMPLE_SIZE, emptyScopeEx.errorCode());
            assertEquals("mdc-corr-422", emptyScopeEx.correlationId());
        } finally {
            MDC.clear();
        }
        service.resolvedModelPoints = null;

        // 4. Inline revalidation: k-anonymity suppressed blocks revalidation; zero surrogate does not blow up; drift circuit opens when flagged
        AtomicReference<StochasticSimulationRequest> capturedSim = new AtomicReference<>();
        AtomicReference<StochasticSimulationResponse> rawSimOverride = new AtomicReference<>();
        service.setResourceIntelligenceExecutor(new ResourceIntelligenceExecutor() {
            @Override public InferenceResponse executeInference(String rk, InferenceRequest req, TabularFrameSource src) {
                throw new UnsupportedOperationException();
            }
            @Override public org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse executeAnomalyScan(
                    String rk, org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanRequest req, TabularFrameSource src) {
                throw new UnsupportedOperationException();
            }
            @Override public StochasticSimulationResponse executeStochasticSimulation(String rk, StochasticSimulationRequest req) {
                capturedSim.set(req);
                return rawSimOverride.get();
            }
            @Override public CounterfactualResponse executeCounterfactualSearch(String rk, CounterfactualRequest req) {
                return stubCounterfactualResponse(req, List.of());
            }
            @Override public Optional<IntelligenceJobReceipt> getJob(String jobId) {
                return Optional.empty();
            }
        });
        service.anchorRevalidator = (req, anchor) -> BigDecimal.ZERO;
        StochasticSimulationResponse baseSuppressed = stubSimulationResponse(
                validSimRequest().toStochasticSimulationRequest("workforce.budgets", 5, null),
                List.of(new AnchorScenarioPoint("P50", 0.50d, 1, BigDecimal.ZERO, Map.of("wage_index", 0.04d), null, null, AnchorRevalidationStatus.NOT_REVALIDATED))
        );
        rawSimOverride.set(new StochasticSimulationResponse(
                baseSuppressed.contractVersion(),
                baseSuppressed.provenance(),
                baseSuppressed.modelCard(),
                baseSuppressed.scenarioCount(),
                baseSuppressed.horizonPeriods(),
                baseSuppressed.fanSeries(),
                baseSuppressed.terminalMean(),
                baseSuppressed.terminalStdDev(),
                baseSuppressed.terminalCvar95(),
                baseSuppressed.terminalQuantiles(),
                baseSuppressed.thresholdBreaches(),
                baseSuppressed.tornadoDrivers(),
                baseSuppressed.anchorScenarios(),
                1,
                SuppressionStatus.SUPPRESSED_PRIMARY_K_ANONYMITY
        ));
        IntelligenceExecutionException suppressedEx = assertThrows(
                IntelligenceExecutionException.class,
                () -> service.stochasticSimulationStats(validSimRequest()));
        assertEquals(IntelligenceErrorCode.K_ANONYMITY_SUPPRESSED, suppressedEx.errorCode());

        // Zero surrogate == exact zero yields 0.0 drift
        rawSimOverride.set(stubSimulationResponse(
                validSimRequest().toStochasticSimulationRequest("workforce.budgets", 5, null),
                List.of(new AnchorScenarioPoint("P50", 0.50d, 1, BigDecimal.ZERO, Map.of("wage_index", 0.04d), null, null, AnchorRevalidationStatus.NOT_REVALIDATED))
        ));
        StochasticSimulationResponse zeroBoth = service.stochasticSimulationStats(validSimRequest());
        assertEquals(0.0d, zeroBoth.anchorScenarios().getFirst().relativeDriftPct());
        assertEquals(AnchorRevalidationStatus.REVALIDATED_WITHIN_TOLERANCE, zeroBoth.anchorScenarios().getFirst().revalidationStatus());

        // Drift circuit breaker when getFailOnAnchorDriftExceeded == true
        service.anchorRevalidator = (req, anchor) -> new BigDecimal("100.00");
        service.failOnAnchorDriftExceeded = true;
        IntelligenceExecutionException driftOpen = assertThrows(
                IntelligenceExecutionException.class,
                () -> service.stochasticSimulationStats(validSimRequest()));
        assertEquals(IntelligenceErrorCode.DRIFT_CIRCUIT_OPEN, driftOpen.errorCode());
        service.failOnAnchorDriftExceeded = null;

        // 5. Orchestrator readiness & undeclared lever guard
        CounterfactualSearchOrchestrator notReadyOrchestrator = new CounterfactualSearchOrchestrator() {
            @Override public CounterfactualResponse executeCounterfactualSearch(
                    String rk, CounterfactualRequest req, CounterfactualRuleValidator validator) {
                return stubCounterfactualResponse(req, List.of(new CounterfactualCandidate(
                        "bad-lever", 1,
                        List.of(new CounterfactualLeverDelta("undeclared_lever", 1, 2, 1.0d, BigDecimal.ONE)),
                        new BigDecimal("0.49"), new BigDecimal("-0.06"), 1.0d,
                        CounterfactualFeasibilityStatus.VALIDATED_BY_RULES_ENGINE, List.of()
                )));
            }
            @Override public IntelligenceJobReceipt submitCounterfactualJob(
                    String rk, CounterfactualRequest req, CounterfactualRuleValidator validator) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean isRuleValidatorReady(CounterfactualRuleValidator perResourceValidator) {
                return false;
            }
        };
        service.setCounterfactualSearchOrchestrator(notReadyOrchestrator);
        assertFalse(service.getStructuralCapabilities().statsCounterfactual());
        IntelligenceExecutionException badLeverEx = assertThrows(
                IntelligenceExecutionException.class,
                () -> service.counterfactualStats(validCfRequest()));
        assertEquals(IntelligenceErrorCode.CONTRACT_VERSION_MISMATCH, badLeverEx.errorCode());

        // 6. Security matchers separate stateful job POST routes from read-only POST routes and parse cleanly
        PathPatternParser parser = new PathPatternParser();
        List<String> readOnly = Arrays.asList(PraxisMetadataRequestMatchers.readOnlyPostPatterns());
        List<String> jobPost = Arrays.asList(PraxisMetadataRequestMatchers.intelligenceJobPostPatterns());
        List<String> jobPollCancel = Arrays.asList(PraxisMetadataRequestMatchers.intelligenceJobPollAndCancelPatterns());
        assertFalse(readOnly.contains("/api/*/stats/simulation/jobs"));
        assertFalse(readOnly.contains("/api/*/stats/counterfactual/jobs"));
        assertTrue(jobPost.contains("/api/*/stats/simulation/jobs"));
        assertTrue(jobPost.contains("/api/*/stats/counterfactual/jobs"));
        assertTrue(jobPollCancel.contains("/api/praxis/intelligence/jobs/*"));
        assertTrue(jobPollCancel.contains("/api/praxis/intelligence/jobs/*/cancel"));
        for (String p : jobPost) {
            assertNotNull(parser.parse(p));
        }
        for (String p : jobPollCancel) {
            assertNotNull(parser.parse(p));
        }
    }

    private static StochasticSimulationStatsRequest<SampleFilter> validSimRequest() {
        return new StochasticSimulationStatsRequest<>(
                new SampleFilter(), "req-sim-1", null, 500, 6, 42L, null,
                SamplingStrategy.QUASI_MONTE_CARLO_SOBOL, DependenceMethod.IMAN_CONOVER,
                List.of(sampleModelPoint("pt-1", "src-a", 10, "1000.00")),
                List.of(sampleFactor("wage_index")),
                List.of(),
                List.of(new SimulationThresholdSpec(
                        "ceiling", "Ceiling cap", ThresholdDirection.UPPER_CAP,
                        new BigDecimal("1200.00"), null, null)),
                null,
                null,
                5
        );
    }

    private static StochasticSimulationStatsRequest<SampleFilter> validSimRequestWithNoPoints() {
        return new StochasticSimulationStatsRequest<>(
                new SampleFilter(), "req-sim-2", null, 500, 6, 42L, null,
                SamplingStrategy.QUASI_MONTE_CARLO_SOBOL, DependenceMethod.IMAN_CONOVER,
                null,
                List.of(sampleFactor("wage_index")),
                List.of(),
                List.of(),
                null,
                null,
                2
        );
    }

    private static CounterfactualStatsRequest<SampleFilter> validCfRequest() {
        return new CounterfactualStatsRequest<>(
                new SampleFilter(), "req-cf-1", null, "fiscal_ratio",
                CounterfactualDirection.DECREASE_TO_TARGET,
                new BigDecimal("0.55"),
                new BigDecimal("0.49"),
                null,
                List.of(sampleLever("headcount_delta")),
                5,
                2,
                42L,
                null
        );
    }

    private static SimulationModelPoint sampleModelPoint(String key, String source, int weight, String baseAmount) {
        return new SimulationModelPoint(
                key, key, source, weight, new BigDecimal(baseAmount),
                List.of(), Map.of("wage_index", 1.0d), BigDecimal.ZERO
        );
    }

    private static StochasticFactorSpec sampleFactor(String key) {
        return new StochasticFactorSpec(
                key, FactorDistributionKind.NORMAL, Map.of("mean", 0.04d, "stdDev", 0.02d),
                null, null, false
        );
    }

    private static CounterfactualLeverSpec sampleLever(String key) {
        return new CounterfactualLeverSpec(
                key, CounterfactualLeverType.DISCRETE_INT, 10, 0.0d, 20.0d, 1.0d,
                List.of(), 1.0d, -0.01d, Map.of(), -1
        );
    }

    private static ResourceIntelligenceExecutor stubExecutor(
            AtomicReference<StochasticSimulationRequest> capturedSim,
            java.util.function.Function<CounterfactualRequest, CounterfactualResponse> cfStub
    ) {
        return new ResourceIntelligenceExecutor() {
            @Override
            public InferenceResponse executeInference(String rk, InferenceRequest req, TabularFrameSource src) {
                throw new UnsupportedOperationException();
            }

            @Override
            public org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse executeAnomalyScan(
                    String rk, org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanRequest req,
                    TabularFrameSource src) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StochasticSimulationResponse executeStochasticSimulation(
                    String rk, StochasticSimulationRequest req) {
                capturedSim.set(req);
                return stubSimulationResponse(req, List.of(
                        new AnchorScenarioPoint(
                                "P50", 0.50d, 10, new BigDecimal("1000.00"),
                                Map.of("wage_index", 0.04d), null, null,
                                AnchorRevalidationStatus.NOT_REVALIDATED),
                        new AnchorScenarioPoint(
                                "P90", 0.90d, 90, new BigDecimal("1200.00"),
                                Map.of("wage_index", 0.09d), null, null,
                                AnchorRevalidationStatus.NOT_REVALIDATED)
                ));
            }

            @Override
            public CounterfactualResponse executeCounterfactualSearch(String rk, CounterfactualRequest req) {
                return cfStub.apply(req);
            }

            @Override
            public Optional<IntelligenceJobReceipt> getJob(String jobId) {
                return Optional.empty();
            }
        };
    }

    private static StochasticSimulationResponse stubSimulationResponse(
            StochasticSimulationRequest req,
            List<AnchorScenarioPoint> anchors
    ) {
        AnalyticalProvenance prov = provenance(EpistemicKind.STOCHASTIC_SIMULATION, req.requestId(), req.seed(), 10L);
        ModelCardDescriptor card = new ModelCardDescriptor(
                "stub-sim-v1", "1.0.0", "Stub Simulation", "workforce", "qmc-sobol",
                EpistemicKind.STOCHASTIC_SIMULATION, "Unit test", List.of(), 1, List.of("Test"), "Review");
        List<FanChartPoint> fan = new java.util.ArrayList<>(req.horizonPeriods());
        for (int p = 1; p <= req.horizonPeriods(); p++) {
            fan.add(new FanChartPoint(
                    p,
                    new BigDecimal("1000.00"),
                    new BigDecimal("50.00"),
                    Map.of("p10", new BigDecimal("940.00"), "p50", new BigDecimal("1000.00"), "p90", new BigDecimal("1060.00")),
                    Map.of()
            ));
        }
        return new StochasticSimulationResponse(
                IntelligenceContractVersion.V1,
                prov,
                card,
                req.scenarioCount(),
                req.horizonPeriods(),
                fan,
                new BigDecimal("1000.00"),
                new BigDecimal("50.00"),
                new BigDecimal("1090.00"),
                Map.of("p10", new BigDecimal("940.00"), "p50", new BigDecimal("1000.00"), "p90", new BigDecimal("1060.00")),
                List.of(new ThresholdBreachOutcome(
                        "ceiling", ThresholdDirection.UPPER_CAP, null, 0.12d, 0.09d, 0.16d,
                        new BigDecimal("40.00"), null)),
                List.of(new TornadoSensitivityDriver(
                        "wage_index", 0.85d, new BigDecimal("950.00"), new BigDecimal("1070.00"),
                        new BigDecimal("120.00"), 1)),
                anchors,
                0,
                SuppressionStatus.EXACT_RELEASE
        );
    }

    private static CounterfactualResponse stubCounterfactualResponse(
            CounterfactualRequest req,
            List<CounterfactualCandidate> candidates
    ) {
        AnalyticalProvenance prov = provenance(EpistemicKind.OPTIMIZATION_RESULT, req.requestId(), req.seed(), 1L);
        ModelCardDescriptor card = new ModelCardDescriptor(
                "stub-cf-v1", "1.0.0", "Stub Counterfactual", "workforce", "sparse-search",
                EpistemicKind.OPTIMIZATION_RESULT, "Unit test", List.of(), 1, List.of("Test"), "Review");
        return new CounterfactualResponse(
                IntelligenceContractVersion.V1, prov, card,
                req.baselineValue(), req.targetValue(), true, candidates, 0
        );
    }

    private static AnalyticalProvenance provenance(EpistemicKind kind, String reqId, long seed, long sampleSize) {
        return new AnalyticalProvenance(
                IntelligenceContractVersion.V1, "stub-engine", "1.0.0", "java-21", null,
                kind == EpistemicKind.STOCHASTIC_SIMULATION ? "stub-sim-v1" : "stub-cf-v1",
                "1.0.0", seed, null, null, null, kind, sampleSize, 0, reqId, null, Instant.now()
        );
    }

    @SuppressWarnings("unchecked")
    private static BaseCrudRepository<SampleItem, Long> mockRepository() {
        BaseCrudRepository<SampleItem, Long> repo = mock(BaseCrudRepository.class);
        when(repo.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        return repo;
    }

    static class SampleSimulationService extends AbstractBaseQueryResourceService<
            SampleItem, SampleResponse, Long, SampleFilter> {
        StatsSupportMode simulationMode = StatsSupportMode.DISABLED;
        StatsSupportMode counterfactualMode = StatsSupportMode.DISABLED;
        List<SimulationModelPoint> resolvedModelPoints = null;
        AnchorScenarioRevalidator anchorRevalidator;
        Boolean failOnAnchorDriftExceeded;
        ResourceFilterAccessScope<SampleItem> filterAccessScope = ResourceFilterAccessScope.unrestricted();

        SampleSimulationService(BaseCrudRepository<SampleItem, Long> repo) {
            super(repo, SampleItem.class);
        }

        @Override
        protected ResourceFilterAccessScope<SampleItem> resolveResourceFilterAccessScope() {
            return filterAccessScope;
        }

        @Override
        protected String resolveResourceKey() {
            return "workforce.budgets";
        }

        @Override
        protected ResourceMapper<SampleItem, SampleResponse, Void, Void, Long> getResourceMapper() {
            return new ResourceMapper<>() {
                @Override public SampleResponse toResponse(SampleItem entity) { return new SampleResponse(entity.id); }
                @Override public SampleItem newEntity(Void dto) { throw new UnsupportedOperationException(); }
                @Override public void applyUpdate(SampleItem entity, Void dto) { throw new UnsupportedOperationException(); }
                @Override public Long extractId(SampleItem entity) { return entity.id; }
            };
        }

        @Override
        public StatsSupportMode getSimulationStatsSupportMode() {
            return simulationMode;
        }

        @Override
        public StatsSupportMode getCounterfactualStatsSupportMode() {
            return counterfactualMode;
        }

        @Override
        protected List<SimulationModelPoint> resolveSimulationModelPoints(
                StochasticSimulationStatsRequest<SampleFilter> request,
                Specification<SampleItem> effectiveSpecification) {
            return resolvedModelPoints != null
                    ? resolvedModelPoints
                    : super.resolveSimulationModelPoints(request, effectiveSpecification);
        }

        @Override
        protected AnchorScenarioRevalidator resolveAnchorScenarioRevalidator() {
            return anchorRevalidator;
        }

        @Override
        protected Boolean getFailOnAnchorDriftExceeded() {
            return failOnAnchorDriftExceeded;
        }
    }

    static class SampleItem {
        @Id Long id;
    }

    record SampleResponse(Long id) { }

    static class SampleFilter implements GenericFilterDTO { }
}
