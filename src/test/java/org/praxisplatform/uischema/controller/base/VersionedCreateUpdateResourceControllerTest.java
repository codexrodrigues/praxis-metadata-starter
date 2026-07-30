package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.praxisplatform.uischema.service.base.VersionedCreateUpdateResourceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = VersionedCreateUpdateResourceControllerTest.VersionedController.class)
@Import({
        VersionedCreateUpdateResourceControllerTest.VersionedController.class,
        VersionedCreateUpdateResourceControllerTest.VersionConfiguration.class,
        GlobalExceptionHandler.class
})
class VersionedCreateUpdateResourceControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ResourceVersionEtagService etags;
    @MockBean VersionedService service;

    @Test
    void requiresIfMatchAndValidatesItInsideVersionedService() throws Exception {
        when(service.update(eq(11L), any(UpdateDto.class), any())).thenAnswer(invocation -> {
            ResourceVersionUpdatePrecondition<Long> precondition = invocation.getArgument(2);
            precondition.requireMatch(7L);
            return new ResponseDto(11L);
        });
        when(service.getResourceVersion(11L)).thenReturn(OptionalLong.of(8L));

        mockMvc.perform(put("/versioned/11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_VERSION_REQUIRED"));

        mockMvc.perform(put("/versioned/11")
                        .header("If-Match", etags.create("test.versioned", 11L, 6L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.errors[0].code").value("STALE_RESOURCE_VERSION"));

        mockMvc.perform(put("/versioned/11")
                        .header("If-Match", "not-an-etag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RESOURCE_VERSION"));

        mockMvc.perform(put("/versioned/11")
                        .header("If-Match", etags.create("test.versioned", 11L, 7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", etags.create("test.versioned", 11L, 8L)))
                .andExpect(jsonPath("$.data.id").value(11));
    }

    interface VersionedService extends VersionedCreateUpdateResourceService<
            ResponseDto, Long, FilterDto, CreateDto, UpdateDto> { }

    static class ResponseDto {
        private Long id;
        ResponseDto() { }
        ResponseDto(Long id) { this.id = id; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class CreateDto { }

    static class UpdateDto {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class FilterDto implements GenericFilterDTO { }

    @org.springframework.web.bind.annotation.RestController
    @ApiResource(value = "/versioned", resourceKey = "test.versioned")
    static class VersionedController extends AbstractCreateUpdateResourceController<
            ResponseDto, Long, FilterDto, CreateDto, UpdateDto> {

        @Autowired VersionedService service;

        @Override protected VersionedService getService() { return service; }
        @Override protected Long getResponseId(ResponseDto dto) { return dto.getId(); }
        @Override protected String getBasePath() { return "/versioned"; }
    }

    @TestConfiguration
    static class VersionConfiguration {
        @Bean ResourceVersionEtagService resourceVersionEtagService() {
            return new ResourceVersionEtagService("test-secret");
        }
    }
}
