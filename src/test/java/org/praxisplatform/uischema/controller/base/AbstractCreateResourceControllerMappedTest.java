package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseCreateResourceService;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceCommandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AbstractCreateResourceControllerMappedTest.CreateOnlyController.class)
@Import(AbstractCreateResourceControllerMappedTest.CreateOnlyController.class)
class AbstractCreateResourceControllerMappedTest {

    @Autowired MockMvc mockMvc;
    @MockBean CreateOnlyService service;

    @Test
    void mapsCreateWithoutUpdateOrDeleteEndpoints() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.create(any(SimpleCreateDto.class)))
                .thenReturn(new BaseCreateUpdateResourceCommandService.SavedResult<>(11L, new SimpleResponseDto(11L)));

        mockMvc.perform(post("/create-only").contentType(MediaType.APPLICATION_JSON).content("{\"id\":11}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/create-only/11"))
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$._links.update").doesNotExist())
                .andExpect(jsonPath("$._links.delete").doesNotExist());

        mockMvc.perform(put("/create-only/11").contentType(MediaType.APPLICATION_JSON).content("{\"id\":11}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/create-only/11")).andExpect(status().isMethodNotAllowed());
        verify(service).create(any(SimpleCreateDto.class));
    }

    @Test
    void advertisesCreateOnly() {
        CreateOnlyController controller = new CreateOnlyController();
        controller.service = mock(CreateOnlyService.class);
        assertEquals(List.of(), controller.exposeEntityActionRels(10L));
        assertEquals(List.of("create"), controller.exposeCollectionActionRels());
    }

    interface CreateOnlyService extends BaseCreateResourceService<SimpleResponseDto, Long, SimpleFilterDTO, SimpleCreateDto> {
        @Override default Optional<String> getDatasetVersion() { return Optional.of("1"); }
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

    static class SimpleFilterDTO implements GenericFilterDTO {}

    @org.springframework.web.bind.annotation.RestController
    @ApiResource(value = "/create-only", resourceKey = "test.create-only")
    static class CreateOnlyController extends AbstractCreateResourceController<SimpleResponseDto, Long, SimpleFilterDTO, SimpleCreateDto> {
        @Autowired CreateOnlyService service;
        @Override protected CreateOnlyService getService() { return service; }
        @Override protected Long getResponseId(SimpleResponseDto dto) { return dto.getId(); }
        @Override protected String getBasePath() { return "/create-only"; }
        List<String> exposeEntityActionRels(Long id) {
            return buildEntityActionLinks(id).stream().map(link -> link.getRel().value()).toList();
        }
        List<String> exposeCollectionActionRels() {
            return buildCollectionActionLinks().stream().map(link -> link.getRel().value()).toList();
        }
    }
}
