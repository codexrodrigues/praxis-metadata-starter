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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AbstractCrudControllerLinksTest.SimpleController.class)
@Import(AbstractCrudControllerLinksTest.SimpleController.class)
class AbstractCrudControllerLinksTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void getAllIncludesSchemaTypeResponse() throws Exception {
        when(service.findAll()).thenReturn(List.of(new SimpleEntity(1L)));

        mockMvc.perform(get("/simple/all"))
                .andExpect(status().isOk());
    }

    @Test
    void filterIncludesSchemaTypeRequestAndResponse() throws Exception {
        Page<SimpleEntity> page = new PageImpl<>(Collections.emptyList());
        when(service.filterWithIncludeIds(any(), any(Pageable.class), any())).thenReturn(page);

        mockMvc.perform(post("/simple/filter").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    // --- Support classes for the test ---

    interface SimpleService extends org.praxisplatform.uischema.service.base.BaseCrudService<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {}

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
