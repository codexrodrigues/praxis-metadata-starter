package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.exporting.CollectionExportRequest;
import org.praxisplatform.uischema.exporting.CollectionExportFormat;
import org.praxisplatform.uischema.exporting.CollectionExportResult;
import org.praxisplatform.uischema.exporting.CollectionExportScope;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = AbstractResourceQueryControllerGetByIdsTest.SimpleController.class,
        properties = "praxis.query.by-ids.max=3"
)
@Import(AbstractResourceQueryControllerGetByIdsTest.SimpleController.class)
class AbstractResourceQueryControllerGetByIdsTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void getByIdsDelegatesToOrderedQueryBoundary() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.findAllById(eq(List.of(1L, 3L, 2L))))
                .thenReturn(List.of(new SimpleDto(1L), new SimpleDto(3L), new SimpleDto(2L)));

        mockMvc.perform(get("/simple/by-ids").param("ids", "1", "3", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(3))
                .andExpect(jsonPath("$[2].id").value(2));

        verify(service).findAllById(eq(List.of(1L, 3L, 2L)));
    }

    @Test
    void getByIdsReturnsEmptyListWhenNoIds() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));

        mockMvc.perform(get("/simple/by-ids"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(content().string("[]"));

        verify(service, never()).findAllById(any());
    }

    @Test
    void getByIdsReturns422WhenExceedsLimit() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));

        mockMvc.perform(get("/simple/by-ids").param("ids", "1", "2", "3", "4"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(containsString("Maximum number of IDs exceeded: 3")));

        verify(service, never()).findAllById(any());
    }

    @Test
    void exportDelegatesCollectionStateAndReturnsBinaryPayload() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.exportCollection(any())).thenAnswer(invocation -> {
            CollectionExportRequest<SimpleFilterDTO> request = invocation.getArgument(0);
            return CollectionExportResult.text(
                    "id,name\n1,Ana",
                    request.fileName(),
                    "text/csv"
            );
        });

        mockMvc.perform(post("/simple/export")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "csv",
                                  "scope": "selected",
                                  "fileName": "simple.csv",
                                  "selection": {
                                    "keyField": "id",
                                    "selectedKeys": [1]
                                  },
                                  "filters": {}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(header().string("Content-Disposition", containsString("filename=\"simple.csv\"")))
                .andExpect(content().contentType("text/csv"))
                .andExpect(content().string("id,name\n1,Ana"));

        verify(service).exportCollection(any());
    }

    @Test
    void exportReturnsAcceptedJsonForDeferredPayloads() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.exportCollection(any())).thenReturn(CollectionExportResult.deferred(
                CollectionExportFormat.CSV,
                CollectionExportScope.FILTERED,
                "/downloads/simple-job.csv",
                "job-123",
                "simple-job.csv",
                java.util.Map.of("provider", "queue")
        ));

        mockMvc.perform(post("/simple/export")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "format": "csv",
                                  "scope": "filtered",
                                  "filters": {}
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(content().contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("deferred"))
                .andExpect(jsonPath("$.downloadUrl").value("/downloads/simple-job.csv"))
                .andExpect(jsonPath("$.jobId").value("job-123"));
    }

    interface SimpleService extends BaseResourceQueryService<SimpleDto, Long, SimpleFilterDTO> {
        @Override
        default Optional<String> getDatasetVersion() {
            return Optional.of("1");
        }
    }

    static class SimpleDto {
        private Long id;
        SimpleDto() {}
        SimpleDto(Long id) { this.id = id; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class SimpleFilterDTO implements GenericFilterDTO {}

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/simple")
    static class SimpleController extends AbstractResourceQueryController<SimpleDto, Long, SimpleFilterDTO> {

        @Autowired
        SimpleService service;

        @Override
        protected SimpleService getService() {
            return service;
        }

        @Override
        protected Long getResponseId(SimpleDto dto) {
            return dto.getId();
        }

        @Override
        protected String getBasePath() {
            return "/simple";
        }
    }
}
