package org.praxisplatform.uischema.controller.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.capability.*;
import org.praxisplatform.uischema.command.*;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VersionedItemCommandHelpersTest {
    static final ResourceVersionEtagService ETAGS = new ResourceVersionEtagService("helper-test-secret");
    Controller controller;
    MockMvc mvc;

    @BeforeEach void setup() {
        controller = new Controller();
        controller.initializeBasePath();
        ReflectionTestUtils.setField(controller, "resourceVersionEtagService", ETAGS);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(controller.service.findById(1L)).thenReturn(ResourceRepresentationResult.versioned(new Body(1L, "CURRENT"), 0));
        when(controller.service.getDatasetVersion()).thenReturn(Optional.of("dataset"));
    }

    @Test void commandAndPatchPreserveStrongCapturedEtagAndCanonicalEnvelope() throws Exception {
        mvc.perform(post("/helper-items/1/approve").header("If-Match", token(0)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", token(1)))
                .andExpect(header().string("X-Data-Version", "dataset"))
                .andExpect(jsonPath("$.data.state").value("APPROVED"))
                .andExpect(jsonPath("$.data.persistedVersion").doesNotExist());
        mvc.perform(patch("/helper-items/1/reopen").header("If-Match", token(1)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", token(2)))
                .andExpect(jsonPath("$.data.state").value("OPEN"));
        assertEquals(2, controller.mutations);
        verify(controller.service, never()).findById(anyLong());
    }

    @Test void staleMissingAndWeakPreconditionsCannotMutate() throws Exception {
        mvc.perform(post("/helper-items/1/approve")).andExpect(status().isPreconditionRequired());
        mvc.perform(post("/helper-items/1/approve").header("If-Match", token(7))).andExpect(status().isPreconditionFailed());
        mvc.perform(post("/helper-items/1/approve").header("If-Match", "W/" + token(0))).andExpect(status().isBadRequest());
        assertEquals(0, controller.mutations);
    }

    @Test void denialIsEnforcedByGovernedExecutorAndUnknownReceiptStoreFailsClosed() throws Exception {
        CapabilityService capabilities = mock(CapabilityService.class);
        when(capabilities.itemOperationAvailability(anyString(), anyString(), anyString(), any())).thenReturn(AvailabilityDecision.deny("Blocked", Map.of()));
        ReflectionTestUtils.setField(controller, "capabilityService", capabilities);
        mvc.perform(post("/helper-items/1/approve").header("If-Match", token(0))).andExpect(status().isForbidden());
        assertEquals(0, controller.mutations);
        mvc.perform(post("/helper-items/1/approve").header("If-Match", token(0)).header("Idempotency-Key", "one"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void replayKeepsHistoricalBodyAndVersionAndStillChecksAccess() throws Exception {
        InMemoryReceipts receipts = new InMemoryReceipts();
        ReflectionTestUtils.setField(controller, "resourceCommandIdempotencyStore", receipts);
        mvc.perform(post("/helper-items/1/approve").header("If-Match", token(0)).header("Idempotency-Key", "one"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", token(1)));
        controller.version = 20;
        CapabilityService changedState = mock(CapabilityService.class);
        when(changedState.itemOperationAvailability(anyString(), anyString(), anyString(), any())).thenReturn(AvailabilityDecision.deny("No longer open", Map.of()));
        ReflectionTestUtils.setField(controller, "capabilityService", changedState);
        mvc.perform(post("/helper-items/1/approve").header("If-Match", token(0)).header("Idempotency-Key", "one"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", token(1)))
                .andExpect(jsonPath("$.data.state").value("APPROVED"));
        assertEquals(1, controller.mutations);
        verify(controller.service, times(2)).findById(1L);
        when(controller.service.findById(1L)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        mvc.perform(post("/helper-items/1/approve").header("If-Match", token(0)).header("Idempotency-Key", "one"))
                .andExpect(status().isForbidden());
    }

    @Test void relatedChildHelperAuthorizesParentBindsFilterAndLimitsPage() {
        when(controller.child.getDefaultSort()).thenReturn(Sort.by("name"));
        when(controller.child.getDatasetVersion()).thenReturn(Optional.of("children-v2"));
        when(controller.child.filter(any(), any(), any())).thenAnswer(invocation -> {
            ChildFilter filter = invocation.getArgument(0); Pageable pageable = invocation.getArgument(1);
            assertEquals(1L, filter.parentId); assertEquals(2, pageable.getPageNumber());
            assertEquals(20, pageable.getPageSize()); assertEquals(Sort.by("name"), pageable.getSort());
            return new PageImpl<>(List.of("child"), pageable, 41);
        });
        var response = controller.children(1L, 2, 20);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("children-v2", response.getHeaders().getFirst("X-Data-Version"));
        String json = new ObjectMapper().findAndRegisterModules().valueToTree(response.getBody()).toString();
        assertTrue(json.contains("/children")); assertTrue(json.contains("collection"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.children(1L, -1, 20));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.children(1L, 0, 201));
        verify(controller.child, times(1)).filter(any(), any(), any());
    }

    static String token(long version) { return ETAGS.create("helper.items", 1L, version); }
    public record Body(Long id, String state) { }
    public static class Filter implements GenericFilterDTO { }
    public static class ChildFilter implements GenericFilterDTO { Long parentId; }
    @RestController @RequestMapping("/helper-items") @ApiResource(value = "/helper-items", resourceKey = "helper.items")
    static class Controller extends AbstractResourceQueryController<Body, Long, Filter> {
        @SuppressWarnings("unchecked") final BaseResourceQueryService<Body, Long, Filter> service = mock(BaseResourceQueryService.class);
        @SuppressWarnings("unchecked") final BaseResourceQueryService<String, Long, ChildFilter> child = mock(BaseResourceQueryService.class);
        long version; int mutations;
        protected BaseResourceQueryService<Body, Long, Filter> getService() { return service; }
        protected Long getResponseId(Body dto) { return dto.id(); }
        @PostMapping("/{id}/approve") ResponseEntity<?> approve(@PathVariable Long id,
                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                @RequestHeader(value = "Idempotency-Key", required = false) String key) {
            return executeVersionedItemCommand("approve", id, ifMatch, key, Map.of("approved", true), condition -> mutate(condition, "APPROVED"));
        }
        @PatchMapping("/{id}/reopen") ResponseEntity<?> reopen(@PathVariable Long id,
                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
            return executeVersionedItemPatch("reopen", id, ifMatch, condition -> mutate(condition, "OPEN"));
        }
        ResourceRepresentationResult<Body> mutate(ResourceVersionUpdatePrecondition<Long> condition, String state) {
            condition.requireMatch(version); version++; mutations++;
            return ResourceRepresentationResult.versioned(new Body(1L, state), version);
        }
        ResponseEntity<RestApiResponse<Page<String>>> children(Long parentId, int page, int size) {
            return filterRelatedChildResource(parentId, child, "/children", ChildFilter::new, (filter, id) -> filter.parentId = id, page, size);
        }
    }
    @Test void idempotentCommandCapturesDiscoveryOutsideTransactionWhenBulkPublicationIsActive() throws Exception {
        CanonicalCapabilityResolver resolver = mock(CanonicalCapabilityResolver.class);
        org.praxisplatform.uischema.openapi.OpenApiDocumentService documents = mock(org.praxisplatform.uischema.openapi.OpenApiDocumentService.class);
        when(documents.hasLocalPublishedBulkOpenApiPublication()).thenReturn(true);
        when(documents.withSchemaCacheReadLock(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(documents.resolveGroupFromPath("/helper-items")).thenReturn("helper");
        when(documents.getDocumentForGroup("helper")).thenReturn(new ObjectMapper().createObjectNode());
        var fence = mock(org.praxisplatform.uischema.openapi.OpenApiDocumentService.BulkLifecycleDocumentFence.class);
        when(documents.captureBulkLifecycleDocumentFence()).thenReturn(fence);
        when(resolver.resolveOperations(any(), eq("/helper-items"))).thenReturn(Map.of());
        ReflectionTestUtils.setField(controller, "capabilityService", new DefaultCapabilityService(resolver, null, null, documents));
        ReflectionTestUtils.setField(controller, "resourceCommandIdempotencyStore", new InMemoryReceipts());
        mvc.perform(post("/helper-items/1/approve").header("If-Match", token(0)).header("Idempotency-Key", "bulk-pub-tx-1"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", token(1)))
                .andExpect(jsonPath("$.data.state").value("APPROVED"));
    }

    static class InMemoryReceipts implements ResourceCommandIdempotencyStore {
        private final Map<Identity, ResourceRepresentationResult<?>> receipts = new HashMap<>();
        @Override @SuppressWarnings("unchecked") public <T> ResourceRepresentationResult<T> execute(Identity identity, String fingerprint, Class<T> type, Runnable authorize,
                Supplier<ResourceRepresentationResult<T>> mutation) {
            boolean active = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                authorize.run();
                return (ResourceRepresentationResult<T>) receipts.computeIfAbsent(identity, ignored -> mutation.get());
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(active);
            }
        }
    }
}
