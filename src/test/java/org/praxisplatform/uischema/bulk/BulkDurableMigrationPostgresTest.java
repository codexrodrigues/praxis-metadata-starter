package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drift must be rejected even when Flyway history and migration checksums remain intact. */
class BulkDurableMigrationPostgresTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String EVALUATION_FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final BulkFingerprintContext CONTEXT = new BulkFingerprintContext(
            "tenant:prod:payroll", "operator", "employees",
            new CanonicalOperationRef("admin", "employee-bulk-approve", "/employees/bulk/approve", "POST"),
            "schema-r1", ActionCollectionAtomicity.PER_ITEM);

    @Test
    void validatesPhysicalDurabilityAndBindingInsteadOfTrustingMigrationHistory() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            for (String mutation : List.of(
                    "alter table praxis_bulk.praxis_bulk_item_receipt set unlogged",
                    "alter table praxis_bulk.praxis_bulk_admission set unlogged",
                    "alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_binding",
                    "alter table praxis_bulk.praxis_bulk_execution disable trigger praxis_bulk_execution_protect_terminal_reason",
                    "alter table praxis_bulk.praxis_bulk_item_receipt disable trigger praxis_bulk_item_receipt_reject_mutation",
                    "alter table praxis_bulk.praxis_bulk_admission disable trigger praxis_bulk_admission_reject_mutation",
                    "alter table praxis_bulk.praxis_bulk_execution alter column owner_epoch drop not null",
                    "alter table praxis_bulk.praxis_bulk_admission alter column reason_code drop not null",
                    "alter table praxis_bulk.praxis_bulk_execution alter column next_ordinal set default 0",
                    "alter table praxis_bulk.praxis_bulk_execution alter column terminal_reason_code set default 'DEADLINE_EXCEEDED'",
                    "alter table praxis_bulk.praxis_bulk_execution enable row level security",
                    "alter table praxis_bulk.praxis_bulk_admission enable row level security",
                    "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_epoch_check; "
                            + "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_epoch_check check (owner_epoch >= 0)",
                    "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_proposal_key; "
                            + "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_proposal_key unique (proposal_id) deferrable initially deferred",
                    "alter table praxis_bulk.praxis_bulk_item_receipt drop constraint praxis_bulk_item_receipt_execution_fkey; "
                            + "alter table praxis_bulk.praxis_bulk_item_receipt add constraint praxis_bulk_item_receipt_execution_fkey "
                            + "foreign key (execution_id) references praxis_bulk.praxis_bulk_execution(execution_id) not valid",
                    "alter table praxis_bulk.praxis_bulk_admission drop constraint praxis_bulk_admission_execution_fkey; "
                            + "alter table praxis_bulk.praxis_bulk_admission add constraint praxis_bulk_admission_execution_fkey "
                            + "foreign key (execution_id) references praxis_bulk.praxis_bulk_execution(execution_id) not valid",
                    "alter table praxis_bulk.praxis_bulk_admission drop constraint praxis_bulk_admission_target_key",
                    "alter table praxis_bulk.praxis_bulk_execution drop constraint praxis_bulk_execution_terminal_reason_check; "
                            + "alter table praxis_bulk.praxis_bulk_execution add constraint praxis_bulk_execution_terminal_reason_check "
                            + "check (terminal_reason_code is null or status='STOPPED')",
                    "alter table praxis_bulk.praxis_bulk_admission drop constraint praxis_bulk_admission_outcome_reason_check; "
                            + "alter table praxis_bulk.praxis_bulk_admission add constraint praxis_bulk_admission_outcome_reason_check "
                            + "check (outcome in ('DENIED','INVALID','CONFLICT'))",
                    "alter function praxis_bulk.protect_praxis_bulk_execution_binding() security definer",
                    "alter function praxis_bulk.reject_praxis_bulk_admission_mutation() security definer",
                    "create or replace function praxis_bulk.reject_praxis_bulk_item_receipt_mutation() "
                            + "returns trigger language plpgsql as $$begin return new; end;$$",
                    "create or replace function praxis_bulk.reject_praxis_bulk_admission_mutation() "
                            + "returns trigger language plpgsql as $$begin return new; end;$$",
                    "create function praxis_bulk.protect_praxis_bulk_execution_binding(integer) returns integer language sql as 'select $1'",
                    "create trigger praxis_bulk_execution_protect_binding before update on praxis_bulk.praxis_bulk_item_receipt "
                            + "for each row execute function praxis_bulk.protect_praxis_bulk_execution_binding()",
                    "create table praxis_bulk.unexpected(id integer)",
                    "create type praxis_bulk.unexpected as (id integer)",
                    "create unique index unexpected_execution_owner on praxis_bulk.praxis_bulk_execution(owner_id)",
                    "create index unexpected_expression on praxis_bulk.praxis_bulk_execution ((lower(owner_id)))",
                    "create aggregate praxis_bulk.unexpected_sum(integer) (sfunc=int4pl, stype=integer)",
                    "create rule unexpected_update as on update to praxis_bulk.praxis_bulk_execution do instead nothing",
                    "create policy unexpected_policy on praxis_bulk.praxis_bulk_execution using (true)",
                    "drop index praxis_bulk.praxis_bulk_schema_history_s_idx; "
                            + "create unique index praxis_bulk_schema_history_s_idx on praxis_bulk.praxis_bulk_execution(owner_id)",
                    "create unique index praxis_bulk_fk_bound_owner on praxis_bulk.praxis_bulk_execution(owner_id); "
                            + "create table public.praxis_bulk_external_ref(owner_id text references praxis_bulk.praxis_bulk_execution(owner_id))")) {
                assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(4);
                sql.execute(mutation);
                assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                        .as("reject drift: %s", mutation).isInstanceOf(IllegalStateException.class);
                sql.execute("drop schema praxis_bulk cascade");
            }
        }
    }

    @Test
    void validationIsStableWhenOperationalDatasourceDefaultsToOwnedSchema() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var url = postgres.getJdbcUrl("postgres", "postgres") + "&currentSchema=praxis_bulk";
            try (var physicalConnection = java.sql.DriverManager.getConnection(url, "postgres", "postgres");
                    var scopedDataSource = new SingleConnectionDataSource(physicalConnection, true)) {
                assertThat(BulkExecutionMigrator.migrate(scopedDataSource)).isEqualTo(4);
                BulkExecutionMigrator.validate(scopedDataSource);
                try (var statement = physicalConnection.createStatement();
                        var result = statement.executeQuery("select current_schema(), current_setting('search_path')")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("praxis_bulk");
                    assertThat(result.getString(2)).isEqualTo("praxis_bulk");
                }
            }
        }
    }

    @Test
    void v3StopUpgradesWithoutInventingItsCauseAndSurvivesIndependentReadback() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            migrateToV3(dataSource);
            UUID executionId = insertExecution(sql, "STOPPED", 0, true).id();
            UUID rolledBackAttempt = UUID.randomUUID();
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set active_attempt_id=?, active_attempt_ordinal=0,
                        active_target_digest=?, active_attempt_epoch=1
                    where execution_id=?
                    """, rolledBackAttempt, "sha256:" + "c".repeat(64), executionId);
            int[] checksums = {1, 2, 3};
            for (int version : checksums) {
                checksums[version - 1] = sql.queryForObject(
                        "select checksum from praxis_bulk.praxis_bulk_schema_history where version=?",
                        Integer.class, Integer.toString(version));
            }

            assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(1);
            for (int version = 1; version <= 3; version++) {
                assertThat(sql.queryForObject(
                        "select checksum from praxis_bulk.praxis_bulk_schema_history where version=?",
                        Integer.class, Integer.toString(version))).isEqualTo(checksums[version - 1]);
            }
            var independent = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres"));
            assertThat(independent.queryForObject("""
                    select terminal_reason_code from praxis_bulk.praxis_bulk_execution where execution_id=?
                    """, String.class, executionId)).isEqualTo("LEGACY_REASON_NOT_RECORDED");
            assertThat(independent.queryForObject("""
                    select count(*) from praxis_bulk.praxis_bulk_execution
                    where execution_id=? and active_attempt_id is null and active_attempt_ordinal is null
                      and active_target_digest is null and active_attempt_epoch is null
                    """, Integer.class, executionId)).isEqualTo(1);
            assertThat(independent.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_admission", Integer.class)).isZero();
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution set terminal_reason_code=null where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            BulkExecutionMigrator.validate(dataSource);
            assertThat(BulkExecutionMigrator.migrate(dataSource)).isZero();
        }
    }

    @Test
    void v4AdmissionIsAppendOnlyScopedAndRestrictedToClosedOutcomeReasons() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(4);
            var fixture = insertExecution(sql, "RUNNING", 0, false);
            UUID executionId = fixture.id();
            UUID attemptId = UUID.randomUUID();
            insertAdmission(sql, executionId, attemptId, 0, fixture.digest0(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            assertThat(sql.queryForObject("select reason_code from praxis_bulk.praxis_bulk_admission "
                    + "where execution_id=? and unit_ordinal=0", String.class, executionId))
                    .isEqualTo("TARGET_VERSION_CONFLICT");
            assertThatThrownBy(() -> insertAdmission(sql, executionId, UUID.randomUUID(), 0,
                    "sha256:" + "d".repeat(64), "CONFLICT", "TARGET_STATE_CONFLICT"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> insertAdmission(sql, UUID.randomUUID(), UUID.randomUUID(), 1,
                    "sha256:" + "e".repeat(64), "DENIED", "TARGET_DENIED"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> insertAdmission(sql, executionId, UUID.randomUUID(), 1,
                    "sha256:" + "e".repeat(64), "DENIED", "UNAPPROVED_CODE"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> insertAdmission(sql, executionId, UUID.randomUUID(), 1,
                    "sha256:" + "e".repeat(64), "CONFLICT", "TARGET_DENIED"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sql.execute("update praxis_bulk.praxis_bulk_admission set outcome='DENIED'"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sql.execute("delete from praxis_bulk.praxis_bulk_admission"))
                    .isInstanceOf(RuntimeException.class);
            // The unchanged progress is deliberately rejected by physical validation: a
            // separate admission must be acknowledged before the next ordinal is exposed.
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.update("update praxis_bulk.praxis_bulk_execution set next_ordinal=1 where execution_id=?", executionId);
            BulkExecutionMigrator.validate(dataSource);
            insertAdmission(sql, executionId, UUID.randomUUID(), 1,
                    fixture.digest1(), "DENIED", "TARGET_DENIED");
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set next_ordinal=2, status='COMPLETED', terminal_at=clock_timestamp()
                    where execution_id=?
                    """, executionId);
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.update("update praxis_bulk.praxis_bulk_execution set status='COMPLETED_WITH_ERRORS' where execution_id=?",
                    executionId);
            BulkExecutionMigrator.validate(dataSource);
            assertThat(sql.queryForObject("select status from praxis_bulk.praxis_bulk_execution "
                    + "where execution_id=?", String.class, executionId)).isEqualTo("COMPLETED_WITH_ERRORS");

            sql.execute("create role admission_runtime login");
            sql.execute("grant usage on schema praxis_bulk to admission_runtime");
            sql.execute("grant select, insert on praxis_bulk.praxis_bulk_admission to admission_runtime");
            BulkExecutionMigrator.validate(dataSource);
            var runtime = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl("admission_runtime", "postgres"), "admission_runtime", ""));
            assertThat(runtime.queryForObject("select count(*) from praxis_bulk.praxis_bulk_admission",
                    Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> runtime.execute("update praxis_bulk.praxis_bulk_admission set reason_code='TARGET_DENIED'"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> runtime.execute("delete from praxis_bulk.praxis_bulk_admission"))
                    .isInstanceOf(RuntimeException.class);
            sql.execute("grant update on praxis_bulk.praxis_bulk_admission to admission_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.execute("revoke update on praxis_bulk.praxis_bulk_admission from admission_runtime");
            BulkExecutionMigrator.validate(dataSource);
            sql.execute("grant update(reason_code) on praxis_bulk.praxis_bulk_admission to admission_runtime");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void v4PhysicalReadbackRejectsReceiptAdmissionOverlapAndPrefixHoles() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(4);
            var fixture = insertExecution(sql, "RUNNING", 0, false);
            UUID executionId = fixture.id();
            insertAdmission(sql, executionId, UUID.randomUUID(), 0, fixture.digest0(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            sql.update("update praxis_bulk.praxis_bulk_execution set next_ordinal=1 where execution_id=?", executionId);
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_item_receipt
                    (execution_id, unit_ordinal, target_digest, expected_version, attempt_id,
                     owner_epoch, outcome, confirmed_at)
                    values (?, 0, ?, 'v1', ?, 1, 'CONFIRMED', clock_timestamp())
                    """, executionId, fixture.digest0(), UUID.randomUUID());
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);

            sql.execute("drop schema praxis_bulk cascade");
            assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(4);
            fixture = insertExecution(sql, "RUNNING", 0, false);
            executionId = fixture.id();
            insertAdmission(sql, executionId, UUID.randomUUID(), 1, fixture.digest1(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            UUID finalExecutionId = executionId;
            sql.update("update praxis_bulk.praxis_bulk_execution set next_ordinal=1 where execution_id=?", finalExecutionId);
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);

            sql.execute("drop schema praxis_bulk cascade");
            assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(4);
            fixture = insertExecution(sql, "RUNNING", 0, false);
            executionId = fixture.id();
            insertAdmission(sql, executionId, UUID.randomUUID(), 0, fixture.digest0(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            sql.update("update praxis_bulk.praxis_bulk_execution set next_ordinal=1 where execution_id=?", executionId);
            BulkExecutionMigrator.validate(dataSource);
            sql.update("update praxis_bulk.praxis_bulk_execution set status='STOPPED', "
                    + "terminal_at=clock_timestamp(), terminal_reason_code='POLICY_BLOCKED' where execution_id=?", executionId);
            // Append-only evidence cannot be patched. A forged matching prefix is still rejected.
            sql.execute("alter table praxis_bulk.praxis_bulk_admission disable trigger praxis_bulk_admission_reject_mutation");
            sql.update("update praxis_bulk.praxis_bulk_admission set expected_version='forged' where execution_id=?", executionId);
            sql.execute("alter table praxis_bulk.praxis_bulk_admission enable trigger praxis_bulk_admission_reject_mutation");
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void pendingAckRequiresTheExactReceiptRatherThanAnAdmissionOrAnotherAttempt() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(4);
            var fixture = insertExecution(sql, "RUNNING", 0, false);
            UUID attemptId = UUID.randomUUID();
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_item_receipt
                    (execution_id, unit_ordinal, target_digest, expected_version, attempt_id,
                     owner_epoch, outcome, confirmed_at)
                    values (?, 0, ?, 'v1', ?, 1, 'CONFIRMED', clock_timestamp())
                    """, fixture.id(), fixture.digest0(), attemptId);
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='UNIT_COMMITTED_PENDING_ACK', active_attempt_id=?, active_attempt_ordinal=0,
                        active_target_digest=?, active_attempt_epoch=1
                    where execution_id=?
                    """, attemptId, fixture.digest0(), fixture.id());
            BulkExecutionMigrator.validate(dataSource);

            sql.update("update praxis_bulk.praxis_bulk_execution set active_attempt_id=? where execution_id=?",
                    UUID.randomUUID(), fixture.id());
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.update("update praxis_bulk.praxis_bulk_execution set active_attempt_id=? where execution_id=?",
                    attemptId, fixture.id());
            sql.update("update praxis_bulk.praxis_bulk_execution set active_target_digest=? where execution_id=?",
                    fixture.digest1(), fixture.id());
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
            sql.update("update praxis_bulk.praxis_bulk_execution set active_target_digest=? where execution_id=?",
                    fixture.digest0(), fixture.id());
            sql.update("update praxis_bulk.praxis_bulk_execution set owner_epoch=2, active_attempt_epoch=2 "
                    + "where execution_id=?", fixture.id());
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);

            sql.execute("drop schema praxis_bulk cascade");
            assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(4);
            fixture = insertExecution(sql, "RUNNING", 0, false);
            attemptId = UUID.randomUUID();
            insertAdmission(sql, fixture.id(), attemptId, 0, fixture.digest0(),
                    "CONFLICT", "TARGET_VERSION_CONFLICT");
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='UNIT_COMMITTED_PENDING_ACK', active_attempt_id=?, active_attempt_ordinal=0,
                        active_target_digest=?, active_attempt_epoch=1
                    where execution_id=?
                    """, attemptId, fixture.digest0(), fixture.id());
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(dataSource))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void newStopsRequireApprovedReasonAndCannotClaimLegacyProvenance() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var sql = new JdbcTemplate(dataSource);
            assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(4);
            UUID executionId = insertExecution(sql, "RUNNING", 0, false).id();
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='UNIT_IN_FLIGHT', active_attempt_id=?, active_attempt_ordinal=0,
                        active_target_digest=?, active_attempt_epoch=1
                    where execution_id=?
                    """, UUID.randomUUID(), "sha256:" + "c".repeat(64), executionId);
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp(),
                        terminal_reason_code='UNIT_ROLLED_BACK' where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='RUNNING', active_attempt_id=null, active_attempt_ordinal=null,
                        active_target_digest=null, active_attempt_epoch=null
                    where execution_id=?
                    """, executionId);
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp() where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp(),
                        terminal_reason_code='LEGACY_REASON_NOT_RECORDED' where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp(),
                        terminal_reason_code='RAW_PROVIDER_EXCEPTION' where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set status='STOPPED', terminal_at=clock_timestamp(),
                        terminal_reason_code='POLICY_BLOCKED' where execution_id=?
                    """, executionId);
            var independent = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres"));
            assertThat(independent.queryForObject("select terminal_reason_code from "
                    + "praxis_bulk.praxis_bulk_execution where execution_id=?", String.class, executionId))
                    .isEqualTo("POLICY_BLOCKED");
            assertThatThrownBy(() -> sql.update("""
                    update praxis_bulk.praxis_bulk_execution
                    set terminal_reason_code='RECOVERY_STOPPED' where execution_id=?
                    """, executionId)).isInstanceOf(RuntimeException.class);
            BulkExecutionMigrator.validate(dataSource);
        }
    }

    private static void migrateToV3(javax.sql.DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target("3").load().migrate();
    }

    private static ExecutionFixture insertExecution(JdbcTemplate sql, String status, int nextOrdinal, boolean terminal) {
        var reader = new BulkProtocolReader<>(BulkIdentityCodecs.strings());
        var request = reader.<com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.JsonNode>readCommand(
                """
                {"executionMode":"SYNC","selection":{"mode":"EXPLICIT","targets":[
                  {"id":"1","expectedVersion":"v1"},{"id":"2","expectedVersion":"v2"}]},
                  "parameters":{"reason":"migration-fixture"}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                com.fasterxml.jackson.databind.JsonNode::deepCopy,
                com.fasterxml.jackson.databind.JsonNode::deepCopy);
        Instant created = Instant.now().minusSeconds(5);
        var snapshot = BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                com.fasterxml.jackson.databind.JsonNode::deepCopy,
                com.fasterxml.jackson.databind.JsonNode::deepCopy);
        var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(600), snapshot);
        var empty = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var evaluation = new BulkEvaluationSnapshot(proposal, created.plusSeconds(1), List.of(
                new BulkTargetEvidence<>(new BulkTarget<>("1", "v1"), "observed-v1", empty, empty,
                        BulkTargetEligibility.executable()),
                new BulkTargetEvidence<>(new BulkTarget<>("2", "v2"), "observed-v2", empty, empty,
                        BulkTargetEligibility.executable())),
                new BulkEvaluationGovernance("test-evaluator-r1", "test-grants-r1", List.of(
                        new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                                "resource:approve", "NEVER_APPLIED", "test-policy-r1", created.plusMillis(500)))));
        UUID proposalId = proposal.id();
        UUID executionId = UUID.randomUUID();
        sql.update("""
                insert into praxis_bulk.praxis_bulk_proposal
                (proposal_id, namespace_id, subject_id, resource_key, operation_id,
                 created_at, expires_at, fingerprint, payload)
                values (?, 'tenant:prod:payroll', 'operator', 'employees', 'employee-bulk-approve',
                        ?, ?, ?, ?)
                """, proposalId, java.sql.Timestamp.from(proposal.createdAt()),
                java.sql.Timestamp.from(proposal.expiresAt()), snapshot.fingerprint(),
                BulkSnapshotStorageCodec.encode(snapshot));
        sql.update("""
                insert into praxis_bulk.praxis_bulk_evaluation
                (proposal_id, input_fingerprint, evaluation_fingerprint, payload)
                values (?, ?, ?, ?)
                """, proposalId, snapshot.fingerprint(), evaluation.fingerprint(),
                BulkEvaluationStorageCodec.encode(evaluation));
        sql.update("""
                insert into praxis_bulk.praxis_bulk_execution
                (execution_id, proposal_id, namespace_id, subject_id, resource_key, operation_id,
                 idempotency_key_digest, reservation_fingerprint, input_fingerprint,
                 evaluation_fingerprint, structural_revision, owner_id, owner_epoch, status,
                 next_ordinal, target_count, deadline_at, created_at, updated_at, terminal_at)
                values (?, ?, 'tenant:prod:payroll', 'operator', 'employees', 'employee-bulk-approve',
                        ?, ?, ?, ?, 'structural-r1', 'owner', 1, ?, ?, 2,
                        clock_timestamp() + interval '30 seconds', clock_timestamp(), clock_timestamp(),
                        case when ? then clock_timestamp() else null end)
                """, executionId, proposalId, FINGERPRINT, EVALUATION_FINGERPRINT,
                snapshot.fingerprint(), evaluation.fingerprint(), status, nextOrdinal, terminal);
        return new ExecutionFixture(executionId,
                BulkExecutionMigrator.evidenceTargetDigest(evaluation, 0),
                BulkExecutionMigrator.evidenceTargetDigest(evaluation, 1));
    }

    private static void insertAdmission(JdbcTemplate sql, UUID executionId, UUID attemptId,
            int ordinal, String digest, String outcome, String reason) {
        sql.update("""
                insert into praxis_bulk.praxis_bulk_admission
                (execution_id, unit_ordinal, target_digest, expected_version, attempt_id,
                 owner_epoch, outcome, reason_code, recorded_at)
                values (?, ?, ?, ?, ?, 1, ?, ?, clock_timestamp())
                """, executionId, ordinal, digest, ordinal == 0 ? "v1" : "v2", attemptId, outcome, reason);
    }

    private record ExecutionFixture(UUID id, String digest0, String digest1) { }
}
