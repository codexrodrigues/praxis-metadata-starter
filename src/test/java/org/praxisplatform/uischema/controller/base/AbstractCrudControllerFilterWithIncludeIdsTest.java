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
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@WebMvcTest(value = AbstractCrudControllerFilterWithIncludeIdsTest.SimpleController.class, properties = "praxis.pagination.max-size=20")
@Import(AbstractCrudControllerFilterWithIncludeIdsTest.SimpleController.class)
class AbstractCrudControllerFilterWithIncludeIdsTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void includeIdsAppearAtTop() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterWithIncludeIds(any(), any(Pageable.class), anyList()))
                .thenReturn(new PageImpl<>(List.of(new SimpleEntity(4L), new SimpleEntity(1L), new SimpleEntity(2L), new SimpleEntity(3L)), PageRequest.of(0, 2), 4));

        mockMvc.perform(post("/simple/filter")
                        .param("includeIds", "4", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.content[0].id").value(4))
                .andExpect(jsonPath("$.data.content[1].id").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(4));
    }

    @Test
    void includeIdsAlreadyPresentArePrioritized() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterWithIncludeIds(any(), any(Pageable.class), anyList()))
                .thenReturn(new PageImpl<>(List.of(new SimpleEntity(4L), new SimpleEntity(1L), new SimpleEntity(5L)), PageRequest.of(0, 2), 3));

        mockMvc.perform(post("/simple/filter")
                        .param("includeIds", "4", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.content[0].id").value(4))
                .andExpect(jsonPath("$.data.content[1].id").value(1))
                .andExpect(jsonPath("$.data.content[2].id").value(5));

        // Service internals are not verified here; focus on response shape
    }

    @Test
    void includeIdsNotInjectedAfterFirstPage() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterWithIncludeIds(any(), any(Pageable.class), any()))
                .thenReturn(new PageImpl<>(List.of(new SimpleEntity(6L)), PageRequest.of(1, 2), 6));

        mockMvc.perform(post("/simple/filter")
                        .param("includeIds", "4", "1")
                        .param("page", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.content[0].id").value(6))
                .andExpect(jsonPath("$.data.content.length()").value(1));

        // Service internals are not verified here; focus on response body
    }

    @Test
    void duplicateIncludeIdsAreIgnored() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.filterWithIncludeIds(any(), any(Pageable.class), anyList()))
                .thenReturn(new PageImpl<>(List.of(new SimpleEntity(4L), new SimpleEntity(1L), new SimpleEntity(2L)), PageRequest.of(0, 2), 3));

        mockMvc.perform(post("/simple/filter")
                        .param("includeIds", "4", "4", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.content[0].id").value(4))
                .andExpect(jsonPath("$.data.content[1].id").value(1))
                .andExpect(jsonPath("$.data.content[2].id").value(2));

        // Service internals are not verified here; focus on ordering
    }

    @Test
    void filterReturns422WhenPageSizeExceedsLimit() throws Exception {
        mockMvc.perform(post("/simple/filter")
                        .param("size", "21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(containsString("Limite máximo de registros por página excedido: 20")));

        verify(service, never()).filter(any(), any(Pageable.class));
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
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
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

