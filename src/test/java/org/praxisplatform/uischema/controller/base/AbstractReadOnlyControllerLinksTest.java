package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AbstractReadOnlyControllerLinksTest.ReadOnlyController.class)
@Import(AbstractReadOnlyControllerLinksTest.ReadOnlyController.class)
class AbstractReadOnlyControllerLinksTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ReadOnlyService service;

    interface ReadOnlyService extends org.praxisplatform.uischema.service.base.BaseCrudService<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {}

    static class SimpleEntity {
        private Long id;
        public SimpleEntity() {}
        public SimpleEntity(Long id) { this.id = id; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class SimpleDto {
        private Long id;
        public SimpleDto() {}
        public SimpleDto(Long id) { this.id = id; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class SimpleFilterDTO implements GenericFilterDTO {}

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/ro")
    static class ReadOnlyController extends AbstractReadOnlyController<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {
        @Autowired
        ReadOnlyService service;
        @Override
        protected ReadOnlyService getService() { return service; }
        @Override
        protected SimpleDto toDto(SimpleEntity entity) { return new SimpleDto(entity.getId()); }
        @Override
        protected SimpleEntity toEntity(SimpleDto dto) { return new SimpleEntity(dto.getId()); }
        @Override
        protected Long getEntityId(SimpleEntity entity) { return entity.getId(); }
        @Override
        protected Long getDtoId(SimpleDto dto) { return dto.getId(); }
        @Override
        protected String getBasePath() { return "/ro"; }
    }

    @Test
    void getAll_omitsWriteLinks() throws Exception {
        when(service.findAll()).thenReturn(List.of(new SimpleEntity(1L)));

        mockMvc.perform(get("/ro/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded..links.create").doesNotExist())
                .andExpect(jsonPath("$._embedded..links.update").doesNotExist())
                .andExpect(jsonPath("$._embedded..links.delete").doesNotExist());
    }

    @Test
    void getById_omitsWriteLinks() throws Exception {
        when(service.findById(1L)).thenReturn(new SimpleEntity(1L));

        mockMvc.perform(get("/ro/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.create").doesNotExist())
                .andExpect(jsonPath("$.links.update").doesNotExist())
                .andExpect(jsonPath("$.links.delete").doesNotExist());
    }

    @Test
    void writeOperations_return405() throws Exception {
        // POST /
        mockMvc.perform(post("/ro").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        // PUT /{id}
        mockMvc.perform(put("/ro/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        // DELETE /{id}
        mockMvc.perform(delete("/ro/1"))
                .andExpect(status().isMethodNotAllowed());

        // DELETE /batch
        mockMvc.perform(delete("/ro/batch").contentType(MediaType.APPLICATION_JSON).content("[1,2]"))
                .andExpect(status().isMethodNotAllowed());
    }
}
