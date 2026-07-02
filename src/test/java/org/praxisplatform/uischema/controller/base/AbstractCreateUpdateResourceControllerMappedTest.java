package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceCommandService;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AbstractCreateUpdateResourceControllerMappedTest.PartialController.class)
@Import(AbstractCreateUpdateResourceControllerMappedTest.PartialController.class)
class AbstractCreateUpdateResourceControllerMappedTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    PartialService service;

    @Test
    void createAndUpdateAreMappedWithoutDeleteEndpoints() throws Exception {
        when(service.getDatasetVersion()).thenReturn(Optional.of("1"));
        when(service.create(any(SimpleCreateDto.class)))
                .thenReturn(new BaseCreateUpdateResourceCommandService.SavedResult<>(11L, new SimpleResponseDto(11L)));
        when(service.update(eq(11L), any(SimpleUpdateDto.class))).thenReturn(new SimpleResponseDto(11L));

        mockMvc.perform(post("/partial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/partial/11"))
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$._links.delete").doesNotExist());

        mockMvc.perform(put("/partial/11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$._links.delete").doesNotExist());

        mockMvc.perform(delete("/partial/11"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(delete("/partial/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[11]"))
                .andExpect(status().isMethodNotAllowed());

        verify(service).create(any(SimpleCreateDto.class));
        verify(service).update(eq(11L), any(SimpleUpdateDto.class));
    }

    @Test
    void actionLinksExposeCreateAndUpdateOnly() {
        PartialController controller = new PartialController();
        controller.service = mock(PartialService.class);

        assertEquals(List.of("update"), controller.exposeEntityActionRels(10L));
        assertEquals(List.of("create"), controller.exposeCollectionActionRels());
    }

    interface PartialService extends BaseCreateUpdateResourceService<
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
    @ApiResource(value = "/partial", resourceKey = "test.partial")
    static class PartialController extends AbstractCreateUpdateResourceController<
            SimpleResponseDto,
            Long,
            SimpleFilterDTO,
            SimpleCreateDto,
            SimpleUpdateDto> {

        @Autowired
        PartialService service;

        @Override
        protected PartialService getService() {
            return service;
        }

        @Override
        protected Long getResponseId(SimpleResponseDto dto) {
            return dto.getId();
        }

        @Override
        protected String getBasePath() {
            return "/partial";
        }

        List<String> exposeEntityActionRels(Long id) {
            return buildEntityActionLinks(id).stream().map(link -> link.getRel().value()).toList();
        }

        List<String> exposeCollectionActionRels() {
            return buildCollectionActionLinks().stream().map(link -> link.getRel().value()).toList();
        }
    }
}
