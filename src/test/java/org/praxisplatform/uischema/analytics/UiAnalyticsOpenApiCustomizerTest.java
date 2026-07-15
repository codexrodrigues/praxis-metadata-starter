package org.praxisplatform.uischema.analytics;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.AnalyticsComparisonPeriodBinding;
import org.praxisplatform.uischema.annotation.AnalyticsDimensionBinding;
import org.praxisplatform.uischema.annotation.AnalyticsGranularity;
import org.praxisplatform.uischema.annotation.AnalyticsIntent;
import org.praxisplatform.uischema.annotation.AnalyticsMetricBinding;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.annotation.AnalyticsPolicyReference;
import org.praxisplatform.uischema.annotation.AnalyticsPresentationFamily;
import org.praxisplatform.uischema.annotation.AnalyticsProjection;
import org.praxisplatform.uischema.annotation.AnalyticsRecordOpen;
import org.praxisplatform.uischema.annotation.AnalyticsSort;
import org.praxisplatform.uischema.annotation.AnalyticsSortDirection;
import org.praxisplatform.uischema.annotation.AnalyticsSurfaceTarget;
import org.praxisplatform.uischema.annotation.UiAnalytics;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.stats.ComparisonPeriodMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UiAnalyticsOpenApiCustomizerTest {

    @Test
    @SuppressWarnings("unchecked")
    void injectsAnalyticsAndPreservesExistingXUiKeys() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        UiAnalyticsOpenApiCustomizer customizer = new UiAnalyticsOpenApiCustomizer(
                handlerMapping,
                operationResolver,
                new UiAnalyticsAnnotationMapper()
        );

        SampleController controller = new SampleController();
        Method method = SampleController.class.getDeclaredMethod("groupBy");
        HandlerMethod handlerMethod = new HandlerMethod(controller, method);
        RequestMappingInfo requestMappingInfo = RequestMappingInfo.paths("/reports/stats/group-by").build();

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(requestMappingInfo, handlerMethod));
        when(operationResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenReturn(new CanonicalOperationRef("reports", "groupBy", "/reports/stats/group-by", "post"));

        Operation operation = new Operation();
        operation.setExtensions(new LinkedHashMap<>(Map.of("x-ui", new LinkedHashMap<>(Map.of("responseSchema", "ReportResponse")))));
        OpenAPI openAPI = new OpenAPI().paths(new Paths().addPathItem("/reports/stats/group-by", new PathItem().post(operation)));

        customizer.customise(openAPI);

        Map<String, Object> xUi = (Map<String, Object>) openAPI.getPaths()
                .get("/reports/stats/group-by")
                .getPost()
                .getExtensions()
                .get("x-ui");

        assertEquals("ReportResponse", xUi.get("responseSchema"));
        Map<String, Object> analytics = (Map<String, Object>) xUi.get("analytics");
        assertNotNull(analytics);
        assertNotNull(analytics.get("projections"));
        Map<String, Object> projection = ((java.util.List<Map<String, Object>>) analytics.get("projections")).get(0);
        Map<String, Object> defaults = (Map<String, Object>) projection.get("defaults");
        assertEquals("month", defaults.get("granularity"));
        assertFalse(projection.containsKey("governance"));
    }

    @Test
    void ignoresUnannotatedMethods() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);
        UiAnalyticsOpenApiCustomizer customizer = new UiAnalyticsOpenApiCustomizer(
                handlerMapping,
                operationResolver,
                new UiAnalyticsAnnotationMapper()
        );

        UnannotatedController controller = new UnannotatedController();
        Method method = UnannotatedController.class.getDeclaredMethod("groupBy");
        HandlerMethod handlerMethod = new HandlerMethod(controller, method);
        RequestMappingInfo requestMappingInfo = RequestMappingInfo.paths("/reports/stats/group-by").build();

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(requestMappingInfo, handlerMethod));

        Operation operation = new Operation();
        OpenAPI openAPI = new OpenAPI().paths(new Paths().addPathItem("/reports/stats/group-by", new PathItem().post(operation)));

        customizer.customise(openAPI);

        assertNull(openAPI.getPaths().get("/reports/stats/group-by").getPost().getExtensions());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsComparisonPeriodBindingWithCanonicalRequestValues() throws Exception {
        UiAnalytics annotation = ComparisonController.class.getDeclaredMethod("comparison")
                .getAnnotation(UiAnalytics.class);

        Map<String, Object> analytics = new UiAnalyticsAnnotationMapper()
                .toXUiAnalytics(annotation, "/reports/stats/comparison");
        Map<String, Object> projection = ((java.util.List<Map<String, Object>>) analytics.get("projections")).get(0);
        Map<String, Object> source = (Map<String, Object>) projection.get("source");
        Map<String, Object> bindings = (Map<String, Object>) projection.get("bindings");
        Map<String, Object> period = (Map<String, Object>) bindings.get("comparisonPeriod");

        assertEquals("comparison", source.get("operation"));
        assertEquals("/reports", source.get("resource"));
        assertEquals("competencia", period.get("field"));
        assertEquals("America/Sao_Paulo", period.get("timezone"));
        assertEquals("LAST_30_DAYS", period.get("preset"));
        assertEquals("PREVIOUS_ALIGNED", period.get("mode"));
    }

    @Test
    void rejectsComparisonProjectionWithoutCanonicalPeriodBinding() throws Exception {
        UiAnalytics annotation = InvalidComparisonController.class.getDeclaredMethod("comparison")
                .getAnnotation(UiAnalytics.class);

        assertThrows(IllegalArgumentException.class,
                () -> new UiAnalyticsAnnotationMapper().toXUiAnalytics(annotation, "/reports/stats/comparison"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsVersionedPolicyReferencesWithoutExecutablePolicyDetails() throws Exception {
        UiAnalytics annotation = GovernedComparisonController.class.getDeclaredMethod("comparison")
                .getAnnotation(UiAnalytics.class);

        Map<String, Object> analytics = new UiAnalyticsAnnotationMapper()
                .toXUiAnalytics(annotation, "/reports/stats/comparison");
        Map<String, Object> projection = ((java.util.List<Map<String, Object>>) analytics.get("projections")).get(0);
        Map<String, Object> governance = (Map<String, Object>) projection.get("governance");
        java.util.List<Map<String, Object>> policyRefs =
                (java.util.List<Map<String, Object>>) governance.get("policyRefs");

        assertEquals(2, policyRefs.size());
        assertEquals("classification-policy", policyRefs.get(0).get("policyId"));
        assertEquals("2026-07", policyRefs.get(0).get("policyVersion"));
        assertEquals("criticality", policyRefs.get(0).get("role"));
        assertEquals("criticalityLevel", policyRefs.get(0).get("resultField"));
        Map<String, Object> attestation = (Map<String, Object>) policyRefs.get(0).get("attestation");
        assertEquals("criticalityPolicyId", attestation.get("policyIdField"));
        assertEquals("criticalityPolicyVersion", attestation.get("policyVersionField"));
        assertEquals("retention-policy", policyRefs.get(1).get("policyId"));
        assertFalse(policyRefs.get(0).containsKey("thresholds"));
        assertFalse(policyRefs.get(0).containsKey("expression"));
    }

    @Test
    void rejectsIncompletePolicyReferenceIdentity() throws Exception {
        UiAnalytics annotation = InvalidPolicyReferenceController.class.getDeclaredMethod("comparison")
                .getAnnotation(UiAnalytics.class);

        assertThrows(IllegalArgumentException.class,
                () -> new UiAnalyticsAnnotationMapper().toXUiAnalytics(annotation, "/reports/stats/comparison"));
    }

    @Test
    void rejectsIncompletePolicyAttestation() throws Exception {
        UiAnalytics annotation = InvalidPolicyAttestationController.class.getDeclaredMethod("comparison")
                .getAnnotation(UiAnalytics.class);

        assertThrows(IllegalArgumentException.class,
                () -> new UiAnalyticsAnnotationMapper().toXUiAnalytics(annotation, "/reports/stats/comparison"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsBucketKeyToExplicitPublicFilterField() throws Exception {
        UiAnalytics annotation = CrossFilterController.class.getDeclaredMethod("groupBy")
                .getAnnotation(UiAnalytics.class);

        Map<String, Object> analytics = new UiAnalyticsAnnotationMapper()
                .toXUiAnalytics(annotation, "/reports/stats/group-by");
        Map<String, Object> projection = ((java.util.List<Map<String, Object>>) analytics.get("projections")).get(0);
        Map<String, Object> bindings = (Map<String, Object>) projection.get("bindings");
        Map<String, Object> dimension = (Map<String, Object>) bindings.get("primaryDimension");

        assertEquals("departmentId", dimension.get("keyFilterField"));
        assertFalse(dimension.containsKey("keyPropertyPath"));
        assertFalse(dimension.containsKey("labelPropertyPath"));
    }

    @Test
    void rejectsCrossFilterWithoutExplicitBucketKeyBinding() throws Exception {
        UiAnalytics annotation = InvalidCrossFilterController.class.getDeclaredMethod("groupBy")
                .getAnnotation(UiAnalytics.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new UiAnalyticsAnnotationMapper().toXUiAnalytics(annotation, "/reports/stats/group-by"));

        assertEquals(
                "Analytics projections with crossFilter=true require primaryDimension.field and primaryDimension.keyFilterField.",
                error.getMessage()
        );
    }

    @Test
    void rejectsBlankBucketKeyBindingEvenWithoutCrossFilter() throws Exception {
        UiAnalytics annotation = BlankFilterFieldController.class.getDeclaredMethod("groupBy")
                .getAnnotation(UiAnalytics.class);

        assertThrows(IllegalArgumentException.class,
                () -> new UiAnalyticsAnnotationMapper().toXUiAnalytics(annotation, "/reports/stats/group-by"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsNominalRecordIdentityToCrossResourceSurfaceReference() throws Exception {
        UiAnalytics annotation = RecordOpenController.class.getDeclaredMethod("groupBy")
                .getAnnotation(UiAnalytics.class);

        Map<String, Object> analytics = new UiAnalyticsAnnotationMapper()
                .toXUiAnalytics(annotation, "/reports/stats/group-by");
        Map<String, Object> projection = ((java.util.List<Map<String, Object>>) analytics.get("projections")).get(0);
        Map<String, Object> interactions = (Map<String, Object>) projection.get("interactions");
        Map<String, Object> recordOpen = (Map<String, Object>) interactions.get("recordOpen");
        Map<String, Object> target = (Map<String, Object>) recordOpen.get("target");

        assertEquals("employeeId", recordOpen.get("sourceIdentityField"));
        assertEquals("human-resources.employees", target.get("resourceKey"));
        assertEquals("employee-360", target.get("surfaceId"));
        assertFalse(target.containsKey("path"));
        assertFalse(target.containsKey("schemaUrl"));
        assertFalse(recordOpen.containsKey("widget"));
        assertFalse(recordOpen.containsKey("presentation"));
    }

    @Test
    void rejectsPartialRecordOpenReference() throws Exception {
        UiAnalytics annotation = InvalidRecordOpenController.class.getDeclaredMethod("groupBy")
                .getAnnotation(UiAnalytics.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new UiAnalyticsAnnotationMapper().toXUiAnalytics(annotation, "/reports/stats/group-by"));

        assertEquals(
                "Analytics recordOpen requires sourceIdentityField, target.resourceKey and target.surfaceId.",
                error.getMessage()
        );
    }

    static class SampleController {

        @PostMapping("/reports/stats/group-by")
        @UiAnalytics(
                projections = {
                        @AnalyticsProjection(
                                id = "ranking-table",
                                intent = AnalyticsIntent.RANKING,
                                sourceOperation = AnalyticsOperation.GROUP_BY,
                                primaryDimension = @AnalyticsDimensionBinding(field = "department", label = "Department"),
                                primaryMetrics = {
                                        @AnalyticsMetricBinding(field = "total", aggregation = "sum", label = "Total")
                                },
                                defaultSort = {
                                        @AnalyticsSort(field = "total", direction = AnalyticsSortDirection.DESC)
                                },
                                defaultLimit = 10,
                                defaultGranularity = AnalyticsGranularity.MONTH,
                                preferredFamilies = {
                                        AnalyticsPresentationFamily.ANALYTIC_TABLE,
                                        AnalyticsPresentationFamily.CHART
                                },
                                drillDown = true
                        )
                }
        )
        public void groupBy() {
        }
    }

    static class UnannotatedController {
        @PostMapping("/reports/stats/group-by")
        public void groupBy() {
        }
    }

    static class CrossFilterController {

        @PostMapping("/reports/stats/group-by")
        @UiAnalytics(
                projections = @AnalyticsProjection(
                        id = "department-cross-filter",
                        intent = AnalyticsIntent.RANKING,
                        sourceOperation = AnalyticsOperation.GROUP_BY,
                        primaryDimension = @AnalyticsDimensionBinding(
                                field = "department",
                                label = "Department",
                                keyFilterField = "departmentId"
                        ),
                        primaryMetrics = @AnalyticsMetricBinding(field = "total", aggregation = "sum"),
                        crossFilter = true
                )
        )
        public void groupBy() {
        }
    }

    static class InvalidCrossFilterController {

        @PostMapping("/reports/stats/group-by")
        @UiAnalytics(
                projections = @AnalyticsProjection(
                        id = "invalid-cross-filter",
                        intent = AnalyticsIntent.RANKING,
                        sourceOperation = AnalyticsOperation.GROUP_BY,
                        primaryDimension = @AnalyticsDimensionBinding(field = "department"),
                        primaryMetrics = @AnalyticsMetricBinding(field = "total", aggregation = "sum"),
                        crossFilter = true
                )
        )
        public void groupBy() {
        }
    }

    static class BlankFilterFieldController {

        @PostMapping("/reports/stats/group-by")
        @UiAnalytics(
                projections = @AnalyticsProjection(
                        id = "blank-filter-field",
                        intent = AnalyticsIntent.RANKING,
                        sourceOperation = AnalyticsOperation.GROUP_BY,
                        primaryDimension = @AnalyticsDimensionBinding(
                                field = "department",
                                keyFilterField = " "
                        ),
                        primaryMetrics = @AnalyticsMetricBinding(field = "total", aggregation = "sum")
                )
        )
        public void groupBy() {
        }
    }

    static class RecordOpenController {

        @PostMapping("/reports/stats/group-by")
        @UiAnalytics(
                projections = @AnalyticsProjection(
                        id = "department-record-open",
                        intent = AnalyticsIntent.RANKING,
                        sourceOperation = AnalyticsOperation.GROUP_BY,
                        primaryDimension = @AnalyticsDimensionBinding(field = "department"),
                        primaryMetrics = @AnalyticsMetricBinding(field = "total", aggregation = "sum"),
                        recordOpen = @AnalyticsRecordOpen(
                                sourceIdentityField = "employeeId",
                                target = @AnalyticsSurfaceTarget(
                                        resourceKey = "human-resources.employees",
                                        surfaceId = "employee-360"
                                )
                        )
                )
        )
        public void groupBy() {
        }
    }

    static class InvalidRecordOpenController {

        @PostMapping("/reports/stats/group-by")
        @UiAnalytics(
                projections = @AnalyticsProjection(
                        id = "invalid-record-open",
                        intent = AnalyticsIntent.RANKING,
                        sourceOperation = AnalyticsOperation.GROUP_BY,
                        primaryDimension = @AnalyticsDimensionBinding(field = "department"),
                        primaryMetrics = @AnalyticsMetricBinding(field = "total", aggregation = "sum"),
                        recordOpen = @AnalyticsRecordOpen(
                                sourceIdentityField = "employeeId",
                                target = @AnalyticsSurfaceTarget(
                                        resourceKey = "human-resources.employees",
                                        surfaceId = " "
                                )
                        )
                )
        )
        public void groupBy() {
        }
    }

    static class ComparisonController {

        @PostMapping("/reports/stats/comparison")
        @UiAnalytics(
                projections = {
                        @AnalyticsProjection(
                                id = "monthly-comparison",
                                intent = AnalyticsIntent.COMPARISON,
                                sourceOperation = AnalyticsOperation.COMPARISON,
                                primaryDimension = @AnalyticsDimensionBinding(field = "department", label = "Department"),
                                comparisonPeriod = @AnalyticsComparisonPeriodBinding(
                                        field = "competencia",
                                        timezone = "America/Sao_Paulo",
                                        preset = ComparisonPeriodPreset.LAST_30_DAYS,
                                        mode = ComparisonPeriodMode.PREVIOUS_ALIGNED
                                ),
                                primaryMetrics = {
                                        @AnalyticsMetricBinding(field = "total", aggregation = "sum", label = "Total")
                                }
                        )
                }
        )
        public void comparison() {
        }
    }

    static class InvalidComparisonController {

        @PostMapping("/reports/stats/comparison")
        @UiAnalytics(
                projections = {
                        @AnalyticsProjection(
                                id = "invalid-comparison",
                                intent = AnalyticsIntent.COMPARISON,
                                sourceOperation = AnalyticsOperation.COMPARISON,
                                primaryMetrics = {
                                        @AnalyticsMetricBinding(field = "total", aggregation = "sum")
                                }
                        )
                }
        )
        public void comparison() {
        }
    }

    static class GovernedComparisonController {

        @PostMapping("/reports/stats/comparison")
        @UiAnalytics(
                projections = {
                        @AnalyticsProjection(
                                id = "governed-comparison",
                                intent = AnalyticsIntent.COMPARISON,
                                sourceOperation = AnalyticsOperation.COMPARISON,
                                comparisonPeriod = @AnalyticsComparisonPeriodBinding(
                                        field = "competencia",
                                        timezone = "America/Sao_Paulo"
                                ),
                                primaryMetrics = {
                                        @AnalyticsMetricBinding(field = "total", aggregation = "sum")
                                },
                                policyRefs = {
                                        @AnalyticsPolicyReference(
                                                policyId = "classification-policy",
                                                policyVersion = "2026-07",
                                                role = "criticality",
                                                resultField = "criticalityLevel",
                                                policyIdField = "criticalityPolicyId",
                                                policyVersionField = "criticalityPolicyVersion"
                                        ),
                                        @AnalyticsPolicyReference(
                                                policyId = "retention-policy",
                                                policyVersion = "3",
                                                role = "retention",
                                                resultField = "retentionClass"
                                        )
                                }
                        )
                }
        )
        public void comparison() {
        }
    }

    static class InvalidPolicyReferenceController {

        @PostMapping("/reports/stats/comparison")
        @UiAnalytics(
                projections = {
                        @AnalyticsProjection(
                                id = "invalid-policy-reference",
                                intent = AnalyticsIntent.COMPARISON,
                                sourceOperation = AnalyticsOperation.COMPARISON,
                                comparisonPeriod = @AnalyticsComparisonPeriodBinding(
                                        field = "competencia",
                                        timezone = "America/Sao_Paulo"
                                ),
                                primaryMetrics = {
                                        @AnalyticsMetricBinding(field = "total", aggregation = "sum")
                                },
                                policyRefs = @AnalyticsPolicyReference(
                                        policyId = "",
                                        policyVersion = "2026-07",
                                        role = "criticality",
                                        resultField = "criticalityLevel"
                                )
                        )
                }
        )
        public void comparison() {
        }
    }

    static class InvalidPolicyAttestationController {

        @PostMapping("/reports/stats/comparison")
        @UiAnalytics(
                projections = {
                        @AnalyticsProjection(
                                id = "invalid-policy-attestation",
                                intent = AnalyticsIntent.COMPARISON,
                                sourceOperation = AnalyticsOperation.COMPARISON,
                                comparisonPeriod = @AnalyticsComparisonPeriodBinding(
                                        field = "competencia",
                                        timezone = "America/Sao_Paulo"
                                ),
                                primaryMetrics = {
                                        @AnalyticsMetricBinding(field = "total", aggregation = "sum")
                                },
                                policyRefs = @AnalyticsPolicyReference(
                                        policyId = "classification-policy",
                                        policyVersion = "2026-07",
                                        role = "criticality",
                                        resultField = "criticalityLevel",
                                        policyIdField = "criticalityPolicyId"
                                )
                        )
                }
        )
        public void comparison() {
        }
    }
}
