package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.praxisplatform.uischema.service.base.BaseCrudService;

@WebMvcTest(AbstractCrudControllerLocateTest.SimpleController.class)
@Import(AbstractCrudControllerLocateTest.SimpleController.class)
class AbstractCrudControllerLocateTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void returnsPositionAndPage() throws Exception {
        when(service.locate(any(), any(), eq(5L))).thenReturn(OptionalLong.of(17));
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));

        mockMvc.perform(post("/simple/locate")
                        .param("id", "5")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.position").value(17))
                .andExpect(jsonPath("$.page").value(1));

        verify(service).locate(any(), any(), eq(5L));
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
