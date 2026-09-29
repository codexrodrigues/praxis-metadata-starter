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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

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
                    registry(actionDefinition("item+id")), new ObjectMapper().getTypeFactory(),
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
                    registry(actionDefinition("id", "inventory-business")), new ObjectMapper().getTypeFactory(),
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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(),
                    new FilteredSchemaReferenceResolver()).compileAll().getFirst();
            var first = BulkOperationalDescriptorComposer.compose(structural, provider("provider.r1", "deployment-a"));
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
            assertEquals("sha256:de8b98f775e78fca9a187ef76900df02688605fc122a92b3bc285f323d646074",
                    first.descriptorFingerprint(), "operational framing is a versioned digest contract");
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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(),
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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(),
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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(),
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
                    new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver(), runtime, control,
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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(),
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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(),
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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(),
                    new FilteredSchemaReferenceResolver(), nodeARuntime, nodeAControl,
                    List.of(provider("provider.r1", "deployment-a", 200, ACTION_ID, nodeARuntime)));
            var nodeB = new BulkOperationLifecycle(bindings,
                    new OpenApiCanonicalOperationResolver(nodeBDocuments, mvc, bindings, List.of("inventory")), nodeBDocuments,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(),
                    new FilteredSchemaReferenceResolver(), nodeBRuntime, nodeBControl,
                    List.of(provider("provider.r1", "deployment-a", 200, ACTION_ID, nodeBRuntime)));

            var publishedA = nodeA.publish(operation, 0);
            assertEquals(publishedA, nodeB.requireReady(operation), "node B initially has the same cached composition");
            assertEquals(2, nodeA.suspend(operation, publishedA.generation()));
            nodeBDocuments.setRefreshedDocument("inventory", refreshedDocument);
            assertThrows(IllegalStateException.class, () -> nodeB.requireReady(operation));
            var publishedB = nodeB.publish(operation, 2);

            assertEquals(3, publishedB.generation());
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
        private final String revision;
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
                    registry(actionDefinition()), mapper.getTypeFactory(), new FilteredSchemaReferenceResolver());
            var firstDescriptor = firstCompiler.compileAll().getFirst();
            String firstDigest = BulkStructuralSegmentDigest.compute(firstDescriptor);
            assertEquals("sha256:f598700cdb04aa3eda9dbd45c8791263143cbc5e3e1beb3a7849fd743047fadb",
                    firstDigest);

            var repeatedDocuments = new TestDocuments(original);
            var repeatedResolver = new OpenApiCanonicalOperationResolver(repeatedDocuments, mvc, bindings);
            var repeatedCompiler = new BulkOperationStructuralCompiler(bindings, repeatedResolver, repeatedDocuments,
                    registry(actionDefinition()), mapper.getTypeFactory(), new FilteredSchemaReferenceResolver());
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
                    registry(actionDefinition()), mapper.getTypeFactory(), new FilteredSchemaReferenceResolver());
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
                registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());
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
                    registry(wrongAction), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());
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
                    registry(stale), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

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
                    registry(stale), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

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
                    registry(stale), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

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
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());

            assertEquals(7, compiler.compileAll().getFirst().operations().size());
            assertEquals(2, documents.strictReads());

            ((ObjectNode) other.path("paths").path("/api/items/bulk/proposals/{proposalId}").path("get"))
                    .put("operationId", "changed.proposal.identity");
            TestDocuments changedTarget = new TestDocuments(primaryAlias, other);
            var changedResolver = new OpenApiCanonicalOperationResolver(changedTarget, mvc, bindings,
                    List.of("inventory", "other"));
            var changedCompiler = new BulkOperationStructuralCompiler(bindings, changedResolver, changedTarget,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());
            assertThrows(IllegalStateException.class, changedCompiler::compileAll);

            JsonNode duplicateElsewhere = aliasProposalTemplate(document(true));
            operation((ObjectNode) duplicateElsewhere.path("paths"), "/api/unrelated", "get",
                    "items.bulk.proposal", false, true);
            TestDocuments duplicateDocument = new TestDocuments(primaryAlias, duplicateElsewhere);
            var duplicateResolver = new OpenApiCanonicalOperationResolver(duplicateDocument, mvc, bindings,
                    List.of("inventory", "other"));
            var duplicateCompiler = new BulkOperationStructuralCompiler(bindings, duplicateResolver, duplicateDocument,
                    registry(actionDefinition()), new ObjectMapper().getTypeFactory(), new FilteredSchemaReferenceResolver());
            assertThrows(IllegalStateException.class, duplicateCompiler::compileAll);
        }
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
        int cacheClears() { return cacheClears.get(); }
        @Override public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
            throw new UnsupportedOperationException("schema hash is not part of structural composition");
        }
        @Override public void installBulkLifecycleInvalidationGuard(Runnable guard) { invalidationGuard = guard; }
        @Override public boolean supportsFreshBulkLifecycleComposition() { return true; }
        @Override public <T> T withBulkLifecycleCompositionLock(Supplier<T> action) { return action.get(); }
        @Override public <T> T withFreshBulkLifecycleDocuments(java.util.Set<String> groups, Supplier<T> action) {
            var snapshot = new java.util.HashMap<String, JsonNode>();
            for (String group : groups) {
                JsonNode document = refreshDocuments.get(group);
                if (document == null) throw new IllegalStateException("unexpected fresh group " + group);
                snapshot.put(group, document.deepCopy());
                freshReads.incrementAndGet();
            }
            freshSnapshot.set(snapshot);
            try { return action.get(); }
            finally { freshSnapshot.remove(); }
        }
        @Override public void clearCaches() { runGuard(); cacheClears.incrementAndGet(); }
        private void runGuard() { if (invalidationGuard != null) invalidationGuard.run(); }
    }
}
