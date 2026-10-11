package org.praxisplatform.uischema.bulk;

import java.net.URI;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.preview;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.snapshot;

/**
 * Opt-in proof on an explicitly assigned disposable Neon branch. Creates a fresh database and
 * two roles, never resets inherited schemas or starts local PostgreSQL. The coordinator owns
 * branch cleanup; database/role names are emitted without credentials for evidence and cleanup.
 * The fixture grant and publication are test authority, not production IAM or HTTP readiness.
 */
@EnabledIfEnvironmentVariable(named = "PRAXIS_BULK_NEON_CONNECTION_FILE", matches = ".+")
class BulkOptionalPoliciesNeonTest {
    private static final String NAMESPACE = "optional-policies-neon-proof";
    private static final String DEPLOYMENT = "optional-policies-neon-proof-deployment";
    private static final String OPERATION = "optional-policies-update";
    private static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext(NAMESPACE,
            "fixture-operator", "fixture-domain", new CanonicalOperationRef("proof", OPERATION,
                    "/proof/bulk", "POST"), "schema-r1", ActionCollectionAtomicity.PER_ITEM);

    @Test
    void emptyPoliciesPersistAndExecuteButRevokedHostAuthorityStillStopsMutation() throws Exception {
        URI uri = URI.create(Files.readString(Path.of(required("PRAXIS_BULK_NEON_CONNECTION_FILE"))).strip());
        assertThat(uri.getScheme()).isIn("postgresql", "postgres");
        assertThat(uri.getHost()).isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_HOST"));
        assertThat(uri.getHost()).endsWith(".neon.tech").doesNotContain("-pooler");
        String[] credentials = uri.getRawUserInfo().split(":", 2);
        String ownerRole = decode(credentials[0]);
        String ownerPassword = decode(credentials[1]);
        assertThat(ownerRole).isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_OWNER"));
        var initial = source(uri, decode(uri.getPath().substring(1)), ownerRole, ownerPassword);
        var initialSql = new JdbcTemplate(initial);
        assertThat(initialSql.queryForObject("select current_user=session_user and current_user=?", Boolean.class, ownerRole)).isTrue();
        assertThat(initialSql.queryForObject("select not rolsuper and rolcreaterole and rolcreatedb from pg_roles where rolname=current_user", Boolean.class)).isTrue();
        assertThat(initialSql.queryForObject("select current_setting('server_version_num')::integer >= 170000", Boolean.class)).isTrue();
        assertThat(initialSql.queryForObject("select to_regnamespace('praxis_bulk') is null", Boolean.class)).isTrue();
        String resumeDatabase = System.getenv("PRAXIS_BULK_NEON_RESUME_DATABASE");
        boolean resume = resumeDatabase != null && !resumeDatabase.isBlank();
        if (!resume) assertThat(initialSql.queryForObject("select count(*) from pg_roles where rolname like 'praxis_bulk_%'", Integer.class)).isZero();
        if (resume) assertThat(resumeDatabase).matches("bulk_optional_[a-f0-9]{12}");
        String suffix = resume ? resumeDatabase.substring("bulk_optional_".length())
                : UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String database = "bulk_optional_" + suffix;
        String runtimeRole = "bulk_optional_runtime_" + suffix;
        String controlRole = "bulk_optional_control_" + suffix;
        String runtimePassword = UUID.randomUUID().toString();
        String controlPassword = UUID.randomUUID().toString();
        // Only generated identifiers/passwords are interpolated; no request or environment SQL.
        if (!resume) provision(initial, "create database " + database);
        System.out.println("NEON_PROOF_DATABASE=" + database);
        Path credentialFile = Path.of(required("PRAXIS_BULK_NEON_FIXTURE_CREDENTIALS_FILE"));
        var fixtureCredentials = new java.util.Properties();
        fixtureCredentials.setProperty("database", database); fixtureCredentials.setProperty("runtimeRole", runtimeRole);
        fixtureCredentials.setProperty("runtimePassword", runtimePassword); fixtureCredentials.setProperty("controlRole", controlRole);
        fixtureCredentials.setProperty("controlPassword", controlPassword);
        Files.createFile(credentialFile, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        try (var writer = Files.newBufferedWriter(credentialFile)) { fixtureCredentials.store(writer, "Assigned disposable Neon proof credentials"); }
        if (resume) {
            assertThat(runtimeRole).isEqualTo(required("PRAXIS_BULK_NEON_RESUME_RUNTIME_ROLE"));
            assertThat(controlRole).isEqualTo(required("PRAXIS_BULK_NEON_RESUME_CONTROL_ROLE"));
            for (String role : List.of(runtimeRole, controlRole))
                assertThat(initialSql.queryForObject("select rolcanlogin and rolinherit and not rolsuper and not rolcreaterole and not rolcreatedb and not rolreplication and not rolbypassrls from pg_catalog.pg_roles where rolname=?", Boolean.class, role)).isTrue();
            // Explicitly assigned disposable logins only; no attributes, membership or grants change.
            provision(initial, "alter role " + runtimeRole + " password '" + runtimePassword + "'");
            provision(initial, "alter role " + controlRole + " password '" + controlPassword + "'");
        } else {
            provision(initial, "create role " + runtimeRole + " login inherit nocreaterole nosuperuser nocreatedb noreplication nobypassrls password '" + runtimePassword + "'");
            provision(initial, "create role " + controlRole + " login inherit nocreaterole nosuperuser nocreatedb noreplication nobypassrls password '" + controlPassword + "'");
        }
        System.out.println("NEON_PROOF_ROLES=" + runtimeRole + "," + controlRole);
        var owner = new MeasuredDataSource(source(uri, database, ownerRole, ownerPassword));
        try (var runtime = new MeasuredPool(source(uri, database, runtimeRole, runtimePassword), "runtime");
                var control = new MeasuredPool(source(uri, database, controlRole, controlPassword), "control")) {
        var roles = new BulkExecutionRoleConfiguration(ownerRole, Set.of(runtimeRole), Set.of(), Set.of(controlRole));
        if (!resume) {
        owner.measure("fresh", () -> assertThat(BulkExecutionMigrator.migrate(owner,
                Map.of(NAMESPACE, DEPLOYMENT), roles)).isEqualTo(20));
        owner.measure("strict", () -> BulkExecutionMigrator.validate(owner, roles));
        owner.measure("repeat-zero", () -> assertThat(BulkExecutionMigrator.migrate(owner,
                Map.of(NAMESPACE, DEPLOYMENT), roles)).isZero());
        owner.measure("negative-acl", () -> rejectsAlteredAcl(owner, roles, runtimeRole));
        owner.measure("negative-trigger", () -> rejectsAlteredTrigger(owner, roles));
        owner.measure("negative-DE", () -> BulkNeonAdversarialProof.catalogs(owner, roles, runtimeRole));
        System.out.println("NEON_PROOF_MIGRATION=20,strict-validation-passed");
        }
        var ownerSql = new JdbcTemplate(owner);
        if (resume) {
            assertThat(ownerSql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_schema_history where success and version is not null", Integer.class)).isEqualTo(20);
            assertThat(ownerSql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_read_bootstrap", String.class)).isEqualTo("COMPLETE");
            assertThat(ownerSql.queryForObject("select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap", String.class)).isEqualTo("COMPLETE");
            assertThat(ownerSql.queryForObject("select to_regclass('public.bulk_optional_domain') is null", Boolean.class)).isTrue();
            assertThat(ownerSql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_proposal", Integer.class)).isZero();
            System.out.println("NEON_PROOF_RESUME=existing-P3-V20,no-migrate-or-fresh");
        }
        var manager = new DataSourceTransactionManager(runtime);
        var infrastructure = new BulkExecutionInfrastructure(runtime, manager, NAMESPACE, DEPLOYMENT, roles);
        var controlPlane = new BulkControlPlaneInfrastructure(control, new DataSourceTransactionManager(control),
                NAMESPACE, DEPLOYMENT, controlRole, infrastructure);
        assertThat(new JdbcTemplate(runtime).queryForObject("select session_user || ':' || current_user", String.class))
                .isEqualTo(runtimeRole + ":" + runtimeRole);
        assertThat(new JdbcTemplate(control).queryForObject("select session_user || ':' || current_user", String.class))
                .isEqualTo(controlRole + ":" + controlRole);
        System.out.println("NEON_PROOF_AUTHENTICATED_ROLES=" + runtimeRole + "," + controlRole);
        runtime.recorder.measure("live-runtime-attestation", () -> infrastructure.withLifecycleRead(connection -> null));
        measureProfiles("live-control-attestation", runtime, control, () -> controlPlane.withConnection(connection -> null));
        assertBoundedStatementTimeout(runtime);
        var callbacks = new AtomicInteger();
        // A real control credential bound as runtime reaches the live identity guard before protected work.
        var wrongIdentity = new BulkExecutionInfrastructure(control, new DataSourceTransactionManager(control), NAMESPACE, DEPLOYMENT, roles);
        assertThatThrownBy(() -> wrongIdentity.withLifecycleRead(connection -> callbacks.incrementAndGet()))
                .hasMessageContaining("Bulk connection identity is not an allowlisted PostgreSQL role");
        assertThat(callbacks).hasValue(0);
        var controlBeforeDrift = ownerSql.queryForList("select * from praxis_bulk.praxis_bulk_operation_control order by namespace_id,operation_id");
        var publicationBeforeDrift = ownerSql.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by deployment_id");
        ownerSql.execute("grant update on praxis_bulk.praxis_bulk_target_manifest to " + runtimeRole);
        try {
            assertThatThrownBy(() -> infrastructure.withLifecycleRead(connection -> callbacks.incrementAndGet()))
                    .hasMessageContaining("bulk runtime manifest grants differ");
            assertThatThrownBy(() -> controlPlane.withConnection(connection -> callbacks.incrementAndGet()))
                    .hasMessageContaining("bulk runtime manifest grants differ");
            assertThat(callbacks).hasValue(0);
        } finally { ownerSql.execute("revoke update on praxis_bulk.praxis_bulk_target_manifest from " + runtimeRole); }
        assertThat(ownerSql.queryForList("select * from praxis_bulk.praxis_bulk_operation_control order by namespace_id,operation_id")).isEqualTo(controlBeforeDrift);
        assertThat(ownerSql.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by deployment_id")).isEqualTo(publicationBeforeDrift);
        System.out.println("NEON_PROOF_WRONG_IDENTITY=real-connection-rejected,callbacks-zero");
        System.out.println("NEON_PROOF_LIVE_DRIFT=runtime-and-control-rejected-before-callback,fixture-grant-revoked");
        measureProfiles("publication-seed", runtime, control, () -> seedPublication(ownerSql, infrastructure, controlPlane));
        ownerSql.execute("create table public.bulk_optional_grant(subject text primary key, allowed boolean not null, revision integer not null)");
        ownerSql.execute("create table public.bulk_optional_domain(id integer primary key, writes integer not null default 0)");
        ownerSql.execute("grant select on public.bulk_optional_grant to " + runtimeRole);
        ownerSql.execute("grant select, update on public.bulk_optional_domain to " + runtimeRole);
        ownerSql.update("insert into public.bulk_optional_grant values (?,true,1)", CONTEXT.subjectId());
        ownerSql.update("insert into public.bulk_optional_domain(id) values (1),(2),(3)");
        var tx = new TransactionTemplate(manager);
        var store = new JdbcBulkProposalStore(infrastructure);
        var runtimeSql = new JdbcTemplate(runtime);
        var kernel = new JdbcBulkDurableExecution(infrastructure);
        governanceNegatives(tx, store, infrastructure, ownerSql);
        var first = evaluation(1);
        tx.executeWithoutResult(status -> store.insertEvaluated(first, preview(first)));
        var recovered = tx.execute(status -> store.findEvaluation(CONTEXT, first.proposal().id()).orElseThrow());
        assertThat(recovered.governance().policies()).isEmpty();
        assertThat(recovered.governance().authorizationFingerprint()).isEqualTo("fixture-grant-r1");
        assertThat(recovered.fingerprint()).isEqualTo(first.fingerprint());
        System.out.println("NEON_PROOF_ROUNDTRIP=empty-policy-set-and-fingerprint-preserved");
        var admitted = kernel.reserve(CONTEXT, first.proposal().id(), "accepted", "proof-owner", "structural-r1", Instant.now().plusSeconds(30));
        var admissionCalls = new AtomicInteger();
        var mutationCalls = new AtomicInteger();
        BulkUnitAdmissionCallback admission = unit -> {
            admissionCalls.incrementAndGet();
            var grant = runtimeSql.queryForMap("select allowed,revision from public.bulk_optional_grant where subject=?", CONTEXT.subjectId());
            return Boolean.TRUE.equals(grant.get("allowed"))
                    && unit.governance().authorizationFingerprint().equals("fixture-grant-r" + grant.get("revision"))
                    ? BulkUnitAdmission.admit() : BulkUnitAdmission.stop(BulkUnitReasonCode.AUTHORIZATION_REVOKED);
        };
        BulkUnitMutationCallback mutation = unit -> {
            mutationCalls.incrementAndGet();
            assertThat(runtimeSql.update("update public.bulk_optional_domain set writes=writes+1 where id=?", unit.targetEvidence().target().id())).isEqualTo(1);
            return BulkUnitMutationResult.confirmed();
        };
        kernel.advance(admitted, admission, mutation);
        assertThat(mutationCalls).hasValue(1);
        assertThat(ownerSql.queryForObject("select writes from public.bulk_optional_domain where id=1", Integer.class)).isEqualTo(1);
        assertThat(ownerSql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Integer.class, admitted.executionId())).isEqualTo(1);
        assertThat(ownerSql.queryForObject("select status from praxis_bulk.praxis_bulk_execution where execution_id=?", String.class, admitted.executionId())).isEqualTo("COMPLETED");
        assertDomainReceiptRollback(tx, store, kernel, admission, runtime, ownerSql);
        // Evaluate while the grant is valid, immediately before its explicit revocation.
        var second = evaluation(2);
        tx.executeWithoutResult(status -> store.insertEvaluated(second, preview(second)));
        ownerSql.update("update public.bulk_optional_grant set allowed=false, revision=2 where subject=?", CONTEXT.subjectId());
        int beforeReplay = admissionCalls.get();
        var replay = kernel.reserve(CONTEXT, first.proposal().id(), "accepted", "proof-owner", "structural-r1", Instant.now().plusSeconds(30));
        kernel.advance(replay, admission, mutation);
        assertThat(replay.executionId()).isEqualTo(admitted.executionId());
        assertThat(admissionCalls).hasValue(beforeReplay);
        assertThat(mutationCalls).hasValue(1);
        var revoked = kernel.reserve(CONTEXT, second.proposal().id(), "revoked", "proof-owner", "structural-r1", Instant.now().plusSeconds(30));
        kernel.advance(revoked, admission, mutation);
        assertThat(admissionCalls).hasValue(beforeReplay + 1);
        assertThat(mutationCalls).hasValue(1);
        assertThat(ownerSql.queryForObject("select writes from public.bulk_optional_domain where id=2", Integer.class)).isZero();
        assertThat(ownerSql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Integer.class, revoked.executionId())).isZero();
        assertThat(ownerSql.queryForObject("select terminal_reason_code from praxis_bulk.praxis_bulk_execution where execution_id=?", String.class, revoked.executionId())).isEqualTo("AUTHORIZATION_REVOKED");
        System.out.println("NEON_PROOF_EXECUTION=one-domain-write-and-receipt,replay-without-callback,revocation-zero-write-and-receipt");
        }
    }

    private static void governanceNegatives(TransactionTemplate tx, JdbcBulkProposalStore store,
            BulkExecutionInfrastructure infrastructure, JdbcTemplate owner) {
        var empty = evaluation(1);
        var policy = new BulkPolicyObservation("fixture-tenant", "fixture-environment", "DOMAIN",
                "POLICY", "optional-policies-proof", "APPLIED", "fixture-policy-r1", empty.evaluatedAt());
        var value = new BulkEvaluationSnapshot(empty.proposal(), empty.evaluatedAt(), empty.targets(),
                new BulkEvaluationGovernance("fixture-evaluator-r1", "fixture-grant-r1", List.of(policy)));
        tx.executeWithoutResult(status -> store.insertEvaluated(value, preview(value)));
        var loaded = tx.execute(status -> store.findEvaluation(CONTEXT, value.proposal().id()).orElseThrow());
        assertThat(loaded.governance()).isEqualTo(value.governance());
        assertThat(loaded.fingerprint()).isEqualTo(value.fingerprint());
        var later = loaded.evaluatedAt().plusSeconds(1);
        var changed = new BulkEvaluationGovernance(loaded.governance().evaluatorRevision(),
                loaded.governance().authorizationFingerprint(), List.of(new BulkPolicyObservation(policy.tenantId(),
                policy.environment(), policy.targetLayer(), policy.targetArtifactType(), policy.targetArtifactKey(),
                "PREVIOUSLY_APPLIED_WITHOUT_ELIGIBLE_HEAD", "fixture-policy-r2", later)));
        assertThat(loaded.matchesCurrentEvidence(CONTEXT, later, loaded.targets(), changed)).isFalse();
        assertThat(tx.execute(status -> store.findEvaluation(CONTEXT, value.proposal().id()).orElseThrow()).fingerprint())
                .isEqualTo(value.fingerprint());
        System.out.println("NEON_PROOF_G_NONEMPTY=committed-roundtrip,changed-policy-rejected,no-rewrite");
        for (String kind : List.of("missing-governance", "corrupt-json")) {
            var corrupt = evaluation(1);
            var document = (com.fasterxml.jackson.databind.node.ObjectNode) corrupt.storageDocument();
            document.remove("governance");
            byte[] payload = kind.equals("missing-governance") ? BulkSnapshotStorageCodec.json(document)
                    : "{invalid-json".getBytes(StandardCharsets.UTF_8);
            // INSERT only: opaque storage plus canonical companions. No immutable fence or FK is disabled.
            tx.executeWithoutResult(status -> {
                store.insert(corrupt.proposal());
                infrastructure.withConnection(connection -> {
                    try (var statement = connection.prepareStatement("insert into praxis_bulk.praxis_bulk_evaluation (proposal_id,input_fingerprint,evaluation_fingerprint,payload) values (?,?,?,?)")) {
                        statement.setObject(1, corrupt.proposal().id()); statement.setString(2, corrupt.proposal().snapshot().fingerprint());
                        statement.setString(3, corrupt.fingerprint()); statement.setBytes(4, payload); statement.executeUpdate();
                    }
                    BulkOrdinalManifest.insert(connection, corrupt);
                    BulkPreviewStorage.insert(connection, corrupt, preview(corrupt));
                    return null;
                });
            });
            assertThat(owner.queryForObject("select payload from praxis_bulk.praxis_bulk_evaluation where proposal_id=?", byte[].class, corrupt.proposal().id()))
                    .isEqualTo(payload);
            assertThatThrownBy(() -> tx.execute(status -> store.findEvaluation(CONTEXT, corrupt.proposal().id())))
                    .isInstanceOfSatisfying(BulkProposalStorageException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkProposalStorageException.Reason.CORRUPT)).hasNoCause();
            var protectedInput = tx.execute(status -> store.find(CONTEXT, corrupt.proposal().id()));
            assertThat(protectedInput).isPresent();
            System.out.println("NEON_PROOF_G_" + kind + "=committed-visible,protected-load-CORRUPT,input-intact");
        }
    }

    private static void assertDomainReceiptRollback(TransactionTemplate tx, JdbcBulkProposalStore store,
            JdbcBulkDurableExecution kernel, BulkUnitAdmissionCallback admission, MeasuredPool runtime, JdbcTemplate owner) {
        var value = evaluation(3);
        tx.executeWithoutResult(status -> store.insertEvaluated(value, preview(value)));
        var reservation = kernel.reserve(CONTEXT, value.proposal().id(), "rollback-proof", "proof-owner", "structural-r1", Instant.now().plusSeconds(30));
        // Deferred domain-only trigger observes the real receipt in the same transaction, then aborts COMMIT.
        owner.execute("""
                create function public.bulk_proof_abort_commit() returns trigger language plpgsql as $$
                begin
                  if exists(select 1 from praxis_bulk.praxis_bulk_item_receipt r
                      join praxis_bulk.praxis_bulk_execution e using(execution_id)
                      where e.proposal_id=TG_ARGV[0]::uuid) then
                    raise exception 'fixture commit rejected after receipt' using errcode='PZ001';
                  end if;
                  raise exception 'fixture receipt was not written' using errcode='PZ002';
                end; $$
                """);
        owner.execute("create constraint trigger bulk_proof_abort_commit after update on public.bulk_optional_domain "
                + "deferrable initially deferred for each row when(new.id=3) execute function public.bulk_proof_abort_commit('" + value.proposal().id() + "')");
        int receiptsBefore = runtime.recorder.successfulReceiptInserts.get();
        int failedCommitsBefore = runtime.recorder.deferredReceiptCommitFailures.get();
        var mutations = new AtomicInteger();
        try {
            // Outcome may be reconciliation-required; assert the exact commit failure and durable state below.
            try {
                kernel.advance(reservation, admission, unit -> {
                    mutations.incrementAndGet();
                    assertThat(new JdbcTemplate(runtime).update("update public.bulk_optional_domain set writes=writes+1 where id=3")).isEqualTo(1);
                    return BulkUnitMutationResult.confirmed();
                });
            } catch (BulkDurableExecutionException expected) {
                assertThat(expected.reason()).isEqualTo(BulkDurableExecutionException.Reason.RECONCILIATION_REQUIRED);
            }
            assertThat(mutations).hasValue(1);
            assertThat(runtime.recorder.successfulReceiptInserts).hasValue(receiptsBefore + 1);
            assertThat(runtime.recorder.deferredReceiptCommitFailures).hasValue(failedCommitsBefore + 1);
            assertThat(owner.queryForObject("select writes from public.bulk_optional_domain where id=3", Integer.class)).isZero();
            assertThat(owner.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Integer.class, reservation.executionId())).isZero();
        } finally {
            owner.execute("drop trigger bulk_proof_abort_commit on public.bulk_optional_domain");
            owner.execute("drop function public.bulk_proof_abort_commit()");
        }
        System.out.println("NEON_PROOF_F_ROLLBACK=real-receipt-inserted,commit-PZ001,domain-and-receipt-zero,no-callback-retry");
    }

    private static void measureProfiles(String label, MeasuredPool runtime, MeasuredPool control, CheckedAction action) throws Exception {
        runtime.recorder.measure(label + "-runtime", () -> control.recorder.measure(label + "-control", action));
    }

    private static void assertBoundedStatementTimeout(DataSource runtime) throws Exception {
        try (var connection = runtime.getConnection()) {
            connection.setAutoCommit(false);
            try {
                BulkExecutionInfrastructure.attestWithBoundStatementTimeout(connection, c -> {
                    try (var s = c.createStatement()) { s.execute("select pg_catalog.pg_sleep(0.35)"); }
                });
                throw new AssertionError("250ms PostgreSQL timeout did not reject the slow statement");
            } catch (java.sql.SQLException expected) { assertThat(expected.getSQLState()).isEqualTo("57014"); }
            finally { connection.rollback(); }
        }
        System.out.println("NEON_PROOF_STATEMENT_TIMEOUT=250ms-enforced,transaction-rolled-back");
    }

    /** Direct stable host-style pool: independent credentials, no SET ROLE, closed after proof. */
    private static final class MeasuredPool extends com.zaxxer.hikari.HikariDataSource {
        private final MeasuredDataSource recorder;
        MeasuredPool(PGSimpleDataSource source, String profile) throws Exception {
            recorder = new MeasuredDataSource(source);
            setDataSource(source); setMaximumPoolSize(1); setMinimumIdle(1); setPoolName("neon-proof-" + profile);
            long started = System.nanoTime();
            try (var connection = getConnection()) {
                assertThat(connection.getMetaData().getDriverVersion()).isEqualTo("42.7.11");
                assertThat(source.getSslMode()).isEqualTo("verify-full");
                assertThat(source.getProperty(org.postgresql.PGProperty.SSL_FACTORY)).isEqualTo("org.postgresql.ssl.DefaultJavaSSLFactory");
                var executor = connection.unwrap(org.postgresql.jdbc.PgConnection.class).getQueryExecutor();
                var field = org.postgresql.core.QueryExecutorBase.class.getDeclaredField("pgStream"); field.setAccessible(true);
                var stream = (org.postgresql.core.PGStream) field.get(executor);
                assertThat(stream.getSocket()).isInstanceOf(javax.net.ssl.SSLSocket.class);
                var tls = ((javax.net.ssl.SSLSocket) stream.getSocket()).getSession();
                assertThat(tls.getPeerHost()).isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_HOST"));
                System.out.println("NEON_CLIENT_TLS=" + profile + ",protocol=" + tls.getProtocol() + ",cipher=" + tls.getCipherSuite()
                        + ",peer=" + tls.getPeerHost() + ",principal=" + tls.getPeerPrincipal());
            }
            System.out.println("NEON_POOL=" + profile + ",authenticatedTlsSetupMillis=" + (System.nanoTime() - started) / 1_000_000);
        }
        @Override public Connection getConnection() throws java.sql.SQLException { return recorder.measured(super.getConnection()); }
    }

    private static void rejectsAlteredAcl(DataSource owner, BulkExecutionRoleConfiguration roles,
            String runtimeRole) throws Exception {
        var validator = BulkExecutionMigrator.class.getDeclaredMethod("validateV5RolesAndPrivileges",
                Connection.class, BulkExecutionRoleConfiguration.class);
        validator.setAccessible(true);
        var grants = List.of(
                "grant update on praxis_bulk.praxis_bulk_target_manifest to " + runtimeRole,
                "grant update(proposal_id) on praxis_bulk.praxis_bulk_proposal to praxis_bulk_control_owner",
                "grant select on praxis_bulk.praxis_bulk_target_manifest to public",
                "grant select on praxis_bulk.praxis_bulk_target_manifest to " + runtimeRole + " with grant option",
                "grant " + runtimeRole + " to " + roles.controlPlaneGranteeRoles().iterator().next());
        var expectedMessages = List.of("bulk runtime manifest grants differ",
                "operation-control definer-owner proposal column privileges differ",
                "PUBLIC must not have governed lifecycle table privileges", "grant options are forbidden",
                "configured bulk roles inherit unexpected PostgreSQL roles");
        for (int index = 0; index < grants.size(); index++) {
            String grant = grants.get(index);
            String expectedMessage = expectedMessages.get(index);
            try (var connection = owner.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    try (var statement = connection.createStatement()) { statement.execute(grant); }
                    assertThatThrownBy(() -> {
                        try { validator.invoke(null, connection, roles); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    }).isInstanceOf(IllegalStateException.class).hasMessageContaining(expectedMessage);
                } finally { connection.rollback(); }
            }
        }
        System.out.println("NEON_PROOF_NEGATIVES=table,column,PUBLIC,grant-option,membership-rejected-and-rolled-back");
    }

    private static void rejectsAlteredTrigger(DataSource owner, BulkExecutionRoleConfiguration roles) throws Exception {
        try (var connection = owner.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("set local search_path=pg_catalog");
                    statement.execute("alter table praxis_bulk.praxis_bulk_capacity_slot disable trigger praxis_bulk_capacity_slot_guard");
                }
                assertThatThrownBy(() -> BulkCapacityOccupancyCatalog.validate(connection, roles, true))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("V19 trigger differs: praxis_bulk_capacity_slot_guard");
            } finally { connection.rollback(); }
        }
        assertThat(new JdbcTemplate(owner).queryForObject("select tgenabled::text from pg_trigger where tgrelid='praxis_bulk.praxis_bulk_capacity_slot'::regclass and tgname='praxis_bulk_capacity_slot_guard'", String.class)).isEqualTo("O");
        System.out.println("NEON_PROOF_TRIGGER=disabled-guard-rejected-and-rollback-restored-enabled");
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    /** Counts JDBC execute calls and SQL time, never SQL text/parameters or product telemetry. */
    private static final class MeasuredDataSource extends DelegatingDataSource {
        private final Map<String, long[]> counts = new TreeMap<>();
        private final AtomicInteger successfulReceiptInserts = new AtomicInteger();
        private final AtomicInteger deferredReceiptCommitFailures = new AtomicInteger();
        MeasuredDataSource(DataSource delegate) { super(delegate); }
        void measure(String label, CheckedAction action) throws Exception {
            counts.clear();
            long started = System.nanoTime();
            try { action.run(); }
            finally {
                System.out.println("NEON_PHASE=" + label + ",wallMillis=" + (System.nanoTime() - started) / 1_000_000);
                counts.forEach((phase, value) -> System.out.println("NEON_SQL=" + label + "," + phase
                        + ",executes=" + value[0] + ",aclBatch=" + value[1] + ",aclPair=" + value[2]
                        + ",catalogReads=" + value[4] + ",sqlMillis=" + value[3] / 1_000_000));
            }
        }
        @Override public Connection getConnection() throws java.sql.SQLException { return measured(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws java.sql.SQLException {
            return measured(super.getConnection(user, password));
        }
        private Connection measured(Connection connection) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result;
                        try { result = method.invoke(connection, args); }
                        catch (InvocationTargetException failure) {
                            if (method.getName().equals("commit") && failure.getCause() instanceof java.sql.SQLException sql
                                    && "PZ001".equals(sql.getSQLState())) deferredReceiptCommitFailures.incrementAndGet();
                            throw failure.getCause();
                        }
                        if (!(result instanceof Statement statement)) return result;
                        String preparedSql = args != null && args.length > 0 && args[0] instanceof String sql ? sql : "";
                        Class<?> statementType = statement instanceof java.sql.CallableStatement ? java.sql.CallableStatement.class
                                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{statementType},
                                (statementProxy, called, values) -> {
                                    if (!called.getName().startsWith("execute")) {
                                        try { return called.invoke(statement, values); }
                                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    }
                                    String sql = preparedSql.isEmpty() && values != null && values.length > 0
                                            && values[0] instanceof String text ? text : preparedSql;
                                    String phase = StackWalker.getInstance().walk(frames -> frames
                                            .filter(frame -> frame.getClassName().equals(BulkExecutionMigrator.class.getName()))
                                            .map(StackWalker.StackFrame::getMethodName)
                                            .filter(name -> Set.of("completeCapacityReadBootstrap", "completeCapacityOccupancyBootstrap",
                                                    "initializeGovernedLifecycle", "validateOwnerBootstrapBeforeWrites",
                                                    "migrateSchemaWithHistoricalPreflight", "validateCurrent", "validateLiveRuntimeRoleAccess",
                                                    "validateLiveControlPlaneRoleAccess").contains(name))
                                            .reduce((near, outer) -> outer).orElse("other"));
                                    long started = System.nanoTime();
                                    try {
                                        Object executed = called.invoke(statement, values);
                                        if (sql.stripLeading().startsWith("insert into praxis_bulk.praxis_bulk_item_receipt")) successfulReceiptInserts.incrementAndGet();
                                        return executed;
                                    }
                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    finally {
                                        var count = counts.computeIfAbsent(phase, ignored -> new long[5]);
                                        count[0]++;
                                        if (sql.contains("with governed_tables as") || sql.contains("q.relname,coalesce(r.rolname")) count[1]++;
                                        if (StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(BulkCatalogPhase.class.getName())))) count[4]++;
                                        if (sql.contains("select 'T:' || acl.privilege_type")) count[2]++;
                                        count[3] += System.nanoTime() - started;
                                    }
                                });
                    });
        }
    }

    private static BulkEvaluationSnapshot evaluation(int id) {
        Instant created = Instant.now().minusSeconds(1);
        var intent = snapshot(CONTEXT, BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.integers(), Integer.toString(id), "1");
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(300), intent, CONTROL_EXPECTATION);
        var evidence = new BulkTargetEvidence<>(new BulkTarget<>(id, "v1"), "v1",
                BulkEvaluationSnapshotTest.JSON.objectNode(), BulkEvaluationSnapshotTest.JSON.objectNode(), BulkTargetEligibility.executable());
        return new BulkEvaluationSnapshot(proposal, Instant.now(), List.of(evidence),
                new BulkEvaluationGovernance("fixture-evaluator-r1", "fixture-grant-r1", List.of()));
    }

    private static void seedPublication(JdbcTemplate owner, BulkExecutionInfrastructure runtime, BulkControlPlaneInfrastructure controlPlane) {
        owner.update("insert into praxis_bulk.praxis_bulk_operation_control (namespace_id,operation_id,state,generation,updated_at) values (?,?,'UNCOMPOSED',0,clock_timestamp()) on conflict (namespace_id,operation_id) do nothing", NAMESPACE, OPERATION);
        assertThat(owner.queryForObject("select state||':'||generation from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?", String.class, NAMESPACE, OPERATION)).isEqualTo("UNCOMPOSED:0");
        // Same test-only photograph as BulkPostgresTestSupport.ready, but through the actual
        // separately authenticated control-plane role. No production readiness is claimed.
        var publication = runtime.withLifecycleRead(connection ->
                JdbcBulkOpenApiPublication.lockForRead(connection, NAMESPACE, DEPLOYMENT));
        long suspendedGeneration = controlPlane.withConnection(connection -> {
            var suspended = JdbcBulkOpenApiPublication.transition(connection, NAMESPACE, DEPLOYMENT,
                    publication.generation(), JdbcBulkOpenApiPublication.Target.SUSPENDED, null);
            assertThat(suspended.applied()).isTrue();
            return suspended.generation();
        });
        // Canonical publication and operation admission remain one atomic transaction.
        controlPlane.withConnection(connection -> {
            var published = JdbcBulkOpenApiPublication.transition(connection, NAMESPACE, DEPLOYMENT,
                    suspendedGeneration, JdbcBulkOpenApiPublication.Target.PUBLISHED, "sha256:" + "0".repeat(64));
            assertThat(published.applied()).isTrue();
            var ready = JdbcBulkOperationControl.transition(connection, NAMESPACE, OPERATION, 0,
                    JdbcBulkOperationControl.Target.READY, "sha256:" + "0".repeat(64), "structural-r1",
                    published.generation(), "sha256:" + "0".repeat(64));
            assertThat(ready.applied()).isTrue();
            return null;
        });
    }

    private static PGSimpleDataSource source(URI uri, String database, String user, String password) {
        var result = new PGSimpleDataSource();
        result.setServerNames(new String[]{uri.getHost()});
        result.setPortNumbers(new int[]{uri.getPort() < 0 ? 5432 : uri.getPort()});
        result.setDatabaseName(database); result.setUser(user); result.setPassword(password);
        result.setSslMode("verify-full");
        // Same official pgJDBC/JDK truststore path as the reference host's operational tooling.
        result.setProperty(org.postgresql.PGProperty.SSL_FACTORY,
                "org.postgresql.ssl.DefaultJavaSSLFactory");
        result.setConnectTimeout(10); result.setSocketTimeout(30);
        return result;
    }
    private static void provision(PGSimpleDataSource source, String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(15); statement.execute(sql);
        } catch (java.sql.SQLException failure) {
            // Never echo SQL containing the generated fixture credentials.
            throw new IllegalStateException("Disposable Neon provisioning failed; SQLSTATE=" + failure.getSQLState());
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required proof setting absent: " + name);
        return value;
    }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
}
