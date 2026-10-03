package org.praxisplatform.uischema.bulk;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import javax.sql.DataSource;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.praxisplatform.uischema.bulk.BulkEvaluationSnapshotTest.*;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkEvaluationStorePostgresTest {
    private EmbeddedPostgres postgres;
    private DataSource schemaOwnerDataSource;
    private DataSource dataSource;
    private JdbcTemplate sql;
    private TransactionTemplate tx;
    private JdbcBulkProposalStore store;
    @BeforeAll void start() throws Exception {
        postgres=EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
        schemaOwnerDataSource=postgres.getPostgresDatabase();
        dataSource=BulkPostgresTestSupport.runtimeDataSource(postgres);sql=new JdbcTemplate(schemaOwnerDataSource);
        var manager=new DataSourceTransactionManager(dataSource);tx=new TransactionTemplate(manager);
        store=new JdbcBulkProposalStore(new BulkExecutionInfrastructure(dataSource,manager,CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));
        System.out.println("Evaluation store PostgreSQL: "+sql.queryForObject("select version()",String.class));
    }
    @AfterAll void stop() throws Exception {if(postgres!=null)postgres.close();}
    @BeforeEach void reset(){sql.execute("drop schema if exists praxis_bulk cascade");}
    void migrate(){
        assertThat(BulkPostgresTestSupport.migrate(schemaOwnerDataSource, CONTEXT.namespaceId())).isEqualTo(16);
        BulkPostgresTestSupport.ready(schemaOwnerDataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
    }
    int count(String table){return sql.queryForObject("select count(*) from praxis_bulk."+table,Integer.class);}
    @Test void upgradePreservesV1PayloadAndHistoryWithoutFabricatingEvaluationOrExecution() {
        Flyway.configure().dataSource(schemaOwnerDataSource).locations("classpath:db/praxis-bulk-migrations").schemas("praxis_bulk")
                .defaultSchema("praxis_bulk").table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true).target("1").load().migrate();
        var value=proposal();BulkPostgresTestSupport.insertLegacyProposal(sql,value);
        var before=sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history where version='1'",Integer.class);
        // V1 is already installed by this upgrade fixture, so Flyway executes V2 through V16.
        assertThat(BulkPostgresTestSupport.migrate(schemaOwnerDataSource, CONTEXT.namespaceId())).isEqualTo(15);
        BulkPostgresTestSupport.ready(schemaOwnerDataSource, CONTEXT.namespaceId(), CONTEXT.operationRef().operationId());
        assertThat(sql.queryForObject("select checksum from praxis_bulk.praxis_bulk_schema_history where version='1'",Integer.class)).isEqualTo(before);
        var recovered=tx.execute(status->store.find(CONTEXT,value.id()).orElseThrow());
        assertThat(recovered.snapshot().fingerprint()).isEqualTo(value.snapshot().fingerprint());
        var evidence=tx.execute(status->store.findEvaluation(CONTEXT,value.id()));assertThat(evidence).isEmpty();
        assertThat(count("praxis_bulk_execution")).isZero();
        assertThat(count("praxis_bulk_item_receipt")).isZero();
    }
    @Test void allModalitiesAndCodecsRecoverExactFactsAndPlansFromDatabase() {
        migrate();
        int subject = 0;
        for(var mode:BulkMode.values()) {
            persist(evaluation(proposal(snapshot(subjectContext(++subject), mode,BulkIdentityCodecs.integers(),"42","1.0"))));
            persist(evaluation(proposal(snapshot(subjectContext(++subject), mode,BulkIdentityCodecs.longs(),"\"9223372036854775807\"","1.0"))));
            persist(evaluation(proposal(snapshot(subjectContext(++subject), mode,BulkIdentityCodecs.strings(),"\"101\"","1.0"))));
            persist(evaluation(proposal(snapshot(subjectContext(++subject), mode,BulkIdentityCodecs.uuids(),"\"123e4567-e89b-12d3-a456-426614174000\"","1.0"))));
        }
        assertThat(count("praxis_bulk_evaluation")).isEqualTo(12);
    }
    private static BulkFingerprintContext subjectContext(int sequence) {
        return new BulkFingerprintContext(CONTEXT.namespaceId(), "subject-" + sequence,
                CONTEXT.resourceKey(), CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity());
    }
    @Test void bothRowsAreInvisibleUntilCommitAndRollbackRemovesBoth() {
        migrate();var value=evaluation(proposal());
        tx.executeWithoutResult(status->{
            store.insertEvaluated(value, BulkEvaluationSnapshotTest.preview(value));
            try(var connection=dataSource.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("select (select count(*) from praxis_bulk.praxis_bulk_proposal)+(select count(*) from praxis_bulk.praxis_bulk_evaluation)")){
                rows.next();assertThat(rows.getInt(1)).isZero();
            }catch(java.sql.SQLException error){throw new AssertionError(error);}
            assertThat(store.findEvaluation(CONTEXT,value.proposal().id())).isPresent();status.setRollbackOnly();
        });
        assertThat(count("praxis_bulk_proposal")).isZero();assertThat(count("praxis_bulk_evaluation")).isZero();
        assertThat(count("praxis_bulk_target_manifest")).isZero();
    }
    @Test void oldWriterWithoutManifestFailsAtCommitAndCannotLeaveOrphanEvidence() {
        migrate();
        var value=evaluation(proposal());
        tx.executeWithoutResult(status -> store.insert(value.proposal()));
        var runtimeSql=new JdbcTemplate(dataSource);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> runtimeSql.update("""
                insert into praxis_bulk.praxis_bulk_evaluation
                    (proposal_id,input_fingerprint,evaluation_fingerprint,payload) values (?,?,?,?)
                """, value.proposal().id(), value.proposal().snapshot().fingerprint(),
                value.fingerprint(), BulkEvaluationStorageCodec.encode(value))))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("praxis_bulk_evaluation")).isZero();
        assertThat(count("praxis_bulk_target_manifest")).isZero();
        assertThat(count("praxis_bulk_allocation")).isEqualTo(1);
    }
    @Test void oldWriterFullTransactionRollsBackProposalEvaluationAllocationAndQuota() {
        migrate();
        var value=evaluation(proposal());
        var runtimeSql=new JdbcTemplate(dataSource);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            store.insert(value.proposal());
            runtimeSql.update("""
                    insert into praxis_bulk.praxis_bulk_evaluation
                        (proposal_id,input_fingerprint,evaluation_fingerprint,payload) values (?,?,?,?)
                    """, value.proposal().id(), value.proposal().snapshot().fingerprint(),
                    value.fingerprint(), BulkEvaluationStorageCodec.encode(value));
            // A V8 writer supplies the private manifest, but has no V9 safe projection.
            runtimeSql.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                BulkOrdinalManifest.insert(connection, value);
                return null;
            });
        })).isInstanceOf(RuntimeException.class);
        assertThat(count("praxis_bulk_proposal")).isZero();
        assertThat(count("praxis_bulk_evaluation")).isZero();
        assertThat(count("praxis_bulk_target_manifest")).isZero();
        assertThat(count("praxis_bulk_preview_state")).isZero();
        assertThat(count("praxis_bulk_target_preview")).isZero();
        assertThat(count("praxis_bulk_allocation")).isZero();
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_subject_bucket",Integer.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation where state in ('PENDING','ACTIVE')",Integer.class)).isZero();
    }
    @Test void manifestPreservesNulInWireIdentityVersionAndPayloadAndLongIdentity() {
        migrate();
        for (var value : List.of(evaluation(proposal(specialSnapshot("id\u0000part", "v\u0000part", "reason\u0000part"))),
                evaluation(proposal(specialSnapshot("x".repeat(5000), "v1", "plain"))),
                evaluation(proposal(specialSnapshot("version-id", "v".repeat(5000), "plain"))))) {
            persist(value);
            var target=value.targets().getFirst().target();
            var manifest=sql.queryForMap("""
                    select wire_identity, wire_identity_digest, expected_version, target_count
                      from praxis_bulk.praxis_bulk_target_manifest where proposal_id=? and ordinal=0
                    """,value.proposal().id());
            byte[] wire=BulkSnapshotStorageCodec.json(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode((String) target.id()));
            assertThat((byte[])manifest.get("wire_identity")).isEqualTo(wire);
            assertThat(manifest.get("wire_identity_digest")).isEqualTo(BulkTargetDigest.wireIdentity(wire));
            assertThat((byte[])manifest.get("expected_version")).isEqualTo(target.expectedVersion().getBytes(StandardCharsets.UTF_8));
            assertThat(manifest.get("target_count")).isEqualTo(1);
        }
    }
    @Test void tenThousandTargetsPersistThroughTheLastOrdinal() {
        migrate();
        var selected=new java.util.ArrayList<BulkTarget<String>>(10_000);
        var evidence=new java.util.ArrayList<BulkTargetEvidence<?>>(10_000);
        var empty=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (int ordinal=0;ordinal<10_000;ordinal++) {
            var target=new BulkTarget<>("target-"+ordinal,"v1");
            selected.add(target);
            evidence.add(new BulkTargetEvidence<>(target,"observed",empty,empty,
                    BulkTargetEligibility.executable()));
        }
        var request=new BulkCommandEvaluationRequest<com.fasterxml.jackson.databind.JsonNode,String,com.fasterxml.jackson.databind.JsonNode>(
                BulkExecutionMode.SYNC,new BulkSelection<>(BulkSelectionMode.EXPLICIT,selected,null,null),empty);
        var input=proposal(BulkIntentSnapshot.command(CONTEXT,BulkIdentityCodecs.strings(),request,
                com.fasterxml.jackson.databind.JsonNode::deepCopy,com.fasterxml.jackson.databind.JsonNode::deepCopy));
        var value=new BulkEvaluationSnapshot(input,input.createdAt().plusSeconds(1),evidence,governance());
        tx.executeWithoutResult(status->store.insertEvaluated(value, BulkEvaluationSnapshotTest.preview(value)));
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?
                """,Integer.class,input.id())).isEqualTo(10_000);
        assertThat(sql.queryForObject("""
                select max(ordinal) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?
                """,Integer.class,input.id())).isEqualTo(9_999);
        assertThat(sql.queryForObject("""
                select count(distinct wire_identity_digest) from praxis_bulk.praxis_bulk_target_manifest
                 where proposal_id=?
                """,Integer.class,input.id())).isEqualTo(10_000);
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_target_preview where proposal_id=?
                """, Integer.class, input.id())).isEqualTo(10_000);
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?
                """, Integer.class, input.id())).isEqualTo(10_000);
        assertThat(sql.queryForObject("""
                select max(ordinal) from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?
                """, Integer.class, input.id())).isEqualTo(9_999);
        assertThat(sql.queryForObject("""
                select projection_state from praxis_bulk.praxis_bulk_preview_state where proposal_id=?
                """, String.class, input.id())).isEqualTo("COMPLETE");
        long heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        long start=System.nanoTime();
        BulkExecutionMigrator.validate(schemaOwnerDataSource, BulkPostgresTestSupport.testRoleConfiguration());
        long elapsedMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        long heapAfter=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        System.out.printf("V11 10k validate: %d ms, heap used before/after %d/%d bytes (not peak)%n",
                elapsedMillis,heapBefore,heapAfter);
        // A corrupt owner-side fixture expands to roughly 320 MiB of preview bytes.
        // The validator must reject the first item under a 256 MiB test heap,
        // without JDBC eagerly materializing the entire 10k-row result set.
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        sql.execute("""
                update praxis_bulk.praxis_bulk_target_preview
                   set diagnostics=convert_to('[' || repeat('X',32760) || ']','UTF8')
                """);
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        try (var connection=schemaOwnerDataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> BulkPreviewStorage.validateAll(connection))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid protected");
            connection.rollback();
            assertThatThrownBy(() -> BulkPreviewItemIntegrity.validateAll(connection))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity");
            connection.rollback();
        } catch (java.sql.SQLException error) { throw new AssertionError(error); }
    }
    @Test void providerAllowlistRedactsProtectedMessageAndUnlistedCodeRejectsProjection() {
        migrate();
        var ordinary=evaluation(proposal());
        var old=ordinary.targets().getFirst();
        var privateMessage=new org.praxisplatform.uischema.command.ResourceCommandMessage(
                org.praxisplatform.uischema.command.ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                "STATE_NOT_ALLOWED", "SECRET employee context", null, java.util.Map.of());
        var target=new BulkTargetEvidence<>(old.target(),old.observedVersion(),old.facts(),old.plan(),
                BulkTargetEligibility.blocked(List.of(privateMessage)));
        var value=new BulkEvaluationSnapshot(ordinary.proposal(),ordinary.evaluatedAt(),List.of(target),governance());
        assertThatThrownBy(() -> new BulkPreviewProjection(value,"payroll-preview/1",List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unlisted");
        var projection=new BulkPreviewProjection(value,"payroll-preview/1",List.of(
                new BulkPreviewProjection.PublicDiagnostic(privateMessage.category(),privateMessage.code(),
                        "Evento indisponível para aprovação.")));
        tx.executeWithoutResult(status -> store.insertEvaluated(value,projection));
        byte[] publicBytes=(byte[])sql.queryForMap("""
                select diagnostics from praxis_bulk.praxis_bulk_target_preview where proposal_id=? and ordinal=0
                """,value.proposal().id()).get("diagnostics");
        String publicPayload=new String(publicBytes,StandardCharsets.UTF_8);
        assertThat(publicPayload).contains("Evento indisponível").doesNotContain("SECRET");
        assertThat(new String((byte[])sql.queryForMap("""
                select payload from praxis_bulk.praxis_bulk_evaluation where proposal_id=?
                """,value.proposal().id()).get("payload"),StandardCharsets.UTF_8)).contains("SECRET");
        BulkExecutionMigrator.validate(schemaOwnerDataSource,BulkPostgresTestSupport.testRoleConfiguration());
    }
    @Test void explicitlyUnavailablePreviewPreservesValidEvaluationWithoutFabricatingItems() {
        migrate();
        var value=evaluation(proposal());
        tx.executeWithoutResult(status -> store.insertEvaluated(value,BulkPreviewProjection.unavailable(value)));
        assertThat(sql.queryForObject("""
                select projection_state from praxis_bulk.praxis_bulk_preview_state where proposal_id=?
                """,String.class,value.proposal().id())).isEqualTo("UNAVAILABLE");
        assertThat(sql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_target_preview where proposal_id=?
                """,Integer.class,value.proposal().id())).isZero();
        String recovered=tx.execute(status -> store.findEvaluation(CONTEXT,value.proposal().id()).orElseThrow().fingerprint());
        assertThat(recovered).isEqualTo(value.fingerprint());
        var runtimeSql=new JdbcTemplate(dataSource);
        assertThatThrownBy(() -> runtimeSql.update("""
                insert into praxis_bulk.praxis_bulk_target_preview
                    (proposal_id,evaluation_fingerprint,ordinal,decision,diagnostics)
                values (?,?,0,'EXECUTABLE',?)
                """,value.proposal().id(),value.fingerprint(),bytes("[]")))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("complete parent projection");
        BulkExecutionMigrator.validate(schemaOwnerDataSource,BulkPostgresTestSupport.testRoleConfiguration());
    }
    @Test void completedPreviewBootstrapNeverRestoresRevokedGrantAndAclDriftFailsClosed() {
        migrate();
        var roles=BulkPostgresTestSupport.testRoleConfiguration();
        sql.execute("revoke insert on praxis_bulk.praxis_bulk_target_preview from bulk_runtime_test");
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),roles))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_preview','insert')
                """,Boolean.class)).isFalse();
        sql.execute("grant insert on praxis_bulk.praxis_bulk_target_preview to bulk_runtime_test");
        sql.execute("grant select on praxis_bulk.praxis_bulk_preview_bootstrap to public");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,roles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("praxis_bulk_preview_bootstrap");
    }
    @Test void previewDriftCannotBeTreatedAsAnEmptyOrSafeEvaluation() {
        migrate();
        var ordinary=evaluation(proposal());
        var old=ordinary.targets().getFirst();
        var privateMessage=new org.praxisplatform.uischema.command.ResourceCommandMessage(
                org.praxisplatform.uischema.command.ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                "STATE_NOT_ALLOWED", "private", null, java.util.Map.of());
        var blocked=new BulkTargetEvidence<>(old.target(),old.observedVersion(),old.facts(),old.plan(),
                BulkTargetEligibility.blocked(List.of(privateMessage)));
        var value=new BulkEvaluationSnapshot(ordinary.proposal(),ordinary.evaluatedAt(),List.of(blocked),governance());
        tx.executeWithoutResult(status -> store.insertEvaluated(value,new BulkPreviewProjection(value,"test/1",List.of(
                new BulkPreviewProjection.PublicDiagnostic(privateMessage.category(),privateMessage.code(),"Public")))));
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_target_preview set diagnostics=? where proposal_id=?",
                bytes("[{\"category\":\"CONFLICT_DEPENDENCY\",\"code\":\"OTHER\",\"message\":\"Public\"}]"),
                value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,
                BulkPostgresTestSupport.testRoleConfiguration())).isInstanceOf(IllegalStateException.class);
    }
    @Test void messageOnlyPreviewDriftFailsProviderAllowlistAndDigestValidation() {
        migrate();
        var ordinary=evaluation(proposal());
        var old=ordinary.targets().getFirst();
        var privateMessage=new org.praxisplatform.uischema.command.ResourceCommandMessage(
                org.praxisplatform.uischema.command.ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                "STATE_NOT_ALLOWED", "private employee detail", null, java.util.Map.of());
        var blocked=new BulkTargetEvidence<>(old.target(),old.observedVersion(),old.facts(),old.plan(),
                BulkTargetEligibility.blocked(List.of(privateMessage)));
        var value=new BulkEvaluationSnapshot(ordinary.proposal(),ordinary.evaluatedAt(),List.of(blocked),governance());
        tx.executeWithoutResult(status -> store.insertEvaluated(value,new BulkPreviewProjection(value,"test/1",List.of(
                new BulkPreviewProjection.PublicDiagnostic(privateMessage.category(),privateMessage.code(),"Public")))));
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_target_preview set diagnostics=? where proposal_id=?",
                bytes("[{\"category\":\"CONFLICT_DEPENDENCY\",\"code\":\"STATE_NOT_ALLOWED\",\"message\":\"private employee detail\"}]"),
                value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,
                BulkPostgresTestSupport.testRoleConfiguration())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preview");
    }
    @Test void v11LeafDetectsMessageOnlyDriftAndDigestDriftWithoutDecodingProtectedEvaluation() throws Exception {
        migrate();
        var ordinary=evaluation(proposal());
        var old=ordinary.targets().getFirst();
        var privateMessage=new org.praxisplatform.uischema.command.ResourceCommandMessage(
                org.praxisplatform.uischema.command.ResourceCommandErrorCategory.CONFLICT_DEPENDENCY,
                "STATE_NOT_ALLOWED", "private employee detail", null, java.util.Map.of());
        var blocked=new BulkTargetEvidence<>(old.target(),old.observedVersion(),old.facts(),old.plan(),
                BulkTargetEligibility.blocked(List.of(privateMessage)));
        var value=new BulkEvaluationSnapshot(ordinary.proposal(),ordinary.evaluatedAt(),List.of(blocked),governance());
        tx.executeWithoutResult(status -> store.insertEvaluated(value,new BulkPreviewProjection(value,"test/1",List.of(
                new BulkPreviewProjection.PublicDiagnostic(privateMessage.category(),privateMessage.code(),"Public")))));
        try (var connection=schemaOwnerDataSource.getConnection()) {
            BulkPreviewItemIntegrity.validateAll(connection);
        }
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_target_preview set diagnostics=? where proposal_id=?",
                bytes("[{\"category\":\"CONFLICT_DEPENDENCY\",\"code\":\"STATE_NOT_ALLOWED\",\"message\":\"Private\"}]"),
                value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        try (var connection=schemaOwnerDataSource.getConnection()) {
            assertThatThrownBy(() -> BulkPreviewItemIntegrity.validateAll(connection))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity");
        }
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_target_preview set diagnostics=? where proposal_id=?",
                bytes("[{\"category\":\"CONFLICT_DEPENDENCY\",\"code\":\"STATE_NOT_ALLOWED\",\"message\":\"Public\"}]"),
                value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview enable trigger user");
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_preview_item_integrity set item_digest=? where proposal_id=?",
                "sha256:"+"0".repeat(64), value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity enable trigger user");
        try (var connection=schemaOwnerDataSource.getConnection()) {
            assertThatThrownBy(() -> BulkPreviewItemIntegrity.validateAll(connection))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity");
        }
    }
    @Test void v11PendingFenceBlocksNewParentForBothWriterGenerationsAndOldWriterAfterCompletion() {
        migrate();
        var id=java.util.UUID.randomUUID();
        String fingerprint="sha256:"+"0".repeat(64);
        sql.update("update praxis_bulk.praxis_bulk_preview_integrity_bootstrap set phase='PENDING' where bootstrap_version=11");
        assertThatThrownBy(() -> sql.update("""
                insert into praxis_bulk.praxis_bulk_preview_state
                    (proposal_id,evaluation_fingerprint,projection_state,integrity_version)
                values (?,?,'UNAVAILABLE',11)
                """,id,fingerprint)).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("bootstrap is pending");
        assertThatThrownBy(() -> sql.update("""
                insert into praxis_bulk.praxis_bulk_preview_state
                    (proposal_id,evaluation_fingerprint,projection_state)
                values (?,?,'UNAVAILABLE')
                """,id,fingerprint)).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("bootstrap is pending");
        sql.update("update praxis_bulk.praxis_bulk_preview_integrity_bootstrap set phase='COMPLETE' where bootstrap_version=11");
        assertThatThrownBy(() -> sql.update("""
                insert into praxis_bulk.praxis_bulk_preview_state
                    (proposal_id,evaluation_fingerprint,projection_state)
                values (?,?,'UNAVAILABLE')
                """,id,fingerprint)).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("integrity_version");
    }
    @Test void v11IntegrityAclAndCatalogDriftFailClosed() {
        migrate();
        var value=evaluation(proposal());
        tx.executeWithoutResult(status -> store.insertEvaluated(value,BulkEvaluationSnapshotTest.preview(value)));
        var runtimeSql=new JdbcTemplate(dataSource);
        assertThat(runtimeSql.queryForObject("""
                select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity
                """,Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> runtimeSql.execute("""
                update praxis_bulk.praxis_bulk_preview_item_integrity
                   set item_digest=item_digest
                """)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> runtimeSql.execute("""
                delete from praxis_bulk.praxis_bulk_preview_item_integrity
                """)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> runtimeSql.execute("""
                select * from praxis_bulk.praxis_bulk_preview_integrity_bootstrap
                """)).isInstanceOf(RuntimeException.class);
        sql.execute("revoke insert on praxis_bulk.praxis_bulk_preview_item_integrity from bulk_runtime_test");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,
                BulkPostgresTestSupport.testRoleConfiguration())).isInstanceOf(IllegalStateException.class);
        sql.execute("grant insert on praxis_bulk.praxis_bulk_preview_item_integrity to bulk_runtime_test");
        sql.execute("grant select on praxis_bulk.praxis_bulk_preview_integrity_bootstrap to public");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,
                BulkPostgresTestSupport.testRoleConfiguration())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap");
    }
    @Test void v8ToV9PreviewBootstrapRetriesWrongRoleWithoutHealingAfterCompletion() {
        Flyway.configure().dataSource(schemaOwnerDataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .baselineOnMigrate(false).cleanDisabled(true).target("8").load().migrate();
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"bulk_runtime_test");
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"durable_runtime");
        sql.update("update praxis_bulk.praxis_bulk_manifest_bootstrap set phase='COMPLETE' where bootstrap_version=8");
        var wrong=new BulkExecutionRoleConfiguration("postgres",java.util.Set.of("bulk_runtime_test"),
                java.util.Set.of(),java.util.Set.of());
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),wrong))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_bootstrap where bootstrap_version=9
                """,String.class)).isEqualTo("PENDING");
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_preview','insert')
                """,Boolean.class)).isFalse();
        grantPublicationReadToExistingRuntimeRoles();
        assertThat(BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),
                BulkPostgresTestSupport.testRoleConfiguration())).isZero();
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_bootstrap where bootstrap_version=9
                """,String.class)).isEqualTo("COMPLETE");
        BulkExecutionMigrator.validate(schemaOwnerDataSource,BulkPostgresTestSupport.testRoleConfiguration());
    }
    @Test void v10ToV11BackfillsExactCompletePreviewOnlyAfterValidatedRetry() throws Exception {
        Flyway.configure().dataSource(schemaOwnerDataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .baselineOnMigrate(false).cleanDisabled(true).target("10").load().migrate();
        sql.update("insert into praxis_bulk.praxis_bulk_namespace_binding(namespace_id,deployment_id,bound_at) values(?,?,clock_timestamp())",
                CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID);
        BulkPostgresTestSupport.ready(schemaOwnerDataSource,CONTEXT.namespaceId(),CONTEXT.operationRef().operationId());
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"bulk_runtime_test");
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"durable_runtime");
        var value=evaluation(proposal());
        var diagnostics=BulkPreviewStorage.diagnostics(List.of());
        byte[] allowlist=bytes("[]");
        String revision="test-preview/1";
        String digest=v9Digest(value.fingerprint(),revision,allowlist,"EXECUTABLE",diagnostics);
        var ownerTx=new TransactionTemplate(new DataSourceTransactionManager(schemaOwnerDataSource));
        ownerTx.executeWithoutResult(status -> {
            insertV7Evaluation(value);
            sql.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                BulkOrdinalManifest.insert(connection,value);
                return null;
            });
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_preview_state
                        (proposal_id,evaluation_fingerprint,projection_state,projector_revision,
                         target_count,public_allowlist,projection_digest)
                    values (?,?,'COMPLETE',?,?,?,?)
                    """,value.proposal().id(),value.fingerprint(),revision,1,allowlist,digest);
            sql.update("""
                    insert into praxis_bulk.praxis_bulk_target_preview
                        (proposal_id,evaluation_fingerprint,ordinal,decision,diagnostics)
                    values (?,?,0,'EXECUTABLE',?)
                    """,value.proposal().id(),value.fingerprint(),diagnostics);
        });
        sql.update("update praxis_bulk.praxis_bulk_manifest_bootstrap set phase='COMPLETE' where bootstrap_version=8");
        sql.update("update praxis_bulk.praxis_bulk_preview_bootstrap set phase='COMPLETE' where bootstrap_version=9");
        var wrong=new BulkExecutionRoleConfiguration("postgres",java.util.Set.of("bulk_runtime_test"),
                java.util.Set.of(),java.util.Set.of());
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),wrong))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_integrity_bootstrap
                """,String.class)).isEqualTo("PENDING");
        assertThat(count("praxis_bulk_preview_item_integrity")).isZero();
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test',
                    'praxis_bulk.praxis_bulk_preview_item_integrity','insert')
                """,Boolean.class)).isFalse();
        grantPublicationReadToExistingRuntimeRoles();
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_state disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_preview_state set projection_digest=? where proposal_id=?",
                "sha256:"+"0".repeat(64),value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_state enable trigger user");
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),
                BulkPostgresTestSupport.testRoleConfiguration())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bulk preview projection is incomplete or corrupt");
        assertThat(count("praxis_bulk_preview_item_integrity")).isZero();
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_state disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_preview_state set projection_digest=? where proposal_id=?",
                digest,value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_state enable trigger user");
        assertThat(BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),
                BulkPostgresTestSupport.testRoleConfiguration())).isZero();
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_integrity_bootstrap
                """,String.class)).isEqualTo("COMPLETE");
        assertThat(count("praxis_bulk_preview_item_integrity")).isEqualTo(1);
        BulkExecutionMigrator.validate(schemaOwnerDataSource,BulkPostgresTestSupport.testRoleConfiguration());
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_preview_item_integrity set item_digest=? where proposal_id=?",
                "sha256:"+"0".repeat(64),value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_item_integrity enable trigger user");
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),
                BulkPostgresTestSupport.testRoleConfiguration())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bulk preview item integrity is incomplete or corrupt");
        assertThat(count("praxis_bulk_preview_item_integrity")).isEqualTo(1);
        assertThat(sql.queryForObject("""
                select item_digest from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?
                """,String.class,value.proposal().id())).isEqualTo("sha256:"+"0".repeat(64));
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_preview_integrity_bootstrap
                """,String.class)).isEqualTo("COMPLETE");
    }
    private static String v9Digest(String fingerprint, String revision, byte[] allowlist,
            String decision, byte[] diagnostics) throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        for (byte[] field : List.of(bytes("praxis.bulk.preview/1"),bytes(fingerprint),bytes(revision),allowlist)) {
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(field.length).array());
            digest.update(field);
        }
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(0).array());
        for (byte[] field : List.of(bytes(decision),diagnostics)) {
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(field.length).array());
            digest.update(field);
        }
        return "sha256:"+java.util.HexFormat.of().formatHex(digest.digest());
    }
    @Test void writerQueuedBehindV11DdlObservesPendingFenceAfterMigrationCommit() throws Exception {
        Flyway.configure().dataSource(schemaOwnerDataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .baselineOnMigrate(false).cleanDisabled(true).target("10").load().migrate();
        try (var blocker=schemaOwnerDataSource.getConnection(); var executor=Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var lock=blocker.createStatement()) {
                lock.execute("lock table praxis_bulk.praxis_bulk_preview_state in access share mode");
            }
            var ddl=executor.submit(() -> Flyway.configure().dataSource(schemaOwnerDataSource)
                    .locations("classpath:db/praxis-bulk-migrations").schemas("praxis_bulk")
                    .defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                    .baselineOnMigrate(false).cleanDisabled(true).target("11").load().migrate());
            awaitQueuedPreviewStateLocks(1);
            var writer=executor.submit(() -> sql.update("""
                    insert into praxis_bulk.praxis_bulk_preview_state
                        (proposal_id,evaluation_fingerprint,projection_state)
                    values (?,?,'UNAVAILABLE')
                    """,java.util.UUID.randomUUID(),"sha256:"+"0".repeat(64)));
            awaitQueuedPreviewStateLocks(2);
            assertThat(ddl.isDone()).isFalse();
            assertThat(writer.isDone()).isFalse();
            blocker.rollback();
            assertThat(ddl.get(10,TimeUnit.SECONDS).migrationsExecuted).isEqualTo(1);
            assertThatThrownBy(() -> writer.get(10,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasStackTraceContaining("bootstrap is pending");
            assertThat(sql.queryForObject("""
                    select phase from praxis_bulk.praxis_bulk_preview_integrity_bootstrap
                    """,String.class)).isEqualTo("PENDING");
        }
    }
    private void awaitQueuedPreviewStateLocks(int expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<deadline) {
            Integer count=sql.queryForObject("""
                    select count(*) from pg_locks
                     where relation='praxis_bulk.praxis_bulk_preview_state'::regclass
                       and not granted
                    """,Integer.class);
            if (count != null && count>=expected) return;
            Thread.sleep(10);
        }
        fail("V11 DDL/writer did not queue on preview state within 10 seconds");
    }
    private static BulkIntentSnapshot specialSnapshot(String id, String version, String reason) {
        var json=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        String idJson=new String(BulkSnapshotStorageCodec.json(json.textNode(id)),StandardCharsets.UTF_8);
        String versionJson=new String(BulkSnapshotStorageCodec.json(json.textNode(version)),StandardCharsets.UTF_8);
        String reasonJson=new String(BulkSnapshotStorageCodec.json(json.textNode(reason)),StandardCharsets.UTF_8);
        var request=new BulkProtocolReader<>(BulkIdentityCodecs.strings())
                .<com.fasterxml.jackson.databind.JsonNode,com.fasterxml.jackson.databind.JsonNode>readCommand(bytes(
                        "{\"executionMode\":\"SYNC\",\"selection\":{\"mode\":\"EXPLICIT\",\"targets\":[{\"id\":"
                        +idJson+",\"expectedVersion\":"+versionJson+"}]},\"parameters\":{\"reason\":"+reasonJson+"}}"),
                        com.fasterxml.jackson.databind.JsonNode::deepCopy,
                        com.fasterxml.jackson.databind.JsonNode::deepCopy);
        return BulkIntentSnapshot.command(CONTEXT,BulkIdentityCodecs.strings(),request,
                com.fasterxml.jackson.databind.JsonNode::deepCopy,com.fasterxml.jackson.databind.JsonNode::deepCopy);
    }
    @Test void oldWriterWaitingOnControlLockStillFailsClosedAfterRelease() throws Exception {
        migrate();var value=evaluation(proposal());
        tx.executeWithoutResult(status -> store.insert(value.proposal()));
        try (var blocker=schemaOwnerDataSource.getConnection(); var executor=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var lock=blocker.prepareStatement("""
                    select 1 from praxis_bulk.praxis_bulk_operation_control
                     where namespace_id=? and operation_id=? for update
                    """)) {
                lock.setString(1,CONTEXT.namespaceId());lock.setString(2,CONTEXT.operationRef().operationId());
                lock.executeQuery().close();
            }
            var started=new CountDownLatch(1);
            var writer=executor.submit(() -> {
                started.countDown();
                try {
                    tx.executeWithoutResult(status -> {
                        new JdbcTemplate(dataSource).update("""
                                insert into praxis_bulk.praxis_bulk_evaluation
                                    (proposal_id,input_fingerprint,evaluation_fingerprint,payload)
                                values (?,?,?,?)
                                """,value.proposal().id(),value.proposal().snapshot().fingerprint(),
                                value.fingerprint(),BulkEvaluationStorageCodec.encode(value));
                        new JdbcTemplate(dataSource).execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                            BulkOrdinalManifest.insert(connection, value);
                            return null;
                        });
                    });
                    return false;
                } catch (RuntimeException expected) { return true; }
            });
            assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while (!Boolean.TRUE.equals(sql.queryForObject("""
                    select exists(select 1 from pg_stat_activity where wait_event_type='Lock'
                      and query like '%insert into praxis_bulk.praxis_bulk_evaluation%')
                    """,Boolean.class)) && System.nanoTime()<deadline) Thread.sleep(20);
            assertThat(writer.isDone()).isFalse();
            blocker.commit();
            assertThat(writer.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("praxis_bulk_evaluation")).isZero();
        assertThat(count("praxis_bulk_target_manifest")).isZero();
    }
    @Test void manifestMatchesProtectedEvidenceAndDriftFailsValidation() {
        migrate();var value=evaluation(proposal());persist(value);
        assertThat(count("praxis_bulk_target_manifest")).isEqualTo(value.targets().size());
        assertThat(sql.queryForObject("select target_digest from praxis_bulk.praxis_bulk_target_manifest where proposal_id=? and ordinal=0",
                String.class,value.proposal().id())).isEqualTo(BulkTargetDigest.of(value.fingerprint(),0,
                value.targets().getFirst().target().id(),value.targets().getFirst().target().expectedVersion()));
        sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_target_manifest set target_digest=? where proposal_id=?",
                "sha256:"+"0".repeat(64),value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest enable trigger user");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,
                BulkPostgresTestSupport.testRoleConfiguration()))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void manifestByteIdentityVersionAndPhysicalCountDriftFailValidation() {
        migrate();var value=evaluation(proposal(specialSnapshot("id\u0000part","v\u0000part","payload\u0000part")));
        persist(value);
        var original=sql.queryForMap("""
                select wire_identity,wire_identity_digest,expected_version,target_count
                  from praxis_bulk.praxis_bulk_target_manifest where proposal_id=? and ordinal=0
                """,value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest disable trigger user");
        try {
            for (String mutation : List.of("wire_identity=decode('00','hex')",
                    "wire_identity_digest='sha256:"+"0".repeat(64)+"'",
                    "expected_version=decode('00','hex')", "target_count=2")) {
                sql.update("update praxis_bulk.praxis_bulk_target_manifest set "+mutation+" where proposal_id=?",
                        value.proposal().id());
                sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest enable trigger user");
                assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,
                        BulkPostgresTestSupport.testRoleConfiguration()))
                        .as("reject manifest drift: %s",mutation).isInstanceOf(IllegalStateException.class);
                sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest disable trigger user");
                sql.update("""
                        update praxis_bulk.praxis_bulk_target_manifest
                           set wire_identity=?,wire_identity_digest=?,expected_version=?,target_count=?
                         where proposal_id=?
                        """,original.get("wire_identity"),original.get("wire_identity_digest"),
                        original.get("expected_version"),original.get("target_count"),value.proposal().id());
            }
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest enable trigger user");
        }
        BulkExecutionMigrator.validate(schemaOwnerDataSource,BulkPostgresTestSupport.testRoleConfiguration());
    }
    @Test void manifestAclAndMutationFenceAreAttested() {
        migrate();
        sql.execute("grant select on praxis_bulk.praxis_bulk_target_manifest to public");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,
                BulkPostgresTestSupport.testRoleConfiguration()))
                .isInstanceOf(IllegalStateException.class);
        sql.execute("revoke select on praxis_bulk.praxis_bulk_target_manifest from public");
        sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest disable trigger praxis_bulk_target_manifest_immutable");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,
                BulkPostgresTestSupport.testRoleConfiguration()))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void bootstrapMarkerIsPrivateAndCompletionIsValidated() {
        migrate();
        var roles=BulkPostgresTestSupport.testRoleConfiguration();
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_manifest_bootstrap','select')
                """,Boolean.class)).isFalse();
        sql.execute("grant select on praxis_bulk.praxis_bulk_manifest_bootstrap to bulk_runtime_test");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,roles))
                .isInstanceOf(IllegalStateException.class);
        sql.execute("revoke select on praxis_bulk.praxis_bulk_manifest_bootstrap from bulk_runtime_test");
        sql.update("update praxis_bulk.praxis_bulk_manifest_bootstrap set phase='PENDING'");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,roles))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("manifest bootstrap is not complete");
    }
    @Test void internalRetentionGrantsCannotMutateBootstrapMarker() throws Exception {
        migrate();
        var roles=BulkPostgresTestSupport.testRoleConfiguration();
        for (String role:List.of("praxis_bulk_retention_owner","praxis_bulk_retention_executor")) {
            sql.execute("grant update on praxis_bulk.praxis_bulk_manifest_bootstrap to "+role);
            assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,roles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("manifest bootstrap marker ACL must be owner-only");
            sql.execute("revoke update on praxis_bulk.praxis_bulk_manifest_bootstrap from "+role);
        }
        sql.execute("grant update on praxis_bulk.praxis_bulk_manifest_bootstrap to praxis_bulk_retention_executor");
        try (var connection=schemaOwnerDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.createStatement()) {
                statement.execute("set role praxis_bulk_retention_executor");
                assertThat(statement.executeUpdate("""
                        update praxis_bulk.praxis_bulk_manifest_bootstrap set phase='PENDING'
                        """)).isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
        assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_manifest_bootstrap",String.class))
                .isEqualTo("COMPLETE");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,roles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest bootstrap marker ACL must be owner-only");
    }
    @Test void completedBootstrapRejectsMissingManifestWithoutRecreatingIt() {
        migrate();
        var value=evaluation(proposal());
        persist(value);
        sql.execute("alter table praxis_bulk.praxis_bulk_target_preview drop constraint praxis_bulk_target_preview_manifest_fkey");
        sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest disable trigger user");
        try {
            sql.update("delete from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?",
                    value.proposal().id());
        } finally {
            sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest enable trigger user");
        }
        assertThat(count("praxis_bulk_target_manifest")).isZero();
        var roles=BulkPostgresTestSupport.testRoleConfiguration();
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),roles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Protected bulk manifest differs from evaluation");
        assertThat(count("praxis_bulk_target_manifest")).isZero();
        assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_manifest_bootstrap",String.class))
                .isEqualTo("COMPLETE");
        assertThatThrownBy(() -> BulkExecutionMigrator.validate(schemaOwnerDataSource,roles))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("praxis_bulk_target_manifest")).isZero();
    }
    @Test void v7EvaluationBackfillsAtomicallyBeforeValidation() {
        Flyway.configure().dataSource(schemaOwnerDataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target("7").load().migrate();
        sql.update("insert into praxis_bulk.praxis_bulk_namespace_binding(namespace_id,deployment_id,bound_at) values(?,?,clock_timestamp())",
                CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID);
        BulkPostgresTestSupport.ready(schemaOwnerDataSource,CONTEXT.namespaceId(),CONTEXT.operationRef().operationId());
        var first=evaluation(proposal(specialSnapshot("legacy\u0000id", "v\u0000legacy", "legacy\u0000payload")));
        var second=evaluation(proposal(specialSnapshot("x".repeat(5000), "v2", "legacy-long")));
        insertV7Evaluation(first);
        insertV7Evaluation(second);
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_evaluation set payload=? where proposal_id=?",
                bytes("CORRUPT"),second.proposal().id());
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("praxis_bulk_target_manifest")).isZero();
        sql.update("update praxis_bulk.praxis_bulk_evaluation set payload=? where proposal_id=?",
                BulkEvaluationStorageCodec.encode(second),second.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation enable trigger user");
        assertThat(BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID))).isZero();
        assertThat(count("praxis_bulk_target_manifest")).isEqualTo(first.targets().size()+second.targets().size());
        assertThat(count("praxis_bulk_target_preview")).isZero();
        assertThat(sql.queryForList("""
                select projection_state from praxis_bulk.praxis_bulk_preview_state order by proposal_id
                """, String.class)).containsExactly("UNAVAILABLE_LEGACY", "UNAVAILABLE_LEGACY");
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"bulk_runtime_test");
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"durable_runtime");
        var runtimeSql=new JdbcTemplate(dataSource);
        assertThatThrownBy(() -> runtimeSql.update("""
                insert into praxis_bulk.praxis_bulk_target_preview
                    (proposal_id,evaluation_fingerprint,ordinal,decision,diagnostics)
                values (?,?,0,'EXECUTABLE',?)
                """,first.proposal().id(),first.fingerprint(),bytes("[]")))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("complete parent projection");
        BulkExecutionMigrator.validate(schemaOwnerDataSource,BulkPostgresTestSupport.testRoleConfiguration());
    }
    @Test void v7UpgradeProvisionsOnlyNewManifestPrivilegesForExistingRuntimeRoles() {
        migrateToV7();
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"bulk_runtime_test");
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"durable_runtime");
        var value=evaluation(proposal(specialSnapshot("upgrade\u0000id","v\u0000upgrade","payload\u0000upgrade")));
        insertV7Evaluation(value);
        // Install V8..V16 DDL before the host grants the newly created V14 read function.
        assertThat(Flyway.configure().dataSource(schemaOwnerDataSource)
                .locations("classpath:db/praxis-bulk-migrations").schemas("praxis_bulk")
                .defaultSchema("praxis_bulk").table("praxis_bulk_schema_history")
                .baselineOnMigrate(false).cleanDisabled(true).load().migrate().migrationsExecuted).isEqualTo(9);
        grantPublicationReadToExistingRuntimeRoles();
        assertThat(BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),
                BulkPostgresTestSupport.testRoleConfiguration())).isZero();
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_manifest','select,insert')
                """,Boolean.class)).isTrue();
        BulkExecutionMigrator.validate(schemaOwnerDataSource,BulkPostgresTestSupport.testRoleConfiguration());
        assertThat(count("praxis_bulk_target_manifest")).isEqualTo(value.targets().size());
        assertThat(BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),
                BulkPostgresTestSupport.testRoleConfiguration())).isZero();
        sql.execute("revoke insert on praxis_bulk.praxis_bulk_target_manifest from bulk_runtime_test");
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),
                BulkPostgresTestSupport.testRoleConfiguration())).isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_manifest','insert')
                """,Boolean.class)).isFalse();
        assertThat(sql.queryForObject("""
                select phase from praxis_bulk.praxis_bulk_manifest_bootstrap where bootstrap_version=8
                """,String.class)).isEqualTo("COMPLETE");
    }
    @Test void failedV8BootstrapRetriesGrantOnlyUntilDurableCompletion() {
        migrateToV7();
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"bulk_runtime_test");
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"durable_runtime");
        var value=evaluation(proposal(specialSnapshot("retry\u0000id","v\u0000retry","payload\u0000retry")));
        insertV7Evaluation(value);
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user");
        sql.update("update praxis_bulk.praxis_bulk_evaluation set payload=? where proposal_id=?",
                bytes("CORRUPT"),value.proposal().id());
        var roles=BulkPostgresTestSupport.testRoleConfiguration();
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),roles))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_manifest_bootstrap",String.class))
                .isEqualTo("PENDING");
        assertThat(count("praxis_bulk_target_manifest")).isZero();
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_manifest','select,insert')
                """,Boolean.class)).isFalse();
        grantPublicationReadToExistingRuntimeRoles();
        sql.update("update praxis_bulk.praxis_bulk_evaluation set payload=? where proposal_id=?",
                BulkEvaluationStorageCodec.encode(value),value.proposal().id());
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation enable trigger user");
        assertThat(BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),roles)).isZero();
        assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_manifest_bootstrap",String.class))
                .isEqualTo("COMPLETE");
        assertThat(count("praxis_bulk_target_manifest")).isEqualTo(value.targets().size());
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_manifest','select,insert')
                """,Boolean.class)).isTrue();
        sql.execute("revoke insert on praxis_bulk.praxis_bulk_target_manifest from bulk_runtime_test");
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),roles))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_manifest','insert')
                """,Boolean.class)).isFalse();
    }
    @Test void wrongNoRoleUpgradeCannotCompleteAndCorrectRoleRetryRecovers() {
        migrateToV7();
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"bulk_runtime_test");
        BulkPostgresTestSupport.grantRuntimeRole(schemaOwnerDataSource,"durable_runtime");
        var value=evaluation(proposal(specialSnapshot("upgrade-role","v1","plain")));
        insertV7Evaluation(value);
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_manifest_bootstrap",String.class))
                .isEqualTo("PENDING");
        assertThat(count("praxis_bulk_target_manifest")).isZero();
        assertThat(sql.queryForObject("""
                select has_table_privilege('bulk_runtime_test','praxis_bulk.praxis_bulk_target_manifest','select,insert')
                """,Boolean.class)).isFalse();
        grantPublicationReadToExistingRuntimeRoles();
        var roles=BulkPostgresTestSupport.testRoleConfiguration();
        assertThat(BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),roles)).isZero();
        assertThat(sql.queryForObject("select phase from praxis_bulk.praxis_bulk_manifest_bootstrap",String.class))
                .isEqualTo("COMPLETE");
        assertThat(count("praxis_bulk_target_manifest")).isEqualTo(value.targets().size());
        BulkExecutionMigrator.validate(schemaOwnerDataSource,roles);
    }
    @Test void v7UpgradeRejectsUnprovisionedRoleWithoutGrantingManifest() {
        migrateToV7();
        sql.execute("create role missing_runtime login");
        var roles=new BulkExecutionRoleConfiguration("postgres",java.util.Set.of("missing_runtime"),
                java.util.Set.of(),java.util.Set.of());
        assertThatThrownBy(() -> BulkExecutionMigrator.migrate(schemaOwnerDataSource,
                java.util.Map.of(CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID),roles))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sql.queryForObject("""
                select has_table_privilege('missing_runtime','praxis_bulk.praxis_bulk_target_manifest','select,insert')
                """,Boolean.class)).isFalse();
        assertThat(count("praxis_bulk_target_manifest")).isZero();
    }
    private void grantPublicationReadToExistingRuntimeRoles() {
        // Host-owned V14 ACL only; V16 pending bootstrap must provision its own exact runtime grants.
        sql.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to bulk_runtime_test,durable_runtime");
    }
    private void migrateToV7() {
        Flyway.configure().dataSource(schemaOwnerDataSource).locations("classpath:db/praxis-bulk-migrations")
                .schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").baselineOnMigrate(false).cleanDisabled(true)
                .target("7").load().migrate();
        sql.update("insert into praxis_bulk.praxis_bulk_namespace_binding(namespace_id,deployment_id,bound_at) values(?,?,clock_timestamp())",
                CONTEXT.namespaceId(),BulkPostgresTestSupport.DEPLOYMENT_ID);
        BulkPostgresTestSupport.ready(schemaOwnerDataSource,CONTEXT.namespaceId(),CONTEXT.operationRef().operationId());
    }
    private void insertV7Evaluation(BulkEvaluationSnapshot value) {
        var proposal=value.proposal();var context=proposal.snapshot().context();
        sql.update("""
                insert into praxis_bulk.praxis_bulk_proposal
                (proposal_id,namespace_id,subject_id,resource_key,operation_id,created_at,expires_at,
                 fingerprint,payload,control_generation,control_descriptor_fingerprint,control_structural_revision)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                """,proposal.id(),context.namespaceId(),context.subjectId(),context.resourceKey(),
                context.operationRef().operationId(),java.sql.Timestamp.from(proposal.createdAt()),
                java.sql.Timestamp.from(proposal.expiresAt()),proposal.snapshot().fingerprint(),
                BulkSnapshotStorageCodec.encode(proposal.snapshot()),proposal.controlExpectation().generation(),
                proposal.controlExpectation().descriptorFingerprint(),proposal.controlExpectation().structuralRevision());
        sql.update("""
                insert into praxis_bulk.praxis_bulk_evaluation
                (proposal_id,input_fingerprint,evaluation_fingerprint,payload) values(?,?,?,?)
                """,proposal.id(),proposal.snapshot().fingerprint(),value.fingerprint(),BulkEvaluationStorageCodec.encode(value));
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
            tx.executeWithoutResult(status -> store.insertEvaluated(value, BulkEvaluationSnapshotTest.preview(value)));
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
            assertThatThrownBy(()->store.insertEvaluated(value, BulkEvaluationSnapshotTest.preview(value))).isInstanceOf(BulkProposalStorageException.class).hasNoCause();
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
        tx.executeWithoutResult(status -> {
            var runtimeSql = new JdbcTemplate(dataSource);
            runtimeSql.update("insert into praxis_bulk.praxis_bulk_evaluation(proposal_id,input_fingerprint,evaluation_fingerprint,payload) values(?,?,?,?)",value.proposal().id(),value.proposal().snapshot().fingerprint(),value.fingerprint(),BulkEvaluationStorageCodec.encode(value));
            runtimeSql.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                BulkOrdinalManifest.insert(connection, value);
                BulkPreviewStorage.insert(connection, value, BulkEvaluationSnapshotTest.preview(value));
                return null;
            });
        });
        assertThatThrownBy(()->sql.execute("update praxis_bulk.praxis_bulk_evaluation set payload=payload")).isInstanceOf(RuntimeException.class);
    }
    @Test void physicalValidationRejectsDisabledTriggerUnloggedAndAlteredBinding() {
        for(String mutation:List.of("alter table praxis_bulk.praxis_bulk_evaluation disable trigger user","alter table praxis_bulk.praxis_bulk_tombstone set unlogged")){
            migrate();sql.execute(mutation);assertThatThrownBy(()->BulkExecutionMigrator.validate(dataSource)).isInstanceOf(RuntimeException.class);reset();
        }
        migrate();String name=sql.queryForObject("select conname from pg_constraint where conrelid='praxis_bulk.praxis_bulk_evaluation'::regclass and contype='f'",String.class);
        sql.execute("alter table praxis_bulk.praxis_bulk_evaluation drop constraint \""+name+"\"");
        assertThatThrownBy(()->BulkExecutionMigrator.validate(dataSource)).isInstanceOf(RuntimeException.class);
    }
    @Test void runtimeRoleCanInsertAndReadButCannotUpdateOrDeleteEitherRow() {
        migrate();
        var ds=BulkPostgresTestSupport.runtimeDataSource(postgres);
        var manager=new DataSourceTransactionManager(ds);var runtimeTx=new TransactionTemplate(manager);
        var runtime=new JdbcBulkProposalStore(new BulkExecutionInfrastructure(ds,manager,CONTEXT.namespaceId(),
                BulkPostgresTestSupport.DEPLOYMENT_ID, BulkPostgresTestSupport.testRoleConfiguration()));var value=evaluation(proposal());
        runtimeTx.executeWithoutResult(status->runtime.insertEvaluated(value, BulkEvaluationSnapshotTest.preview(value)));var loaded=runtimeTx.execute(status->runtime.findEvaluation(CONTEXT,value.proposal().id()));assertThat(loaded).isPresent();
        var restricted=new JdbcTemplate(ds);
        for(String table:List.of("praxis_bulk_proposal","praxis_bulk_evaluation")) {
            assertThatThrownBy(()->restricted.execute("delete from praxis_bulk."+table)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->restricted.execute("update praxis_bulk."+table+" set payload=payload")).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void governanceSurvivesCommitAndChangedPolicyCannotMatchRecoveredEvidence() {
        migrate(); var value = evaluation(proposal());
        tx.executeWithoutResult(status -> store.insertEvaluated(value, BulkEvaluationSnapshotTest.preview(value)));
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
        sql.execute("alter table praxis_bulk.praxis_bulk_target_manifest drop constraint praxis_bulk_target_manifest_evaluation_fkey");
        sql.execute("alter table praxis_bulk.praxis_bulk_preview_state drop constraint praxis_bulk_preview_state_evaluation_fkey");
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
        try{tx.executeWithoutResult(status->store.insertEvaluated(value, BulkEvaluationSnapshotTest.preview(value)));return "COMMITTED";}
        catch(BulkProposalStorageException error){return error.reason().name();}
    }
    private void persist(BulkEvaluationSnapshot value){
        var scope = value.proposal().snapshot().context();
        tx.executeWithoutResult(status->store.insertEvaluated(value, BulkEvaluationSnapshotTest.preview(value)));var loaded=tx.execute(status->store.findEvaluation(scope,value.proposal().id()).orElseThrow());
        assertThat(loaded.governance()).isEqualTo(value.governance());
        assertThat(loaded.fingerprint()).isEqualTo(value.fingerprint());assertThat(loaded.evaluatedAt()).isEqualTo(value.evaluatedAt());
        assertThat(loaded.targets().getFirst().plan().get("amount").isBigDecimal()).isTrue();
    }
}
