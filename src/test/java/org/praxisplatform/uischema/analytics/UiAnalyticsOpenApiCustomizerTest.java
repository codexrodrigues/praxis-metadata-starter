package org.praxisplatform.uischema.analytics;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.AnalyticsDimensionBinding;
import org.praxisplatform.uischema.annotation.AnalyticsGranularity;
import org.praxisplatform.uischema.annotation.AnalyticsIntent;
import org.praxisplatform.uischema.annotation.AnalyticsMetricBinding;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.annotation.AnalyticsPresentationFamily;
import org.praxisplatform.uischema.annotation.AnalyticsProjection;
import org.praxisplatform.uischema.annotation.AnalyticsSort;
import org.praxisplatform.uischema.annotation.AnalyticsSortDirection;
import org.praxisplatform.uischema.annotation.UiAnalytics;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
