package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.service.base.BaseCrudService;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesPoint;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AbstractCrudControllerTimeSeriesStatsTest.SimpleController.class)
@Import(AbstractCrudControllerTimeSeriesStatsTest.SimpleController.class)
class AbstractCrudControllerTimeSeriesStatsTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void returnsTimeSeriesStatsPayload() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.timeSeriesStats(any())).thenReturn(new TimeSeriesStatsResponse(
                "createdOn",
                TimeSeriesGranularity.DAY,
                new StatsMetricRequest(StatsMetric.COUNT, null),
                List.of(
                        new TimeSeriesPoint(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01"), "2026-03-01", 2L, 2L),
                        new TimeSeriesPoint(LocalDate.parse("2026-03-02"), LocalDate.parse("2026-03-02"), "2026-03-02", 1L, 1L)
                )
        ));

        mockMvc.perform(post("/simple/stats/timeseries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "filter": {},
                                  "field": "createdOn",
                                  "granularity": "DAY",
                                  "metric": {
                                    "operation": "COUNT"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.field").value("createdOn"))
                .andExpect(jsonPath("$.data.granularity").value("DAY"))
                .andExpect(jsonPath("$.data.points[0].start").value("2026-03-01"))
                .andExpect(jsonPath("$.data.points[0].end").value("2026-03-01"))
                .andExpect(jsonPath("$.data.points[0].label").value("2026-03-01"))
                .andExpect(jsonPath("$.data.points[0].value").value(2))
                .andExpect(jsonPath("$.data.points[1].start").value("2026-03-02"))
                .andExpect(jsonPath("$.data.points[1].end").value("2026-03-02"))
                .andExpect(jsonPath("$.data.points[1].label").value("2026-03-02"));

        verify(service).timeSeriesStats(any());
    }

    @Test
    void returnsTimeSeriesStatsPayloadWithMetricsAndValues() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.timeSeriesStats(any())).thenReturn(new TimeSeriesStatsResponse(
                "createdOn",
                TimeSeriesGranularity.DAY,
                new StatsMetricRequest(StatsMetric.COUNT, null, "total"),
                List.of(
                        new TimeSeriesPoint(
                                LocalDate.parse("2026-03-01"),
                                LocalDate.parse("2026-03-01"),
                                "2026-03-01",
                                2L,
                                2L,
                                Map.of("salary", 1500L, "total", 2L)
                        )
                ),
                List.of(
                        new StatsMetricRequest(StatsMetric.COUNT, null, "total"),
                        new StatsMetricRequest(StatsMetric.SUM, "salary", "salary")
                )
        ));

        mockMvc.perform(post("/simple/stats/timeseries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "filter": {},
                                  "field": "createdOn",
                                  "granularity": "DAY",
                                  "metrics": [
                                    {
                                      "operation": "COUNT",
                                      "alias": "total"
                                    },
                                    {
                                      "operation": "SUM",
                                      "field": "salary",
                                      "alias": "salary"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics[0].alias").value("total"))
                .andExpect(jsonPath("$.data.metrics[1].field").value("salary"))
                .andExpect(jsonPath("$.data.points[0].values.total").value(2))
                .andExpect(jsonPath("$.data.points[0].values.salary").value(1500));

        verify(service).timeSeriesStats(any());
    }

    interface SimpleService extends BaseCrudService<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {
        @Override
        default Optional<String> getDatasetVersion() { return Optional.of("1"); }
    }

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/simple")
    static class SimpleController extends AbstractCrudController<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {
        @Autowired
        SimpleService service;

        @Override
        protected SimpleService getService() { return service; }

        @Override
        protected SimpleDto toDto(SimpleEntity entity) { return new SimpleDto(entity.getId()); }

        @Override
        protected SimpleEntity toEntity(SimpleDto dto) { return new SimpleEntity(dto.getId()); }

        @Override
        protected Long getEntityId(SimpleEntity entity) { return entity.getId(); }

        @Override
        protected Long getDtoId(SimpleDto dto) { return dto.getId(); }

        @Override
        protected String getBasePath() { return "/simple"; }
    }
}
