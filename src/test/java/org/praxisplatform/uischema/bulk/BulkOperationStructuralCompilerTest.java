package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionInteractionPolicy;
import org.praxisplatform.uischema.action.ActionOutcomeMode;
import org.praxisplatform.uischema.action.ActionOutcomePolicy;
import org.praxisplatform.uischema.action.ActionPreconditionPolicy;
import org.praxisplatform.uischema.action.ActionRequirement;
import org.praxisplatform.uischema.action.ActionRefreshPolicy;
import org.praxisplatform.uischema.action.ActionResourceVersionTransport;
import org.praxisplatform.uischema.action.ActionSelectionPolicy;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkOperationStructuralCompilerTest {
    private static final String RESOURCE = "inventory.items";
    private static final String ACTION_ID = "items.bulk-approve";
    private static final String EVALUATION_ID = ACTION_ID + ".evaluation";

    private static BulkExecutionRoleConfiguration controlPlaneTestRoles(javax.sql.DataSource admin) {
        BulkPostgresTestSupport.grantRuntimeRole(admin, "bulk_runtime_test");
        BulkPostgresTestSupport.grantRuntimeRole(admin, "durable_runtime");
        BulkPostgresTestSupport.grantControlRole(admin, "bulk_control_test");
        return new BulkExecutionRoleConfiguration("postgres", java.util.Set.of("bulk_runtime_test", "durable_runtime"),
                java.util.Set.of(), java.util.Set.of("bulk_control_test"));
    }

    @Test
    void composesSevenRealOperationsAgainstOneStrictGroupAndKeepsBodylessRolesBodyless() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("inventory"));
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper(), new FilteredSchemaReferenceResolver());

            BulkOperationStructuralDescriptor descriptor = compiler.compileAll().getFirst();

            assertEquals(RESOURCE, descriptor.resourceKey());
            assertEquals("inventory", descriptor.openApiGroup());
            assertEquals(BulkMode.DOMAIN_COMMAND, descriptor.mode());
            assertEquals(ActionCollectionAtomicity.PER_ITEM, descriptor.atomicity());
            assertEquals(List.of(BulkOperationStructuralDescriptor.Role.values()),
                    descriptor.operations().stream().map(BulkOperationStructuralDescriptor.Operation::role).toList());
            assertEquals(List.of("GET", "GET", "GET", "GET", "POST", "POST", "POST"),
                    descriptor.operations().stream().map(operation -> operation.reference().method()).toList());
            assertEquals(List.of(false, false, false, false, false, true, true),
                    descriptor.operations().stream().map(operation -> operation.requestSchema().isPresent()).toList());
            assertEquals(List.of(200, 202), descriptor.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .responseSchema().variants().stream().map(variant -> variant.status()).toList());
            assertEquals(1, documents.strictReads(), "all seven schema reads must use one exact group snapshot");
            assertEquals("approveSelectedItems", descriptor.action().id());
            assertEquals(List.of("BULK_APPROVE"), descriptor.action().requiredAuthorities());
            assertEquals("/api/items/actions/bulk-approve|post|request|internal:false|idField:id|readOnly:false",
                    descriptor.action().requestSchema().schemaId());
            assertEquals("/api/items/actions/bulk-approve|post|response|internal:false|idField:id|readOnly:false",
                    descriptor.action().responseSchema().schemaId());

            var returnedSchema = (com.fasterxml.jackson.databind.node.ObjectNode) descriptor
                    .operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .requestSchema().orElseThrow().schema();
            returnedSchema.remove("type");
            assertTrue(descriptor.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .requestSchema().orElseThrow().schema().has("type"));
            assertNotSame(returnedSchema, descriptor.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .requestSchema().orElseThrow().schema());
        }
    }

    @Test
    void acceptsCanonicalActionSchemaProjectionWhenIdFieldContainsPlus() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition("item+id")), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver());

            var descriptor = compiler.compileAll().getFirst();

            assertEquals("/api/items/actions/bulk-approve|post|request|internal:false|idField:item+id|readOnly:false",
                    descriptor.action().requestSchema().schemaId());
        }
    }

    @Test
    void preservesBusinessCatalogGroupSeparatelyFromStrictOpenApiGroup() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition("id", "inventory-business")), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver());

            var descriptor = compiler.compileAll().getFirst();

            assertEquals("inventory-business", descriptor.action().group());
            assertEquals("inventory", descriptor.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION)
                    .reference().group());
        }
    }

    @Test
    void operationalDescriptorIsDeterministicAndSensitiveToProviderCodecProfileAndDeployment() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(operationalDocument(BulkIdentityCodecs.longs()));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var structural = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver()).compileAll().getFirst();
            var first = BulkOperationalDescriptorComposer.compose(structural, provider("provider.r1", "deployment-a"));
            assertEquals("sha256:f3993230845dcce3c8ad40ab2a4101eb29e406151534a2d6a48d36b999e8d8ea", first.descriptorFingerprint(),
                    "independent baseline rc146 operational/1 command oracle");
            var repeated = BulkOperationalDescriptorComposer.compose(structural, provider("provider.r1", "deployment-a"));

            assertEquals(first.structuralRevision(), repeated.structuralRevision());
            assertEquals(first.descriptorFingerprint(), repeated.descriptorFingerprint());
            assertEquals(first.identity(), repeated.identity());
            assertEquals(1, first.expectation(1).generation());
            assertEquals(first.descriptorFingerprint(), first.expectation(2).descriptorFingerprint());
            assertFalse(first.descriptorFingerprint().equals(BulkOperationalDescriptorComposer.compose(
                    structural, provider("provider.r2", "deployment-a")).descriptorFingerprint()));
            assertFalse(first.descriptorFingerprint().equals(BulkOperationalDescriptorComposer.compose(
                    structural, provider("provider.r1", "deployment-b")).descriptorFingerprint()));
            assertFalse(first.descriptorFingerprint().equals(BulkOperationalDescriptorComposer.compose(
                    structural, provider("provider.r1", "deployment-a", 199)).descriptorFingerprint()));
            assertFalse(first.descriptorFingerprint().equals(BulkOperationalDescriptorComposer.compose(
                    structural, providerWithCodec("deployment-a", alternateLongCodec())).descriptorFingerprint()));
            assertThrows(IllegalArgumentException.class, () -> BulkOperationalDescriptorComposer.compose(
                    structural, providerWithCodec("deployment-a", BulkIdentityCodecs.integers())));
            assertTrue(first.descriptorFingerprint().matches("sha256:[0-9a-f]{64}"),
                    "the operational digest includes the newly versioned structural UI evidence");
        }
    }

    @Test
    void operationalCompositionRejectsProviderForDifferentConfirmationIdentity() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(operationalDocument(BulkIdentityCodecs.longs()));
            var structural = new BulkOperationStructuralCompiler(bindings,
                    new OpenApiCanonicalOperationResolver(documents, mvc, bindings), documents,
                    registry(actionDefinition()), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver()).compileAll().getFirst();
            assertThrows(IllegalArgumentException.class, () -> BulkOperationalDescriptorComposer.compose(
                    structural, provider("provider.r1", "deployment-a", "another.confirmation")));
        }
    }

    @Test
    void operationalCompositionRejectsAtomicityWithoutAnAtomicRuntimeProof() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(operationalDocument(BulkIdentityCodecs.longs()));
            var structural = new BulkOperationStructuralCompiler(bindings,
                    new OpenApiCanonicalOperationResolver(documents, mvc, bindings), documents,
                    registry(actionDefinition()), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver()).compileAll().getFirst();
            var atomic = new BulkOperationStructuralDescriptor(structural.resourceKey(), structural.openApiGroup(),
                    structural.mode(), ActionCollectionAtomicity.ATOMIC, structural.action(), structural.operations());
            assertThrows(IllegalArgumentException.class, () -> BulkOperationalDescriptorComposer.compose(
                    atomic, provider("provider.r1", "deployment-a")));
        }
    }

    @Test
    void operationalCompositionReadsEachHostContributionExactlyOnce() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(operationalDocument(BulkIdentityCodecs.longs()));
            var structural = new BulkOperationStructuralCompiler(bindings,
                    new OpenApiCanonicalOperationResolver(documents, mvc, bindings), documents,
                    registry(actionDefinition()), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver()).compileAll().getFirst();
            var provider = provider("provider.r1", "deployment-a");
            BulkOperationalDescriptorComposer.compose(structural, provider);
            assertEquals(1, provider.profileReads.get());
            assertEquals(1, provider.providerIdReads.get());
            assertEquals(1, provider.providerRevisionReads.get());
            assertEquals(1, provider.codecReads.get());
            assertEquals(1, provider.infrastructureReads.get());
        }
    }

    @Test
    void p1OperationalProfileRejectsUnprovedModeLimitsAndPrecision() {
        assertThrows(IllegalArgumentException.class, () -> new BulkOperationalProfile(
                EnumSet.of(BulkMode.DOMAIN_COMMAND), EnumSet.of(BulkExecutionMode.ASYNC),
                EnumSet.of(BulkSelectionMode.EXPLICIT), 200, 1024, Duration.ofMinutes(5), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new BulkOperationalProfile(
                EnumSet.of(BulkMode.DOMAIN_COMMAND), EnumSet.of(BulkExecutionMode.SYNC),
                EnumSet.of(BulkSelectionMode.QUERY), 200, 1024, Duration.ofMinutes(5), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new BulkOperationalProfile(
                EnumSet.of(BulkMode.DOMAIN_COMMAND), EnumSet.of(BulkExecutionMode.SYNC),
                EnumSet.of(BulkSelectionMode.EXPLICIT), 201, 1024, Duration.ofMinutes(5), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new BulkOperationalProfile(
                EnumSet.of(BulkMode.DOMAIN_COMMAND), EnumSet.of(BulkExecutionMode.SYNC),
                EnumSet.of(BulkSelectionMode.EXPLICIT), 200, 1024, Duration.ofMinutes(16), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new BulkOperationalProfile(
                EnumSet.of(BulkMode.DOMAIN_COMMAND), EnumSet.of(BulkExecutionMode.SYNC),
                EnumSet.of(BulkSelectionMode.EXPLICIT), 200, 1024, Duration.ofMinutes(5), Duration.ofMillis(5001)));
        assertThrows(IllegalArgumentException.class, () -> new BulkOperationalProfile(
                EnumSet.of(BulkMode.DOMAIN_COMMAND), EnumSet.of(BulkExecutionMode.SYNC),
                EnumSet.of(BulkSelectionMode.EXPLICIT), 200, 1024, Duration.ofNanos(1), Duration.ofSeconds(1)));
    }

    @Test
    void lifecyclePublicationCasRemainsInsideTheFreshSnapshotAndCacheWriteLock() throws Exception {
        verifyPublicationAdmission(false);
    }

    @Test
    void poolWaitExpiryBeforeCasCannotReconcileAnotherPublishersReadyRowAsSuccess() throws Exception {
        verifyPublicationAdmission(true);
    }

    private void verifyPublicationAdmission(boolean expireWhileAcquiringConnection) throws Exception {
        try (var context = context();
             var http = new org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(2));
             var sql = org.mockito.Mockito.mockStatic(JdbcBulkOperationControl.class,
                     org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE))) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            JsonNode document = operationalDocument(BulkIdentityCodecs.longs());
            var source = new org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport() {
                @Override public String resolveGroupFromPath(String path) { return "inventory"; }
                @Override public JsonNode fetchOpenApiDocument(org.springframework.web.client.RestTemplate client,
                        String base, String group, org.slf4j.Logger logger) { return document.deepCopy(); }
                @Override public JsonNode fetchOpenApiGroupDocument(org.springframework.web.client.RestTemplate client,
                        String base, String group, org.slf4j.Logger logger) { return document.deepCopy(); }
                @Override public JsonNode fetchFreshOpenApiGroupDocument(org.springframework.web.client.RestTemplate client,
                        String base, String group, org.slf4j.Logger logger) { return document.deepCopy(); }
            };
            var documents = new org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService(
                    http, new ObjectMapper(), source, true, Duration.ofMillis(400));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("inventory"));
            var runtime = org.mockito.Mockito.mock(BulkExecutionInfrastructure.class,
                    org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
            org.mockito.Mockito.when(runtime.namespace()).thenReturn("test-namespace");
            org.mockito.Mockito.when(runtime.deploymentId()).thenReturn("deployment-a");
            var control = org.mockito.Mockito.mock(BulkControlPlaneInfrastructure.class,
                    org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
            org.mockito.Mockito.when(control.namespace()).thenReturn("test-namespace");
            org.mockito.Mockito.when(control.deploymentId()).thenReturn("deployment-a");
            var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
            org.mockito.Mockito.when(runtime.withLifecycleRead(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                org.springframework.jdbc.core.ConnectionCallback<?> callback = invocation.getArgument(0);
                return callback.doInConnection(connection);
            });
            org.mockito.Mockito.when(control.withConnection(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                if (expireWhileAcquiringConnection) java.util.concurrent.TimeUnit.MILLISECONDS.sleep(600);
                org.springframework.jdbc.core.ConnectionCallback<?> callback = invocation.getArgument(0);
                return callback.doInConnection(connection);
            });
            var provider = provider("provider.r1", "deployment-a", 200, ACTION_ID, runtime);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents, registry(actionDefinition()),
                    new ObjectMapper(), new FilteredSchemaReferenceResolver());
            var descriptor = BulkOperationalDescriptorComposer.compose(compiler.compileAll().getFirst(), provider);
            var reads = new AtomicInteger();
            var attempts = new AtomicInteger();
            sql.when(() -> JdbcBulkOperationControl.lockForAdmission(connection, "test-namespace", ACTION_ID))
                    .thenAnswer(invocation -> reads.incrementAndGet() == 1
                            ? new JdbcBulkOperationControl.Snapshot("UNCOMPOSED", 0, null, null)
                            : new JdbcBulkOperationControl.Snapshot("READY", 1,
                                    descriptor.descriptorFingerprint(), descriptor.structuralRevision()));
            sql.when(() -> JdbcBulkOperationControl.transition(connection, "test-namespace", ACTION_ID, 0,
                    JdbcBulkOperationControl.Target.READY, descriptor.descriptorFingerprint(), descriptor.structuralRevision()))
                    .thenAnswer(invocation -> {
                        attempts.incrementAndGet();
                        var lock = (java.util.concurrent.locks.ReentrantReadWriteLock)
                                org.springframework.test.util.ReflectionTestUtils.getField(documents, "cacheLifecycleLock");
                        var snapshot = (ThreadLocal<?>) org.springframework.test.util.ReflectionTestUtils.getField(documents, "lifecycleSnapshot");
                        assertTrue(lock.isWriteLockedByCurrentThread(), "durable CAS must exclude cache invalidation");
                        assertTrue(snapshot.get() != null, "durable CAS must remain inside the captured fresh composition");
                        return new JdbcBulkOperationControl.Transition(true, 1);
                    });
            var lifecycle = new BulkOperationLifecycle(bindings, resolver, documents, registry(actionDefinition()),
                    new ObjectMapper(), new FilteredSchemaReferenceResolver(), runtime, control, List.of(provider));
            var identity = new BulkOperationControlIdentity("test-namespace", ACTION_ID);
            if (expireWhileAcquiringConnection) {
                assertThrows(IllegalStateException.class, () -> lifecycle.publish(identity, 0));
                assertEquals(0, attempts.get(), "pool expiry must not start a CAS");
                assertEquals(1, reads.get(), "pre-CAS failure cannot reconcile a concurrent READY row as our commit");
            } else {
                assertEquals(1, lifecycle.publish(identity, 0).generation());
                assertEquals(1, attempts.get());
            }
        }
    }

    @Test
    void lifecyclePublishesOnlyFreshCompositionAndSuspendsBeforeInvalidatingCaches() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start(); var context = context()) {
            var admin = postgres.getPostgresDatabase();
            var operation = new BulkOperationControlIdentity("test-namespace", ACTION_ID);
            BulkExecutionMigrator.migrateWithOperations(admin, java.util.Map.of("test-namespace", "deployment-a"),
                    List.of(operation));
            var roles = controlPlaneTestRoles(admin);
            var runtimeDs = BulkPostgresTestSupport.runtimeDataSource(postgres);
            var controlDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_control_test", "postgres"),
                    "bulk_control_test", "");
            var runtime = new BulkExecutionInfrastructure(runtimeDs, new DataSourceTransactionManager(runtimeDs),
                    "test-namespace", "deployment-a", roles);
            var uncertainCommitManager = new CommitThenFailOnceTransactionManager(controlDs);
            var control = new BulkControlPlaneInfrastructure(controlDs,
                    uncertainCommitManager, "test-namespace", "deployment-a", "bulk_control_test", runtime);
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(operationalDocument(BulkIdentityCodecs.longs()));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var provider = provider("provider.r1", "deployment-a", 200, ACTION_ID, runtime);
            var lifecycle = new BulkOperationLifecycle(bindings, resolver, documents, registry(actionDefinition()),
                    new ObjectMapper(), new FilteredSchemaReferenceResolver(), runtime, control,
                    List.of(provider));

            assertThrows(IllegalStateException.class, () -> lifecycle.requireReady(operation));
            var ready = lifecycle.publish(operation, 0);
            assertEquals(1, ready.generation());
            assertEquals(2, documents.freshReads(), "initial readiness and publication each compose a fresh snapshot");
            assertEquals(ready, lifecycle.requireReady(operation));

            uncertainCommitManager.failNextCommitAcknowledgement();
            assertEquals(2, lifecycle.suspend(operation, 1));
            assertEquals(1, documents.cacheClears());
            assertThrows(IllegalStateException.class, () -> lifecycle.requireReady(operation));
            assertThrows(IllegalStateException.class, () -> lifecycle.publish(operation, 1));
            var republished = lifecycle.publish(operation, 2);
            assertEquals(3, republished.generation());
            assertEquals(5, documents.freshReads(), "readiness revalidates its source after each state transition");
            assertEquals(republished, lifecycle.requireReady(operation));
            assertEquals("READY", new JdbcTemplate(admin).queryForObject(
                    "select state from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?",
                    String.class, operation.namespaceId(), operation.confirmationOperationId()));
            assertEquals(3L, new JdbcTemplate(admin).queryForObject(
                    "select generation from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?",
                    Long.class, operation.namespaceId(), operation.confirmationOperationId()));
            documents.clearCaches();
            assertEquals(4L, new JdbcTemplate(admin).queryForObject(
                    "select generation from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?",
                    Long.class, operation.namespaceId(), operation.confirmationOperationId()),
                    "a direct public cache clear must durably suspend before clearing");
            assertThrows(IllegalStateException.class, () -> lifecycle.requireReady(operation));
        }
    }

    @Test
    void scopedDiscoveryReusesOneCaptureOutsideLocksAndKeepsAuthorization() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
                var context = context(); var fixture = discoveryFixture(postgres, context);
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var capturedFence = new java.util.concurrent.atomic.AtomicReference<OpenApiDocumentService.BulkLifecycleDocumentFence>();
            var catalog = new org.praxisplatform.uischema.action.ActionCatalogService(registry(fixture.action),
                    (definition, availabilityContext) -> {
                        org.junit.jupiter.api.Assertions.assertSame(fixture.action, definition);
                        assertDiscoveryConsumerUnlocked(fixture);
                        try {
                            assertTrue(executor.submit(() -> fixture.documents.withBulkLifecycleCompositionLock(() -> true))
                                    .get(1, java.util.concurrent.TimeUnit.SECONDS), "another writer can enter while the consumer runs");
                        } catch (Exception failure) { throw new AssertionError(failure); }
                        capturedFence.set(currentDiscoveryFence(fixture.lifecycle));
                        assertEquals(fixture.ready, fixture.lifecycle.requireReady(fixture.identity));
                        assertEquals(fixture.ready, fixture.lifecycle.requireReady(fixture.identity));
                        return new org.praxisplatform.uischema.action.RequiredAuthoritiesActionAvailabilityRule()
                                .evaluate(definition, availabilityContext);
                    }, (key, path, id) -> new org.praxisplatform.uischema.action.ActionAvailabilityContext(
                            key, path, id, "tenant-a", java.util.Locale.ROOT, () -> "reader",
                            java.util.Set.of(), null), fixture.lifecycle::projectReadyActions);
            int before = fixture.freshReads.get();
            for (int request = 1; request <= 2; request++) {
                var item = catalog.findByResourceKey(RESOURCE).actions().getFirst();
                assertFalse(item.availability().allowed());
                assertEquals("missing-authority", item.availability().reason());
                org.junit.jupiter.api.Assertions.assertNotNull(item.execution().bulk());
                assertEquals(before + request, fixture.freshReads.get(), "two readiness checks reuse only this response capture");
                assertThrows(IllegalStateException.class, () -> capturedFence.get().read(() -> true),
                        "the lifecycle closes its handle when the response finishes");
            }
            assertEquals(fixture.ready, fixture.lifecycle.requireReady(fixture.identity));
            assertEquals(before + 3, fixture.freshReads.get(), "outside discovery readiness always starts fresh");
        }
    }

    @Test
    void scopedDiscoveryRejectsCaughtProviderDriftAndDurableRepublishOfIdenticalContent() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
                var context = context(); var fixture = discoveryFixture(postgres, context)) {
            var calls = new AtomicInteger();
            var providerReadsAfterCaughtFailure = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.projectReadyActions(List.of(fixture.action), contracts -> {
                calls.incrementAndGet();
                fixture.provider.revision = "provider.r2";
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(fixture.identity));
                fixture.provider.revision = "provider.r1";
                providerReadsAfterCaughtFailure.set(fixture.provider.providerRevisionReads.get());
                return false; // The real host rule also catches readiness failures and returns denied.
            }));
            assertEquals(1, calls.get());
            assertTrue(fixture.provider.providerRevisionReads.get() > providerReadsAfterCaughtFailure.get(),
                    "the final provider check still executes after the host catches a scoped failure");
            assertFalse(fixture.lifecycle.projectReadyActions(List.of(fixture.action)).isEmpty());

            calls.set(0);
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.projectReadyActions(List.of(fixture.action), contracts -> {
                calls.incrementAndGet();
                assertEquals(fixture.ready, fixture.lifecycle.requireReady(fixture.identity));
                fixture.control.withConnection(connection -> {
                    assertTrue(JdbcBulkOperationControl.transition(connection, fixture.identity.namespaceId(), ACTION_ID, 1,
                            JdbcBulkOperationControl.Target.SUSPENDED, null, null).applied());
                    assertTrue(JdbcBulkOperationControl.transition(connection, fixture.identity.namespaceId(), ACTION_ID, 2,
                            JdbcBulkOperationControl.Target.READY, fixture.ready.descriptorFingerprint(),
                            fixture.ready.structuralRevision()).applied());
                    return null;
                });
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(fixture.identity));
                return false;
            }));
            assertEquals(1, calls.get());
            assertEquals(3, fixture.lifecycle.requireReady(fixture.identity).generation());

            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.projectReadyActions(List.of(fixture.action), contracts -> {
                fixture.control.withConnection(connection -> JdbcBulkOperationControl.transition(connection,
                        fixture.identity.namespaceId(), ACTION_ID, 3, JdbcBulkOperationControl.Target.SUSPENDED, null, null));
                return true; // No scoped readiness call: the final durable fence must catch this.
            }));
            assertFalse(fixture.lifecycle.<Boolean>projectReadyActions(List.of(fixture.action), contracts -> {
                assertTrue(contracts.isEmpty());
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(fixture.identity));
                return false;
            }));
        }
    }

    @Test
    void scopedDiscoveryCleansFailuresAndIsolatesConcurrentResponsesWithoutRetry() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
                var context = context(); var fixture = discoveryFixture(postgres, context)) {
            int before = fixture.freshReads.get();
            var calls = new AtomicInteger();
            fixture.failFresh.set(true);
            assertFalse(fixture.lifecycle.<Boolean>projectReadyActions(List.of(fixture.action), contracts -> {
                calls.incrementAndGet();
                assertTrue(contracts.isEmpty());
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.requireReady(fixture.identity));
                return false;
            }));
            assertEquals(1, calls.get());
            assertEquals(before + 1, fixture.freshReads.get(), "preparation failure cannot retry from the host availability rule");
            fixture.failFresh.set(false);

            var failure = new IllegalArgumentException("consumer failed");
            var escapedFence = new java.util.concurrent.atomic.AtomicReference<OpenApiDocumentService.BulkLifecycleDocumentFence>();
            org.junit.jupiter.api.Assertions.assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> fixture.lifecycle.projectReadyActions(List.of(fixture.action), contracts -> {
                        calls.incrementAndGet();
                        escapedFence.set(currentDiscoveryFence(fixture.lifecycle));
                        throw failure;
                    })));
            assertEquals(2, calls.get());
            assertThrows(IllegalStateException.class, () -> escapedFence.get().read(() -> true));
            assertFalse(fixture.lifecycle.projectReadyActions(List.of(fixture.action)).isEmpty());

            int beforeConcurrent = fixture.freshReads.get();
            var entered = new java.util.concurrent.CountDownLatch(2);
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<Boolean> request = () -> fixture.lifecycle.projectReadyActions(List.of(fixture.action), contracts -> {
                    assertDiscoveryConsumerUnlocked(fixture);
                    entered.countDown();
                    try { assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS), "consumers do not retain preparation mutex"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    assertEquals(fixture.ready, fixture.lifecycle.requireReady(fixture.identity));
                    assertEquals(fixture.ready, fixture.lifecycle.requireReady(fixture.identity));
                    return !contracts.isEmpty();
                });
                var first = executor.submit(request);
                var second = executor.submit(request);
                assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals(beforeConcurrent + 2, fixture.freshReads.get(), "each concurrent response owns exactly one fresh capture");
        }
    }

    private DiscoveryFixture discoveryFixture(EmbeddedPostgres postgres, AnnotationConfigWebApplicationContext context) {
        var identity = new BulkOperationControlIdentity("test-namespace", ACTION_ID);
        var admin = postgres.getPostgresDatabase();
        BulkExecutionMigrator.migrateWithOperations(admin, java.util.Map.of("test-namespace", "deployment-a"), List.of(identity));
        var roles = controlPlaneTestRoles(admin);
        var runtimeDs = BulkPostgresTestSupport.runtimeDataSource(postgres);
        var controlDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_control_test", "postgres"), "bulk_control_test", "");
        var runtime = new BulkExecutionInfrastructure(runtimeDs, new DataSourceTransactionManager(runtimeDs),
                "test-namespace", "deployment-a", roles);
        var control = new BulkControlPlaneInfrastructure(controlDs, new DataSourceTransactionManager(controlDs),
                "test-namespace", "deployment-a", "bulk_control_test", runtime);
        var freshReads = new AtomicInteger();
        var failFresh = new java.util.concurrent.atomic.AtomicBoolean();
        JsonNode document = projectedDocument();
        var source = new org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport() {
            @Override public String resolveGroupFromPath(String path) { return "inventory"; }
            @Override public JsonNode fetchOpenApiDocument(org.springframework.web.client.RestTemplate client,
                    String base, String group, org.slf4j.Logger logger) { return document.deepCopy(); }
            @Override public JsonNode fetchOpenApiGroupDocument(org.springframework.web.client.RestTemplate client,
                    String base, String group, org.slf4j.Logger logger) { return document.deepCopy(); }
            @Override public JsonNode fetchFreshOpenApiGroupDocument(org.springframework.web.client.RestTemplate client,
                    String base, String group, org.slf4j.Logger logger) {
                freshReads.incrementAndGet();
                if (failFresh.get()) throw new IllegalStateException("fresh source unavailable");
                return document.deepCopy();
            }
        };
        var client = new org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10));
        var documents = new org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService(client, new ObjectMapper(), source, true);
        org.springframework.test.util.ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");
        var mvc = context.getBean(RequestMappingHandlerMapping.class);
        var bindings = BulkResourceOperationBindings.from(mvc);
        var action = actionDefinition();
        var provider = provider("provider.r1", "deployment-a", 200, ACTION_ID, runtime);
        var lifecycle = new BulkOperationLifecycle(bindings, new OpenApiCanonicalOperationResolver(documents, mvc, bindings),
                documents, registry(action), new ObjectMapper(), new FilteredSchemaReferenceResolver(),
                runtime, control, List.of(provider));
        var ready = lifecycle.publish(identity, 0);
        return new DiscoveryFixture(documents, client, lifecycle, provider, action, identity, control, ready, freshReads, failFresh);
    }

    private static void assertDiscoveryConsumerUnlocked(DiscoveryFixture fixture) {
        var lock = (java.util.concurrent.locks.ReentrantReadWriteLock)
                org.springframework.test.util.ReflectionTestUtils.getField(fixture.documents, "cacheLifecycleLock");
        var preparation = (java.util.concurrent.locks.ReentrantLock)
                org.springframework.test.util.ReflectionTestUtils.getField(fixture.documents, "compositionPreparationLock");
        assertFalse(lock.isWriteLockedByCurrentThread());
        assertEquals(0, lock.getReadHoldCount());
        assertFalse(preparation.isHeldByCurrentThread());
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }

    private static OpenApiDocumentService.BulkLifecycleDocumentFence currentDiscoveryFence(BulkOperationLifecycle lifecycle) {
        var frames = (ThreadLocal<?>) org.springframework.test.util.ReflectionTestUtils.getField(lifecycle, "responseProjection");
        return (OpenApiDocumentService.BulkLifecycleDocumentFence)
                org.springframework.test.util.ReflectionTestUtils.getField(frames.get(), "documentFence");
    }

    private record DiscoveryFixture(org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService documents,
            org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate client, BulkOperationLifecycle lifecycle,
            TestProvider provider, ActionDefinition action, BulkOperationControlIdentity identity,
            BulkControlPlaneInfrastructure control, BulkOperationControlExpectation ready, AtomicInteger freshReads,
            java.util.concurrent.atomic.AtomicBoolean failFresh) implements AutoCloseable {
        @Override public void close() { client.close(); }
    }

    @Test
    void actionProjectionUsesOneFreshCompositionAndRejectsEveryStaleFenceAndMissingUiSelection() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start(); var context = context()) {
            var admin = postgres.getPostgresDatabase();
            var identity = new BulkOperationControlIdentity("test-namespace", ACTION_ID);
            BulkExecutionMigrator.migrateWithOperations(admin, java.util.Map.of("test-namespace", "deployment-a"), List.of(identity));
            var roles = controlPlaneTestRoles(admin);
            var runtimeDs = BulkPostgresTestSupport.runtimeDataSource(postgres);
            var controlDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_control_test", "postgres"), "bulk_control_test", "");
            var runtime = new BulkExecutionInfrastructure(runtimeDs, new DataSourceTransactionManager(runtimeDs),
                    "test-namespace", "deployment-a", roles);
            var control = new BulkControlPlaneInfrastructure(controlDs, new DataSourceTransactionManager(controlDs),
                    "test-namespace", "deployment-a", "bulk_control_test", runtime);
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var document = projectedDocument();
            var documents = new TestDocuments(document);
            var provider = provider("provider.r1", "deployment-a", 200, ACTION_ID, runtime);
            var action = actionDefinition();
            var lifecycle = new BulkOperationLifecycle(bindings, new OpenApiCanonicalOperationResolver(documents, mvc, bindings),
                    documents, registry(action), new ObjectMapper(), new FilteredSchemaReferenceResolver(),
                    runtime, control, List.of(provider));
            assertTrue(lifecycle.projectReadyActions(List.of(action)).isEmpty(), "UNCOMPOSED cannot expose bulk");
            var ready = lifecycle.publish(identity, 0);
            int before = documents.freshReads();
            var contracts = lifecycle.projectReadyActions(List.of(action));
            assertEquals(before + 1, documents.freshReads(), "one fresh composition per response");
            var bulk = contracts.get(ACTION_ID).bulk();
            org.junit.jupiter.api.Assertions.assertNotNull(bulk);
            org.junit.jupiter.api.Assertions.assertSame(action.operation(), bulk.confirmationOperation().operation());
            org.junit.jupiter.api.Assertions.assertSame(action.requestSchema(), bulk.confirmationOperation().requestSchema());
            org.junit.jupiter.api.Assertions.assertSame(action.responseSchema(), bulk.confirmationOperation().responseSchema());
            assertEquals(200, bulk.limits().maxTargets());
            assertEquals("/properties/parameters", bulk.parametersPointer());
            assertTrue(bulk.proposalOperation().responseSchema().url().contains("idField=proposalId"));
            assertTrue(bulk.proposalOperation().responseSchema().url().contains("readOnly=true"));
            assertTrue(bulk.evaluationOperation().responseSchema().url().contains("readOnly=false"));
            org.junit.jupiter.api.Assertions.assertNull(bulk.proposalOperation().requestSchema());

            var catalog = new org.praxisplatform.uischema.action.ActionCatalogService(registry(action),
                    (definition, ctx) -> org.praxisplatform.uischema.capability.AvailabilityDecision.allow(java.util.Map.of()),
                    (key, path, id) -> null, lifecycle::projectReadyActions);
            before = documents.freshReads();
            assertEquals(bulk, catalog.findByResourceKey(RESOURCE).actions().getFirst().execution().bulk());
            assertEquals(before + 1, documents.freshReads());

            var changed = document.deepCopy();
            ((ObjectNode) changed.path("components").path("schemas").path("ProposalView"))
                    .put("description", "Changed UI component, identical HTTP transport");
            documents.setRefreshedDocument("inventory", changed);
            assertTrue(lifecycle.projectReadyActions(List.of(action)).isEmpty(), "stale UI revision must close the projection");
            documents.setRefreshedDocument("inventory", document);
            documents.setPublicDocument("inventory", document);
            var afterCacheDrift = runtime.withLifecycleRead(connection -> JdbcBulkOperationControl.lockForAdmission(
                    connection, identity.namespaceId(), ACTION_ID));
            assertEquals("SUSPENDED", afterCacheDrift.state(), "a divergent public cache must durably suspend projection");
            ready = lifecycle.publish(identity, afterCacheDrift.generation());
            provider.revision = "provider.r2";
            assertTrue(lifecycle.projectReadyActions(List.of(action)).isEmpty(), "operational-only fingerprint drift closes projection");
            provider.revision = "provider.r1";

            // A remote suspend + republish can preserve content but must not preserve this read's generation.
            var readyForRace = ready;
            documents.onFreshSnapshot = () -> control.withConnection(connection -> {
                JdbcBulkOperationControl.transition(connection, identity.namespaceId(), ACTION_ID, readyForRace.generation(),
                        JdbcBulkOperationControl.Target.SUSPENDED, null, null);
                return JdbcBulkOperationControl.transition(connection, identity.namespaceId(), ACTION_ID, readyForRace.generation() + 1,
                        JdbcBulkOperationControl.Target.READY, readyForRace.descriptorFingerprint(), readyForRace.structuralRevision());
            });
            assertTrue(lifecycle.projectReadyActions(List.of(action)).isEmpty(), "generation changes during composition are stale");
            assertFalse(lifecycle.projectReadyActions(List.of(action)).isEmpty(), "a later read can observe the newly stable generation");
            var afterRace = runtime.withLifecycleRead(connection -> JdbcBulkOperationControl.lockForAdmission(
                    connection, identity.namespaceId(), ACTION_ID));
            lifecycle.suspend(identity, afterRace.generation());
            assertTrue(lifecycle.projectReadyActions(List.of(action)).isEmpty(), "SUSPENDED cannot expose bulk");

            // Raw inline responses remain valid structural evidence, but are insufficient for operational READY.
            JsonNode unprojectable = operationalDocument(BulkIdentityCodecs.longs());
            unprojectable.path("paths").forEach(path -> path.forEach(op -> ((ObjectNode) op).remove("x-ui")));
            documents.setRefreshedDocument("inventory", unprojectable);
            var suspended = runtime.withLifecycleRead(connection -> JdbcBulkOperationControl.lockForAdmission(
                    connection, identity.namespaceId(), ACTION_ID));
            var suspendedGeneration = suspended.generation();
            assertThrows(IllegalStateException.class, () -> lifecycle.publish(identity, suspendedGeneration),
                    "missing UI selection prevents publication");
            assertThrows(IllegalStateException.class, () -> lifecycle.requireReady(identity));
            assertTrue(lifecycle.projectReadyActions(List.of(action)).isEmpty(), "missing UI selection cannot publish dead links");
            assertEquals("SUSPENDED", new JdbcTemplate(admin).queryForObject(
                    "select state from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?",
                    String.class, identity.namespaceId(), ACTION_ID));
            documents.setRefreshedDocument("inventory", document);
            documents.setPublicDocument("inventory", document);
            suspended = runtime.withLifecycleRead(connection -> JdbcBulkOperationControl.lockForAdmission(
                    connection, identity.namespaceId(), ACTION_ID));
            lifecycle.publish(identity, suspended.generation());
            documents.setRefreshedDocument("inventory", unprojectable);
            assertThrows(IllegalStateException.class, () -> lifecycle.requireReady(identity), "an existing READY row cannot bypass broken UI composition");
            org.junit.jupiter.api.Assertions.assertNull(catalog.findByResourceKey(RESOURCE).actions().getFirst().execution().bulk());
        }
    }

    private JsonNode projectedDocument() {
        ObjectNode root = (ObjectNode) operationalDocument(BulkIdentityCodecs.longs());
        ObjectNode schemas = root.putObject("components").putObject("schemas");
        schemas.putObject("Transport").put("type", "object").putObject("properties").putObject("accepted").put("type", "boolean");
        schemas.putObject("ProposalView").put("type", "object").putObject("properties").putObject("proposalId").put("type", "string");
        root.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(entry -> {
            ObjectNode operation = (ObjectNode) entry.getValue();
            operation.path("responses").forEach(response -> ((ObjectNode) response.path("content").path("application/json"))
                    .set("schema", JsonNodeFactory.instance.objectNode().put("$ref", "#/components/schemas/Transport")));
            operation.putObject("x-ui").put("responseSchema", "ProposalView");
        }));
        ((ObjectNode) root.path("paths").path("/api/items/actions/bulk-approve/evaluation").path("post")
                .path("requestBody").path("content").path("application/json").path("schema").path("properties"))
                .putObject("parameters").put("type", "object");
        return root;
    }

    private static final class CommitThenFailOnceTransactionManager extends DataSourceTransactionManager {
        private boolean failNextCommit = true;

        private CommitThenFailOnceTransactionManager(javax.sql.DataSource dataSource) { super(dataSource); }

        private void failNextCommitAcknowledgement() { failNextCommit = true; }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (failNextCommit) {
                failNextCommit = false;
                throw new TransactionSystemException("Injected lost commit acknowledgement after PostgreSQL commit");
            }
        }
    }

    @Test
    void lifecycleFailsClosedWhenAnyValidatedConfirmationHasNoExactProvider() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start(); var context = context()) {
            var admin = postgres.getPostgresDatabase();
            var operation = new BulkOperationControlIdentity("test-namespace", ACTION_ID);
            BulkExecutionMigrator.migrateWithOperations(admin, java.util.Map.of("test-namespace", "deployment-a"),
                    List.of(operation));
            var roles = controlPlaneTestRoles(admin);
            var runtimeDs = BulkPostgresTestSupport.runtimeDataSource(postgres);
            var controlDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_control_test", "postgres"),
                    "bulk_control_test", "");
            var runtime = new BulkExecutionInfrastructure(runtimeDs, new DataSourceTransactionManager(runtimeDs),
                    "test-namespace", "deployment-a", roles);
            var control = new BulkControlPlaneInfrastructure(controlDs,
                    new DataSourceTransactionManager(controlDs), "test-namespace", "deployment-a", "bulk_control_test", runtime);
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(operationalDocument(BulkIdentityCodecs.longs()));
            var lifecycle = new BulkOperationLifecycle(bindings,
                    new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("inventory")), documents,
                    registry(actionDefinition()), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver(), runtime, control, List.of());

            assertThrows(IllegalStateException.class, () -> lifecycle.publish(operation, 0));
            assertEquals("UNCOMPOSED", new JdbcTemplate(admin).queryForObject(
                    "select state from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?",
                    String.class, operation.namespaceId(), operation.confirmationOperationId()));
            assertEquals(0L, new JdbcTemplate(admin).queryForObject(
                    "select generation from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?",
                    Long.class, operation.namespaceId(), operation.confirmationOperationId()));
        }
    }

    @Test
    void publicationRefreshesEveryPublishedOpenApiGroupBeforeCheckingGlobalCollisions() throws Exception {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(operationalDocument(BulkIdentityCodecs.longs()),
                    new ObjectMapper().createObjectNode().putObject("paths"),
                    operationalDocument(BulkIdentityCodecs.longs()), collisionDocument());
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings,
                    List.of("inventory", "other"));
            assertEquals(List.of("inventory", "other"),
                    resolver.refreshPublishedOpenApiGroupsStrict(java.util.Set.of("inventory")));
            assertEquals(2, documents.freshReads(), "both the target and non-target collision domain are refreshed");
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver());
            assertThrows(IllegalStateException.class, compiler::compileAll,
                    "a newly duplicated operationId outside the target group must prevent composition");
        }
    }

    @Test
    void secondNodeCannotRepublishItsStaleOpenApiCacheAfterAnotherNodeSuspends() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start(); var context = context()) {
            var admin = postgres.getPostgresDatabase();
            var operation = new BulkOperationControlIdentity("test-namespace", ACTION_ID);
            BulkExecutionMigrator.migrateWithOperations(admin, java.util.Map.of("test-namespace", "deployment-a"),
                    List.of(operation));
            var roles = controlPlaneTestRoles(admin);
            var runtimeDs = BulkPostgresTestSupport.runtimeDataSource(postgres);
            var nodeARuntime = new BulkExecutionInfrastructure(runtimeDs, new DataSourceTransactionManager(runtimeDs),
                    "test-namespace", "deployment-a", roles);
            var nodeBRuntime = new BulkExecutionInfrastructure(runtimeDs, new DataSourceTransactionManager(runtimeDs),
                    "test-namespace", "deployment-a", roles);
            var nodeAControlDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_control_test", "postgres"),
                    "bulk_control_test", "");
            var nodeBControlDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_control_test", "postgres"),
                    "bulk_control_test", "");
            var nodeAControl = new BulkControlPlaneInfrastructure(nodeAControlDs,
                    new DataSourceTransactionManager(nodeAControlDs), "test-namespace", "deployment-a", "bulk_control_test", nodeARuntime);
            var nodeBControl = new BulkControlPlaneInfrastructure(nodeBControlDs,
                    new DataSourceTransactionManager(nodeBControlDs), "test-namespace", "deployment-a", "bulk_control_test", nodeBRuntime);
            JsonNode cachedDocument = operationalDocument(BulkIdentityCodecs.longs());
            JsonNode refreshedDocument = cachedDocument.deepCopy();
            for (String status : List.of("200", "202")) {
                ((ObjectNode) refreshedDocument.path("paths").path("/api/items/actions/bulk-approve").path("post")
                        .path("responses").path(status).path("content").path("application/json")
                        .path("schema").path("properties").path("accepted")).put("type", "string");
            }
            var nodeADocuments = new TestDocuments(cachedDocument);
            var nodeBDocuments = new TestDocuments(cachedDocument);
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var nodeA = new BulkOperationLifecycle(bindings,
                    new OpenApiCanonicalOperationResolver(nodeADocuments, mvc, bindings, List.of("inventory")), nodeADocuments,
                    registry(actionDefinition()), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver(), nodeARuntime, nodeAControl,
                    List.of(provider("provider.r1", "deployment-a", 200, ACTION_ID, nodeARuntime)));
            var nodeB = new BulkOperationLifecycle(bindings,
                    new OpenApiCanonicalOperationResolver(nodeBDocuments, mvc, bindings, List.of("inventory")), nodeBDocuments,
                    registry(actionDefinition()), new ObjectMapper(),
                    new FilteredSchemaReferenceResolver(), nodeBRuntime, nodeBControl,
                    List.of(provider("provider.r1", "deployment-a", 200, ACTION_ID, nodeBRuntime)));

            var publishedA = nodeA.publish(operation, 0);
            assertEquals(publishedA, nodeB.requireReady(operation), "node B initially has the same cached composition");
            assertEquals(2, nodeA.suspend(operation, publishedA.generation()));
            nodeBDocuments.setRefreshedDocument("inventory", refreshedDocument);
            assertThrows(IllegalStateException.class, () -> nodeB.requireReady(operation));
            var suspendedB = nodeBRuntime.withLifecycleRead(connection -> JdbcBulkOperationControl.lockForAdmission(
                    connection, operation.namespaceId(), ACTION_ID));
            assertEquals("SUSPENDED", suspendedB.state(), "a remote ready generation cannot override a divergent node cache");
            var publishedB = nodeB.publish(operation, suspendedB.generation());

            assertEquals(suspendedB.generation() + 1, publishedB.generation());
            assertTrue(!publishedA.descriptorFingerprint().equals(publishedB.descriptorFingerprint()));
            assertEquals(3, nodeBDocuments.freshReads(), "initial readiness, suspended readiness, and publication use isolated snapshots");
            assertEquals(publishedB, nodeB.requireReady(operation));
        }
    }

    private static TestProvider provider(String revision, String deployment) {
        return provider(revision, deployment, 200);
    }

    private static TestProvider provider(String revision, String deployment, int maxTargets) {
        return provider(revision, deployment, maxTargets, ACTION_ID);
    }

    private static TestProvider providerWithCodec(String deployment, BulkIdentityCodec<?, ?> codec) {
        return new TestProvider("provider.r1", deployment, 200, ACTION_ID, codec);
    }

    private static BulkIdentityCodec<String, Long> alternateLongCodec() {
        var delegate = BulkIdentityCodecs.longs();
        return new BulkIdentityCodec<>() {
            @Override public String readWire(JsonNode node) { return delegate.readWire(node); }
            @Override public Long decode(String wire) { return delegate.decode(wire); }
            @Override public String encode(Long id) { return delegate.encode(id); }
            @Override public io.swagger.v3.oas.models.media.Schema<?> wireSchema() { return delegate.wireSchema(); }
            @Override public JsonNode canonicalWireSchema() { return delegate.canonicalWireSchema(); }
            @Override public String codecId() { return "long-revision-2"; }
        };
    }

    private JsonNode operationalDocument(BulkIdentityCodec<?, ?> codec) {
        var root = document(true).deepCopy();
        var requestSchema = JsonNodeFactory.instance.objectNode();
        requestSchema.put("type", "object");
        var selection = requestSchema.putObject("properties").putObject("selection");
        selection.put("type", "object");
        var targets = selection.putObject("properties").putObject("targets");
        targets.put("type", "array");
        var item = targets.putObject("items");
        item.put("type", "object");
        var identitySchema = codec.canonicalWireSchema().deepCopy();
        item.putObject("properties").set("id", identitySchema);
        ((ObjectNode) root.path("paths").path("/api/items/actions/bulk-approve/evaluation").path("post")
                .path("requestBody").path("content").path("application/json")).set("schema", requestSchema);
        ((ObjectNode) root).putObject("components").putObject("schemas")
                .set("Transport", mediaSchema().path("schema"));
        root.path("paths").forEach(path -> path.forEach(operation ->
                ((ObjectNode) operation).putObject("x-ui").put("responseSchema", "Transport")));
        return root;
    }

    private JsonNode collisionDocument() {
        var document = new ObjectMapper().createObjectNode();
        document.putObject("paths").putObject("/new-collision").putObject("get").put("operationId", ACTION_ID);
        return document;
    }

    private static TestProvider provider(String revision, String deployment, String operationId) {
        return provider("provider.r1", deployment, 200, operationId);
    }

    private static TestProvider provider(String revision, String deployment, int maxTargets, String operationId) {
        return new TestProvider(revision, deployment, maxTargets, operationId, BulkIdentityCodecs.longs());
    }

    private static TestProvider provider(String revision, String deployment, int maxTargets, String operationId,
            BulkExecutionInfrastructure infrastructure) {
        return new TestProvider(revision, deployment, maxTargets, operationId, BulkIdentityCodecs.longs(), infrastructure);
    }

    private static final class TestProvider implements BulkOperationDescriptorProvider {
        private String revision;
        private final String deployment;
        private final int maxTargets;
        private final String operationId;
        private final BulkExecutionInfrastructure infrastructure;
        private final BulkIdentityCodec<?, ?> codec;
        private final AtomicInteger profileReads = new AtomicInteger();
        private final AtomicInteger providerIdReads = new AtomicInteger();
        private final AtomicInteger providerRevisionReads = new AtomicInteger();
        private final AtomicInteger codecReads = new AtomicInteger();
        private final AtomicInteger infrastructureReads = new AtomicInteger();

        private TestProvider(String revision, String deployment, int maxTargets, String operationId,
                BulkIdentityCodec<?, ?> codec) {
            this(revision, deployment, maxTargets, operationId, codec, null);
        }

        private TestProvider(String revision, String deployment, int maxTargets, String operationId,
                BulkIdentityCodec<?, ?> codec, BulkExecutionInfrastructure configuredInfrastructure) {
            this.revision = revision;
            this.deployment = deployment;
            this.maxTargets = maxTargets;
            this.operationId = operationId;
            this.codec = codec;
            if (configuredInfrastructure == null) {
                var dataSource = new JdbcDataSource();
                dataSource.setURL("jdbc:h2:mem:operational-descriptor-" + deployment + ";DB_CLOSE_DELAY=-1");
                this.infrastructure = new BulkExecutionInfrastructure(dataSource,
                        new DataSourceTransactionManager(dataSource), "test-namespace", deployment, BulkPostgresTestSupport.testRoleConfiguration());
            } else this.infrastructure = configuredInfrastructure;
        }

        @Override public String confirmationOperationId() { return operationId; }
        @Override public String providerId() { providerIdReads.incrementAndGet(); return "events.approval"; }
        @Override public String providerRevision() { providerRevisionReads.incrementAndGet(); return revision; }
        @Override public BulkIdentityCodec<?, ?> identityCodec() { codecReads.incrementAndGet(); return codec; }
        @Override public String identitySchemaPointer() {
            return "/properties/selection/properties/targets/items/properties/id";
        }
        @Override public BulkOperationalProfile profile() {
            profileReads.incrementAndGet();
            return new BulkOperationalProfile(EnumSet.of(BulkMode.DOMAIN_COMMAND),
                    EnumSet.of(BulkExecutionMode.SYNC),
                    EnumSet.of(BulkSelectionMode.EXPLICIT), maxTargets, 1024 * 1024,
                    Duration.ofMinutes(5), Duration.ofSeconds(5));
        }
        @Override public BulkExecutionInfrastructure executionInfrastructure() {
            infrastructureReads.incrementAndGet(); return infrastructure;
        }
    }

    @Test
    void structuralSegmentDigestIsStableForTheSameSnapshotAndSensitiveToResponseSchemaChanges() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var mapper = new ObjectMapper();
            var original = document(true);
            var firstDocuments = new TestDocuments(original);
            var firstResolver = new OpenApiCanonicalOperationResolver(firstDocuments, mvc, bindings);
            var firstCompiler = new BulkOperationStructuralCompiler(bindings, firstResolver, firstDocuments,
                    registry(actionDefinition()), mapper, new FilteredSchemaReferenceResolver());
            var firstDescriptor = firstCompiler.compileAll().getFirst();
            String firstDigest = BulkStructuralSegmentDigest.compute(firstDescriptor);
            assertEquals("sha256:bb246583bcfd30c128f113670abe0c07c5f0ca25526ad30d318386b9edafa7b5", firstDigest,
                    "golden produced independently from the published rc146 baseline, structure/3 unchanged");
            assertEquals("praxis.bulk.structure/3", BulkStructuralSegmentDigest.canonicalContent(firstDescriptor)
                    .path("structureVersion").asText());
            assertTrue(firstDigest.matches("sha256:[0-9a-f]{64}"));
            ObjectNode previousFraming = (ObjectNode) BulkStructuralSegmentDigest.canonicalContent(firstDescriptor).deepCopy();
            previousFraming.put("structureVersion", "praxis.bulk.structure/2");
            previousFraming.path("operations").forEach(operation -> {
                ((ObjectNode) operation).remove("filteredRequest");
                ((ObjectNode) operation).remove("filteredResponse");
            });
            assertEquals("sha256:f598700cdb04aa3eda9dbd45c8791263143cbc5e3e1beb3a7849fd743047fadb",
                    BulkCanonicalJson.structuralDescriptorDigest(previousFraming), "the raw/action evidence from /2 is unchanged");

            var repeatedDocuments = new TestDocuments(original);
            var repeatedResolver = new OpenApiCanonicalOperationResolver(repeatedDocuments, mvc, bindings);
            var repeatedCompiler = new BulkOperationStructuralCompiler(bindings, repeatedResolver, repeatedDocuments,
                    registry(actionDefinition()), mapper, new FilteredSchemaReferenceResolver());
            assertEquals(firstDigest, BulkStructuralSegmentDigest.compute(
                    repeatedCompiler.compileAll().getFirst()));

            JsonNode changed = original.deepCopy();
            for (String status : List.of("200", "202")) {
                ((ObjectNode) changed.path("paths").path("/api/items/actions/bulk-approve").path("post")
                        .path("responses").path(status).path("content").path("application/json")
                        .path("schema").path("properties").path("accepted"))
                        .put("description", "Confirmed by the domain handler");
            }
            var changedDocuments = new TestDocuments(changed);
            var changedResolver = new OpenApiCanonicalOperationResolver(changedDocuments, mvc, bindings);
            var changedCompiler = new BulkOperationStructuralCompiler(bindings, changedResolver, changedDocuments,
                    registry(actionDefinition()), mapper, new FilteredSchemaReferenceResolver());
            assertFalse(firstDigest.equals(BulkStructuralSegmentDigest.compute(
                    changedCompiler.compileAll().getFirst())));

            // Object member order is not semantic input to the digest.
            JsonNode canonicalContent = BulkStructuralSegmentDigest.canonicalContent(firstDescriptor);
            assertDigestChanges(canonicalContent, "/action/id", "different-action");
            assertDigestChanges(canonicalContent, "/action/requestSchemaReference/schemaId", "different-action-schema");
            assertDigestChanges(canonicalContent, "/action/execution/selection/maxItems", 51);
            assertDigestChanges(canonicalContent, "/operations/0/reference/path", "/api/items/changed");
            assertDigestChanges(canonicalContent, "/operations/5/requestJavaType", "example.ChangedRequest");
            assertDigestChanges(canonicalContent, "/operations/5/requestSchema/mediaType", "application/problem+json");
            assertDigestChanges(canonicalContent, "/operations/5/requestSchema/specVersion", "OPENAPI_3_1");
            assertDigestChanges(canonicalContent, "/operations/5/requestSchema/schema/type", "string");
            assertDigestChanges(canonicalContent, "/operations/5/responseJavaType", "example.ChangedResponse");
            assertDigestChanges(canonicalContent, "/operations/5/responseSchema/specVersion", "OPENAPI_3_1");
            assertDigestChanges(canonicalContent, "/operations/5/responseSchema/variants/0/status", 204);
            assertDigestChanges(canonicalContent, "/operations/5/responseSchema/variants/0/mediaType", "application/problem+json");
            ObjectNode reversedMembers = mapper.createObjectNode();
            var names = new java.util.ArrayList<String>();
            canonicalContent.fieldNames().forEachRemaining(names::add);
            java.util.Collections.reverse(names);
            names.forEach(name -> reversedMembers.set(name, canonicalContent.get(name)));
            assertEquals(BulkStructuralSegmentDigest.compute(firstDescriptor),
                    BulkCanonicalJson.structuralDescriptorDigest(reversedMembers));
        }
    }

    @Test
    void structuralSegmentAcceptsReaderSchemaDepthAndDecimalsAndCanonicalizesRequiredSets() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            JsonNode original = document(true);
            JsonNode reordered = original.deepCopy();
            for (String path : List.of("/api/items/actions/bulk-approve/evaluation",
                    "/api/items/actions/bulk-approve")) {
                ObjectNode schema = (ObjectNode) original.path("paths").path(path).path("post")
                        .path("requestBody").path("content").path("application/json").path("schema");
                ObjectNode properties = (ObjectNode) schema.path("properties");
                ObjectNode numeric = properties.putObject("ratio");
                numeric.put("type", "number");
                numeric.put("minimum", 0.5d);
                ObjectNode nested = properties.putObject("nested");
                ObjectNode cursor = nested;
                for (int depth = 0; depth < 20; depth++) cursor = cursor.putObject("level" + depth);
                cursor.put("type", "string");
                schema.putArray("required").add("accepted").add("ratio");

                ObjectNode reorderedSchema = (ObjectNode) reordered.path("paths").path(path).path("post")
                        .path("requestBody").path("content").path("application/json").path("schema");
                ObjectNode reorderedProperties = (ObjectNode) reorderedSchema.path("properties");
                ObjectNode reorderedNumeric = reorderedProperties.putObject("ratio");
                reorderedNumeric.put("type", "number");
                reorderedNumeric.put("minimum", 0.5d);
                ObjectNode reorderedNested = reorderedProperties.putObject("nested");
                ObjectNode reorderedCursor = reorderedNested;
                for (int depth = 0; depth < 20; depth++) reorderedCursor = reorderedCursor.putObject("level" + depth);
                reorderedCursor.put("type", "string");
                reorderedSchema.putArray("required").add("ratio").add("accepted");
            }

            String originalDigest = compileDigest(original, mvc, bindings);
            assertEquals(originalDigest, compileDigest(reordered, mvc, bindings));
        }
    }

    private String compileDigest(JsonNode document, RequestMappingHandlerMapping mvc,
            BulkResourceOperationBindings bindings) {
        var documents = new TestDocuments(document);
        var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
        var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                registry(actionDefinition()), new ObjectMapper(), new FilteredSchemaReferenceResolver());
        return BulkStructuralSegmentDigest.compute(compiler.compileAll().getFirst());
    }

    private static void assertDigestChanges(JsonNode content, String pointer, String replacement) {
        assertDigestChanges(content, pointer, new ObjectMapper().getNodeFactory().textNode(replacement));
    }

    private static void assertDigestChanges(JsonNode content, String pointer, int replacement) {
        assertDigestChanges(content, pointer, new ObjectMapper().getNodeFactory().numberNode(replacement));
    }

    private static void assertDigestChanges(JsonNode content, String pointer, JsonNode replacement) {
        JsonNode changed = content.deepCopy();
        int separator = pointer.lastIndexOf('/');
        JsonNode parent = changed.at(pointer.substring(0, separator));
        String property = pointer.substring(separator + 1).replace("~1", "/").replace("~0", "~");
        assertTrue(parent instanceof ObjectNode, "Expected object parent for " + pointer);
        ((ObjectNode) parent).set(property, replacement);
        assertFalse(BulkCanonicalJson.structuralDescriptorDigest(content)
                .equals(BulkCanonicalJson.structuralDescriptorDigest(changed)), pointer);
    }

    @Test
    void rejectsAChangedActionDefinitionInsteadOfComposingAnUnrelatedWorkflowAction() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var wrongAction = new ActionDefinition("another-action", RESOURCE, "/api/items", "inventory",
                    ActionScope.COLLECTION, "Wrong action", "", new CanonicalOperationRef("inventory",
                    ACTION_ID, "/api/items/actions/bulk-approve", "POST"),
                    new CanonicalSchemaRef("request", "request", "/schemas/request"),
                    new CanonicalSchemaRef("response", "response", "/schemas/response"), 0, "",
                    List.of(), List.of(), List.of(), ActionExecutionContract.defaults(ActionScope.COLLECTION));

            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(wrongAction), new ObjectMapper(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
        }
    }

    @Test
    void rejectsMissingStrictResponseSchemaAndDoesNotReturnPartialDescriptor() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(false));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
        }
    }

    @Test
    void rejectsLifecycleRequestBodyPublishedByTheOpenApiCustomizer() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            JsonNode document = document(true);
            ((ObjectNode) document.path("paths").path("/api/items/bulk/executions/{executionId}").path("get"))
                    .putObject("requestBody");
            var documents = new TestDocuments(document);
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
            assertEquals(1, documents.strictReads(), "lifecycle validation must use the captured group snapshot");
        }
    }

    @Test
    void refusesCompositionWhenAnInvalidOrphanDeclarationCoexistsWithAValidBinding() {
        try (var context = context(CompleteBulkController.class, OrphanBulkController.class)) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper(), new FilteredSchemaReferenceResolver());
            var confirmation = mvc.getHandlerMethods().entrySet().stream()
                    .map(java.util.Map.Entry::getValue)
                    .filter(handler -> handler.getMethod().getName().equals("confirm"))
                    .filter(handler -> handler.getBeanType().equals(CompleteBulkController.class))
                    .findFirst().orElseThrow();

            assertTrue(bindings.bulkOperationFor(confirmation).isPresent());
            assertThrows(IllegalStateException.class, compiler::compileAll);
            assertEquals(0, documents.strictReads(), "diagnostics must stop before reading schema documents");
        }
    }

    @Test
    void rejectsSameActionIdentityWhenCatalogSemanticsDifferFromWorkflowAnnotation() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            ActionDefinition canonical = actionDefinition();
            ActionDefinition stale = new ActionDefinition(canonical.id(), canonical.resourceKey(),
                    canonical.resourcePath(), canonical.group(), ActionScope.ITEM, canonical.title(),
                    canonical.description(), canonical.operation(), canonical.requestSchema(), canonical.responseSchema(),
                    canonical.order(), canonical.successMessage(), canonical.requiredAuthorities(),
                    canonical.allowedStates(), canonical.tags(), canonical.execution());
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(stale), new ObjectMapper(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
        }
    }

    @Test
    void rejectsSameActionWhenCanonicalSchemaReferencesDiffer() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            ActionDefinition canonical = actionDefinition();
            ActionDefinition stale = new ActionDefinition(canonical.id(), canonical.resourceKey(),
                    canonical.resourcePath(), canonical.group(), canonical.scope(), canonical.title(),
                    canonical.description(), canonical.operation(),
                    new CanonicalSchemaRef("wrong-request", "request", "/schemas/filtered?wrong=request"),
                    canonical.responseSchema(), canonical.order(), canonical.successMessage(),
                    canonical.requiredAuthorities(), canonical.allowedStates(), canonical.tags(), canonical.execution());
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(stale), new ObjectMapper(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
        }
    }

    @Test
    void rejectsActionSchemaReferenceWithCanonicalUrlButAlteredSchemaId() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var documents = new TestDocuments(document(true));
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings);
            ActionDefinition canonical = actionDefinition();
            ActionDefinition stale = new ActionDefinition(canonical.id(), canonical.resourceKey(),
                    canonical.resourcePath(), canonical.group(), canonical.scope(), canonical.title(),
                    canonical.description(), canonical.operation(),
                    new CanonicalSchemaRef("tampered-schema-id", "request", canonical.requestSchema().url()),
                    canonical.responseSchema(), canonical.order(), canonical.successMessage(),
                    canonical.requiredAuthorities(), canonical.allowedStates(), canonical.tags(), canonical.execution());
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(stale), new ObjectMapper(), new FilteredSchemaReferenceResolver());

            assertThrows(IllegalStateException.class, compiler::compileAll);
        }
    }

    @Test
    void rejectsMatchingButInvalidActionExecutionContract() throws Exception {
        WorkflowAction workflow = InvalidCollectionIfMatch.class
                .getDeclaredMethod("action").getAnnotation(WorkflowAction.class);
        ActionDefinition canonical = actionDefinition();
        ActionExecutionContract invalidExecution = new ActionExecutionContract(
                new ActionInteractionPolicy(workflow.interactionMode(), workflow.riskLevel(),
                        workflow.confirmationRequired(), workflow.reversible()),
                new ActionPreconditionPolicy(workflow.idempotencyKey(), workflow.correlationId(),
                        workflow.resourceVersion(), workflow.resourceVersionTransport(),
                        workflow.resourceVersionField(), workflow.resourceVersionTargetResourceKey(),
                        workflow.resourceVersionTargetIdField()),
                new ActionSelectionPolicy(workflow.selectionIdsField(), workflow.selectionVersionsField(),
                        workflow.maxSelection()),
                new ActionOutcomePolicy(workflow.outcomeMode(), workflow.atomicity()),
                new ActionRefreshPolicy(workflow.refreshItem(), workflow.refreshCollection(),
                        workflow.refreshActions(), workflow.refreshCapabilities(), List.of(workflow.invalidatesResourceKeys())));
        ActionDefinition matchingInvalidCatalog = new ActionDefinition(workflow.id(), canonical.resourceKey(),
                canonical.resourcePath(), canonical.group(), workflow.scope(), workflow.title(), workflow.description(),
                canonical.operation(), canonical.requestSchema(), canonical.responseSchema(), workflow.order(),
                workflow.successMessage(), List.of(workflow.requiredAuthorities()), List.of(workflow.allowedStates()),
                List.of(workflow.tags()), invalidExecution);

        assertFalse(BulkOperationStructuralCompiler.workflowMatches(matchingInvalidCatalog, workflow));
    }

    @Test
    void permitsCanonicalPathTemplateAliasesAcrossPublishedGroupsAndRejectsChangedTargetIdentity() {
        try (var context = context()) {
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            JsonNode other = document(true);
            JsonNode primaryAlias = aliasProposalTemplate(other);
            TestDocuments documents = new TestDocuments(primaryAlias, other);
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("inventory", "other"));
            var compiler = new BulkOperationStructuralCompiler(bindings, resolver, documents,
                    registry(actionDefinition()), new ObjectMapper(), new FilteredSchemaReferenceResolver());

            assertEquals(7, compiler.compileAll().getFirst().operations().size());
            assertEquals(2, documents.strictReads());

            ((ObjectNode) other.path("paths").path("/api/items/bulk/proposals/{proposalId}").path("get"))
                    .put("operationId", "changed.proposal.identity");
            TestDocuments changedTarget = new TestDocuments(primaryAlias, other);
            var changedResolver = new OpenApiCanonicalOperationResolver(changedTarget, mvc, bindings,
                    List.of("inventory", "other"));
            var changedCompiler = new BulkOperationStructuralCompiler(bindings, changedResolver, changedTarget,
                    registry(actionDefinition()), new ObjectMapper(), new FilteredSchemaReferenceResolver());
            assertThrows(IllegalStateException.class, changedCompiler::compileAll);

            JsonNode duplicateElsewhere = aliasProposalTemplate(document(true));
            operation((ObjectNode) duplicateElsewhere.path("paths"), "/api/unrelated", "get",
                    "items.bulk.proposal", false, true);
            TestDocuments duplicateDocument = new TestDocuments(primaryAlias, duplicateElsewhere);
            var duplicateResolver = new OpenApiCanonicalOperationResolver(duplicateDocument, mvc, bindings,
                    List.of("inventory", "other"));
            var duplicateCompiler = new BulkOperationStructuralCompiler(bindings, duplicateResolver, duplicateDocument,
                    registry(actionDefinition()), new ObjectMapper(), new FilteredSchemaReferenceResolver());
            assertThrows(IllegalStateException.class, duplicateCompiler::compileAll);
        }
    }

    @Test
    void missingUpdateProvidersDenyAdmissionAndMalformedUpdatesNeverTouchControlRows() throws Exception {
        for (var controller : List.of(MixedBulkController.class, InvalidMixedBulkController.class, OrphanUpdateController.class)) {
            try (var context = context(controller)) {
                var mvc = context.getBean(RequestMappingHandlerMapping.class);
                var bindings = BulkResourceOperationBindings.from(mvc);
                assertEquals(controller == MixedBulkController.class, bindings.diagnostics().isEmpty());
                assertTrue(bindings.declaresUpdateConfirmation("crud.uniform"));
                var runtime = org.mockito.Mockito.mock(BulkExecutionInfrastructure.class,
                        org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
                var control = org.mockito.Mockito.mock(BulkControlPlaneInfrastructure.class,
                        org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
                org.mockito.Mockito.when(runtime.namespace()).thenReturn("test-namespace");
                org.mockito.Mockito.when(control.namespace()).thenReturn("test-namespace");
                org.mockito.Mockito.when(runtime.deploymentId()).thenReturn("deployment-a");
                org.mockito.Mockito.when(control.deploymentId()).thenReturn("deployment-a");
                var reads = new java.util.ArrayList<String>();
                var transitions = new java.util.ArrayList<String>();
                // Both IDs have a row in this JDBC fixture. Observe the actual SQL-bound ID, not a
                // mocked lifecycle answer, so any UPDATE read or transition fails the final oracle.
                var readConnection = controlConnection(reads, false);
                var transitionConnection = controlConnection(transitions, true);
                org.mockito.Mockito.doAnswer(call -> {
                    org.springframework.jdbc.core.ConnectionCallback<?> work = call.getArgument(0);
                    return work.doInConnection(readConnection);
                }).when(runtime).withLifecycleRead(org.mockito.ArgumentMatchers.any());
                org.mockito.Mockito.doAnswer(call -> {
                    org.springframework.jdbc.core.ConnectionCallback<?> work = call.getArgument(0);
                    return work.doInConnection(transitionConnection);
                }).when(control).withConnection(org.mockito.ArgumentMatchers.any());
                var docs = new TestDocuments(projectedDocument());
                var lifecycle = new BulkOperationLifecycle(bindings, new OpenApiCanonicalOperationResolver(docs, mvc, bindings),
                        docs, registry(actionDefinition()), new ObjectMapper(), new FilteredSchemaReferenceResolver(), runtime, control, List.of());
                var update = new BulkOperationControlIdentity("test-namespace", "crud.uniform");
                org.mockito.Mockito.clearInvocations(runtime, control);
                assertThrows(IllegalStateException.class, () -> lifecycle.publish(update, 3));
                if (controller != MixedBulkController.class)
                    assertThrows(IllegalStateException.class, () -> lifecycle.suspend(update, 3));
                assertThrows(IllegalStateException.class, () -> lifecycle.requireReady(update));
                org.mockito.Mockito.verify(runtime, org.mockito.Mockito.never()).withLifecycleRead(org.mockito.ArgumentMatchers.any());
                org.mockito.Mockito.verify(control, org.mockito.Mockito.never()).withConnection(org.mockito.ArgumentMatchers.any());
                assertTrue(reads.isEmpty());
                assertTrue(transitions.isEmpty());
                if (controller == MixedBulkController.class) {
                    docs.clearCaches();
                    assertEquals(java.util.Set.of(ACTION_ID, "crud.uniform"), java.util.Set.copyOf(reads));
                    assertEquals(java.util.Set.of(ACTION_ID, "crud.uniform"), java.util.Set.copyOf(transitions));
                    assertEquals(1, docs.cacheClears());
                }
            }
        }
    }

    private java.sql.Connection controlConnection(List<String> operations, boolean transition) throws Exception {
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        org.mockito.Mockito.when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            var statement = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
            org.mockito.Mockito.doAnswer(binding -> {
                operations.add(binding.getArgument(1));
                return null;
            }).when(statement).setString(org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyString());
            org.mockito.Mockito.when(statement.executeQuery()).thenAnswer(query -> {
                var rows = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                org.mockito.Mockito.when(rows.next()).thenReturn(true, false);
                org.mockito.Mockito.when(rows.getString(1)).thenReturn("READY");
                org.mockito.Mockito.when(rows.getLong(2)).thenReturn(transition ? 4L : 3L);
                org.mockito.Mockito.when(rows.getBoolean(1)).thenReturn(true);
                return rows;
            });
            return statement;
        });
        return connection;
    }

    @Test
    void mixedResourceKeepsCommandFingerprintAndReadinessWhileUpdatesRemainUncomposed() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
                var context = context(MixedBulkController.class)) {
            var identity = new BulkOperationControlIdentity("test-namespace", ACTION_ID);
            var updateIdentity = new BulkOperationControlIdentity("test-namespace", "crud.uniform");
            var admin = postgres.getPostgresDatabase();
            BulkExecutionMigrator.migrateWithOperations(admin, java.util.Map.of("test-namespace", "deployment-a"), List.of(identity, updateIdentity));
            var roles = controlPlaneTestRoles(admin);
            var runtimeDs = BulkPostgresTestSupport.runtimeDataSource(postgres);
            var controlDs = new DriverManagerDataSource(postgres.getJdbcUrl("bulk_control_test", "postgres"), "bulk_control_test", "");
            var runtime = new BulkExecutionInfrastructure(runtimeDs, new DataSourceTransactionManager(runtimeDs), "test-namespace", "deployment-a", roles);
            var control = new BulkControlPlaneInfrastructure(controlDs, new DataSourceTransactionManager(controlDs), "test-namespace", "deployment-a", "bulk_control_test", runtime);
            var mapper = BulkCrudStructuralCompilerTest.mapper();
            var original = projectedDocument();
            String originalDigest;
            try (var commandOnly = context()) {
                var mvc = commandOnly.getBean(RequestMappingHandlerMapping.class);
                var binding = BulkResourceOperationBindings.from(mvc);
                var docs = new TestDocuments(original);
                originalDigest = BulkStructuralSegmentDigest.compute(new BulkOperationStructuralCompiler(binding,
                        new OpenApiCanonicalOperationResolver(docs, mvc, binding), docs, registry(actionDefinition()), mapper,
                        new FilteredSchemaReferenceResolver()).compileAll().getFirst());
            }
            ObjectNode mixed = original.deepCopy();
            var crud = BulkCrudStructuralCompilerTest.document();
            var displayName = BulkCrudStructuralCompilerTest.property(crud, "display_name");
            displayName.remove("nullable"); displayName.putArray("type").add("string").add("null");
            crud.path("paths").fields().forEachRemaining(entry -> {
                if (entry.getKey().contains("/uniform") || entry.getKey().endsWith("/{id}"))
                    ((ObjectNode) mixed.path("paths")).set(entry.getKey().replace("/crud-items", "/api/items"), entry.getValue());
            });
            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var docs = new TestDocuments(mixed);
            var resolver = new OpenApiCanonicalOperationResolver(docs, mvc, bindings);
            var structures = new BulkOperationStructuralCompiler(bindings, resolver, docs, registry(actionDefinition()), mapper,
                    new FilteredSchemaReferenceResolver()).compileAll();
            assertEquals(2, structures.size());
            assertEquals(originalDigest, BulkStructuralSegmentDigest.compute(structures.stream()
                    .filter(value -> value.mode() == BulkMode.DOMAIN_COMMAND).findFirst().orElseThrow()));
            var provider = provider("provider.r1", "deployment-a", 200, ACTION_ID, runtime);
            var lifecycle = new BulkOperationLifecycle(bindings, resolver, docs, registry(actionDefinition()), mapper,
                    new FilteredSchemaReferenceResolver(), runtime, control, List.of(provider));
            var ready = lifecycle.publish(identity, 0);
            assertEquals(ready, lifecycle.requireReady(identity));
            assertEquals(java.util.Set.of(ACTION_ID), lifecycle.projectReadyActions(List.of(actionDefinition())).keySet());
            assertThrows(IllegalStateException.class, () -> lifecycle.publish(updateIdentity, 0));
            assertThrows(IllegalStateException.class, () -> lifecycle.requireReady(updateIdentity));
            var sql = new JdbcTemplate(admin);
            assertEquals("UNCOMPOSED", sql.queryForObject("select state from praxis_bulk.praxis_bulk_operation_control where namespace_id = ? and operation_id = ?", String.class,
                    "test-namespace", "crud.uniform"));
            docs.clearCaches();
            assertEquals("SUSPENDED", sql.queryForObject("select state from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?", String.class,
                    "test-namespace", ACTION_ID));
            assertEquals("SUSPENDED", sql.queryForObject("select state from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?", String.class,
                    "test-namespace", "crud.uniform"));
            assertEquals(1L, sql.queryForObject("select generation from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?", Long.class,
                    "test-namespace", "crud.uniform"));
            var updateProvider = provider("provider.r1", "deployment-a", 200, "crud.uniform", runtime);
            var unsupportedDocs = new TestDocuments(mixed);
            var unsupported = new BulkOperationLifecycle(bindings,
                    new OpenApiCanonicalOperationResolver(unsupportedDocs, mvc, bindings), unsupportedDocs, registry(actionDefinition()), mapper,
                    new FilteredSchemaReferenceResolver(), runtime, control, List.of(provider, updateProvider));
            var unsupportedProvider = assertThrows(IllegalArgumentException.class, () -> unsupported.requireReady(identity));
            var updateStructure = structures.stream().filter(value -> value.mode() == BulkMode.UNIFORM_UPDATE).findFirst().orElseThrow();
            assertTrue(updateStructure.operation(BulkOperationStructuralDescriptor.Role.EVALUATION)
                    .requestSchema().orElseThrow().schema()
                    .at("/properties/selection/properties/targets/items/properties/id").isMissingNode(),
                    "the structural-only S1 wrapper has no operational target identity");
            assertEquals("Identity codec wire schema differs from the canonical evaluation request", unsupportedProvider.getMessage());
        }
    }

    @ApiResource(value = "/api/items", resourceKey = RESOURCE)
    @BulkResourceOperations(proposalOperationId = "items.bulk.proposal", proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution", executionResultsOperationId = "items.bulk.execution-results", cancelOperationId = "items.bulk.cancel",
            updateSourceOperationId = "crud.update", protectedUpdateFields = {"identity", "revision"})
    static class MixedBulkController extends CompleteBulkController {
        @BulkResourceOperation(BulkResourceOperation.Role.UPDATE_SOURCE) @org.springframework.web.bind.annotation.PutMapping("/{id}")
        public BulkCrudStructuralCompilerTest.Update update(@RequestBody BulkCrudStructuralCompilerTest.Update body) { return body; }
        @Operation(operationId = "crud.uniform.evaluation") @PostMapping("/bulk/uniform/evaluation")
        public BulkCrudStructuralCompilerTest.Confirmation evaluateUpdate(@RequestBody BulkCrudStructuralCompilerTest.Evaluation body) { return null; }
        @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "crud.uniform.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.uniform") @PostMapping("/bulk/uniform")
        public BulkCrudStructuralCompilerTest.Confirmation confirmUpdate(@RequestBody BulkCrudStructuralCompilerTest.Confirmation body) { return body; }
    }

    @BulkResourceOperations(proposalOperationId = "items.bulk.proposal", proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution", executionResultsOperationId = "items.bulk.execution-results", cancelOperationId = "items.bulk.cancel")
    static class InvalidMixedBulkController extends MixedBulkController { }

    @ApiResource(value = "/orphan-update", resourceKey = "orphan.update")
    static class OrphanUpdateController {
        @BulkOperation(mode = BulkMode.UNIFORM_UPDATE, evaluationOperationId = "crud.uniform.evaluation", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "crud.uniform") @PostMapping("/bulk/uniform")
        public BulkCrudStructuralCompilerTest.Confirmation confirmUpdate(@RequestBody BulkCrudStructuralCompilerTest.Confirmation body) { return body; }
    }

    private AnnotationConfigWebApplicationContext context(Class<?>... controllers) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.register(controllers.length == 0 ? new Class<?>[] {CompleteBulkController.class} : controllers);
        context.refresh();
        return context;
    }

    private ActionDefinitionRegistry registry(ActionDefinition action) {
        return new ActionDefinitionRegistry() {
            @Override public List<ActionDefinition> findByResourceKey(String resourceKey) {
                return RESOURCE.equals(resourceKey) ? List.of(action) : List.of();
            }
            @Override public List<ActionDefinition> findByGroup(String group) { return List.of(action); }
        };
    }

    private ActionDefinition actionDefinition() {
        return actionDefinition("id");
    }

    private ActionDefinition actionDefinition(String idField) {
        return actionDefinition(idField, "inventory");
    }

    private ActionDefinition actionDefinition(String idField, String actionGroup) {
        var schemaReferences = new FilteredSchemaReferenceResolver();
        CanonicalOperationRef operation = new CanonicalOperationRef("inventory",
                ACTION_ID, "/api/items/actions/bulk-approve", "POST");
        return new ActionDefinition("approveSelectedItems", RESOURCE, "/api/items", actionGroup,
                ActionScope.COLLECTION, "Aprovar selecionados", "", operation,
                schemaReferences.resolve(operation.path(), operation.method(), "request",
                        false, null, null, idField, false),
                schemaReferences.resolve(operation.path(), operation.method(), "response",
                        false, null, null, idField, false), 10, "Aprovados",
                List.of("BULK_APPROVE"), List.of("READY"), List.of("workflow"),
                new ActionExecutionContract(
                        new ActionInteractionPolicy(null, null, false, false),
                        new ActionPreconditionPolicy(null, null, null, ActionResourceVersionTransport.NONE),
                        new ActionSelectionPolicy(null, null, null),
                        new ActionOutcomePolicy(ActionOutcomeMode.SINGLE, ActionCollectionAtomicity.PER_ITEM),
                        new ActionRefreshPolicy(false, true, true, true, List.of())));
    }

    private JsonNode document(boolean includeEveryResponse) {
        var root = new ObjectMapper().createObjectNode();
        root.put("openapi", "3.1.0");
        var paths = root.putObject("paths");
        operation(paths, "/api/items/bulk/proposals/{proposalId}", "get", "items.bulk.proposal", false, true);
        operation(paths, "/api/items/bulk/proposals/{proposalId}/results", "get", "items.bulk.proposal-results", false, true);
        operation(paths, "/api/items/bulk/executions/{executionId}", "get", "items.bulk.execution", false, true);
        operation(paths, "/api/items/bulk/executions/{executionId}/results", "get", "items.bulk.execution-results", false, true);
        operation(paths, "/api/items/bulk/executions/{executionId}/cancel", "post", "items.bulk.cancel", false, true);
        operation(paths, "/api/items/actions/bulk-approve/evaluation", "post", EVALUATION_ID, true, true);
        operation(paths, "/api/items/actions/bulk-approve", "post", ACTION_ID, true, includeEveryResponse);
        return root;
    }

    private JsonNode aliasProposalTemplate(JsonNode source) {
        ObjectNode root = (ObjectNode) source.deepCopy();
        ObjectNode paths = (ObjectNode) root.path("paths");
        JsonNode proposal = paths.remove("/api/items/bulk/proposals/{proposalId}");
        paths.set("/api/items/bulk/proposals/{id}", proposal);
        JsonNode results = paths.remove("/api/items/bulk/proposals/{proposalId}/results");
        paths.set("/api/items/bulk/proposals/{id}/results", results);
        return root;
    }

    private void operation(com.fasterxml.jackson.databind.node.ObjectNode paths, String path, String method,
            String operationId, boolean withRequestBody, boolean withResponse) {
        var operation = paths.putObject(path).putObject(method);
        operation.put("operationId", operationId);
        if (withRequestBody) {
            var content = operation.putObject("requestBody").putObject("content");
            content.set("application/json", mediaSchema());
        }
        var responses = operation.putObject("responses");
        if (withResponse) {
            var success = responses.putObject("200");
            success.putObject("content").set("application/json", mediaSchema());
            if (ACTION_ID.equals(operationId)) {
                var accepted = responses.putObject("202");
                accepted.putObject("content").set("application/json", mediaSchema());
            }
        }
    }

    private JsonNode mediaSchema() {
        var media = new ObjectMapper().createObjectNode();
        media.putObject("schema").put("type", "object").putObject("properties")
                .putObject("accepted").put("type", "boolean");
        return media;
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfiguration { }

    @ApiResource(value = "/api/items", resourceKey = RESOURCE)
    @BulkResourceOperations(proposalOperationId = "items.bulk.proposal",
            proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution",
            executionResultsOperationId = "items.bulk.execution-results",
            cancelOperationId = "items.bulk.cancel")
    static class CompleteBulkController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/bulk/proposals/{proposalId}") public String proposal() { return ""; }
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/bulk/proposals/{proposalId}/results") public String proposalResults() { return ""; }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/bulk/executions/{executionId}") public String execution() { return ""; }
        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/bulk/executions/{executionId}/results") public String executionResults() { return ""; }
        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @PostMapping("/bulk/executions/{executionId}/cancel") public String cancel() { return ""; }

        @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = EVALUATION_ID,
                atomicity = ActionCollectionAtomicity.PER_ITEM)
        @WorkflowAction(id = "approveSelectedItems", title = "Aprovar selecionados",
                scope = ActionScope.COLLECTION, order = 10, successMessage = "Aprovados",
                atomicity = ActionCollectionAtomicity.PER_ITEM, requiredAuthorities = {"BULK_APPROVE"},
                allowedStates = {"READY"}, tags = {"workflow"})
        @Operation(operationId = ACTION_ID)
        @PostMapping("/actions/bulk-approve")
        public String confirm(@RequestBody ConfirmationRequest request) { return ""; }

        @Operation(operationId = EVALUATION_ID)
        @PostMapping("/actions/bulk-approve/evaluation")
        public String evaluate(@RequestBody EvaluationRequest request) { return ""; }
    }

    @ApiResource(value = "/api/orphan", resourceKey = "inventory.orphan")
    static class OrphanBulkController {
        @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "orphan.evaluation",
                atomicity = ActionCollectionAtomicity.PER_ITEM)
        @WorkflowAction(id = "orphan", title = "Orphan", scope = ActionScope.COLLECTION,
                atomicity = ActionCollectionAtomicity.PER_ITEM)
        @Operation(operationId = "orphan.confirmation")
        @PostMapping("/actions/orphan")
        public String confirm(@RequestBody ConfirmationRequest request) { return ""; }
    }

    static class InvalidCollectionIfMatch {
        @WorkflowAction(id = "approveSelectedItems", title = "Aprovar selecionados",
                description = "", scope = ActionScope.COLLECTION, order = 10, successMessage = "Aprovados",
                requiredAuthorities = {"BULK_APPROVE"}, allowedStates = {"READY"}, tags = {"workflow"},
                atomicity = ActionCollectionAtomicity.PER_ITEM, resourceVersion = ActionRequirement.REQUIRED,
                resourceVersionTransport = ActionResourceVersionTransport.IF_MATCH,
                refreshItem = false, refreshCollection = true, refreshActions = true, refreshCapabilities = true)
        void action() { }
    }

    record ConfirmationRequest(String reason) { }
    record EvaluationRequest(List<String> ids) { }

    private static final class TestDocuments implements OpenApiDocumentService {
        private final java.util.Map<String, JsonNode> documents;
        private final java.util.Map<String, JsonNode> refreshDocuments;
        private final AtomicInteger strictReads = new AtomicInteger();
        private final AtomicInteger freshReads = new AtomicInteger();
        private final AtomicInteger cacheClears = new AtomicInteger();
        private volatile Runnable invalidationGuard;
        private Runnable onFreshSnapshot;
        private long documentEpoch;
        private final ThreadLocal<java.util.Map<String, JsonNode>> freshSnapshot = new ThreadLocal<>();
        private TestDocuments(JsonNode document) { this(document, null, document, null); }
        private TestDocuments(JsonNode primary, JsonNode other) {
            this(primary, other, primary, other);
        }
        private TestDocuments(JsonNode primary, JsonNode other, JsonNode refreshed) {
            this(primary, other, refreshed, other);
        }
        private TestDocuments(JsonNode primary, JsonNode other, JsonNode refreshed, JsonNode refreshedOther) {
            this.documents = new java.util.HashMap<>();
            this.documents.put("inventory", primary.deepCopy());
            if (other != null) this.documents.put("other", other.deepCopy());
            this.refreshDocuments = new java.util.HashMap<>();
            this.refreshDocuments.put("inventory", refreshed.deepCopy());
            if (refreshedOther != null) this.refreshDocuments.put("other", refreshedOther.deepCopy());
        }
        @Override public String resolveGroupFromPath(String path) { return "inventory"; }
        @Override public JsonNode getDocumentForGroup(String groupName) { return getDocumentForGroupStrict(groupName); }
        @Override public JsonNode getDocumentForGroupStrict(String groupName) {
            var snapshot = freshSnapshot.get();
            if (snapshot != null) {
                JsonNode fresh = snapshot.get(groupName);
                if (fresh == null) throw new IllegalStateException("unexpected fresh group " + groupName);
                return fresh.deepCopy();
            }
            strictReads.incrementAndGet();
            JsonNode document = documents.get(groupName);
            if (document == null) throw new IllegalStateException("unexpected group " + groupName);
            return document.deepCopy();
        }
        @Override public JsonNode refreshDocumentForGroupStrict(String groupName) {
            var snapshot = freshSnapshot.get();
            if (snapshot != null) {
                JsonNode document = snapshot.get(groupName);
                if (document == null) throw new IllegalStateException("unexpected fresh group " + groupName);
                return document.deepCopy();
            }
            runGuard();
            freshReads.incrementAndGet();
            JsonNode document = refreshDocuments.get(groupName);
            if (document == null) throw new IllegalStateException("unexpected group " + groupName);
            documents.put(groupName, document.deepCopy());
            return document.deepCopy();
        }
        int strictReads() { return strictReads.get(); }
        int freshReads() { return freshReads.get(); }
        void setRefreshedDocument(String group, JsonNode document) { refreshDocuments.put(group, document.deepCopy()); }
        void setPublicDocument(String group, JsonNode document) { documents.put(group, document.deepCopy()); }
        int cacheClears() { return cacheClears.get(); }
        @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
            throw new UnsupportedOperationException("schema hash is not part of structural composition");
        }
        @Override public void installBulkLifecycleInvalidationGuard(Runnable guard) { invalidationGuard = guard; }
        @Override public boolean supportsFreshBulkLifecycleComposition() { return true; }
        @Override public boolean supportsFreshBulkLifecyclePublicCacheCoherence() { return true; }
        @Override public BulkLifecycleDocumentFence captureBulkLifecycleDocumentFence() {
            if (freshSnapshot.get() == null) throw new IllegalStateException("no captured test document snapshot");
            long capturedEpoch = documentEpoch;
            Thread owner = Thread.currentThread();
            return new BulkLifecycleDocumentFence() {
                private boolean closed;
                private void validate() {
                    if (closed || Thread.currentThread() != owner || documentEpoch != capturedEpoch)
                        throw new IllegalStateException("test document capture is no longer valid");
                }
                @Override public <T> T read(Supplier<T> verification) {
                    validate();
                    T result = verification.get();
                    validate();
                    return result;
                }
                @Override public void close() { closed = true; }
            };
        }
        @Override public <T> T withBulkLifecycleCompositionLock(Supplier<T> action) { return action.get(); }
        @Override public <T> T withFreshBulkLifecycleDocuments(java.util.Set<String> groups, Supplier<T> action) {
            var snapshot = new java.util.HashMap<String, JsonNode>();
            for (String group : groups) {
                JsonNode document = refreshDocuments.get(group);
                if (document == null) throw new IllegalStateException("unexpected fresh group " + group);
                snapshot.put(group, document.deepCopy());
                freshReads.incrementAndGet();
            }
            for (String group : groups) {
                JsonNode publiclyServed = documents.get(group);
                if (publiclyServed == null || !publiclyServed.equals(snapshot.get(group))) {
                    clearCaches();
                    throw new IllegalStateException("public document cache differs from the fresh lifecycle document");
                }
            }
            freshSnapshot.set(snapshot);
            Runnable hook = onFreshSnapshot;
            onFreshSnapshot = null;
            if (hook != null) hook.run();
            try { return action.get(); }
            finally { freshSnapshot.remove(); }
        }
        @Override public void clearCaches() {
            runGuard();
            refreshDocuments.forEach((group, document) -> documents.put(group, document.deepCopy()));
            cacheClears.incrementAndGet();
        }
        private void runGuard() {
            documentEpoch = Math.incrementExact(documentEpoch);
            if (invalidationGuard != null) invalidationGuard.run();
        }
    }
}
