package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AbstractCrudControllerMappedCrudTest.SimpleController.class)
@Import(AbstractCrudControllerMappedCrudTest.SimpleController.class)
class AbstractCrudControllerMappedCrudTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void getByIdUsesMappedServiceMethod() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.findByIdMapped(eq(7L), any())).thenReturn(new SimpleDto(7L));

        mockMvc.perform(get("/simple/7"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.id").value(7));

        verify(service).findByIdMapped(eq(7L), any());
    }

    @Test
    void createBuildsLocationFromPersistedEntityAndMapsDtoInReadPath() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.saveResultMapped(any(SimpleEntity.class), any()))
                .thenReturn(new org.praxisplatform.uischema.service.base.BaseCrudService.SavedResult<>(11L, new SimpleDto(11L)));

        mockMvc.perform(post("/simple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/simple/11"))
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.id").value(11));

        verify(service).saveResultMapped(any(SimpleEntity.class), any());
    }

    @Test
    void updateUsesMappedServiceMethod() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.updateMapped(eq(15L), any(SimpleEntity.class), any())).thenReturn(new SimpleDto(15L));

        mockMvc.perform(put("/simple/15")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":15}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.id").value(15));

        verify(service).updateMapped(eq(15L), any(SimpleEntity.class), any());
    }

    interface SimpleService extends org.praxisplatform.uischema.service.base.BaseCrudService<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {
        @Override
        default Optional<String> getDatasetVersion() { return Optional.of("1"); }
    }

    static class SimpleEntity {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
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
    static class SimpleController extends AbstractCrudController<SimpleEntity, SimpleDto, Long, SimpleFilterDTO> {
        @Autowired
        SimpleService service;

        @Override
        protected SimpleService getService() { return service; }

        @Override
        protected SimpleDto toDto(SimpleEntity entity) { return new SimpleDto(entity.getId()); }

        @Override
        protected SimpleEntity toEntity(SimpleDto dto) {
            SimpleEntity entity = new SimpleEntity();
            entity.setId(dto.getId());
            return entity;
        }

        @Override
        protected Long getEntityId(SimpleEntity entity) { return entity.getId(); }

        @Override
        protected Long getDtoId(SimpleDto dto) { return dto.getId(); }

        @Override
        protected String getBasePath() { return "/simple"; }
    }
}
