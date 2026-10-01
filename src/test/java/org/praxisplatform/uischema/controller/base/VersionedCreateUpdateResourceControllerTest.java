package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;
import org.praxisplatform.uischema.concurrency.ResourceVersionScope;
import org.praxisplatform.uischema.concurrency.ResourceVersionScopeProvider;
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

import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private static final ResourceVersionScope TEST_SCOPE = new ResourceVersionScope("tenant-a|test");

    @Autowired MockMvc mockMvc;
    @Autowired ResourceVersionEtagService etags;
    @MockBean VersionedService service;

    @Test
    void requiresIfMatchAndValidatesItInsideVersionedService() throws Exception {
        when(service.update(eq(11L), any(UpdateDto.class), any())).thenAnswer(invocation -> {
            ResourceVersionUpdatePrecondition<Long> precondition = invocation.getArgument(2);
            precondition.requireMatch(7L);
            return ResourceRepresentationResult.versioned(new ResponseDto(11L), 8L);
        });

        mockMvc.perform(put("/versioned/11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_VERSION_REQUIRED"));

        mockMvc.perform(put("/versioned/11")
                        .header("If-Match", etags.create(TEST_SCOPE, "test.versioned", 11L, 6L))
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
                        .header("If-Match", etags.create(new ResourceVersionScope("other-binding"),
                                "test.versioned", 11L, 7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(put("/versioned/11")
                        .header("If-Match", etags.create(TEST_SCOPE, "test.versioned", 11L, 7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", etags.create(TEST_SCOPE, "test.versioned", 11L, 8L)))
                .andExpect(jsonPath("$.data.id").value(11));
        verify(service, never()).findById(any());
        verify(service, never()).update(any(), any(UpdateDto.class));
    }

    @Test
    void getUsesCapturedRevisionEvenWhenCurrentStateAdvancedAfterCapture() throws Exception {
        AtomicLong currentRevision = new AtomicLong(7);
        when(service.findById(11L)).thenAnswer(invocation -> {
            long captured = currentRevision.get();
            var result = ResourceRepresentationResult.versioned(new ResponseDto(11L, captured), captured);
            currentRevision.incrementAndGet();
            return result;
        });

        mockMvc.perform(get("/versioned/11"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", etags.create(TEST_SCOPE, "test.versioned", 11L, 7L)))
                .andExpect(jsonPath("$.data.revision").value(7))
                .andExpect(jsonPath("$.data.persistedVersion").doesNotExist())
                .andExpect(jsonPath("$.data.body").doesNotExist());

        assertEquals(8, currentRevision.get());
        verify(service, times(1)).findById(11L);
    }

    @Test
    void putUsesMutationResultWithoutReloadingTheItem() throws Exception {
        AtomicLong currentRevision = new AtomicLong(7);
        when(service.update(eq(11L), any(UpdateDto.class), any())).thenAnswer(invocation -> {
            ResourceVersionUpdatePrecondition<Long> precondition = invocation.getArgument(2);
            precondition.requireMatch(currentRevision.get());
            long committed = currentRevision.incrementAndGet();
            var result = ResourceRepresentationResult.versioned(new ResponseDto(11L, committed), committed);
            currentRevision.incrementAndGet();
            return result;
        });

        mockMvc.perform(put("/versioned/11")
                        .header("If-Match", etags.create(TEST_SCOPE, "test.versioned", 11L, 7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", etags.create(TEST_SCOPE, "test.versioned", 11L, 8L)))
                .andExpect(jsonPath("$.data.revision").value(8));

        assertEquals(9, currentRevision.get());
        verify(service, never()).findById(any());
    }

    @Test
    void versionedGetWithoutCapturedRevisionFailsClosed() throws Exception {
        when(service.findById(11L)).thenReturn(ResourceRepresentationResult.unversioned(new ResponseDto(11L)));

        mockMvc.perform(get("/versioned/11"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("ETag"));
    }

    @Test
    void versionedPutWithoutCapturedRevisionFailsClosed() throws Exception {
        when(service.update(eq(11L), any(UpdateDto.class), any()))
                .thenReturn(ResourceRepresentationResult.unversioned(new ResponseDto(11L)));

        mockMvc.perform(put("/versioned/11")
                        .header("If-Match", etags.create(TEST_SCOPE, "test.versioned", 11L, 7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11}"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("ETag"));
        verify(service, never()).findById(any());
    }

    interface VersionedService extends VersionedCreateUpdateResourceService<
            ResponseDto, Long, FilterDto, CreateDto, UpdateDto> { }

    static class ResponseDto {
        private Long id;
        private long revision;
        ResponseDto() { }
        ResponseDto(Long id) { this.id = id; }
        ResponseDto(Long id, long revision) { this.id = id; this.revision = revision; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getRevision() { return revision; }
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

        @Bean ResourceVersionScopeProvider resourceVersionScopeProvider() {
            return () -> TEST_SCOPE;
        }
    }
}
