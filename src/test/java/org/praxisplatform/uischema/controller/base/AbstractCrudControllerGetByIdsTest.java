package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.mockito.Answers;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AbstractCrudControllerGetByIdsTest.SimpleController.class, properties = "praxis.query.by-ids.max=3")
class AbstractCrudControllerGetByIdsTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean(answer = Answers.CALLS_REAL_METHODS)
    SimpleService service;

    @Test
    void getByIdsReturnsOrderedList() throws Exception {
        when(service.findAllById(List.of(1L, 3L, 2L)))
                .thenReturn(List.of(new SimpleEntity(3L), new SimpleEntity(1L), new SimpleEntity(2L)));

        mockMvc.perform(get("/simple/by-ids").param("ids", "1", "3", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(3))
                .andExpect(jsonPath("$[2].id").value(2));

        verify(service).findAllById(List.of(1L, 3L, 2L));
    }

    @Test
    void getByIdsReturnsEmptyListWhenNoIds() throws Exception {
        mockMvc.perform(get("/simple/by-ids"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(content().string("[]"));

        verify(service, never()).findAllById(any());
    }

    @Test
    void getByIdsReturns422WhenExceedsLimit() throws Exception {
        mockMvc.perform(get("/simple/by-ids").param("ids", "1", "2", "3", "4"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason(containsString("Limite máximo de IDs excedido: 3")));

        verify(service, never()).findAllById(any());
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

