package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceCommandService;
import org.praxisplatform.uischema.service.base.BaseResourceService;
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

@WebMvcTest(value = AbstractResourceControllerMappedCrudTest.SimpleController.class)
@Import(AbstractResourceControllerMappedCrudTest.SimpleController.class)
class AbstractResourceControllerMappedCrudTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleService service;

    @Test
    void getByIdUsesResourceQueryService() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.findById(eq(7L))).thenReturn(new SimpleResponseDto(7L));

        mockMvc.perform(get("/simple/7"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.id").value(7));

        verify(service).findById(7L);
    }

    @Test
    void createBuildsLocationFromSavedResult() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.create(any(SimpleCreateDto.class)))
                .thenReturn(new BaseResourceCommandService.SavedResult<>(11L, new SimpleResponseDto(11L)));

        mockMvc.perform(post("/simple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/simple/11"))
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.id").value(11));

        verify(service).create(any(SimpleCreateDto.class));
    }

    @Test
    void updateUsesTypedUpdateBoundary() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.update(eq(15L), any(SimpleUpdateDto.class))).thenReturn(new SimpleResponseDto(15L));

        mockMvc.perform(put("/simple/15")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":15}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.id").value(15));

        verify(service).update(eq(15L), any(SimpleUpdateDto.class));
    }

    interface SimpleService extends BaseResourceService<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {

        @Override
        default Optional<String> getDatasetVersion() {
            return Optional.of("1");
        }
    }

    static class SimpleResponseDto {
        private Long id;
        SimpleResponseDto() {}
        SimpleResponseDto(Long id) { this.id = id; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class SimpleCreateDto {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class SimpleUpdateDto {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class SimpleFilterDTO implements GenericFilterDTO {}

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/simple")
    static class SimpleController extends AbstractResourceController<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {

        @Autowired
        SimpleService service;

        @Override
        protected SimpleService getService() {
            return service;
        }

        @Override
        protected Long getResponseId(SimpleResponseDto dto) {
            return dto.getId();
        }

        @Override
        protected String getBasePath() {
            return "/simple";
        }
    }
}
