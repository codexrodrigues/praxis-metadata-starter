package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentCapture;
import org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests covering single-composition/single-compilation {@code publishAll} and
 * {@code reconcileAll}, {@link BulkBatchProgressListener}, {@link BulkPublicationJobService}, and
 * {@link BulkPublicationAdminController} HTTP contracts against real PostgreSQL 17.
 */
class BulkNamespaceBatchAndAsyncPublicationTest {
    private static final String NAMESPACE = "test-namespace";
    private static final String DEPLOYMENT = "deployment-batch-test";
    private static final List<String> ALL_OPERATIONS = List.of(
            "crud.uniform", "crud.items", "crud.uniform-atomic", "crud.items-atomic");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final ObjectMapper COMPILER_MAPPER = BulkCrudStructuralCompilerTest.mapper();

    @Test
    void publishAllSharesOneColdOpenApiCompositionOneDescriptorCompilationAndSkipsReadyIdempotently()
            throws Exception {
        try (var fixture = createFixture(null)) {
            // Measure exact single-pass compileAll schema resolution count for baseline parity
            int expectedSingleCompilationCalls = measureSingleCompilationSchemaCalls(fixture.context);
            assertThat(expectedSingleCompilationCalls).isPositive();

            List<String> progressEvents = new ArrayList<>();
            BulkLifecycleAuditContext audit = BulkLifecycleAuditContext.of("batch-op", "platform", "corr-pub-all");
            BulkNamespaceBatchResult first = fixture.lifecycle.publishAll(
                    NAMESPACE,
                    audit,
                    (phase, total, processed, succeeded, skipped, failed) ->
                            progressEvents.add(phase + ":" + processed + "/" + total
                                    + ":ok=" + succeeded + ":skip=" + skipped + ":fail=" + failed));

            assertThat(first.namespace()).isEqualTo(NAMESPACE);
            assertThat(first.intent()).isEqualTo("PUBLISH_ALL");
            assertThat(first.publicationGeneration()).isEqualTo(2L);
            assertThat(first.totalOperations()).isEqualTo(4);
            assertThat(first.processedCount()).isEqualTo(4);
            assertThat(first.succeededCount()).isEqualTo(4);
            assertThat(first.skippedCount()).isZero();
            assertThat(first.failedCount()).isZero();
            assertThat(first.items())
                    .extracting(BulkNamespaceBatchResult.ItemOutcome::status)
                    .containsOnly(BulkNamespaceBatchResult.ItemStatus.PUBLISHED);
            assertThat(fixture.freshFetches.get()).isEqualTo(1);
            assertThat(fixture.schemaResolveCalls.get()).isEqualTo(expectedSingleCompilationCalls);
            assertThat(progressEvents).containsExactly(
                    "PREPARED:0/4:ok=0:skip=0:fail=0",
                    "PUBLISHING:1/4:ok=1:skip=0:fail=0",
                    "PUBLISHING:2/4:ok=2:skip=0:fail=0",
                    "PUBLISHING:3/4:ok=3:skip=0:fail=0",
                    "PUBLISHING:4/4:ok=4:skip=0:fail=0",
                    "COMPLETED:4/4:ok=4:skip=0:fail=0");
            assertThat(fixture.lifecycle.listAuditEntries(20))
                    .hasSize(4)
                    .allSatisfy(entry -> {
                        assertThat(entry.intent()).isEqualTo("PUBLISH_ALL");
                        assertThat(entry.actor()).isEqualTo("batch-op");
                        assertThat(entry.realm()).isEqualTo("platform");
                        assertThat(entry.previousGeneration()).isEqualTo(0L);
                        assertThat(entry.resultingGeneration()).isEqualTo(1L);
                        assertThat(entry.resultingState()).isEqualTo("READY");
                    });

            // Repeating publishAll skips all READY operations idempotently with zero cold fetches
            BulkNamespaceBatchResult second = fixture.lifecycle.publishAll(
                    NAMESPACE, audit, BulkBatchProgressListener.NOOP);
            assertThat(second.totalOperations()).isEqualTo(4);
            assertThat(second.succeededCount()).isZero();
            assertThat(second.skippedCount()).isEqualTo(4);
            assertThat(second.failedCount()).isZero();
            assertThat(second.items())
                    .extracting(BulkNamespaceBatchResult.ItemOutcome::status)
                    .containsOnly(BulkNamespaceBatchResult.ItemStatus.SKIPPED_ALREADY_READY);
            assertThat(fixture.freshFetches.get()).isEqualTo(1);
            assertThat(fixture.lifecycle.listAuditEntries(20)).hasSize(4);
        }
    }

    @Test
    void reconcileAllOnColdReplicaUsesOneColdCompositionOneCompilationAndSkipsNonReady() throws Exception {
        try (var nodeA = createFixture(null);
             var nodeB = createFixture(nodeA.store)) {
            nodeA.lifecycle.publish(identity("crud.uniform"), 0L);
            nodeA.lifecycle.publish(identity("crud.items"), 0L);
            nodeA.lifecycle.publish(identity("crud.uniform-atomic"), 0L);

            int expectedSingleCompilationCalls = measureSingleCompilationSchemaCalls(nodeB.context);

            BulkLifecycleAuditContext reconcileAudit = BulkLifecycleAuditContext.of(
                    "replica-sync", "ops", "corr-reconcile-all");
            BulkNamespaceBatchResult result = nodeB.lifecycle.reconcileAll(
                    NAMESPACE, reconcileAudit, BulkBatchProgressListener.NOOP);

            assertThat(result.intent()).isEqualTo("RECONCILE_ALL");
            assertThat(result.totalOperations()).isEqualTo(4);
            assertThat(result.processedCount()).isEqualTo(4);
            assertThat(result.succeededCount()).isEqualTo(3);
            assertThat(result.skippedCount()).isEqualTo(1);
            assertThat(result.failedCount()).isZero();
            assertThat(nodeB.freshFetches.get()).isEqualTo(1);
            assertThat(nodeB.schemaResolveCalls.get()).isEqualTo(expectedSingleCompilationCalls);
            assertThat(result.items())
                    .filteredOn(item -> "crud.items-atomic".equals(item.operationId()))
                    .singleElement()
                    .extracting(BulkNamespaceBatchResult.ItemOutcome::status)
                    .isEqualTo(BulkNamespaceBatchResult.ItemStatus.SKIPPED_NOT_READY);
            assertThat(nodeB.lifecycle.requireReady(identity("crud.uniform")).generation()).isEqualTo(1L);
            assertThat(nodeB.lifecycle.listAuditEntries(10))
                    .hasSize(3)
                    .extracting(BulkLifecycleAuditEntry::intent)
                    .containsOnly("RECONCILE_ALL");
        }
    }

    @Test
    void asyncJobServiceAndAdminControllerServe202ConflictPolling404AndAuditTrail() throws Exception {
        try (var fixture = createFixture(null)) {
            BulkPublicationJobService jobService = new BulkPublicationJobService(fixture.lifecycle);
            try {
                MockMvc mvc = standaloneMvc(new BulkPublicationAdminController(jobService));

                // Fail cleanly when reconcileAll runs before publication is committed
                BulkPublicationJobSnapshot failedReconcile = jobService.submit(
                        BulkPublicationJobIntent.RECONCILE_ALL,
                        BulkLifecycleAuditContext.of("precheck", "ops", "corr-fail"));
                BulkPublicationJobSnapshot failedTerminal = awaitTerminal(jobService, failedReconcile.jobId());
                assertThat(failedTerminal.status()).isEqualTo(BulkPublicationJobStatus.FAILED);
                assertThat(failedTerminal.errorCode()).isEqualTo("BULK_PUBLICATION_CONFLICT");

                // Pause first PUBLISH_ALL job in flight to deterministically verify 409 conflict on concurrent submit
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                fixture.holdGate.set(new Gate(entered, release));

                MvcResult submitted = mvc.perform(post("/api/platform/bulk-operations/publication/jobs")
                                .header("X-Correlation-Id", "corr-http-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"intent":"PUBLISH_ALL","actor":"release-bot","realm":"prod"}
                                        """))
                        .andExpect(status().isAccepted())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(header().exists(HttpHeaders.LOCATION))
                        .andExpect(jsonPath("$.status").value("success"))
                        .andExpect(jsonPath("$.data.namespace").value(NAMESPACE))
                        .andExpect(jsonPath("$.data.actor").value("release-bot"))
                        .andExpect(jsonPath("$.data.realm").value("prod"))
                        .andExpect(jsonPath("$.data.correlationId").value("corr-http-1"))
                        .andReturn();

                JsonNode submitJson = JSON.readTree(submitted.getResponse().getContentAsString());
                UUID jobId = UUID.fromString(submitJson.path("data").path("jobId").asText());
                assertThat(submitted.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("/jobs/" + jobId);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

                mvc.perform(post("/api/platform/bulk-operations/publication/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"intent\":\"PUBLISH_ALL\"}"))
                        .andExpect(status().isConflict())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.status").value("failure"))
                        .andExpect(jsonPath("$.errors[0].category").value("CONFLICT"))
                        .andExpect(jsonPath("$.errors[0].code").value("BULK_PUBLICATION_CONFLICT"));

                release.countDown();
                BulkPublicationJobSnapshot completed = awaitTerminal(jobService, jobId);
                assertThat(completed.status()).isEqualTo(BulkPublicationJobStatus.COMPLETED);

                mvc.perform(get("/api/platform/bulk-operations/publication/jobs/" + jobId))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                        .andExpect(jsonPath("$.data.succeededCount").value(4))
                        .andExpect(jsonPath("$.data.result.intent").value("PUBLISH_ALL"));

                mvc.perform(get("/api/platform/bulk-operations/publication/jobs"))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.data.length()").value(2));

                mvc.perform(get("/api/platform/bulk-operations/publication/audit").param("limit", "10"))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.data.length()").value(4))
                        .andExpect(jsonPath("$.data[0].actor").value("release-bot"))
                        .andExpect(jsonPath("$.data[0].intent").value("PUBLISH_ALL"));

                mvc.perform(get("/api/platform/bulk-operations/publication/jobs/" + UUID.randomUUID()))
                        .andExpect(status().isNotFound())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.errors[0].code").value("BULK_PUBLICATION_JOB_NOT_FOUND"));

                mvc.perform(post("/api/platform/bulk-operations/publication/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"intent\":\"NOT_A_VALID_INTENT\"}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors[0].code").value("BULK_PUBLICATION_INVALID_REQUEST"));

                mvc.perform(get("/api/platform/bulk-operations/publication/audit").param("limit", "0"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors[0].code").value("BULK_PUBLICATION_INVALID_REQUEST"));
            } finally {
                jobService.destroy();
            }
        }
    }

    @Test
    void adminControllerMapsUnavailable503AndDiagnostic422Errors() throws Exception {
        BulkPublicationJobService mockJobs = mock(BulkPublicationJobService.class,
                org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(mockJobs.submit(BulkPublicationJobIntent.PUBLISH_ALL, BulkLifecycleAuditContext.sanitized(null, null, null)))
                .thenThrow(new BulkLifecycleUnavailableException("openApi down"));
        when(mockJobs.listAuditEntries(25))
                .thenThrow(new BulkContractDiagnosticException("missing provider", "crud.uniform"));

        MockMvc mvc = standaloneMvc(new BulkPublicationAdminController(mockJobs));
        mvc.perform(post("/api/platform/bulk-operations/publication/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.errors[0].category").value("SYSTEM"))
                .andExpect(jsonPath("$.errors[0].code").value("BULK_LIFECYCLE_UNAVAILABLE"));

        mvc.perform(get("/api/platform/bulk-operations/publication/audit").param("limit", "25"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.errors[0].category").value("VALIDATION"))
                .andExpect(jsonPath("$.errors[0].code").value("BULK_CONTRACT_DIAGNOSTIC"))
                .andExpect(jsonPath("$.errors[0].target").value("crud.uniform"));
    }

    @Test
    void immediateResubmissionAfterObservingTerminalStateNeverConflicts() {
        BulkOperationLifecycle mockLifecycle = mock(BulkOperationLifecycle.class,
                org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        when(mockLifecycle.namespace()).thenReturn(NAMESPACE);
        when(mockLifecycle.publishAll(org.mockito.ArgumentMatchers.eq(NAMESPACE),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BulkNamespaceBatchResult(
                        NAMESPACE, "PUBLISH_ALL", 1L, 0, 0, 0, 0, 0, List.of(), java.time.Instant.now()));
        when(mockLifecycle.reconcileAll(org.mockito.ArgumentMatchers.eq(NAMESPACE),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BulkLifecycleUnavailableException("simulated unavailability"));

        BulkPublicationJobService service = new BulkPublicationJobService(mockLifecycle);
        BulkLifecycleAuditContext audit = BulkLifecycleAuditContext.sanitized("race-tester", "platform", "corr-race");
        try {
            for (int i = 0; i < 100; i++) {
                BulkPublicationJobIntent intent = (i % 2 == 0)
                        ? BulkPublicationJobIntent.PUBLISH_ALL
                        : BulkPublicationJobIntent.RECONCILE_ALL;
                BulkPublicationJobSnapshot submitted = service.submit(NAMESPACE, intent, audit);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                BulkPublicationJobSnapshot terminal = submitted;
                while (terminal.status() != BulkPublicationJobStatus.COMPLETED
                        && terminal.status() != BulkPublicationJobStatus.FAILED) {
                    if (System.nanoTime() >= deadline) {
                        throw new AssertionError("Timed out awaiting job completion at iteration " + i);
                    }
                    Thread.onSpinWait();
                    terminal = service.findJob(submitted.jobId()).orElseThrow();
                }
                assertThat(terminal.status()).isEqualTo(
                        intent == BulkPublicationJobIntent.PUBLISH_ALL
                                ? BulkPublicationJobStatus.COMPLETED
                                : BulkPublicationJobStatus.FAILED);
            }
        } finally {
            service.destroy();
        }
    }

    private static MockMvc standaloneMvc(BulkPublicationAdminController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .build();
    }

    private static BulkPublicationJobSnapshot awaitTerminal(BulkPublicationJobService service, UUID jobId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            BulkPublicationJobSnapshot snapshot = service.findJob(jobId).orElseThrow();
            if (snapshot.status() == BulkPublicationJobStatus.COMPLETED
                    || snapshot.status() == BulkPublicationJobStatus.FAILED) {
                return snapshot;
            }
            Thread.sleep(15);
        }
        throw new AssertionError("Async job did not complete within 10s: " + jobId);
    }

    private static int measureSingleCompilationSchemaCalls(AnnotationConfigWebApplicationContext context) {
        var mvc = context.getBean(RequestMappingHandlerMapping.class);
        var bindings = BulkResourceOperationBindings.from(mvc);
        var docs = new BulkCrudStructuralCompilerTest.Documents(BulkCrudOperationalCompositionTest.atomicDocument());
        var counter = new AtomicInteger();
        new BulkOperationStructuralCompiler(bindings,
                new OpenApiCanonicalOperationResolver(docs, mvc, bindings, List.of("crud")),
                docs, mock(ActionDefinitionRegistry.class), COMPILER_MAPPER, countingResolver(counter)).compileAll();
        return counter.get();
    }

    private static SchemaReferenceResolver countingResolver(AtomicInteger counter) {
        FilteredSchemaReferenceResolver delegate = new FilteredSchemaReferenceResolver();
        return new SchemaReferenceResolver() {
            @Override
            public org.praxisplatform.uischema.schema.CanonicalSchemaRef resolve(
                    org.praxisplatform.uischema.openapi.CanonicalOperationRef operationRef, String schemaType) {
                counter.incrementAndGet();
                return delegate.resolve(operationRef, schemaType);
            }

            @Override
            public org.praxisplatform.uischema.schema.CanonicalSchemaRef resolve(
                    String path, String method, String schemaType, boolean includeInternalSchemas,
                    String tenant, java.util.Locale locale, String idField, Boolean readOnly) {
                counter.incrementAndGet();
                return delegate.resolve(path, method, schemaType, includeInternalSchemas,
                        tenant, locale, idField, readOnly);
            }
        };
    }

    private static BulkOperationControlIdentity identity(String operationId) {
        return new BulkOperationControlIdentity(NAMESPACE, operationId);
    }

    private static BatchFixture createFixture(SharedStore existingStore) throws Exception {
        SharedStore store = existingStore != null ? existingStore : new SharedStore();
        boolean ownsStore = existingStore == null;
        try {
            var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.AtomicCrudController.class);
            ObjectNode value = BulkCrudOperationalCompositionTest.atomicDocument();
            value.put("openapi", "3.0.3");
            value.putObject("info").put("title", "Batch publication fixture").put("version", "1");
            AtomicInteger freshFetches = new AtomicInteger();
            AtomicInteger schemaResolveCalls = new AtomicInteger();
            AtomicReference<Gate> holdGate = new AtomicReference<>();

            var filterHolder = new GovernedOpenApiPublicationFilter[1];
            var registration = mock(jakarta.servlet.FilterRegistration.class);
            when(registration.getClassName()).thenReturn(GovernedOpenApiPublicationFilter.class.getName());
            when(registration.getUrlPatternMappings()).thenReturn(List.of("/*"));
            when(registration.getServletNameMappings()).thenReturn(List.of());
            var servletContext = new MockServletContext() {
                @Override
                public jakarta.servlet.FilterRegistration getFilterRegistration(String name) {
                    return registration;
                }
            };
            OpenApiDocsSupport source = new OpenApiDocsSupport() {
                @Override
                public String localPublicationContextPath() {
                    return "";
                }

                @Override
                public OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(
                        RestTemplate client, String path, Logger logger, ObjectMapper m) {
                    var request = new MockHttpServletRequest(servletContext, "GET", path);
                    request.setServletPath(path);
                    request.addHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER,
                            ((OpenApiInternalRestTemplate) client).producerCaptureToken(path));
                    var response = new MockHttpServletResponse();
                    try {
                        filterHolder[0].doFilter(request, response, (input, output) -> {
                            if (path.endsWith("/crud")) {
                                Gate gate = holdGate.getAndSet(null);
                                if (gate != null) {
                                    gate.entered.countDown();
                                    try {
                                        if (!gate.release.await(10, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException("Timed out waiting on test gate");
                                        }
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException("Interrupted on test gate", interrupted);
                                    }
                                }
                                freshFetches.incrementAndGet();
                            }
                            byte[] bytes = path.endsWith("/swagger-config")
                                    ? "{\"urls\":[{\"name\":\"crud\",\"url\":\"/v3/api-docs/crud\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                    : m.writeValueAsBytes(value);
                            output.getOutputStream().write(bytes);
                        });
                        return OpenApiDocumentCapture.parse(response.getContentAsByteArray(), m);
                    } catch (Exception ex) {
                        throw new IllegalStateException("Servlet producer fixture failed", ex);
                    }
                }

                @Override
                public String resolveGroupFromPath(String path) {
                    return "crud";
                }

                @Override
                public JsonNode fetchOpenApiDocument(RestTemplate client, String base, String group, Logger logger) {
                    return value.deepCopy();
                }

                @Override
                public JsonNode fetchOpenApiGroupDocument(RestTemplate client, String base, String group, Logger logger) {
                    return value.deepCopy();
                }

                @Override
                public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate client, String base, String group, Logger logger) {
                    return value.deepCopy();
                }
            };

            var client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10));
            var documents = new CachedOpenApiDocumentService(client, new ObjectMapper(), source, true);
            ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");
            filterHolder[0] = new GovernedOpenApiPublicationFilter(documents);
            filterHolder[0].init(new MockFilterConfig(servletContext, "bulkPublication"));

            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("crud"));
            List<BulkOperationDescriptorProvider> providers = List.of(
                    new BulkCrudOperationalCompositionTest.Provider("crud.uniform", "r1",
                            BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, store.runtime),
                    new BulkCrudOperationalCompositionTest.Provider("crud.items", "r1",
                            BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, store.runtime),
                    new BulkCrudOperationalCompositionTest.Provider("crud.uniform-atomic", "r1",
                            BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, store.runtime),
                    new BulkCrudOperationalCompositionTest.Provider("crud.items-atomic", "r1",
                            BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, store.runtime));
            var lifecycle = new BulkOperationLifecycle(bindings, resolver, documents,
                    mock(ActionDefinitionRegistry.class), COMPILER_MAPPER, countingResolver(schemaResolveCalls),
                    store.runtime, store.control, providers);
            return new BatchFixture(store, ownsStore, context, client, documents, bindings, resolver,
                    lifecycle, freshFetches, schemaResolveCalls, holdGate);
        } catch (Exception | Error failure) {
            if (ownsStore) {
                store.close();
            }
            throw failure;
        }
    }

    private record Gate(CountDownLatch entered, CountDownLatch release) {
    }

    private static final class SharedStore implements AutoCloseable {
        final EmbeddedPostgres postgres;
        final BulkExecutionInfrastructure runtime;
        final BulkControlPlaneInfrastructure control;

        SharedStore() throws Exception {
            postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
            DataSource admin = postgres.getPostgresDatabase();
            BulkExecutionMigrator.migrateWithOperations(admin, Map.of(NAMESPACE, DEPLOYMENT),
                    ALL_OPERATIONS.stream().map(BulkNamespaceBatchAndAsyncPublicationTest::identity).toList());
            BulkPostgresTestSupport.grantRuntimeRole(admin, "bulk_runtime_test");
            BulkPostgresTestSupport.grantControlRole(admin, "bulk_control_test");
            var roles = new BulkExecutionRoleConfiguration(
                    "postgres", Set.of("bulk_runtime_test"), Set.of(), Set.of("bulk_control_test"));
            DataSource runtimeDs = BulkPostgresTestSupport.runtimeDataSource(postgres);
            DataSource controlDs = new DriverManagerDataSource(
                    postgres.getJdbcUrl("bulk_control_test", "postgres"), "bulk_control_test", "");
            runtime = new BulkExecutionInfrastructure(
                    runtimeDs, new DataSourceTransactionManager(runtimeDs), NAMESPACE, DEPLOYMENT, roles);
            control = new BulkControlPlaneInfrastructure(
                    controlDs, new DataSourceTransactionManager(controlDs), NAMESPACE, DEPLOYMENT, "bulk_control_test", runtime);
        }

        @Override
        public void close() throws Exception {
            postgres.close();
        }
    }

    private record BatchFixture(
            SharedStore store,
            boolean ownsStore,
            AnnotationConfigWebApplicationContext context,
            OpenApiInternalRestTemplate client,
            CachedOpenApiDocumentService documents,
            BulkResourceOperationBindings bindings,
            OpenApiCanonicalOperationResolver resolver,
            BulkOperationLifecycle lifecycle,
            AtomicInteger freshFetches,
            AtomicInteger schemaResolveCalls,
            AtomicReference<Gate> holdGate) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            try {
                context.close();
            } finally {
                try {
                    client.close();
                } finally {
                    if (ownsStore) {
                        store.close();
                    }
                }
            }
        }
    }
}
