package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.DEPLOYMENT;
import static org.praxisplatform.uischema.bulk.BulkCapacityOccupancyPostgresFixture.NAMESPACE;

/**
 * Committed catalog drift in private databases, followed by exact restoration. Privileged
 * fixture DDL is not runtime authority. No altered function is executed as authority, and
 * constraint attestation is asserted only at the full owner validator/migration boundary.
 */
class BulkCapacityOccupancyCatalogPostgresTest {
    private static final String CLAIM = "praxis_bulk.claim_capacity_execution(uuid,text,text,uuid,bigint)";
    private static final String MARKER = "praxis_bulk.lock_capacity_marker()";

    @Test
    void publicConstructorsProfilesAndCaptureRejectGenuineProtectedAsync() throws Exception {
        try (var fixture = populated("catalog-public-async-closed")) {
            var evaluation = fixture.persist();
            var proposal = evaluation.proposal();
            var before = snapshot(fixture);
            assertThat(proposal.snapshot().intent().path("executionMode").asText()).isEqualTo("ASYNC");
            assertThatThrownBy(() -> new BulkStoredProposal(proposal.id(), proposal.createdAt(),
                    proposal.expiresAt(), proposal.snapshot())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new BulkStoredProposal(proposal.id(), proposal.createdAt(),
                    proposal.expiresAt(), proposal.snapshot(), proposal.controlExpectation()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE), Set.of(BulkExecutionMode.SYNC),
                    Set.of(BulkSelectionMode.EXPLICIT), 200, 1024, Duration.ofMinutes(5), Duration.ofSeconds(5)))
                    .isNotNull();
            assertThatThrownBy(() -> new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE),
                    Set.of(BulkExecutionMode.ASYNC), Set.of(BulkSelectionMode.EXPLICIT), 200, 1024,
                    Duration.ofMinutes(5), Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
            var store = new JdbcBulkProposalStore(fixture.runtime);
            unavailable(() -> store.insert(proposal));
            unavailable(() -> store.insertEvaluated(evaluation, BulkPreviewProjection.unavailable(evaluation)));
            var captures = new AtomicInteger();
            var projections = new AtomicInteger();
            var transaction = new TransactionTemplate(fixture.runtime.transactionManager());
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> unavailable(() -> store.captureAndInsertEvaluated(proposal,
                    (connection, remaining) -> {
                        captures.incrementAndGet();
                        fixture.runtimeSql.update("update occupancy_domain_witness set writes=writes+1 where id=1");
                        return evaluation;
                    }, captured -> {
                        projections.incrementAndGet();
                        return BulkPreviewProjection.unavailable(captured);
                    }, () -> Duration.ofSeconds(5))));
            assertThat(captures.get()).isZero();
            assertThat(projections.get()).isZero();
            unchanged(fixture, before);
            validatePositive(fixture);
            fixture.catalogCertificate("public-async", "PUBLIC_ASYNC_REJECTED_BEFORE_CALLBACK");
            fixture.assertionsComplete();
        }
    }

    @Test
    void completeAclDriftIsRejectedWithoutRegrantOrHealing() throws Exception {
        try (var fixture = populated("catalog-acl-no-healing")) {
            committedDrift(fixture, "runtime-column-grant",
                    "grant update(occupancy_sequence) on praxis_bulk.praxis_bulk_capacity_slot to bulk_runtime_test",
                    "revoke update(occupancy_sequence) on praxis_bulk.praxis_bulk_capacity_slot from bulk_runtime_test",
                    () -> count(fixture, """
                            select count(*) from pg_attribute a cross join lateral aclexplode(a.attacl) x
                            where a.attrelid='praxis_bulk.praxis_bulk_capacity_slot'::regclass
                              and a.attname='occupancy_sequence' and x.grantee='bulk_runtime_test'::regrole
                              and x.privilege_type='UPDATE'
                            """) == 1, "V19 runtime table grants differ", true);
            committedDrift(fixture, "public-function-grant", "grant execute on function " + CLAIM + " to public",
                    "revoke execute on function " + CLAIM + " from public",
                    () -> functionAcl(fixture, "x.grantee=0 and x.privilege_type='EXECUTE'") == 1,
                    "V19 function ACL differs: claim_capacity_execution", true);
            committedDrift(fixture, "runtime-function-grant-option",
                    "grant execute on function " + CLAIM + " to bulk_runtime_test with grant option",
                    "revoke grant option for execute on function " + CLAIM + " from bulk_runtime_test",
                    () -> functionAcl(fixture,
                            "x.grantee='bulk_runtime_test'::regrole and x.privilege_type='EXECUTE' and x.is_grantable") == 1,
                    "V19 grant option forbidden", true);
            fixture.assertionsComplete();
        }
    }

    @Test
    void completeDefinerOwnerAndMembershipDriftAreRejectedWithoutRepair() throws Exception {
        try (var fixture = populated("catalog-owner-membership")) {
            committedDrift(fixture, "definer-owner", "alter function " + MARKER + " owner to postgres",
                    "alter function " + MARKER + " owner to praxis_bulk_capacity_owner",
                    () -> count(fixture, "select count(*) from pg_proc where oid='" + MARKER
                            + "'::regprocedure and proowner='postgres'::regrole") == 1,
                    "V19 function attributes/source differ: lock_capacity_marker", true);
            committedDrift(fixture, "definer-membership", "grant praxis_bulk_capacity_owner to bulk_runtime_test",
                    "revoke praxis_bulk_capacity_owner from bulk_runtime_test",
                    () -> count(fixture, """
                            select count(*) from pg_auth_members where roleid='praxis_bulk_capacity_owner'::regrole
                              and member='bulk_runtime_test'::regrole
                            """) == 1, "V19 owner membership forbidden", true);
            fixture.assertionsComplete();
        }
    }

    @Test
    void completeDefinerBodyAndSearchPathDriftAreRejectedBeforeRuntimeWork() throws Exception {
        try (var fixture = populated("catalog-function-source")) {
            String canonicalDefinition = fixture.observer.queryForObject(
                    "select pg_get_functiondef(?::regprocedure)", String.class, MARKER);
            String canonicalBody = fixture.observer.queryForObject(
                    "select prosrc from pg_proc where oid=?::regprocedure", String.class, MARKER);
            committedDrift(fixture, "definer-body", """
                    create or replace function praxis_bulk.lock_capacity_marker() returns text
                    language plpgsql security definer set search_path=pg_catalog,pg_temp
                    as $tamper$ begin return 'ACTIVE'; end; $tamper$
                    """, canonicalDefinition,
                    () -> !canonicalBody.equals(fixture.observer.queryForObject(
                            "select prosrc from pg_proc where oid=?::regprocedure", String.class, MARKER)),
                    "V19 function attributes/source differ: lock_capacity_marker", true);
            committedDrift(fixture, "definer-search-path", "alter function " + MARKER + " set search_path=public",
                    canonicalDefinition,
                    () -> count(fixture, "select count(*) from pg_proc where oid='" + MARKER
                            + "'::regprocedure and proconfig=array['search_path=public']::text[]") == 1,
                    "V19 function attributes/source differ: lock_capacity_marker", true);
            fixture.assertionsComplete();
        }
    }

    @Test
    void completeStatementTriggerDriftIsRejectedWithoutRecreation() throws Exception {
        try (var fixture = populated("catalog-statement-trigger")) {
            committedDrift(fixture, "statement-trigger-disabled", """
                    alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_capacity_statement
                    """, """
                    alter table praxis_bulk.praxis_bulk_execution enable trigger praxis_bulk_execution_capacity_statement
                    """, () -> count(fixture, """
                            select count(*) from pg_trigger
                            where tgrelid='praxis_bulk.praxis_bulk_execution'::regclass
                              and tgname='praxis_bulk_execution_capacity_statement' and tgenabled='D'
                              and tgtype=22 and tgfoid='praxis_bulk.capacity_marker_statement_fence()'::regprocedure
                            """) == 1, "V19 trigger differs: praxis_bulk_execution_capacity_statement", true);
            fixture.assertionsComplete();
        }
    }

    @Test
    void completeConstraintDefinitionDriftFailsEvenWhenNamesAndRowsRemainValid() throws Exception {
        try (var fixture = populated("catalog-constraint-definitions")) {
            String slot = "praxis_bulk_capacity_slot";
            String shape = "capacity_slot_shape_check";
            committedDrift(fixture, "slot-check-true", replacement(slot, shape, "CHECK (true)"),
                    replacement(slot, shape, BulkCapacityOccupancyCatalog.constraints(slot).get(shape)),
                    () -> count(fixture, """
                            select count(*) from pg_constraint where conrelid='praxis_bulk.praxis_bulk_capacity_slot'::regclass
                              and conname='capacity_slot_shape_check' and pg_get_constraintdef(oid)='CHECK (true)'
                              and convalidated
                            """) == 1, "V19 constraints differ: praxis_bulk_capacity_slot", false);
            String history = "praxis_bulk_capacity_occupation";
            String fk = "praxis_bulk_capacity_occupation_execution_id_fkey";
            committedDrift(fixture, "history-fk-cascade", replacement(history, fk,
                    "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id) ON DELETE CASCADE"),
                    replacement(history, fk, BulkCapacityOccupancyCatalog.constraints(history).get(fk)),
                    () -> count(fixture, """
                            select count(*) from pg_constraint
                            where conrelid='praxis_bulk.praxis_bulk_capacity_occupation'::regclass
                              and conname='praxis_bulk_capacity_occupation_execution_id_fkey'
                              and confdeltype='c' and convalidated
                            """) == 1, "V19 constraints differ: praxis_bulk_capacity_occupation", false);
            fixture.assertionsComplete();
        }
    }

    private static BulkCapacityOccupancyPostgresFixture populated(String caseId) throws Exception {
        var fixture = new BulkCapacityOccupancyPostgresFixture(caseId);
        try {
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var reservation = fixture.enqueue(fixture.persist(), "catalog-queued-key", queue);
            assertThat(reservation.execution().status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
            validatePositive(fixture);
            return fixture;
        } catch (Exception | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    /** DDL is committed before validators open their independent connections. */
    private static void committedDrift(BulkCapacityOccupancyPostgresFixture fixture, String dimension,
            String mutation, String restoration, BooleanSupplier driftProbe, String canonicalFailure,
            boolean liveBoundary) throws Exception {
        var before = snapshot(fixture);
        try {
            ownerDdl(fixture, mutation);
            assertThat(driftProbe.getAsBoolean()).as("committed catalog drift is visible").isTrue();
            fixture.catalogCertificate(dimension, "COMMITTED_DRIFT_OBSERVED");
            rejected(() -> {
                try (var connection = fixture.ownerSource.getConnection()) {
                    BulkCapacityOccupancyCatalog.validate(connection, BulkPostgresTestSupport.testRoleConfiguration(), true);
                }
            }, canonicalFailure);
            rejected(() -> BulkExecutionMigrator.validate(fixture.ownerSource,
                    BulkPostgresTestSupport.testRoleConfiguration()), null);
            rejected(() -> BulkExecutionMigrator.migrate(fixture.ownerSource, Map.of(NAMESPACE, DEPLOYMENT),
                    BulkPostgresTestSupport.testRoleConfiguration()), null);
            if (liveBoundary) {
                var callbacks = new AtomicInteger();
                rejected(() -> new TransactionTemplate(fixture.runtime.transactionManager()).executeWithoutResult(status ->
                        fixture.runtime.withConnection(connection -> {
                            callbacks.incrementAndGet();
                            fixture.runtimeSql.update("update occupancy_domain_witness set writes=writes+1 where id=1");
                            return null;
                        })), null);
                assertThat(callbacks.get()).isZero();
            }
            assertThat(driftProbe.getAsBoolean()).as("failed validation and migration do not heal drift").isTrue();
            unchanged(fixture, before);
            fixture.catalogCertificate(dimension, "VALIDATION_REJECTED_NO_HEALING");
        } finally {
            ownerDdl(fixture, restoration);
            validatePositive(fixture);
            assertThat(driftProbe.getAsBoolean()).as("exact restoration removes drift").isFalse();
            unchanged(fixture, before);
            fixture.catalogCertificate(dimension, "RESTORED_EXACT_CATALOG");
        }
    }

    private static void validatePositive(BulkCapacityOccupancyPostgresFixture fixture) throws Exception {
        try (var connection = fixture.ownerSource.getConnection()) {
            BulkCapacityOccupancyCatalog.validate(connection, BulkPostgresTestSupport.testRoleConfiguration(), true);
        }
        BulkExecutionMigrator.validate(fixture.ownerSource, BulkPostgresTestSupport.testRoleConfiguration());
        assertThat(BulkExecutionMigrator.migrate(fixture.ownerSource, Map.of(NAMESPACE, DEPLOYMENT),
                BulkPostgresTestSupport.testRoleConfiguration())).isZero();
    }

    private static void ownerDdl(BulkCapacityOccupancyPostgresFixture fixture, String sql) throws Exception {
        try (Connection connection = fixture.ownerSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("set local lock_timeout='1s'");
                statement.execute("set local statement_timeout='3s'");
                statement.execute(sql);
                connection.commit();
            } catch (Exception | Error failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String replacement(String table, String name, String definition) {
        if (definition == null) throw new IllegalArgumentException("Missing canonical constraint definition");
        return "alter table praxis_bulk." + table + " drop constraint " + name
                + "; alter table praxis_bulk." + table + " add constraint " + name + " " + definition;
    }

    private static long count(BulkCapacityOccupancyPostgresFixture fixture, String sql) {
        return fixture.observer.queryForObject(sql, Long.class);
    }

    private static long functionAcl(BulkCapacityOccupancyPostgresFixture fixture, String predicate) {
        return count(fixture, "select count(*) from pg_proc p cross join lateral aclexplode(p.proacl) x where p.oid='"
                + CLAIM + "'::regprocedure and " + predicate);
    }

    /** Private in-memory full-row snapshots; protected values never enter assertions or manifests. */
    private static Map<String, List<String>> snapshot(BulkCapacityOccupancyPostgresFixture fixture) {
        var snapshot = new LinkedHashMap<String, List<String>>();
        var tables = fixture.observer.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk' and c.relkind='r' order by c.relname
                """, String.class);
        assertThat(tables).contains(BulkExecutionMigrator.HISTORY_TABLE,
                "praxis_bulk_capacity_occupancy_bootstrap", "praxis_bulk_capacity_occupation");
        for (String table : tables) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalStateException("Unsafe test relation");
            snapshot.put(table, fixture.observer.queryForList(
                    "select to_jsonb(t)::text from praxis_bulk." + table + " t order by 1", String.class));
        }
        snapshot.put("domain-witness", fixture.observer.queryForList(
                "select to_jsonb(t)::text from occupancy_domain_witness t order by 1", String.class));
        return snapshot;
    }

    private static void unchanged(BulkCapacityOccupancyPostgresFixture fixture, Map<String, List<String>> before) {
        var after = snapshot(fixture);
        assertThat(before.keySet().equals(after.keySet())).as("business relation set remains unchanged").isTrue();
        for (var entry : before.entrySet())
            assertThat(entry.getValue().equals(after.get(entry.getKey())))
                    .as("all columns remain unchanged in %s", entry.getKey()).isTrue();
        assertThat(fixture.observer.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_capacity_occupancy_bootstrap where bootstrap_version=19
                """, String.class)).isEqualTo("COMPLETE");
    }

    private static void unavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BulkProposalStorageException.class,
                failure -> assertThat(failure.reason()).isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE));
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    /** A timeout, deadlock or driver failure never counts as a rejected catalog. */
    private static void rejected(CheckedAction action, String expectedMessage) throws Exception {
        Throwable failure = null;
        try { action.run(); }
        catch (Exception caught) { failure = caught; }
        assertThat(failure != null).as("catalog boundary rejects drift").isTrue();
        boolean canonical = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                throw new AssertionError("Catalog rejection was a SQL failure, state="
                        + (state != null && state.matches("[0-9A-Z]{5}") ? state : "UNAVAILABLE"));
            }
            if (cause instanceof IllegalStateException && (expectedMessage == null
                    || cause.getMessage() != null && cause.getMessage().contains(expectedMessage))) canonical = true;
        }
        assertThat(canonical).as("rejection reaches the canonical integrity gate").isTrue();
    }
}
