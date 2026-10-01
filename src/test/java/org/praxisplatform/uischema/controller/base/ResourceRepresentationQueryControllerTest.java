package org.praxisplatform.uischema.controller.base;

import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionScope;
import org.praxisplatform.uischema.concurrency.ResourceVersionScopeProvider;
import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.praxisplatform.uischema.service.base.AbstractReadOnlyResourceService;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceCommandService;
import org.praxisplatform.uischema.service.base.VersionedCreateUpdateResourceService;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Unit/MVC contract evidence; concurrent PostgreSQL proof belongs to the consuming host. */
class ResourceRepresentationQueryControllerTest {

    private static final ResourceVersionScope SCOPE = new ResourceVersionScope("snapshot-binding");
    private final ResourceVersionEtagService etags = new ResourceVersionEtagService("snapshot-test-secret");

    @Test
    void getCapturesBodyAndVersionFromOneEntityBeforeCurrentStateChanges() throws Exception {
        Fixture fixture = fixture(true);
        AtomicReference<Row> current = new AtomicReference<>(new Row(1L, "before", 3));
        when(fixture.repository.findById(1L)).thenAnswer(invocation -> Optional.of(current.get()));
        fixture.service.afterCapture = () -> current.set(new Row(1L, "after", 4));

        fixture.mvc.perform(get("/snapshot/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("before"))
                .andExpect(header().string("ETag", token(3)))
                .andExpect(jsonPath("$.data.persistedVersion").doesNotExist())
                .andExpect(jsonPath("$.data.body").doesNotExist());

        assertEquals(4, current.get().revision);
        assertSame(fixture.service.mappedEntity, fixture.service.versionedEntity);
        assertEquals(1, fixture.service.readCalls);
        verify(fixture.repository, times(1)).findById(1L);
    }

    @Test
    void ordinaryGetPreservesBodyAndOmitsItemEtag() throws Exception {
        Fixture fixture = fixture(false);
        when(fixture.repository.findById(1L)).thenReturn(Optional.of(new Row(1L, "ordinary", 0)));

        fixture.mvc.perform(get("/snapshot/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("ordinary"))
                .andExpect(header().doesNotExist("ETag"));
        assertEquals(1, fixture.service.readCalls);
    }

    @Test
    void readAndPreflightPreserveTheCustomAuthorizationOverride() throws Exception {
        Fixture fixture = fixture(true);
        fixture.service.denied = true;

        fixture.mvc.perform(get("/snapshot/1"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("ETag"));
        ResponseStatusException denial = assertThrows(ResponseStatusException.class,
                () -> fixture.controller.preflight(1L, token(3)));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatusCode());
        assertEquals(2, fixture.service.readCalls);
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void preflightUsesTheReadOverrideAndItsCapturedRevision() {
        Fixture fixture = fixture(true);
        when(fixture.repository.findById(1L)).thenReturn(Optional.of(new Row(1L, "before", 3)));

        fixture.controller.preflight(1L, token(3));

        assertEquals(1, fixture.service.readCalls);
        assertSame(fixture.service.mappedEntity, fixture.service.versionedEntity);
        verify(fixture.repository, times(1)).findById(1L);
    }

    @Test
    void preflightPreservesNotFoundAndDoesNotTreatMissingVersionAsZero() throws Exception {
        Fixture fixture = fixture(false);
        when(fixture.repository.findById(1L)).thenReturn(Optional.empty());
        fixture.mvc.perform(get("/snapshot/1"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("ETag"));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> fixture.controller.preflight(1L, token(0)));

        when(fixture.repository.findById(1L)).thenReturn(Optional.of(new Row(1L, "unversioned", 0)));
        assertThrows(IllegalStateException.class, () -> fixture.controller.preflight(1L, token(0)));
    }

    @Test
    void itemDiscoveryStillChecksTheOverriddenReadBeforeCallingTheCatalog() throws Exception {
        Fixture fixture = fixture(true);
        fixture.service.denied = true;
        SurfaceCatalogService catalog = mock(SurfaceCatalogService.class);
        ReflectionTestUtils.setField(fixture.controller, "surfaceCatalogService", catalog);

        fixture.mvc.perform(get("/snapshot/1/surfaces"))
                .andExpect(status().isForbidden());

        assertEquals(1, fixture.service.readCalls);
        verifyNoInteractions(fixture.repository, catalog);
    }

    @Test
    void actionHelperUsesHistoricalEvidenceOrOmitsEtagWithoutAnyItemLookup() {
        Fixture fixture = fixture(true);

        ResponseEntity<String> historical = fixture.controller.actionResult(
                ResourceRepresentationResult.versioned("historical response", 2));
        ResponseEntity<String> unproven = fixture.controller.actionResult(
                ResourceRepresentationResult.unversioned("historical response without revision"));

        assertEquals(token(2), historical.getHeaders().getETag());
        assertEquals("historical response", historical.getBody());
        assertNull(unproven.getHeaders().getETag());
        assertEquals(0, fixture.service.readCalls);
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void actionHelperReplacesReadOnlyDatasetVersionWithoutChangingTheSourceResponse() {
        Fixture fixture = fixture(true);
        fixture.service.datasetVersion = Optional.of("current-dataset");
        ResponseEntity<String> source = ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("X-Data-Version", "previous-dataset")
                .header("X-Trace", "first", "second")
                .eTag(token(8))
                .body("historical response");
        assertThrows(UnsupportedOperationException.class,
                () -> source.getHeaders().get("X-Data-Version").add("forbidden"));

        ResponseEntity<String> materialized = fixture.controller.actionResult(source,
                ResourceRepresentationResult.versioned(source.getBody(), 2));

        assertEquals(HttpStatus.ACCEPTED, materialized.getStatusCode());
        assertEquals(source.getBody(), materialized.getBody());
        assertEquals(List.of("current-dataset"), materialized.getHeaders().get("X-Data-Version"));
        assertEquals(token(2), materialized.getHeaders().getETag());
        assertEquals(List.of("first", "second"), materialized.getHeaders().get("X-Trace"));
        assertEquals(List.of("previous-dataset"), source.getHeaders().get("X-Data-Version"));
        assertEquals(token(8), source.getHeaders().getETag());
        assertEquals(List.of("first", "second"), source.getHeaders().get("X-Trace"));
        assertEquals("historical response", source.getBody());
        assertEquals(0, fixture.service.readCalls);
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void baseCaptureRejectsAVersionedServiceThatOmitsItsEntityVersionHook() {
        Fixture fixture = fixture(false);
        when(fixture.repository.findById(1L)).thenReturn(Optional.of(new Row(1L, "versioned", 3)));
        MissingVersionService service = new MissingVersionService(fixture.repository);

        assertThrows(IllegalStateException.class, () -> service.findById(1L));
        verify(fixture.repository, times(1)).findById(1L);
    }

    private String token(long revision) {
        return etags.create(SCOPE, "test.snapshot", 1L, revision);
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(boolean versioned) {
        BaseCrudRepository<Row, Long> repository = mock(BaseCrudRepository.class);
        ReadService service = new ReadService(repository, versioned);
        ReadController controller = new ReadController(service);
        ReflectionTestUtils.setField(controller, "resourceVersionEtagService", etags);
        ReflectionTestUtils.setField(controller, "resourceVersionScopeProvider",
                (ResourceVersionScopeProvider) () -> SCOPE);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        return new Fixture(repository, service, controller, mvc);
    }

    private record Fixture(BaseCrudRepository<Row, Long> repository, ReadService service,
                           ReadController controller, MockMvc mvc) { }

    static final class Row {
        @Id private Long id;
        private final String name;
        private final long revision;
        Row(Long id, String name, long revision) {
            this.id = id;
            this.name = name;
            this.revision = revision;
        }
    }

    public record View(Long id, String name) { }
    static class Filter implements GenericFilterDTO { }

    static class ReadService extends AbstractReadOnlyResourceService<Row, View, Long, Filter> {
        private final boolean versioned;
        private boolean denied;
        private Optional<String> datasetVersion = Optional.empty();
        private int readCalls;
        private Row mappedEntity;
        private Row versionedEntity;
        private Runnable afterCapture = () -> { };

        ReadService(BaseCrudRepository<Row, Long> repository, boolean versioned) {
            super(repository, Row.class);
            this.versioned = versioned;
        }

        @Override public ResourceRepresentationResult<View> findById(Long id) {
            readCalls++;
            if (denied) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            var result = super.findById(id);
            afterCapture.run();
            return result;
        }

        @Override public Optional<String> getDatasetVersion() {
            return datasetVersion;
        }

        @Override protected OptionalLong getEntityResourceVersion(Row row) {
            versionedEntity = row;
            return versioned ? OptionalLong.of(row.revision) : super.getEntityResourceVersion(row);
        }

        @Override protected ResourceMapper<Row, View, Void, Void, Long> getResourceMapper() {
            return new ResourceMapper<>() {
                @Override public View toResponse(Row row) {
                    mappedEntity = row;
                    return new View(row.id, row.name);
                }
                @Override public Row newEntity(Void ignored) { throw new UnsupportedOperationException(); }
                @Override public void applyUpdate(Row row, Void ignored) { throw new UnsupportedOperationException(); }
                @Override public Long extractId(Row row) { return row.id; }
            };
        }
    }

    static class MissingVersionService extends ReadService
            implements VersionedCreateUpdateResourceService<View, Long, Filter, Void, Void> {
        MissingVersionService(BaseCrudRepository<Row, Long> repository) { super(repository, false); }
        @Override public BaseCreateUpdateResourceCommandService.SavedResult<Long, View> create(Void ignored) {
            throw new UnsupportedOperationException();
        }
        @Override public ResourceRepresentationResult<View> update(
                Long id, Void ignored, ResourceVersionUpdatePrecondition<Long> precondition) {
            throw new UnsupportedOperationException();
        }
    }

    @RestController
    @RequestMapping("/snapshot")
    @ApiResource(value = "/snapshot", resourceKey = "test.snapshot")
    static class ReadController extends AbstractReadOnlyResourceController<View, Long, Filter> {
        private final ReadService service;
        ReadController(ReadService service) { this.service = service; }
        @Override protected ReadService getService() { return service; }
        @Override protected Long getResponseId(View body) { return body.id(); }
        @Override protected String getIdFieldName() { return "id"; }
        @Override protected String getBasePath() { return "/snapshot"; }
        void preflight(Long id, String token) { requireMatchingResourceVersion(id, token); }
        ResponseEntity<String> actionResult(ResourceRepresentationResult<String> result) {
            return withResourceVersion(ResponseEntity.ok(), 1L, result);
        }
        ResponseEntity<String> actionResult(ResponseEntity<String> source,
                                            ResourceRepresentationResult<String> result) {
            return withResourceVersion(ResponseEntity.status(source.getStatusCode())
                    .headers(source.getHeaders()), 1L, result);
        }
    }
}
