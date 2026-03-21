package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.praxisplatform.uischema.service.base.BaseCrudService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AbstractCrudControllerDistributionStatsNotImplementedTest.SimpleController.class)
@Import(AbstractCrudControllerDistributionStatsNotImplementedTest.SimpleController.class)
class AbstractCrudControllerDistributionStatsNotImplementedTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean(answer = Answers.CALLS_REAL_METHODS)
    SimpleService service;

    @Test
    void returns501WhenStatsAreNotImplemented() throws Exception {
        mockMvc.perform(post("/simple/stats/distribution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "filter": {},
                                  "field": "status",
                                  "mode": "TERMS",
                                  "metric": {
                                    "operation": "COUNT"
                                  }
                                }
                                """))
                .andExpect(status().isNotImplemented())
                .andExpect(status().reason(containsString("Not implemented.")));
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
