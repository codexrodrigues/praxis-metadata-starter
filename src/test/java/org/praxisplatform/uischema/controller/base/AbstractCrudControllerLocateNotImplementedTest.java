package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.praxisplatform.uischema.service.base.BaseCrudService;

@WebMvcTest(AbstractCrudControllerLocateNotImplementedTest.SimpleController.class)
@Import(AbstractCrudControllerLocateNotImplementedTest.SimpleController.class)
class AbstractCrudControllerLocateNotImplementedTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void returns501WhenNotImplemented() throws Exception {
        when(service.locate(any(), any(), any())).thenReturn(java.util.OptionalLong.empty());
        mockMvc.perform(post("/simple/locate")
                        .param("id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotImplemented())
                .andExpect(status().reason(containsString("não implementado")));
    }

    interface SimpleService extends BaseCrudService<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {
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
