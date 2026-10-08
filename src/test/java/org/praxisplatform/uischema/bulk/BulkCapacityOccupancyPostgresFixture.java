package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Arrays;
import java.util.UUID;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.postgresql.util.PSQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authenticated installation plus protected ASYNC storage conformance. The dedicated control
 * tuple is trusted test setup; it does not prove public composition, capture or ASYNC publication.
 * All proposal, enqueue, claim and cancellation work uses the ordinary runtime login.
 */
final class BulkCapacityOccupancyPostgresFixture implements AutoCloseable {
    static final String DEPLOYMENT = "deployment-occupancy-conformance";
    static final String NAMESPACE = "tenant:prod:occupancy-conformance";
    static final String TENANT = "occupancy-tenant";
    static final String ENVIRONMENT = "prod";
    static final String REVISION = "occupancy-async-conformance-r1";
    static final String OPERATION = "occupancy-async-conformance";
    static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext(NAMESPACE,
            "occupancy-subject", "occupancy-fixture", new CanonicalOperationRef("conformance", OPERATION,
            "/protected/occupancy-conformance", "PATCH"), REVISION, ActionCollectionAtomicity.PER_ITEM);
    static final String SYNC_OPERATION = "occupancy-sync-conformance";
    static final String SYNC_REVISION = "occupancy-sync-conformance-r1";
    static final BulkFingerprintContext SYNC_CONTEXT = new BulkFingerprintContext(NAMESPACE,
            "occupancy-sync-subject", "occupancy-sync-fixture", new CanonicalOperationRef("conformance",
            SYNC_OPERATION, "/protected/occupancy-sync-conformance", "PATCH"), SYNC_REVISION,
            ActionCollectionAtomicity.PER_ITEM);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final EmbeddedPostgres postgres;
    private final SharedScope scope;
    private final boolean ownsScope;
    private final String localDatabase;
    private final Map<String, String> namespaceDeployments;
    final String tenant;
    final BulkFingerprintContext context;
    final BulkFingerprintContext syncContext;
    private boolean closed;
    private final String caseId;
    private final List<Map<String, Object>> observations = new CopyOnWriteArrayList<>();
    private final List<PhysicalUnit> physicalUnits = new CopyOnWriteArrayList<>();
    private final List<JsonNode> physicalCertificates = new CopyOnWriteArrayList<>();
    private final List<JsonNode> sqlDiagnostics = new CopyOnWriteArrayList<>();
    private final List<JsonNode> claimCertificates = new CopyOnWriteArrayList<>();
    private final List<JsonNode> retentionCertificates = new CopyOnWriteArrayList<>();
    private final List<JsonNode> catalogCertificates = new CopyOnWriteArrayList<>();
    private final List<JsonNode> artifactCertificates = new CopyOnWriteArrayList<>();
    private volatile boolean barriersUsed;
    private boolean assertionsComplete;
    final DataSource ownerSource;
    final DataSource runtimeSource;
    final JdbcTemplate observer;
    final JdbcTemplate runtimeSql;
    final BulkExecutionInfrastructure runtime;
    final JdbcBulkCapacityInstallation.ExpectedBinding expected;
    final JdbcBulkCapacityInstallation installation;
    final JdbcBulkDurableExecution kernel;
    final BulkOperationControlExpectation control;
    final JdbcBulkCapacityIssuer issuer;
    final JdbcBulkCapacityIssuer.CapacityReader reader;
    final BulkCapacityAuthorityInfrastructure provisioner;

    /** One genuine authority shared by local bindings; the scope owns only test resources. */
    static final class SharedScope implements AutoCloseable {
        final EmbeddedPostgres postgres;
        final BulkCapacityAuthorityMigrator.Identity identity;
        final JdbcTemplate authorityObserver;
        final BulkCapacityAuthorityInfrastructure provisioner;
        final JdbcBulkCapacityIssuer.CapacityReader reader;
        final JdbcBulkCapacityIssuer issuer;
        private final Map<String, String> credentials;
        private final List<BulkCapacityOccupancyPostgresFixture> locals = new java.util.ArrayList<>();
        private boolean closed;

        SharedScope() throws Exception {
            this(EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false));
        }

        /** Test-only startup configuration; the default scope retains its original builder policy. */
        SharedScope(EmbeddedPostgres.Builder builder) throws Exception {
            this(builder, Map.of(), null);
        }

        /** Optional authenticated test startup; no secret is published in proof certificates. */
        SharedScope(EmbeddedPostgres.Builder builder, Map<String, String> credentials,
                org.springframework.jdbc.core.ConnectionCallback<Void> bootstrap) throws Exception {
            this.credentials = Map.copyOf(credentials);
            if (!this.credentials.isEmpty()) {
                if (!this.credentials.keySet().equals(Set.of("postgres", "occupancy_provisioner",
                        "occupancy_allocator", "occupancy_reader", "bulk_runtime_test", "durable_runtime"))
                        || this.credentials.values().stream().anyMatch(value -> value == null || value.isBlank())
                        || new java.util.HashSet<>(this.credentials.values()).size() != this.credentials.size()
                        || bootstrap == null)
                    throw new IllegalArgumentException("Authenticated test startup requires distinct role credentials and bootstrap");
            }
            postgres = builder.start();
            try {
                if (bootstrap != null) {
                    // Only the embedded healthcheck has run. Close trust before any authority,
                    // migration, domain or runtime setup, using this preopened administrative loan.
                    try (var connection = postgres.getPostgresDatabase().getConnection()) {
                        bootstrap.doInConnection(connection);
                    }
                }
                var cluster = new JdbcTemplate(this.credentials.isEmpty()
                        ? postgres.getPostgresDatabase() : source("postgres", "postgres"));
                cluster.execute("create role occupancy_provisioner login");
                cluster.execute("create role occupancy_allocator login");
                cluster.execute("create role occupancy_reader login");
                if (!this.credentials.isEmpty()) {
                    cluster.execute("create role bulk_runtime_test login");
                    cluster.execute("create role durable_runtime login");
                    try (var connection = source("postgres", "postgres").getConnection()) {
                        for (String role : List.of("occupancy_provisioner", "occupancy_allocator",
                                "occupancy_reader", "bulk_runtime_test", "durable_runtime")) {
                            char[] password = password(role).toCharArray();
                            try { connection.unwrap(org.postgresql.PGConnection.class)
                                    .alterUserPassword(role, password, "scram-sha-256"); }
                            finally { Arrays.fill(password, '\0'); }
                        }
                    }
                }
                cluster.execute("create database capacity_global");
                identity = new BulkCapacityAuthorityMigrator.Identity(DEPLOYMENT, ENVIRONMENT, UUID.randomUUID(), 11);
                var roles = new BulkCapacityAuthorityMigrator.RoleConfiguration("postgres",
                        "occupancy_provisioner", "occupancy_allocator", "occupancy_reader");
                var owner = source("postgres", "capacity_global");
                authorityObserver = new JdbcTemplate(owner);
                assertThat(BulkCapacityAuthorityMigrator.migrate(owner, identity, roles)).isEqualTo(2);
                provisioner = authority("occupancy_provisioner", roles,
                        BulkCapacityAuthorityInfrastructure.Access.PROVISIONER);
                var allocator = authority("occupancy_allocator", roles,
                        BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR);
                var reading = authority("occupancy_reader", roles, BulkCapacityAuthorityInfrastructure.Access.READER);
                reader = new JdbcBulkCapacityIssuer.CapacityReader(reading);
                issuer = new JdbcBulkCapacityIssuer(allocator, reading);
            } catch (Exception | Error failure) {
                try { postgres.close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        BulkCapacityOccupancyPostgresFixture local(String caseId, int ordinal) throws Exception {
            if (closed || ordinal < 1 || ordinal > 5) throw new IllegalArgumentException("Invalid local binding ordinal");
            String namespace = "tenant:prod:occupancy-global-" + ordinal;
            var async = new BulkFingerprintContext(namespace, "occupancy-global-subject-" + ordinal,
                    CONTEXT.resourceKey(), CONTEXT.operationRef(), REVISION, ActionCollectionAtomicity.PER_ITEM);
            var sync = new BulkFingerprintContext(namespace, "occupancy-global-sync-subject-" + ordinal,
                    SYNC_CONTEXT.resourceKey(), SYNC_CONTEXT.operationRef(), SYNC_REVISION, ActionCollectionAtomicity.PER_ITEM);
            return new BulkCapacityOccupancyPostgresFixture(caseId, this, "capacity_local_" + ordinal,
                    "occupancy-global-tenant-" + ordinal, "occupancy-global-binding-" + ordinal, async, sync, false);
        }

        DataSource source(String role, String database) {
            return new DriverManagerDataSource(postgres.getJdbcUrl(role, database), role, password(role));
        }

        String password(String role) {
            if (credentials.isEmpty()) return "";
            String password = credentials.get(role);
            if (password == null) throw new IllegalArgumentException("No configured test credential for role");
            return password;
        }

        private BulkCapacityAuthorityInfrastructure authority(String role,
                BulkCapacityAuthorityMigrator.RoleConfiguration roles, BulkCapacityAuthorityInfrastructure.Access access) {
            var source = source(role, "capacity_global");
            return new BulkCapacityAuthorityInfrastructure(source, new DataSourceTransactionManager(source), identity, access, roles);
        }

        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            // Local proof manifests are finalized before the shared PostgreSQL cluster closes.
            for (int index = locals.size() - 1; index >= 0; index--) {
                try { locals.get(index).close(); }
                catch (Exception | Error cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            try { postgres.close(); }
            catch (Exception | Error cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
        }
    }

    BulkCapacityOccupancyPostgresFixture(String caseId) throws Exception {
        this(caseId, ownedScope(caseId), "capacity_local", TENANT, "occupancy-binding", CONTEXT, SYNC_CONTEXT, true);
    }

    /** Two namespaces are installed by the official migration before any binding activation. */
    static BulkCapacityOccupancyPostgresFixture withAdditionalNamespace(String caseId, String namespace) throws Exception {
        if (namespace == null || namespace.isBlank() || namespace.equals(NAMESPACE))
            throw new IllegalArgumentException("Distinct explicit additional namespace required");
        return new BulkCapacityOccupancyPostgresFixture(caseId, ownedScope(caseId), "capacity_local", TENANT,
                "occupancy-binding", CONTEXT, SYNC_CONTEXT, true,
                Map.of(NAMESPACE, DEPLOYMENT, namespace, DEPLOYMENT));
    }

    private static SharedScope ownedScope(String caseId) throws Exception {
        validateCaseId(caseId);
        return new SharedScope();
    }

    private static void validateCaseId(String caseId) {
        if (caseId == null || !caseId.matches("[a-z][a-z0-9-]{0,80}"))
            throw new IllegalArgumentException("Invalid proof case ID");
    }

    private BulkCapacityOccupancyPostgresFixture(String caseId, SharedScope scope, String localDatabase,
            String tenant, String binding, BulkFingerprintContext context, BulkFingerprintContext syncContext,
            boolean ownsScope) throws Exception {
        this(caseId, scope, localDatabase, tenant, binding, context, syncContext, ownsScope,
                Map.of(context.namespaceId(), DEPLOYMENT));
    }

    private BulkCapacityOccupancyPostgresFixture(String caseId, SharedScope scope, String localDatabase,
            String tenant, String binding, BulkFingerprintContext context, BulkFingerprintContext syncContext,
            boolean ownsScope, Map<String, String> namespaceDeployments) throws Exception {
        validateCaseId(caseId);
        this.namespaceDeployments = Map.copyOf(namespaceDeployments);
        if (!DEPLOYMENT.equals(this.namespaceDeployments.get(context.namespaceId()))
                || this.namespaceDeployments.values().stream().anyMatch(value -> !DEPLOYMENT.equals(value)))
            throw new IllegalArgumentException("Explicit same-deployment migration map required");
        this.caseId = caseId;
        this.scope = scope;
        this.ownsScope = ownsScope;
        this.postgres = scope.postgres;
        this.localDatabase = localDatabase;
        this.tenant = tenant;
        this.context = context;
        this.syncContext = syncContext;
        try {
            new JdbcTemplate(scope.credentials.isEmpty() ? postgres.getPostgresDatabase()
                    : scope.source("postgres", "postgres")).execute("create database " + localDatabase);
            provisioner = scope.provisioner;
            reader = scope.reader;
            issuer = scope.issuer;
            ownerSource = source("postgres", localDatabase);
            assertThat(BulkPostgresTestSupport.migrate(ownerSource, this.namespaceDeployments)).isEqualTo(20);
            runtimeSource = source("bulk_runtime_test", localDatabase);
            observer = new JdbcTemplate(ownerSource);
            runtimeSql = new JdbcTemplate(runtimeSource);
            assertThat(runtimeSql.queryForObject("select current_user", String.class)).isEqualTo("bulk_runtime_test");
            observer.execute("""
                    create table occupancy_domain_witness(id integer primary key,writes integer not null,
                        last_pid integer,last_xid bigint)
                    """);
            observer.update("insert into occupancy_domain_witness(id,writes) values(1,0),(2,0)");
            observer.execute("grant select,update on occupancy_domain_witness to bulk_runtime_test");
            runtime = new BulkExecutionInfrastructure(runtimeSource, new DataSourceTransactionManager(runtimeSource),
                    context.namespaceId(), DEPLOYMENT, BulkPostgresTestSupport.testRoleConfiguration());
            expected = new JdbcBulkCapacityInstallation.ExpectedBinding(DEPLOYMENT, tenant, ENVIRONMENT,
                    binding, 3, UUID.randomUUID(), UUID.randomUUID(), scope.identity.authorityId(),
                    scope.identity.expectedAuthorityEpoch());
            installation = new JdbcBulkCapacityInstallation(expected, ownerSource,
                    new DataSourceTransactionManager(ownerSource), "postgres", runtime, provisioner, reader,
                    Duration.ofSeconds(20), Duration.ofSeconds(3));
            kernel = new JdbcBulkDurableExecution(runtime, null, expected);
            control = composeDedicatedStorageControl();
            if (!ownsScope) scope.locals.add(this);
        } catch (Exception | Error failure) {
            // A failed borrowed binding must not tear down its siblings or their shared authority.
            if (ownsScope) {
                try { scope.close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    void activate() {
        installation.bootstrap();
        provisioner.enrollBinding(tenant, expected.bindingId(), expected.generation());
        installation.registerAttestation();
        installation.activate();
    }

    UUID install(JdbcBulkCapacityIssuer.CapacityClass capacityClass) {
        issuer.requestCapacity(new JdbcBulkCapacityIssuer.Request(UUID.randomUUID(), DEPLOYMENT, tenant,
                expected.bindingId(), capacityClass, 1));
        var token = issuer.allocateNext(capacityClass).orElseThrow();
        var authenticated = reader.readIssuedToken(token.tokenId()).orElseThrow();
        assertThat(authenticated.capacityClass()).isEqualTo(capacityClass);
        assertThat(installation.install(authenticated.tokenId())).isTrue();
        assertThat(observer.queryForObject("select occupancy_sequence from praxis_bulk.praxis_bulk_capacity_slot where token_id=?",
                Long.class, authenticated.tokenId())).isZero();
        return authenticated.tokenId();
    }

    /** Real canonical factories and typed eligibility, with no public READY admission. */
    BulkEvaluationSnapshot persist() { return persist(2); }

    /** Test-only target cardinality; existing default and canonical persistence path are unchanged. */
    BulkEvaluationSnapshot persist(int targetCount) { return persistFor(context, control, targetCount); }

    /** Explicit trusted same-binding context; default factories and storage path are preserved. */
    BulkEvaluationSnapshot persistFor(BulkFingerprintContext suppliedContext,
            BulkOperationControlExpectation expectedControl, int targetCount) {
        java.util.Objects.requireNonNull(suppliedContext, "suppliedContext");
        java.util.Objects.requireNonNull(expectedControl, "expectedControl");
        if (!context.namespaceId().equals(suppliedContext.namespaceId())
                || suppliedContext.atomicity() != ActionCollectionAtomicity.PER_ITEM
                || !suppliedContext.schemaRevision().equals(expectedControl.structuralRevision()))
            throw new IllegalArgumentException("Explicit same-namespace PER_ITEM fixture context is required");
        return persistBound(suppliedContext, expectedControl, targetCount, runtime);
    }

    private BulkEvaluationSnapshot persistBound(BulkFingerprintContext suppliedContext,
            BulkOperationControlExpectation expectedControl, int targetCount, BulkExecutionInfrastructure boundRuntime) {
        if (!namespaceDeployments.containsKey(suppliedContext.namespaceId())
                || !suppliedContext.namespaceId().equals(boundRuntime.namespace())
                || suppliedContext.atomicity() != ActionCollectionAtomicity.PER_ITEM
                || !suppliedContext.schemaRevision().equals(expectedControl.structuralRevision()))
            throw new IllegalArgumentException("Configured namespace/runtime/control tuple required");
        if (targetCount < 1 || targetCount > 2) throw new IllegalArgumentException("One or two fixture targets");
        var codec = BulkIdentityCodecs.strings();
        var request = new BulkProtocolReader<>(codec).readUniform("""
                {"executionMode":"ASYNC","selection":{"mode":"EXPLICIT","targets":[
                    {"id":"fixture-target-1","expectedVersion":"v1"}%s]},
                 "changes":[{"field":"amount","operator":"SET","value":1.0}]}
                """.formatted(targetCount == 2
                    ? ",{\"id\":\"fixture-target-2\",\"expectedVersion\":\"v2\"}" : "")
                .getBytes(StandardCharsets.UTF_8));
        var snapshot = BulkIntentSnapshot.uniform(suppliedContext, codec, request, JsonNode::deepCopy);
        Instant created = Instant.now().minusSeconds(2);
        var proposal = BulkStoredProposal.asynchronous(UUID.randomUUID(), created, created.plusSeconds(600),
                snapshot, expectedControl);
        var facts = JSON.objectNode().put("fixtureRevision", suppliedContext.schemaRevision());
        var plan = JSON.objectNode().put("amount", new java.math.BigDecimal("1.0"));
        var targets = List.<BulkTargetEvidence<?>>of(
                new BulkTargetEvidence<>(new BulkTarget<>("fixture-target-1", "v1"), "v1", facts, plan,
                        BulkTargetEligibility.executable()),
                new BulkTargetEvidence<>(new BulkTarget<>("fixture-target-2", "v2"), "v2", facts, plan,
                        BulkTargetEligibility.executable())).subList(0, targetCount);
        var governance = new BulkEvaluationGovernance("occupancy-fixture-evaluator-r1",
                "occupancy-fixture-grants-r1", List.of(new BulkPolicyObservation(tenant, "conformance",
                "occupancy-policy", "resource-action-approval", "resource:" + suppliedContext.resourceKey(), "NEVER_APPLIED",
                "occupancy-fixture-policy-r1", created.plusMillis(100))));
        var evaluation = new BulkEvaluationSnapshot(proposal, created.plusSeconds(1), targets, governance);
        assertThat(BulkSnapshotStorageCodec.decode(BulkSnapshotStorageCodec.encode(snapshot), snapshot.fingerprint())
                .intent().path("executionMode").asText()).isEqualTo("ASYNC");
        assertThat(BulkEvaluationStorageCodec.decode(proposal, BulkEvaluationStorageCodec.encode(evaluation),
                evaluation.fingerprint()).hasTypedEligibility()).isTrue();
        var tx = new TransactionTemplate(boundRuntime.transactionManager());
        tx.executeWithoutResult(status -> boundRuntime.withConnection(connection -> {
            JdbcBulkCapacityOccupancy.lockMarker(connection);
            var quota = BulkQuotaLedger.lockProposal(connection, boundRuntime, proposal, false, true);
            insertProtectedInput(connection, evaluation);
            BulkOrdinalManifest.insert(connection, evaluation);
            BulkPreviewStorage.insert(connection, evaluation, BulkEvaluationSnapshotTest.preview(evaluation));
            BulkQuotaLedger.insertPending(connection, proposal, quota);
            return null;
        }));
        return evaluation;
    }

    /** Dedicated trusted SYNC tuple; this does not compose or publish a public READY operation. */
    BulkEvaluationSnapshot persistSync() throws Exception {
        String descriptor = BulkCanonicalJson.digest(JSON.objectNode().put("operationId", syncContext.operationRef().operationId())
                .put("executionMode", "SYNC").put("selectionMode", "EXPLICIT").put("atomicity", "PER_ITEM")
                .put("structuralRevision", syncContext.schemaRevision()).put("scope", "protected-sync-storage-conformance-only"));
        observer.update("""
                insert into praxis_bulk.praxis_bulk_operation_control
                    (namespace_id,operation_id,state,generation,descriptor_fingerprint,structural_revision,updated_at)
                values (?,?,'UNCOMPOSED',0,null,null,clock_timestamp()) on conflict do nothing
                """, context.namespaceId(), syncContext.operationRef().operationId());
        BulkOperationControlExpectation expectation;
        try (var connection = ownerSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcBulkCapacityOccupancy.lockMarker(connection);
                var publication = JdbcBulkOpenApiPublication.lockForRead(connection, context.namespaceId(), DEPLOYMENT);
                assertThat(publication.published()).isTrue();
                var row = observer.queryForMap("""
                        select state,generation,descriptor_fingerprint,structural_revision
                          from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?
                        """, context.namespaceId(), syncContext.operationRef().operationId());
                if (!"READY".equals(row.get("state"))) {
                    var changed = JdbcBulkOperationControl.transition(connection, context.namespaceId(), syncContext.operationRef().operationId(),
                            ((Number) row.get("generation")).longValue(), JdbcBulkOperationControl.Target.READY,
                            descriptor, syncContext.schemaRevision(), publication.generation(), publication.documentDigest());
                    assertThat(changed.applied()).isTrue();
                }
                var current = JdbcBulkOperationControl.lockForAdmission(connection, context.namespaceId(), syncContext.operationRef().operationId());
                assertThat(current.ready()).isTrue();
                assertThat(current.descriptorFingerprint()).isEqualTo(descriptor);
                assertThat(current.structuralRevision()).isEqualTo(syncContext.schemaRevision());
                expectation = new BulkOperationControlExpectation(current.generation(), descriptor, syncContext.schemaRevision());
                connection.commit();
            } catch (Exception | Error failure) { connection.rollback(); throw failure; }
        }
        var evaluation = syncEvaluation(expectation, syncContext, tenant);
        var store = new JdbcBulkProposalStore(runtime);
        new TransactionTemplate(runtime.transactionManager()).executeWithoutResult(status ->
                store.insertEvaluated(evaluation, BulkEvaluationSnapshotTest.preview(evaluation)));
        return evaluation;
    }

    /** Genuine SYNC factories/codecs, also reused as wire input for the isolated published reader. */
    static BulkEvaluationSnapshot syncEvaluation(BulkOperationControlExpectation expectation) {
        return syncEvaluation(expectation, SYNC_CONTEXT, TENANT);
    }

    private static BulkEvaluationSnapshot syncEvaluation(BulkOperationControlExpectation expectation,
            BulkFingerprintContext syncContext, String tenant) {
        var codec = BulkIdentityCodecs.strings();
        var request = new BulkProtocolReader<>(codec).readUniform("""
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[
                    {"id":"fixture-target-1","expectedVersion":"v1"},
                    {"id":"fixture-target-2","expectedVersion":"v2"}]},
                 "changes":[{"field":"amount","operator":"SET","value":1.00},
                            {"field":"memo","operator":"SET","value":"fixture\\u0000text"}]}
                """.getBytes(StandardCharsets.UTF_8));
        var snapshot = BulkIntentSnapshot.uniform(syncContext, codec, request, JsonNode::deepCopy);
        Instant created = Instant.now().minusSeconds(2);
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(600), snapshot, expectation);
        var facts = JSON.objectNode().put("fixtureRevision", syncContext.schemaRevision());
        var plan = JSON.objectNode().put("amount", new java.math.BigDecimal("1.00"));
        var targets = List.<BulkTargetEvidence<?>>of(
                new BulkTargetEvidence<>(new BulkTarget<>("fixture-target-1", "v1"), "v1", facts, plan,
                        BulkTargetEligibility.executable()),
                new BulkTargetEvidence<>(new BulkTarget<>("fixture-target-2", "v2"), "v2", facts, plan,
                        BulkTargetEligibility.executable()));
        return new BulkEvaluationSnapshot(proposal, created.plusSeconds(1), targets,
                new BulkEvaluationGovernance("occupancy-sync-evaluator-r1", "occupancy-sync-grants-r1", List.of(
                        new BulkPolicyObservation(tenant, "conformance", "occupancy-sync-policy",
                                "resource-action-approval", "resource:occupancy-sync-fixture", "NEVER_APPLIED",
                                "occupancy-sync-policy-r1", created.plusMillis(100)))));
    }

    BulkExecutionReservation enqueue(BulkEvaluationSnapshot evaluation, String key, UUID queueToken) {
        return enqueue(evaluation, key, queueToken, Instant.now().plusSeconds(300));
    }

    BulkExecutionReservation enqueue(BulkEvaluationSnapshot evaluation, String key, UUID queueToken, Instant deadline) {
        return kernel.enqueue(context, evaluation.proposal().id(), key, "fixture-supervisor", context.schemaRevision(),
                deadline, queueToken);
    }

    /** Two ordinary-runtime kernels with an intact, test-only pause before A's physical commit. */
    ClaimRace claimRace() { return new ClaimRace(); }

    record ClaimPhysical(int backendPid, long transactionId, long enteredNanos) { }

    final class ClaimRace implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        private final ClaimProbeSource firstSource = new ClaimProbeSource();
        private final ClaimProbeSource secondSource = new ClaimProbeSource();
        private final BulkCommitPauseDataSource pause = new BulkCommitPauseDataSource(firstSource,
                BulkCommitPauseDataSource.PausePoint.BEFORE_COMMIT);
        private final JdbcBulkDurableExecution firstKernel = claimKernel(pause);
        private final JdbcBulkDurableExecution secondKernel = claimKernel(secondSource);

        Optional<BulkExecutionReservation> first(UUID execution, UUID active) {
            pause.arm(Thread.currentThread());
            return firstKernel.claim(context, execution, "concurrent-worker-a", active);
        }

        Optional<BulkExecutionReservation> second(UUID execution, UUID active) {
            return secondKernel.claim(context, execution, "concurrent-worker-b", active);
        }

        void awaitFirstBeforeCommit() throws InterruptedException {
            assertThat(pause.awaitMarker(5, TimeUnit.SECONDS)).as("A reached intact BEFORE_COMMIT barrier").isTrue();
            assertThat(firstSource.physical).isNotNull();
            barriersUsed = true;
            recordClaim("A_BEFORE_PHYSICAL_COMMIT", firstSource.physical, null);
        }

        /**
         * Observe the actual database wait, then release immediately. The deployment bucket
         * serializes this same-deployment race before execution/slot locks; no slot-wait claim.
         * The 700ms window starts at B's actual routine entry, below the unchanged 1s budget.
         */
        void awaitBlockedAndRelease() throws InterruptedException {
            try {
                assertThat(secondSource.entered.await(5, TimeUnit.SECONDS)).as("B entered real SQL claim").isTrue();
                ClaimPhysical a = firstSource.physical;
                ClaimPhysical b = secondSource.physical;
                assertThat(b.backendPid()).isNotEqualTo(a.backendPid());
                assertThat(b.transactionId()).isNotEqualTo(a.transactionId());
                long stop = b.enteredNanos() + TimeUnit.MILLISECONDS.toNanos(700);
                boolean blocked = false;
                while (System.nanoTime() < stop && !blocked) {
                    blocked = Boolean.TRUE.equals(observer.queryForObject("""
                            select exists(select 1 from pg_catalog.pg_stat_activity a
                              where a.pid=? and a.wait_event_type='Lock'
                                and ?=any(pg_catalog.pg_blocking_pids(a.pid))
                                and exists(select 1 from pg_catalog.pg_locks l
                                  where l.pid=a.pid and l.granted and l.mode='RowShareLock'
                                    and l.relation='praxis_bulk.praxis_bulk_deployment_bucket'::regclass))
                            """, Boolean.class, b.backendPid(), a.backendPid()));
                    if (!blocked) Thread.sleep(10);
                }
                assertThat(blocked).as("B really blocked by A upstream at deployment bucket within 700ms").isTrue();
                recordClaim("B_BLOCKED_BY_A_UPSTREAM_DEPLOYMENT_BUCKET", b, a.backendPid());
            } finally {
                // Never wait for a future or shut down its executor while retaining this barrier.
                release();
            }
        }

        void release() {
            pause.release();
            if (barriersUsed && firstSource.physical != null && released.compareAndSet(false, true))
                recordClaim("BEFORE_COMMIT_BARRIER_RELEASED", firstSource.physical, null);
        }

        @Override public void close() { release(); }
    }

    private JdbcBulkDurableExecution claimKernel(DataSource source) {
        var infrastructure = new BulkExecutionInfrastructure(source, new DataSourceTransactionManager(source),
                context.namespaceId(), DEPLOYMENT, BulkPostgresTestSupport.testRoleConfiguration());
        return new JdbcBulkDurableExecution(infrastructure, null, expected);
    }

    void claimOutcome(String claimer, String outcome) {
        if (!Set.of("A", "B").contains(claimer)
                || !Set.of("WON", "EMPTY_AFTER_WINNER_COMMIT", "CAPACITY_WITH_FULL_ROLLBACK").contains(outcome))
            throw new IllegalArgumentException("Unknown sanitized claim outcome");
        claimCertificates.add(JSON.objectNode().put("phase", "DURABLE_CLAIM_RESULT")
                .put("claimer", claimer).put("outcome", outcome));
    }

    private void recordClaim(String phase, ClaimPhysical physical, Integer blockerPid) {
        var certificate = JSON.objectNode().put("phase", phase).put("backendPid", physical.backendPid())
                .put("transactionId", physical.transactionId());
        if (blockerPid != null) certificate.put("blockingBackendPid", blockerPid);
        claimCertificates.add(certificate);
    }

    /**
     * Delegates the exact production routine call unchanged. PID/XID are captured on that
     * physical connection immediately before execution; SQL matching is instrumentation only.
     * No SQL text, parameters, business identities or driver error details enter the manifest.
     */
    private final class ClaimProbeSource extends DriverManagerDataSource {
        private static final String CLAIM_SQL = "select praxis_bulk.claim_capacity_execution(?,?,?,?,?)";
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile ClaimPhysical physical;

        @Override public Connection getConnection() throws SQLException { return wrap(runtimeSource.getConnection()); }

        @Override public Connection getConnection(String username, String password) throws SQLException {
            return wrap(runtimeSource.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> proxy == arguments[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "OccupancyClaimProbeConnection";
                                default -> throw new UnsupportedOperationException(method.getName());
                            };
                        }
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (method.getName().equals("prepareStatement") && arguments != null
                                    && CLAIM_SQL.equals(arguments[0]) && result instanceof PreparedStatement statement) {
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[]{PreparedStatement.class}, (statementProxy, operation, values) -> {
                                            if (operation.getName().equals("executeQuery")) {
                                                assertThat(connection.getAutoCommit()).as("claim operational transaction").isFalse();
                                                try (var probe = connection.createStatement();
                                                        var rows = probe.executeQuery("select pg_backend_pid(),txid_current()")) {
                                                    assertThat(rows.next()).isTrue();
                                                    physical = new ClaimPhysical(rows.getInt(1), rows.getLong(2), System.nanoTime());
                                                    assertThat(rows.next()).isFalse();
                                                }
                                                entered.countDown();
                                            }
                                            try { return operation.invoke(statement, values); }
                                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                                        });
                            }
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }

    private static final Map<String, String> AGING_GUARDS = Map.of(
            "praxis_bulk_execution_guard_terminal", "praxis_bulk_execution",
            "praxis_bulk_execution_protect_cancel", "praxis_bulk_execution",
            "praxis_bulk_execution_protect_binding", "praxis_bulk_execution",
            "praxis_bulk_allocation_protect_transition", "praxis_bulk_allocation",
            "praxis_bulk_capacity_occupation_guard", "praxis_bulk_capacity_occupation");

    record RetentionClockSnapshot(Map<String, Object> execution, List<Map<String, Object>> allocations,
            List<Map<String, Object>> occupations, Map<String, List<Map<String, Object>>> unaffected) { }

    private RetentionClockSnapshot retentionClockSnapshot(UUID execution) {
        var row = observer.queryForMap("select * from praxis_bulk.praxis_bulk_execution where execution_id=?", execution);
        var unaffected = new LinkedHashMap<String, List<Map<String, Object>>>();
        // Byte-bearing canonical inputs and all local installation/control/slot state stay intact.
        for (String table : List.of("proposal", "evaluation", "target_manifest", "preview_state",
                "target_preview", "preview_item_integrity", "capacity_slot", "capacity_installation",
                "capacity_marker", "capacity_occupancy_bootstrap", "operation_control", "namespace_binding",
                "openapi_publication")) {
            String order = switch (table) {
                case "target_manifest", "target_preview", "preview_item_integrity" -> "proposal_id,ordinal";
                case "operation_control" -> "namespace_id,operation_id";
                default -> "1"; // All remaining selected tables have a single-column primary key.
            };
            unaffected.put(table, observer.queryForList("select * from praxis_bulk.praxis_bulk_" + table
                    + " order by " + order));
        }
        unaffected.put("other-allocation", observer.queryForList("""
                select * from praxis_bulk.praxis_bulk_allocation where execution_id is distinct from ? order by allocation_id
                """, execution));
        unaffected.put("domain-witness", observer.queryForList("select * from occupancy_domain_witness order by id"));
        return new RetentionClockSnapshot(row, observer.queryForList("""
                select * from praxis_bulk.praxis_bulk_allocation where execution_id=? order by allocation_id
                """, execution), observer.queryForList("""
                select * from praxis_bulk.praxis_bulk_capacity_occupation where execution_id=? order by token_id,occupancy_sequence
                """, execution), unaffected);
    }

    /**
     * Privileged clock fixture, not 31 days of real execution or a runtime admission path.
     * Only timestamps of an already reconciled STOPPED/next0 execution are shifted. Canonical
     * proposal bytes remain original, so this models the retention clock, not a full host timeline.
     * All CHECK/FK constraints remain active. Five owner-only trigger suspensions exist solely
     * inside this transaction and are restored/attested before commit; rollback also restores DDL.
     */
    void ageStoppedExecutionForRetention(UUID execution) throws Exception {
        BulkExecutionMigrator.validate(ownerSource, BulkPostgresTestSupport.testRoleConfiguration());
        var before = retentionClockSnapshot(execution);
        assertThat(before.execution()).containsEntry("status", "STOPPED").containsEntry("next_ordinal", 0);
        assertThat(before.allocations()).hasSize(1);
        assertThat(before.allocations().getFirst()).containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "RELEASED");
        assertThat(observer.queryForObject("""
                select (select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?)
                     + (select count(*) from praxis_bulk.praxis_bulk_admission where execution_id=?)
                     + (select count(*) from praxis_bulk.praxis_bulk_capacity_slot where current_execution_id=?)
                """, Long.class, execution, execution, execution)).isZero();
        try (var connection = ownerSource.getConnection()) {
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("set local lock_timeout='1s'");
                    statement.execute("set local statement_timeout='3s'");
                    statement.execute("set local time zone 'UTC'");
                    statement.execute("select praxis_bulk.lock_capacity_marker()");
                    for (var guard : new java.util.TreeMap<>(AGING_GUARDS).entrySet())
                        statement.execute("alter table praxis_bulk." + guard.getValue() + " disable trigger " + guard.getKey());
                }
                try (var statement = connection.prepareStatement("""
                        update praxis_bulk.praxis_bulk_execution
                           set created_at=created_at-interval '31 days',updated_at=updated_at-interval '31 days',
                               terminal_at=terminal_at-interval '31 days',deadline_at=deadline_at-interval '31 days',
                               cancel_requested_at=cancel_requested_at-interval '31 days'
                         where execution_id=? and status='STOPPED' and next_ordinal=0
                        """)) {
                    statement.setObject(1, execution);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                try (var statement = connection.prepareStatement("""
                        update praxis_bulk.praxis_bulk_allocation
                           set created_at=created_at-interval '31 days',released_at=released_at-interval '31 days'
                         where execution_id=? and kind='EXECUTION_ASYNC' and state='RELEASED'
                        """)) {
                    statement.setObject(1, execution);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                try (var statement = connection.prepareStatement("""
                        update praxis_bulk.praxis_bulk_capacity_occupation set acquired_at=acquired_at-interval '31 days'
                         where execution_id=?
                        """)) {
                    statement.setObject(1, execution);
                    assertThat(statement.executeUpdate()).isEqualTo(before.occupations().size());
                }
                try (var statement = connection.createStatement()) {
                    for (var guard : new java.util.TreeMap<>(AGING_GUARDS).entrySet())
                        statement.execute("alter table praxis_bulk." + guard.getValue() + " enable trigger " + guard.getKey());
                }
                for (var guard : AGING_GUARDS.entrySet()) {
                    try (var statement = connection.prepareStatement("""
                            select count(*) from pg_catalog.pg_trigger
                             where tgrelid=?::regclass and tgname=? and not tgisinternal and tgenabled='O'
                            """)) {
                        statement.setString(1, "praxis_bulk." + guard.getValue()); statement.setString(2, guard.getKey());
                        try (var rows = statement.executeQuery()) {
                            assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1);
                            assertThat(rows.next()).isFalse();
                        }
                    }
                }
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                        select count(*) from pg_catalog.pg_constraint c
                         where c.connamespace='praxis_bulk'::regnamespace and c.contype in ('c','f')
                           and (not c.convalidated or exists(select 1 from pg_catalog.pg_trigger t
                                  where t.tgconstraint=c.oid and t.tgisinternal and t.tgenabled<>'O'))
                        """)) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
                    assertThat(rows.next()).isFalse();
                }
                connection.commit();
                committed = true;
            } finally {
                // Includes transactional DDL restoration on SQL/assertion/interruption failures.
                if (!committed) connection.rollback();
            }
        }
        var after = retentionClockSnapshot(execution);
        assertOnlyTimeShift(before.execution(), after.execution(),
                Set.of("created_at", "updated_at", "terminal_at", "deadline_at", "cancel_requested_at"));
        assertRowsOnlyTimeShift(before.allocations(), after.allocations(), Set.of("created_at", "released_at"));
        assertRowsOnlyTimeShift(before.occupations(), after.occupations(), Set.of("acquired_at"));
        assertThat(after.unaffected().keySet()).isEqualTo(before.unaffected().keySet());
        before.unaffected().forEach((table, rows) -> assertRowsOnlyTimeShift(rows, after.unaffected().get(table), Set.of()));
        BulkExecutionMigrator.validate(ownerSource, BulkPostgresTestSupport.testRoleConfiguration());
        retentionCertificates.add(JSON.objectNode().put("phase", "PRIVILEGED_RETENTION_CLOCK_SETUP_CERTIFIED")
                .put("shiftDays", 31).put("restoredGuards", AGING_GUARDS.size())
                .put("executionRows", 1).put("allocationRows", 1).put("occupationRows", after.occupations().size())
                .put("allNonTemporalColumnsUnchanged", true).put("canonicalInputBytesUnchanged", true)
                .put("constraintsAndForeignKeysRetained", true).put("exactCatalogValidatedAfterSetup", true)
                .put("realElapsedDaysProven", false));
    }

    private static void assertRowsOnlyTimeShift(List<Map<String, Object>> before, List<Map<String, Object>> after,
            Set<String> temporalColumns) {
        assertThat(after).hasSize(before.size());
        for (int i = 0; i < before.size(); i++) assertOnlyTimeShift(before.get(i), after.get(i), temporalColumns);
    }

    private static void assertOnlyTimeShift(Map<String, Object> before, Map<String, Object> after, Set<String> temporalColumns) {
        assertThat(after.keySet()).isEqualTo(before.keySet());
        before.forEach((column, value) -> {
            Object actual = after.get(column);
            if (temporalColumns.contains(column) && value != null) {
                assertThat(timestamp(actual)).as("fixture-only exact clock shift for %s", column)
                        .isEqualTo(timestamp(value).minus(Duration.ofDays(31)));
            } else if (value instanceof byte[] bytes) {
                assertThat(actual instanceof byte[] && Arrays.equals(bytes, (byte[]) actual))
                        .as("protected byte column unchanged: %s", column).isTrue();
            } else {
                assertThat(Objects.deepEquals(value, actual)).as("non-temporal column unchanged: %s", column).isTrue();
            }
        });
    }

    private static Instant timestamp(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime timestamp) return timestamp.toInstant();
        throw new AssertionError("Unexpected fixture timestamp type");
    }

    /** Executes the real retention function as its executor; membership exists only in this TX. */
    boolean purgeAsRetentionExecutor(UUID execution) throws Exception {
        boolean result;
        try (var connection = ownerSource.getConnection()) {
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("set local lock_timeout='1s'");
                    statement.execute("set local statement_timeout='3s'");
                    statement.execute("grant praxis_bulk_retention_executor to postgres");
                    statement.execute("set local role praxis_bulk_retention_executor");
                }
                try (var statement = connection.prepareStatement("select praxis_bulk.purge_terminal_execution(?)")) {
                    statement.setObject(1, execution);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue(); result = rows.getBoolean(1);
                        assertThat(rows.wasNull()).isFalse(); assertThat(rows.next()).isFalse();
                    }
                }
                try (var statement = connection.createStatement()) {
                    statement.execute("set local role none");
                    statement.execute("revoke praxis_bulk_retention_executor from postgres");
                }
                connection.commit(); committed = true;
            } finally { if (!committed) connection.rollback(); }
        }
        assertThat(observer.queryForObject("""
                select count(*) from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid=m.roleid
                 where r.rolname='praxis_bulk_retention_executor'
                """, Long.class)).isZero();
        BulkExecutionMigrator.validate(ownerSource, BulkPostgresTestSupport.testRoleConfiguration());
        retentionCertificates.add(JSON.objectNode().put("phase", "GOVERNED_RETENTION_EXECUTOR_RESULT")
                .put("purged", result).put("temporaryMembershipRemoved", true));
        return result;
    }

    record PhysicalUnit(int ordinal, int backendPid, long transactionId) { }

    /** A real mutation inside the kernel callback; both JDBC handles must use its bound backend/TX. */
    PhysicalUnit writeDomain(BulkExecutionUnit unit) {
        var physical = runtime.withConnection(connection -> {
            assertThat(connection.getAutoCommit()).isFalse();
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("select pg_backend_pid(),txid_current()")) {
                assertThat(rows.next()).isTrue();
                var observed = new PhysicalUnit(unit.ordinal(), rows.getInt(1), rows.getLong(2));
                assertThat(rows.next()).isFalse();
                var jdbcRead = runtimeSql.queryForMap("select pg_backend_pid() as pid,txid_current() as xid");
                assertThat(((Number) jdbcRead.get("pid")).intValue()).isEqualTo(observed.backendPid());
                assertThat(((Number) jdbcRead.get("xid")).longValue()).isEqualTo(observed.transactionId());
                return observed;
            }
        });
        assertThat(runtimeSql.update("""
                update occupancy_domain_witness set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current()
                 where id=?
                """, unit.ordinal() + 1)).isEqualTo(1);
        assertThat(runtimeSql.queryForObject("select writes from occupancy_domain_witness where id=?", Integer.class,
                unit.ordinal() + 1)).isEqualTo(1);
        // One query on a fresh owner connection observes both absences in the same snapshot.
        var absent = observer.queryForMap("""
                select pg_backend_pid() as observer_pid,writes,
                    (select count(*) from praxis_bulk.praxis_bulk_item_receipt
                     where execution_id=? and unit_ordinal=?) as receipt_count
                  from occupancy_domain_witness where id=?
                """, unit.executionId(), unit.ordinal(), unit.ordinal() + 1);
        assertThat(absent).containsEntry("writes", 0).containsEntry("receipt_count", 0L);
        int observerPid = ((Number) absent.get("observer_pid")).intValue();
        assertThat(observerPid).isNotEqualTo(physical.backendPid());
        physicalCertificates.add(JSON.objectNode().put("phase", "UNCOMMITTED_DOMAIN_AND_RECEIPT_ABSENT")
                .put("ordinal", unit.ordinal()).put("observerPid", observerPid)
                .put("callbackPid", physical.backendPid()).put("callbackXid", physical.transactionId())
                .put("domainWrites", ((Number) absent.get("writes")).intValue())
                .put("receiptCount", ((Number) absent.get("receipt_count")).longValue()));
        physicalUnits.add(physical);
        return physical;
    }

    /** Fresh independent observations certify the domain row and receipt committed in that same TX. */
    void assertPhysicalCommit(UUID execution, PhysicalUnit physical) {
        var rows = observer.queryForMap("""
                select pg_backend_pid() as observer_pid,d.writes,d.last_pid,d.last_xid,d.xmin::text::bigint as write_xid,
                       r.owner_epoch,r.outcome,r.xmin::text::bigint as receipt_xid
                  from occupancy_domain_witness d join praxis_bulk.praxis_bulk_item_receipt r
                    on r.execution_id=? and r.unit_ordinal=? where d.id=?
                """, execution, physical.ordinal(), physical.ordinal() + 1);
        assertThat(rows).containsEntry("writes", 1).containsEntry("last_pid", physical.backendPid())
                .containsEntry("last_xid", physical.transactionId());
        long xid32 = physical.transactionId() & 0xffffffffL;
        assertThat(rows).containsEntry("write_xid", xid32).containsEntry("receipt_xid", xid32).containsEntry("owner_epoch", 2L)
                .containsEntry("outcome", "CONFIRMED");
        int observerPid = ((Number) rows.get("observer_pid")).intValue();
        assertThat(observerPid).isNotEqualTo(physical.backendPid());
        physicalCertificates.add(JSON.objectNode().put("phase", "COMMITTED_DOMAIN_AND_RECEIPT_CERTIFIED")
                .put("ordinal", physical.ordinal()).put("observerPid", observerPid)
                .put("callbackPid", physical.backendPid()).put("callbackXid", physical.transactionId())
                .put("domainXid", ((Number) rows.get("write_xid")).longValue())
                .put("receiptXid", ((Number) rows.get("receipt_xid")).longValue()));
    }

    void waitUntilDeadline(Instant deadline) throws InterruptedException {
        Instant databaseNow = observer.queryForObject("select clock_timestamp()", java.time.OffsetDateTime.class).toInstant();
        long remainingMillis = Math.max(0, Duration.between(databaseNow, deadline).toMillis());
        assertThat(remainingMillis).isLessThanOrEqualTo(5000);
        long stopAt = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        while (databaseNow.isBefore(deadline)) {
            assertThat(System.nanoTime()).as("bounded monotonic deadline observation").isLessThan(stopAt);
            Thread.sleep(Math.max(1, Math.min(250, Duration.between(databaseNow, deadline).toMillis())));
            databaseNow = observer.queryForObject("select clock_timestamp()", java.time.OffsetDateTime.class).toInstant();
        }
        assertThat(databaseNow).isAfterOrEqualTo(deadline);
    }

    long count(String table) {
        if (!List.of("execution", "item_receipt", "admission", "capacity_occupation").contains(table))
            throw new IllegalArgumentException("Unknown fixture count");
        return observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_" + table, Long.class);
    }

    Map<String, Object> slot(UUID token) {
        return observer.queryForMap("""
                select capacity_class,occupancy_sequence,current_execution_id,current_owner_epoch
                  from praxis_bulk.praxis_bulk_capacity_slot where token_id=?
                """, token);
    }

    Map<String, Object> allocation(UUID execution) {
        var rows = observer.queryForList("""
                select allocation_id,kind,state,released_at,release_reason
                  from praxis_bulk.praxis_bulk_allocation where execution_id=?
                """, execution);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    Map<String, Object> controlRow() {
        return observer.queryForMap("""
                select state,generation,descriptor_fingerprint,structural_revision,
                       publication_generation,publication_document_digest
                  from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?
                """, context.namespaceId(), context.operationRef().operationId());
    }

    void observe(String phase, UUID execution) {
        var row = observer.queryForMap("select status,owner_epoch,next_ordinal from praxis_bulk.praxis_bulk_execution where execution_id=?",
                execution);
        observations.add(Map.of("phase", phase, "status", row.get("status"), "epoch", row.get("owner_epoch"),
                "nextOrdinal", row.get("next_ordinal"), "historyRows", count("capacity_occupation")));
    }

    void assertionsComplete() { assertionsComplete = true; }

    private BulkOperationControlExpectation composeDedicatedStorageControl() throws Exception {
        String descriptor = BulkCanonicalJson.digest(JSON.objectNode().put("operationId", context.operationRef().operationId())
                .put("executionMode", "ASYNC").put("selectionMode", "EXPLICIT").put("atomicity", "PER_ITEM")
                .put("structuralRevision", context.schemaRevision()).put("scope", "protected-storage-conformance-only"));
        String document = BulkCanonicalJson.digest(JSON.objectNode().put("fixturePublication", context.operationRef().operationId()));
        observer.update("""
                insert into praxis_bulk.praxis_bulk_operation_control
                    (namespace_id,operation_id,state,generation,descriptor_fingerprint,structural_revision,updated_at)
                values (?,?,'UNCOMPOSED',0,null,null,clock_timestamp())
                """, context.namespaceId(), context.operationRef().operationId());
        try (var connection = ownerSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var publication = JdbcBulkOpenApiPublication.lockForRead(connection, context.namespaceId(), DEPLOYMENT);
                var suspended = JdbcBulkOpenApiPublication.transition(connection, context.namespaceId(), DEPLOYMENT,
                        publication.generation(), JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
                assertThat(suspended.applied()).isTrue();
                var published = JdbcBulkOpenApiPublication.transition(connection, context.namespaceId(), DEPLOYMENT,
                        suspended.generation(), JdbcBulkOpenApiPublication.Target.PUBLISHED, document);
                assertThat(published.applied()).isTrue();
                var changed = JdbcBulkOperationControl.transition(connection, context.namespaceId(), context.operationRef().operationId(), 0,
                        JdbcBulkOperationControl.Target.READY, descriptor, context.schemaRevision(), published.generation(), document);
                assertThat(changed.applied()).isTrue();
                var current = JdbcBulkOperationControl.lockForAdmission(connection, context.namespaceId(), context.operationRef().operationId());
                assertThat(current.ready()).isTrue();
                connection.commit();
                return new BulkOperationControlExpectation(current.generation(), current.descriptorFingerprint(),
                        current.structuralRevision());
            } catch (Exception | Error failure) { connection.rollback(); throw failure; }
        }
    }

    /** Trusted storage-only control on the current photo, not public composition or capture. */
    BulkOperationControlExpectation composeAdditionalStorageControl(BulkFingerprintContext suppliedContext)
            throws Exception {
        java.util.Objects.requireNonNull(suppliedContext, "suppliedContext");
        if (!context.namespaceId().equals(suppliedContext.namespaceId())
                || suppliedContext.atomicity() != ActionCollectionAtomicity.PER_ITEM
                || context.operationRef().operationId().equals(suppliedContext.operationRef().operationId()))
            throw new IllegalArgumentException("A distinct same-namespace PER_ITEM operation is required");
        return composeCurrentPublicationControl(suppliedContext);
    }

    private BulkOperationControlExpectation composeCurrentPublicationControl(BulkFingerprintContext suppliedContext)
            throws Exception {
        if (!namespaceDeployments.containsKey(suppliedContext.namespaceId())
                || suppliedContext.atomicity() != ActionCollectionAtomicity.PER_ITEM)
            throw new IllegalArgumentException("Configured PER_ITEM namespace required");
        var ref = suppliedContext.operationRef();
        String descriptor = BulkCanonicalJson.digest(JSON.objectNode().put("operationId", ref.operationId())
                .put("group", ref.group()).put("path", ref.path()).put("method", ref.method())
                .put("executionMode", "ASYNC").put("selectionMode", "EXPLICIT").put("atomicity", "PER_ITEM")
                .put("structuralRevision", suppliedContext.schemaRevision())
                .put("scope", "protected-storage-conformance-only"));
        try (var connection = ownerSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcBulkCapacityOccupancy.lockMarker(connection);
                var publication = JdbcBulkOpenApiPublication.lockForRead(connection, suppliedContext.namespaceId(), DEPLOYMENT);
                assertThat(publication.published()).isTrue();
                try (var insert = connection.prepareStatement("""
                        insert into praxis_bulk.praxis_bulk_operation_control
                            (namespace_id,operation_id,state,generation,descriptor_fingerprint,structural_revision,updated_at)
                        values (?,?,'UNCOMPOSED',0,null,null,clock_timestamp())
                        """)) {
                    insert.setString(1, suppliedContext.namespaceId()); insert.setString(2, ref.operationId());
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
                var transition = JdbcBulkOperationControl.transition(connection, suppliedContext.namespaceId(),
                        ref.operationId(), 0, JdbcBulkOperationControl.Target.READY, descriptor,
                        suppliedContext.schemaRevision(), publication.generation(), publication.documentDigest());
                assertThat(transition.applied()).isTrue();
                var current = JdbcBulkOperationControl.lockForAdmission(connection, suppliedContext.namespaceId(), ref.operationId());
                assertThat(current.ready()).isTrue();
                connection.commit();
                return new BulkOperationControlExpectation(current.generation(), current.descriptorFingerprint(),
                        current.structuralRevision());
            } catch (Exception | Error failure) { connection.rollback(); throw failure; }
        }
    }

    Map<String, List<Map<String, Object>>> authorityRows() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("capacity_token", "capacity_binding", "binding_attestation", "capacity_request",
                "deployment_capacity", "tenant_capacity", "fairness_cursor"))
            result.put(table, scope.authorityObserver.queryForList("select * from praxis_bulk_capacity." + table + " order by 1"));
        return result;
    }

    /** Explicit view over the SAME physical database/marker and unchanged deployment publication. */
    NamespaceView namespaceView(BulkFingerprintContext additional) throws Exception {
        if (context.namespaceId().equals(additional.namespaceId())
                || !namespaceDeployments.containsKey(additional.namespaceId())
                || !context.resourceKey().equals(additional.resourceKey())
                || !context.operationRef().equals(additional.operationRef())
                || !context.schemaRevision().equals(additional.schemaRevision()))
            throw new IllegalArgumentException("Configured additional namespace with matching canonical operation required");
        return new NamespaceView(additional);
    }

    final class NamespaceView {
        final BulkFingerprintContext context;
        final BulkExecutionInfrastructure runtime;
        final JdbcBulkDurableExecution kernel;
        final BulkOperationControlExpectation control;

        private NamespaceView(BulkFingerprintContext context) throws Exception {
            this.context = context;
            this.runtime = new BulkExecutionInfrastructure(runtimeSource, new DataSourceTransactionManager(runtimeSource),
                    context.namespaceId(), DEPLOYMENT, BulkPostgresTestSupport.testRoleConfiguration());
            this.kernel = new JdbcBulkDurableExecution(runtime, null, expected);
            this.control = composeCurrentPublicationControl(context);
        }

        BulkEvaluationSnapshot persist() { return persistBound(context, control, 2, runtime); }

        BulkExecutionReservation enqueue(BulkEvaluationSnapshot evaluation, String key, UUID queue) {
            return kernel.enqueue(context, evaluation.proposal().id(), key, "fixture-supervisor",
                    context.schemaRevision(), Instant.now().plusSeconds(300), queue);
        }
    }

    private static void insertProtectedInput(Connection connection, BulkEvaluationSnapshot evaluation) throws SQLException {
        var proposal = evaluation.proposal();
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_proposal
                    (proposal_id,namespace_id,subject_id,resource_key,operation_id,created_at,expires_at,
                     fingerprint,payload,control_generation,control_descriptor_fingerprint,control_structural_revision,
                     atomicity,protocol_version,execution_mode)
                values(?,?,?,?,?,?,?,?,?,?,?,?,'PER_ITEM',2,'ASYNC')
                """)) {
            statement.setObject(1, proposal.id()); statement.setString(2, proposal.snapshot().context().namespaceId());
            statement.setString(3, proposal.snapshot().context().subjectId()); statement.setString(4, proposal.snapshot().context().resourceKey());
            statement.setString(5, proposal.snapshot().context().operationRef().operationId()); statement.setObject(6, proposal.createdAt().atOffset(ZoneOffset.UTC));
            statement.setObject(7, proposal.expiresAt().atOffset(ZoneOffset.UTC));
            statement.setString(8, proposal.snapshot().fingerprint());
            statement.setBytes(9, BulkSnapshotStorageCodec.encode(proposal.snapshot()));
            statement.setLong(10, proposal.controlExpectation().generation());
            statement.setString(11, proposal.controlExpectation().descriptorFingerprint());
            statement.setString(12, proposal.controlExpectation().structuralRevision());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_evaluation(proposal_id,input_fingerprint,evaluation_fingerprint,payload)
                values(?,?,?,?)
                """)) {
            statement.setObject(1, proposal.id()); statement.setString(2, proposal.snapshot().fingerprint());
            statement.setString(3, evaluation.fingerprint()); statement.setBytes(4, BulkEvaluationStorageCodec.encode(evaluation));
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private DataSource source(String role, String database) {
        if (role.equals("bulk_runtime_test") && Set.of("claim-transfer", "queued-cancel").contains(caseId)) {
            return new DiagnosticDataSource(postgres.getJdbcUrl(role, database), role);
        }
        return scope.source(role, database);
    }

    /**
     * Observes driver failures before the kernel safely discards their protected causes. This
     * wrapper neither changes the transaction nor records SQL, parameters, messages or row detail.
     * It is enabled only for the two failing diagnostic cases, leaving other campaigns unchanged.
     */
    private final class DiagnosticDataSource extends DriverManagerDataSource {
        DiagnosticDataSource(String url, String role) { super(url, role, scope.password(role)); }

        @Override public Connection getConnection() throws SQLException {
            return (Connection) wrap(super.getConnection(), Connection.class);
        }

        private Object wrap(Object delegate, Class<?> surface) {
            return Proxy.newProxyInstance(BulkCapacityOccupancyPostgresFixture.class.getClassLoader(),
                    new Class<?>[]{surface}, (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(delegate, arguments);
                            if (result instanceof CallableStatement statement) return wrap(statement, CallableStatement.class);
                            if (result instanceof PreparedStatement statement) return wrap(statement, PreparedStatement.class);
                            if (result instanceof Statement statement) return wrap(statement, Statement.class);
                            return result;
                        } catch (InvocationTargetException failure) {
                            Throwable cause = failure.getCause();
                            if (cause instanceof SQLException sql) captureSqlDiagnostic(sql);
                            throw cause;
                        }
                    });
        }
    }

    private synchronized void captureSqlDiagnostic(SQLException failure) {
        var diagnostic = JSON.objectNode().put("caseId", caseId).put("logicalDatabase", "capacity_local");
        String state = failure.getSQLState();
        diagnostic.put("sqlState", state != null && state.matches("[0-9A-Z]{5}") ? state : "UNAVAILABLE");
        diagnostic.put("routine", "UNAVAILABLE");
        var frames = diagnostic.putArray("functionFrames");
        if (failure instanceof PSQLException postgresFailure && postgresFailure.getServerErrorMessage() != null) {
            var server = postgresFailure.getServerErrorMessage();
            String routine = server.getRoutine();
            if (routine != null && routine.matches("[a-zA-Z_][a-zA-Z0-9_]{0,79}")) diagnostic.put("routine", routine);
            String context = server.getWhere();
            if (context != null) {
                // Only declared function names and numeric source lines survive the filter.
                // All intervening SQL context (including parameter values) is discarded.
                Set<String> functions = Set.of("claim_capacity_execution", "materialize_capacity_execution",
                        "guard_capacity_execution", "guard_capacity_slot", "guard_capacity_occupation",
                        "guard_atomic_attempt_transition", "release_active_allocation_on_terminal",
                        "guard_terminal_execution", "protect_allocation_transition", "validate_allocation_binding",
                        "terminal_evidence_complete", "atomic_evidence_complete", "protect_cancel_request");
                var matcher = Pattern.compile("PL/pgSQL function (?:praxis_bulk\\.)?([a-z_][a-z0-9_]*)"
                        + "\\([^\\r\\n]*?\\) line ([0-9]{1,6}) at").matcher(context);
                while (matcher.find()) if (functions.contains(matcher.group(1)))
                    frames.addObject().put("function", matcher.group(1)).put("line", Integer.parseInt(matcher.group(2)));
            }
        }
        sqlDiagnostics.add(diagnostic);
        System.out.println("Occupancy SQL diagnostic: " + diagnostic);
    }


    /** Sanitized test observations only; no catalog SQL, protected IDs or values are exported. */
    void catalogCertificate(String dimension, String phase) {
        if (!dimension.matches("[a-z][a-z-]{0,60}") || !Set.of("COMMITTED_DRIFT_OBSERVED",
                "VALIDATION_REJECTED_NO_HEALING", "RESTORED_EXACT_CATALOG",
                "PUBLIC_ASYNC_REJECTED_BEFORE_CALLBACK").contains(phase))
            throw new IllegalArgumentException("Invalid catalog certificate label");
        catalogCertificates.add(JSON.objectNode().put("dimension", dimension).put("phase", phase));
    }

    /** Published-artifact proof metadata only; protected rows and exception details stay private. */
    void artifactCertificate(String phase, int backendPid, long transactionId) {
        if (!phase.matches("[A-Z][A-Z0-9_]{0,80}") || backendPid < 0 || transactionId < 0)
            throw new IllegalArgumentException("Invalid artifact certificate");
        artifactCertificates.add(JSON.objectNode().put("phase", phase)
                .put("gav", "io.github.codexrodrigues:praxis-metadata-starter:8.0.0-rc.154")
                .put("sha256", "e2c98ca0a551eb4911400e7fc76247e5ce230c33ae42d187df7e2ba929698a61")
                .put("backendPid", backendPid).put("transactionId", transactionId));
    }

    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try { writeManifest(); } catch (Exception | Error cleanup) { failure = cleanup; }
        if (ownsScope) {
            try { scope.close(); }
            catch (Exception | Error cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private void writeManifest() throws Exception {
        String configured = System.getProperty("praxis.bulk.proof.directory");
        Path directory = configured == null ? Files.createTempDirectory("praxis-occupancy-proof-") : Path.of(configured);
        Files.createDirectories(directory);
        var manifest = JSON.objectNode().put("caseId", caseId).put("logicalDatabase", localDatabase)
                .put("harnessPid", ProcessHandle.current().pid()).put("subprocesses", 0)
                .put("processExit", "NOT_APPLICABLE_IN_PROCESS_JUNIT").put("barriersUsed", barriersUsed)
                .put("caseOutcome", assertionsComplete ? "ASSERTIONS_COMPLETE" : "INCOMPLETE_OR_FAILED");
        var states = manifest.putArray("observations");
        for (var observation : observations) {
            var state = states.addObject();
            state.put("phase", (String) observation.get("phase"));
            state.put("status", (String) observation.get("status"));
            state.put("epoch", ((Number) observation.get("epoch")).longValue());
            state.put("nextOrdinal", ((Number) observation.get("nextOrdinal")).intValue());
            state.put("historyRows", ((Number) observation.get("historyRows")).longValue());
        }
        var retention = manifest.putArray("retentionCertificates");
        retentionCertificates.forEach(certificate -> retention.add(certificate.deepCopy()));
        var claims = manifest.putArray("claimCertificates");
        claimCertificates.forEach(certificate -> claims.add(certificate.deepCopy()));
        var catalog = manifest.putArray("catalogCertificates");
        catalogCertificates.forEach(certificate -> catalog.add(certificate.deepCopy()));
        var artifacts = manifest.putArray("artifactCertificates");
        artifactCertificates.forEach(certificate -> artifacts.add(certificate.deepCopy()));
        var diagnostics = manifest.putArray("sqlDiagnostics");
        sqlDiagnostics.forEach(diagnostic -> diagnostics.add(diagnostic.deepCopy()));
        var transactions = manifest.putArray("callbackTransactions");
        for (var physical : physicalUnits) transactions.addObject().put("ordinal", physical.ordinal())
                .put("backendPid", physical.backendPid()).put("transactionId", physical.transactionId())
                .put("phase", "DOMAIN_WRITE_OBSERVED_INSIDE_OPERATIONAL_TX");
        var certificates = manifest.putArray("physicalCertificates");
        physicalCertificates.forEach(certificate -> certificates.add(certificate.deepCopy()));
        Path file = directory.resolve(caseId + ".json");
        Files.writeString(file, manifest.toPrettyString(), StandardCharsets.UTF_8);
        if (configured == null) System.out.println("Occupancy proof manifest: " + file.toAbsolutePath());
    }
}
