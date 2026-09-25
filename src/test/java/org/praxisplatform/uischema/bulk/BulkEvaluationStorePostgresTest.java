package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.*;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkEvaluationStorePostgresTest {
    private EmbeddedPostgres postgres;
    private DataSource dataSource;
    private JdbcTemplate sql;
    private TransactionTemplate tx;
    private JdbcBulkProposalStore store;
    @BeforeAll void start() throws Exception {
        postgres=EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
        dataSource=postgres.getPostgresDatabase();sql=new JdbcTemplate(dataSource);
        var manager=new DataSourceTransactionManager(dataSource);tx=new TransactionTemplate(manager);
        store=new JdbcBulkProposalStore(new BulkExecutionInfrastructure(dataSource,manager,CONTEXT.namespaceId()));
        sql.execute("create role evaluation_runtime login");
        System.out.println("Evaluation store PostgreSQL: "+sql.queryForObject("select version()",String.class));
    }
    @AfterAll void stop() throws Exception {if(postgres!=null)postgres.close();}
    @BeforeEach void reset(){sql.execute("drop schema if exists praxis_bulk cascade");}
    void migrate(){assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(4);}
    int count(String table){return sql.queryForObject("select count(*) from praxis_bulk."+table,Integer.class);}
    @Test void upgradePreservesV1PayloadAndHistoryWithoutFabricatingEvaluationOrExecution() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/praxis-bulk-migrations").schemas("praxis_bulk")
                .defaultSchema("praxis_bulk").table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true).target("1").load().migrate();
        var value=proposal();tx.executeWithoutResult(status->store.insert(value));
        var before=sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history where version='1'",Integer.class);
        assertThat(BulkExecutionMigrator.migrate(dataSource)).isEqualTo(3);
        assertThat(sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history where version='1'",Integer.class)).isEqualTo(before);
        var recovered=tx.execute(status->store.find(CONTEXT,value.id()).orElseThrow());
        assertThat(recovered.snapshot().fingerprint()).isEqualTo(value.snapshot().fingerprint());
        var evidence=tx.execute(status->store.findEvaluation(CONTEXT,value.id()));assertThat(evidence).isEmpty();
        assertThat(count("praxis_bulk_execution")).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
    }
    @Test void allModalitiesAndCodecsRecoverExactFactsAndPlansFromDatabase() {
        migrate();
        for(var mode:BulkMode.values()) {
            persist(evaluation(proposal(snapshot(mode,BulkIdentityCodecs.integers(),"42","1.0"))));
            persist(evaluation(proposal(snapshot(mode,BulkIdentityCodecs.longs(),"\"9223372036854775807\"","1.0"))));
            persist(evaluation(proposal(snapshot(mode,BulkIdentityCodecs.strings(),"\"101\"","1.0"))));
            persist(evaluation(proposal(snapshot(mode,BulkIdentityCodecs.uuids(),"\"123e4567-e89b-12d3-a456-426614174000\"","1.0"))));
        }
        assertThat(count("praxis_bulk_evaluation")).isEqualTo(12);
    }
    @Test void bothRowsAreInvisibleUntilCommitAndRollbackRemovesBoth() {
        migrate();var value=evaluation(proposal());
        tx.executeWithoutResult(status->{
            store.insertEvaluated(value);
            try(var connection=dataSource.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("select (select count(*) from praxis_bulk.praxis_bulk_proposal)+(select count(*) from praxis_bulk.praxis_bulk_evaluation)")){
                rows.next();assertThat(rows.getInt(1)).isZero();
            }catch(java.sql.SQLException error){throw new AssertionError(error);}
            assertThat(store.findEvaluation(CONTEXT,value.proposal().id())).isPresent();status.setRollbackOnly();
        });
        assertThat(count("praxis_bulk_proposal")).isZero();assertThat(count("praxis_bulk_evaluation")).isZero();
    }
    @Test void numericBoundaryValuesRemainRecoverableAfterCommit() {
        migrate();
        for (var number : List.of(new java.math.BigDecimal("10e256"),
                new java.math.BigDecimal("1"+"0".repeat(255)).scaleByPowerOfTen(256),
                new java.math.BigDecimal("9".repeat(200)+"e200"))) {
            var parameters = JSON.objectNode().put("amount", number);
            var request = new BulkCommandEvaluationRequest<com.fasterxml.jackson.databind.JsonNode,String,com.fasterxml.jackson.databind.JsonNode>(
                    BulkExecutionMode.SYNC, new BulkSelection<>(BulkSelectionMode.EXPLICIT,
                    List.of(new BulkTarget<>("1", "v1")), null, null), parameters);
            var input = proposal(BulkIntentSnapshot.command(CONTEXT, BulkIdentityCodecs.strings(), request,
                    com.fasterxml.jackson.databind.JsonNode::deepCopy, com.fasterxml.jackson.databind.JsonNode::deepCopy));
            var evidence = new BulkTargetEvidence<>(new BulkTarget<>("1", "v1"), "v1", parameters, parameters, BulkTargetEligibility.executable());
            var value = new BulkEvaluationSnapshot(input, input.createdAt(), List.of(evidence), governance());
            tx.executeWithoutResult(status -> store.insertEvaluated(value));
            var recovered = tx.execute(status -> store.findEvaluation(CONTEXT, input.id()).orElseThrow());
            assertThat(recovered.fingerprint()).isEqualTo(value.fingerprint());
            assertThat(recovered.proposal().snapshot().intent().at("/parameters/amount").decimalValue()).isEqualByComparingTo(number);
            assertThat(recovered.targets().getFirst().facts().get("amount").decimalValue()).isEqualByComparingTo(number);
            assertThat(recovered.targets().getFirst().plan().get("amount").decimalValue()).isEqualByComparingTo(number);
        }
        assertThat(count("praxis_bulk_proposal")).isEqualTo(3);
        assertThat(count("praxis_bulk_evaluation")).isEqualTo(3);
    }
    @Test void caughtCompanionFailureStillRollsBackItsInput() {
        migrate();var value=evaluation(proposal());
        sql.execute("create function public.fail_evaluation_fixture() returns trigger language plpgsql as $$ begin raise exception 'fixture failure'; end; $$");
        sql.execute("create trigger fail_evaluation_fixture before insert on praxis_bulk.praxis_bulk_evaluation for each row execute function public.fail_evaluation_fixture()");
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            assertThatThrownBy(()->store.insertEvaluated(value)).isInstanceOf(BulkProposalStorageException.class).hasNoCause();
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(count("praxis_bulk_proposal")).isZero();assertThat(count("praxis_bulk_evaluation")).isZero();
    }
    @Test void concurrentConflictingEvidenceHasExactlyOneCommittedPair() throws Exception {
        migrate();var first=evaluation(proposal());var old=first.targets().getFirst();
        var second=new BulkEvaluationSnapshot(first.proposal(),first.evaluatedAt(),List.of(new BulkTargetEvidence<>(old.target(),"other-version",old.facts(),old.plan(), BulkTargetEligibility.executable())), governance());
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->insertAfterBarrier(first,barrier));var b=executor.submit(()->insertAfterBarrier(second,barrier));
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("COMMITTED","CONFLICT");
        }
        var recovered=tx.execute(status->store.findEvaluation(CONTEXT,first.proposal().id()).orElseThrow());
        assertThat(recovered.fingerprint()).isIn(first.fingerprint(),second.fingerprint());
        assertThat(count("praxis_bulk_proposal")).isEqualTo(1);assertThat(count("praxis_bulk_evaluation")).isEqualTo(1);
    }
    @Test void unknownScopeCannotReadEvidenceAndCorruptionCannotFallbackToInput() {
        migrate();var value=evaluation(proposal());persist(value);
        var other=new BulkFingerprintContext(CONTEXT.namespaceId(),"other",CONTEXT.resourceKey(),CONTEXT.operationRef(),CONTEXT.schemaRevision(),CONTEXT.atomicity());
        var hidden=tx.execute(status->store.findEvaluation(other,value.proposal().id()));assertThat(hidden).isEmpty();
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_evaluation set payload=?",bytes("SECRET-corrupt"));
        assertThatThrownBy(()->tx.execute(status->store.findEvaluation(CONTEXT,value.proposal().id())))
                .isInstanceOfSatisfying(BulkProposalStorageException.class,error->assertThat(error.reason()).isEqualTo(BulkProposalStorageException.Reason.CORRUPT)).hasNoCause().hasMessageNotContaining("SECRET");
    }
    @Test void corruptedLinkIsRejectedInsteadOfDisguisedAsMissingEvidence() {
        migrate();var value=evaluation(proposal());persist(value);
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user");
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation drop constraint praxis_bulk_evaluation_proposal_input_fkey");
        sql.execute("update praxis_bulk.praxis_bulk_evaluation set input_fingerprint='sha256:"+"0".repeat(64)+"'");
        assertThatThrownBy(()->tx.execute(status->store.findEvaluation(CONTEXT,value.proposal().id())))
                .isInstanceOfSatisfying(BulkProposalStorageException.class,error->assertThat(error.reason()).isEqualTo(BulkProposalStorageException.Reason.CORRUPT)).hasNoCause();
    }
    @Test void compositeForeignKeyAndImmutableTriggerAreEnforced() {
        migrate();var value=evaluation(proposal());tx.executeWithoutResult(status->store.insert(value.proposal()));
        assertThatThrownBy(()->sql.update("insert into praxis_bulk.praxis_bulk_evaluation(proposal_id,input_fingerprint,evaluation_fingerprint,payload) values(?,?,?,?)",value.proposal().id(),"sha256:"+"0".repeat(64),value.fingerprint(),BulkEvaluationStorageCodec.encode(value))).isInstanceOf(RuntimeException.class);
        sql.update("insert into praxis_bulk.praxis_bulk_evaluation(proposal_id,input_fingerprint,evaluation_fingerprint,payload) values(?,?,?,?)",value.proposal().id(),value.proposal().snapshot().fingerprint(),value.fingerprint(),BulkEvaluationStorageCodec.encode(value));
        assertThatThrownBy(()->sql.execute("update praxis_bulk.praxis_bulk_evaluation set payload=payload")).isInstanceOf(RuntimeException.class);
    }
    @Test void physicalValidationRejectsDisabledTriggerUnloggedAndAlteredBinding() {
        for(String mutation:List.of("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user","alter table praxis_bulk.praxis_bulk_item_receipt set unlogged; alter table praxis_bulk.praxis_bulk_admission set unlogged; alter table praxis_bulk.praxis_bulk_execution set unlogged; alter table praxis_bulk.praxis_bulk_evaluation set unlogged; alter table praxis_bulk.praxis_bulk_proposal set unlogged")){
            migrate();sql.execute(mutation);assertThatThrownBy(()->BulkExecutionMigrator.validate(dataSource)).isInstanceOf(RuntimeException.class);reset();
        }
        migrate();String name=sql.queryForObject("select conname from pg_constraint where conrelid='praxis_bulk.praxis_bulk_evaluation'::regclass and contype='f'",String.class);
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation drop constraint \""+name+"\"");
        assertThatThrownBy(()->BulkExecutionMigrator.validate(dataSource)).isInstanceOf(RuntimeException.class);
    }
    @Test void runtimeRoleCanInsertAndReadButCannotUpdateOrDeleteEitherRow() {
        migrate();sql.execute("grant usage on schema praxis_bulk to evaluation_runtime");sql.execute("grant select,insert on praxis_bulk.praxis_bulk_proposal,praxis_bulk.praxis_bulk_evaluation to evaluation_runtime");
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl("evaluation_runtime","postgres"),"evaluation_runtime","");
        var manager=new DataSourceTransactionManager(ds);var runtimeTx=new TransactionTemplate(manager);
        var runtime=new JdbcBulkProposalStore(new BulkExecutionInfrastructure(ds,manager,CONTEXT.namespaceId()));var value=evaluation(proposal());
        runtimeTx.executeWithoutResult(status->runtime.insertEvaluated(value));var loaded=runtimeTx.execute(status->runtime.findEvaluation(CONTEXT,value.proposal().id()));assertThat(loaded).isPresent();
        var restricted=new JdbcTemplate(ds);
        for(String table:List.of("praxis_bulk_proposal","praxis_bulk_evaluation")) {
            assertThatThrownBy(()->restricted.execute("delete from praxis_bulk."+table)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->restricted.execute("update praxis_bulk."+table+" set payload=payload")).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void governanceSurvivesCommitAndChangedPolicyCannotMatchRecoveredEvidence() {
        migrate(); var value = evaluation(proposal());
        tx.executeWithoutResult(status -> store.insertEvaluated(value));
        // New transaction/connection reads the durable evidence, not the original Java instance.
        var loaded = tx.execute(status -> store.findEvaluation(CONTEXT, value.proposal().id()).orElseThrow());
        assertThat(loaded.governance()).isEqualTo(value.governance());
        var policy = loaded.governance().policies().getFirst();
        var later = loaded.evaluatedAt().plusSeconds(1);
        var withdrawn = new BulkPolicyObservation(policy.tenantId(), policy.environment(), policy.targetLayer(),
                policy.targetArtifactType(), policy.targetArtifactKey(), "PREVIOUSLY_APPLIED_WITHOUT_ELIGIBLE_HEAD",
                "withdrawn-policy-revision", later);
        var changed = new BulkEvaluationGovernance(loaded.governance().evaluatorRevision(),
                loaded.governance().authorizationFingerprint(), List.of(withdrawn));
        assertThat(loaded.matchesCurrentEvidence(CONTEXT, later, loaded.targets(), changed)).isFalse();
        // No rewrite or re-evaluation is persisted by the comparison.
        assertThat(tx.execute(status -> store.findEvaluation(CONTEXT, value.proposal().id()).orElseThrow()).fingerprint())
                .isEqualTo(value.fingerprint());
    }
    @Test void legacyPayloadWithoutGovernanceIsCorruptAndNeverReconstructedAsAuthorized() {
        migrate(); var value = evaluation(proposal()); persist(value);
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) value.storageDocument();
        legacy.remove("governance");
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_evaluation set payload=?, evaluation_fingerprint=? where proposal_id=?",
                BulkSnapshotStorageCodec.json(legacy), BulkCanonicalJson.evaluationDigest(legacy), value.proposal().id());
        assertThatThrownBy(() -> tx.execute(status -> store.findEvaluation(CONTEXT, value.proposal().id())))
                .isInstanceOfSatisfying(BulkProposalStorageException.class, error ->
                        assertThat(error.reason()).isEqualTo(BulkProposalStorageException.Reason.CORRUPT))
                .hasNoCause();
        var input = tx.execute(status -> store.find(CONTEXT, value.proposal().id()));
        assertThat(input).isPresent();
    }
    private String insertAfterBarrier(BulkEvaluationSnapshot value,CyclicBarrier barrier) throws Exception {
        barrier.await(5,TimeUnit.SECONDS);
        try{tx.executeWithoutResult(status->store.insertEvaluated(value));return "COMMITTED";}
        catch(BulkProposalStorageException error){return error.reason().name();}
    }
    private void persist(BulkEvaluationSnapshot value){
        tx.executeWithoutResult(status->store.insertEvaluated(value));var loaded=tx.execute(status->store.findEvaluation(CONTEXT,value.proposal().id()).orElseThrow());
        assertThat(loaded.governance()).isEqualTo(value.governance());
        assertThat(loaded.fingerprint()).isEqualTo(value.fingerprint());assertThat(loaded.evaluatedAt()).isEqualTo(value.evaluatedAt());
        assertThat(loaded.targets().getFirst().plan().get("amount").isBigDecimal()).isTrue();
    }
}
