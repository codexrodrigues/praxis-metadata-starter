package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.service.base.BaseCrudService;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AbstractCrudControllerGroupByStatsTest.SimpleController.class)
@Import(AbstractCrudControllerGroupByStatsTest.SimpleController.class)
class AbstractCrudControllerGroupByStatsTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void returnsGroupByStatsPayload() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.groupByStats(any())).thenReturn(new GroupByStatsResponse(
                "status",
                new StatsMetricRequest(StatsMetric.COUNT, null),
                List.of(
                        new GroupByBucket("OPEN", "OPEN", 2L, 2L),
                        new GroupByBucket("CLOSED", "CLOSED", 1L, 1L)
                )
        ));

        mockMvc.perform(post("/simple/stats/group-by")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "filter": {},
                                  "field": "status",
                                  "metric": {
                                    "operation": "COUNT"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.field").value("status"))
                .andExpect(jsonPath("$.data.metric.operation").value("COUNT"))
                .andExpect(jsonPath("$.data.buckets[0].key").value("OPEN"))
                .andExpect(jsonPath("$.data.buckets[0].value").value(2))
                .andExpect(jsonPath("$.data.buckets[1].key").value("CLOSED"));

        verify(service).groupByStats(any());
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
