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

        // Service internals are not verified here; focus on ordering
    }

    @Test
    void getOptionsByIdsReturnsEmptyListWhenNoIds() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        mockMvc.perform(get("/simple/options/by-ids"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(content().string("[]"));

        verify(service, never()).findAllById(any());
    }

    @Test
    void getOptionsByIdsReturns422WhenExceedsLimit() throws Exception {
        mockMvc.perform(get("/simple/options/by-ids").param("ids", "1", "2", "3", "4"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(containsString("Limite máximo de IDs excedido: 3")));
        verify(service, never()).byIdsOptions(any());
    }

    @Test
    void filterOptionsReturns422WhenPageSizeExceedsLimit() throws Exception {
        mockMvc.perform(post("/simple/options/filter")
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
