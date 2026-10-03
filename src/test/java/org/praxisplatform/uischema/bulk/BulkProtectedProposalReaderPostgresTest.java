package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.*;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.*;

/** Actual PostgreSQL roles and two physical connections; no H2 fallback. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkProtectedProposalReaderPostgresTest {
    private EmbeddedPostgres postgres;
    private DataSource owner;
    private DataSource runtime;
    private JdbcTemplate sql;
    private TransactionTemplate writer;
    private JdbcBulkProposalStore store;
    private BulkProtectedProposalReader reader;

    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true)
                .setRegisterShutdownHook(false).start();
        owner = postgres.getPostgresDatabase();
        runtime = BulkPostgresTestSupport.runtimeDataSource(postgres);
        sql = new JdbcTemplate(owner);
        var manager = new DataSourceTransactionManager(runtime);
        writer = new TransactionTemplate(manager);
        var infrastructure = new BulkExecutionInfrastructure(runtime, manager,
                CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration());
        store = new JdbcBulkProposalStore(infrastructure);
        reader = new BulkProtectedProposalReader(infrastructure);
    }

    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach void reset() {
        sql.execute("drop schema if exists praxis_bulk cascade");
        assertThat(BulkPostgresTestSupport.migrate(owner, CONTEXT.namespaceId())).isEqualTo(16);
        BulkPostgresTestSupport.ready(owner, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
    }

    @Test void observesProtectedPairOrProposalOnlyWithoutCrossScopeDisclosure() throws Exception {
        var evaluated = evaluation(proposal());
        var only = proposal();
        writer.executeWithoutResult(status -> {
            store.insertEvaluated(evaluated, preview(evaluated));
            store.insert(only);
        });
        var pair = reader.read(CONTEXT, evaluated.proposal().id());
        assertThat(pair.kind()).isEqualTo(BulkProtectedProposalReader.Kind.EVALUATED);
        assertThat(pair.proposal().snapshot().fingerprint())
                .isEqualTo(evaluated.proposal().snapshot().fingerprint());
        assertThat(pair.evaluation().fingerprint()).isEqualTo(evaluated.fingerprint());
        assertThat(pair.toString()).doesNotContain("protected-customer-value", "dependentRevision");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(pair))
                .doesNotContain("protected-customer-value", "dependentRevision", "facts", "plan");
        var pending = reader.read(CONTEXT, only.id());
        assertThat(pending.kind()).isEqualTo(BulkProtectedProposalReader.Kind.NOT_EVALUATED);
        assertThat(pending.evaluation()).isNull();
        assertThat(reader.read(CONTEXT, UUID.randomUUID()).kind())
                .isEqualTo(BulkProtectedProposalReader.Kind.ABSENT);
        var otherSubject = new BulkFingerprintContext(CONTEXT.namespaceId(), "other-subject",
                CONTEXT.resourceKey(), CONTEXT.operationRef(), CONTEXT.schemaRevision(),
                CONTEXT.atomicity());
        var otherOperation = new BulkFingerprintContext(CONTEXT.namespaceId(), CONTEXT.subjectId(),
                CONTEXT.resourceKey(), new CanonicalOperationRef("admin", "other-operation",
                "/employees/bulk", "PATCH"), CONTEXT.schemaRevision(), CONTEXT.atomicity());
        assertThat(reader.read(otherSubject, evaluated.proposal().id()).kind())
                .isEqualTo(BulkProtectedProposalReader.Kind.ABSENT);
        assertThat(reader.read(otherOperation, evaluated.proposal().id()).kind())
                .isEqualTo(BulkProtectedProposalReader.Kind.ABSENT);
    }

    @Test void corruptProtectedBlobsFailClosedWithoutPayloadInError() {
        var evaluated = evaluation(proposal());
        writer.executeWithoutResult(status -> store.insertEvaluated(evaluated, preview(evaluated)));
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_evaluation set payload=? where proposal_id=?",
                    bytes("protected-customer-value-corrupt"), evaluated.proposal().id());
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_evaluation enable trigger user");
        }
        assertThatThrownBy(() -> reader.read(CONTEXT, evaluated.proposal().id()))
                .isInstanceOf(BulkProposalStorageException.class)
                .satisfies(error -> {
                    assertThat(((BulkProposalStorageException) error).reason())
                            .isEqualTo(BulkProposalStorageException.Reason.CORRUPT);
                    assertThat(error.toString()).doesNotContain("protected-customer-value");
                });
        sql.execute("alter table praxis_bulk.praxis_bulk_proposal disable trigger user");
        try {
            sql.update("update praxis_bulk.praxis_bulk_proposal set payload=? where proposal_id=?",
                    bytes("protected-customer-value-corrupt"), evaluated.proposal().id());
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_proposal enable trigger user");
        }
        assertThatThrownBy(() -> reader.read(CONTEXT, evaluated.proposal().id()))
                .isInstanceOf(BulkProposalStorageException.class)
                .satisfies(error -> assertThat(((BulkProposalStorageException) error).reason())
                        .isEqualTo(BulkProposalStorageException.Reason.CORRUPT));
    }

    @Test void missingRuntimeReadGrantIsUnavailableRatherThanCorrupt() {
        var evaluated = evaluation(proposal());
        writer.executeWithoutResult(status -> store.insertEvaluated(evaluated, preview(evaluated)));
        sql.execute("revoke select on praxis_bulk.praxis_bulk_proposal from bulk_runtime_test");
        assertThatThrownBy(() -> reader.read(CONTEXT, evaluated.proposal().id()))
                .isInstanceOf(BulkProposalStorageException.class)
                .satisfies(error -> assertThat(((BulkProposalStorageException) error).reason())
                        .isEqualTo(BulkProposalStorageException.Reason.UNAVAILABLE));
    }

    @Test void expiryBetweenSelectsKeepsOldSnapshotAndNextReadSeesAbsence() throws Exception {
        var evaluated = evaluation(proposal());
        writer.executeWithoutResult(status -> store.insertEvaluated(evaluated, preview(evaluated)));
        var paused = new BulkReadPauseDataSource(runtime,
                "from praxis_bulk.praxis_bulk_proposal");
        var manager = new DataSourceTransactionManager(paused);
        var pausedReader = new BulkProtectedProposalReader(new BulkExecutionInfrastructure(paused,
                manager, CONTEXT.namespaceId(), BulkPostgresTestSupport.DEPLOYMENT_ID,
                BulkPostgresTestSupport.testRoleConfiguration()));
        try (var workers = Executors.newSingleThreadExecutor()) {
            var old = workers.submit(() -> {
                paused.arm(Thread.currentThread());
                return pausedReader.read(CONTEXT, evaluated.proposal().id());
            });
            try {
                assertThat(paused.awaitObservation(5, TimeUnit.SECONDS)).isTrue();
                sql.execute("grant praxis_bulk_retention_executor to postgres");
                var ownerTx = new TransactionTemplate(new DataSourceTransactionManager(owner));
                Boolean expired = ownerTx.execute(status -> {
                    sql.execute("set local role praxis_bulk_retention_executor");
                    return sql.queryForObject("select praxis_bulk.expire_unconsumed_proposal(?)",
                            Boolean.class, evaluated.proposal().id());
                });
                assertThat(expired).isTrue();
                paused.release();
                assertThat(old.get(5, TimeUnit.SECONDS).kind())
                        .isEqualTo(BulkProtectedProposalReader.Kind.EVALUATED);
                sql.execute("revoke praxis_bulk_retention_executor from postgres");
                assertThat(reader.read(CONTEXT, evaluated.proposal().id()).kind())
                        .isEqualTo(BulkProtectedProposalReader.Kind.ABSENT);
            } finally {
                paused.release();
                if (Boolean.TRUE.equals(sql.queryForObject(
                        "select pg_has_role('postgres', 'praxis_bulk_retention_executor', 'member')",
                        Boolean.class)))
                    sql.execute("revoke praxis_bulk_retention_executor from postgres");
            }
        }
    }
}
