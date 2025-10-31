package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.praxisplatform.uischema.service.base.BaseCrudService;

@WebMvcTest(AbstractCrudControllerBatchDeleteTest.SimpleController.class)
@Import(AbstractCrudControllerBatchDeleteTest.SimpleController.class)
class AbstractCrudControllerBatchDeleteTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void deleteBatchReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/simple/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2]"))
                .andExpect(status().isNoContent());

        verify(service).deleteAllById(List.of(1L, 2L));
    }

    @Test
    void deleteBatchReturnsBadRequestOnEmptyList() throws Exception {
        mockMvc.perform(delete("/simple/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest());

        verify(service, never()).deleteAllById(any());
    }

    interface SimpleService extends BaseCrudService<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {}

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
