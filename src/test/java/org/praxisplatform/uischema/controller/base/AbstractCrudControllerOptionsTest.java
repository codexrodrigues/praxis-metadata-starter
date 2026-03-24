package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;

import org.praxisplatform.uischema.dto.OptionDTO;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AbstractCrudControllerOptionsTest.SimpleController.class, properties = {"praxis.query.by-ids.max=3", "praxis.pagination.max-size=20"})
@Import(AbstractCrudControllerOptionsTest.SimpleController.class)
class AbstractCrudControllerOptionsTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void filterOptionsReturnsProjectedPage() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterOptions(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new OptionDTO<>(1L, "L1", null), new OptionDTO<>(2L, "L2", null)), PageRequest.of(0, 2), 2));

        mockMvc.perform(post("/simple/options/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].label").value("L1"))
                .andExpect(jsonPath("$.content[1].id").value(2));

        verify(service).filterOptions(any(), any(Pageable.class));
    }

    @Test
    void getOptionsByIdsReturnsOrderedList() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.byIdsOptions(List.of(1L, 3L, 2L)))
                .thenReturn(List.of(new OptionDTO<>(1L, "L1", null), new OptionDTO<>(3L, "L3", null), new OptionDTO<>(2L, "L2", null)));

        mockMvc.perform(get("/simple/options/by-ids").param("ids", "1", "3", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(3))
                .andExpect(jsonPath("$[2].id").value(2));

        verify(service).byIdsOptions(List.of(1L, 3L, 2L));
    }

    @Test
    void getOptionsByIdsReturnsEmptyListWhenNoIds() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        mockMvc.perform(get("/simple/options/by-ids"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(content().string("[]"));

        verify(service, never()).byIdsOptions(any());
    }

    @Test
    void getOptionsByIdsReturns422WhenExceedsLimit() throws Exception {
        mockMvc.perform(get("/simple/options/by-ids").param("ids", "1", "2", "3", "4"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(containsString("Maximum number of IDs exceeded: 3")));
        verify(service, never()).byIdsOptions(any());
    }

    @Test
    void filterOptionsReturns422WhenPageSizeExceedsLimit() throws Exception {
        mockMvc.perform(post("/simple/options/filter")
                        .param("size", "21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(containsString("Maximum page size exceeded: 20")));

        verify(service, never()).filterOptions(any(), any(Pageable.class));
    }

    @Test
    void filterOptionSourceOptionsDelegatesToService() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterOptionSourceOptions(any(), any(), any(), any(Pageable.class), any()))
                .thenReturn(new PageImpl<>(List.of(
                        new OptionDTO<>("perfil-a", "Perfil A", null),
                        new OptionDTO<>("perfil-b", "Perfil B", null)
                ), PageRequest.of(0, 2), 2));

        mockMvc.perform(post("/simple/option-sources/payrollProfile/options/filter")
                        .param("search", "perf")
                        .param("includeIds", "perfil-a", "perfil-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.content[0].id").value("perfil-a"))
                .andExpect(jsonPath("$.content[1].id").value("perfil-b"));

        verify(service).filterOptionSourceOptions(eq("payrollProfile"), any(), eq("perf"), any(Pageable.class), eq(List.of("perfil-a", "perfil-b")));
    }

    @Test
    void filterOptionSourceOptionsReturns404WhenUnknownSource() throws Exception {
        when(service.filterOptionSourceOptions(any(), any(), any(), any(Pageable.class), any()))
                .thenThrow(new IllegalArgumentException("Option source is not registered for resource SimpleEntity: payrollProfile"));

        mockMvc.perform(post("/simple/option-sources/payrollProfile/options/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(status().reason(containsString("Option source is not registered")));
    }

    @Test
    void filterOptionSourceOptionsReturns501WhenNotImplemented() throws Exception {
        when(service.filterOptionSourceOptions(any(), any(), any(), any(Pageable.class), any()))
                .thenThrow(new UnsupportedOperationException("Option source options not implemented: payrollProfile"));

        mockMvc.perform(post("/simple/option-sources/payrollProfile/options/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotImplemented())
                .andExpect(status().reason(containsString("Option source options not implemented")));
    }

    @Test
    void filterOptionSourceOptionsReturns422WhenPageSizeExceedsLimit() throws Exception {
        mockMvc.perform(post("/simple/option-sources/payrollProfile/options/filter")
                        .param("size", "21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(containsString("Maximum page size exceeded: 20")));

        verify(service, never()).filterOptionSourceOptions(any(), any(), any(), any(Pageable.class), any());
    }

    @Test
    void getOptionSourceOptionsByIdsReturnsOrderedList() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.byIdsOptionSourceOptions("payrollProfile", List.of("a", "c", "b")))
                .thenReturn(List.of(
                        new OptionDTO<>("a", "A", null),
                        new OptionDTO<>("c", "C", null),
                        new OptionDTO<>("b", "B", null)
                ));

        mockMvc.perform(get("/simple/option-sources/payrollProfile/options/by-ids").param("ids", "a", "c", "b"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$[0].id").value("a"))
                .andExpect(jsonPath("$[1].id").value("c"))
                .andExpect(jsonPath("$[2].id").value("b"));

        verify(service).byIdsOptionSourceOptions("payrollProfile", List.of("a", "c", "b"));
    }

    @Test
    void getOptionSourceOptionsByIdsReturns404WhenUnknownSource() throws Exception {
        when(service.byIdsOptionSourceOptions("payrollProfile", List.of("a")))
                .thenThrow(new IllegalArgumentException("Option source is not registered for resource SimpleEntity: payrollProfile"));

        mockMvc.perform(get("/simple/option-sources/payrollProfile/options/by-ids").param("ids", "a"))
                .andExpect(status().isNotFound())
                .andExpect(status().reason(containsString("Option source is not registered")));
    }

    @Test
    void getOptionSourceOptionsByIdsReturns501WhenNotImplemented() throws Exception {
        when(service.byIdsOptionSourceOptions("payrollProfile", List.of("a")))
                .thenThrow(new UnsupportedOperationException("Option source by-ids not implemented: payrollProfile"));

        mockMvc.perform(get("/simple/option-sources/payrollProfile/options/by-ids").param("ids", "a"))
                .andExpect(status().isNotImplemented())
                .andExpect(status().reason(containsString("Option source by-ids not implemented")));
    }

    @Test
    void getOptionSourceOptionsByIdsReturns422WhenExceedsLimit() throws Exception {
        mockMvc.perform(get("/simple/option-sources/payrollProfile/options/by-ids").param("ids", "a", "b", "c", "d"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(containsString("Maximum number of IDs exceeded: 3")));

        verify(service, never()).byIdsOptionSourceOptions(any(), any());
    }

    interface SimpleService extends org.praxisplatform.uischema.service.base.BaseCrudService<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {
        @Override
        default Optional<String> getDatasetVersion() { return Optional.of("1"); }
    }

    static class SimpleEntity {
        private Long id;
        SimpleEntity() {}
        SimpleEntity(Long id) { this.id = id; }
        Long getId() { return id; }
        void setId(Long id) { this.id = id; }
    }

    static class SimpleDto {
        private Long id;
        SimpleDto() {}
        SimpleDto(Long id) { this.id = id; }
        Long getId() { return id; }
        void setId(Long id) { this.id = id; }
    }

    static class SimpleFilterDTO implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO {}

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
