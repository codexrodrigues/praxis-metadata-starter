package org.praxisplatform.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.bulk.BulkControlPlaneInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionMigrator;
import org.praxisplatform.uischema.bulk.BulkExecutionRoleConfiguration;
import org.praxisplatform.uischema.bulk.BulkIdentityCodecs;
import org.praxisplatform.uischema.bulk.BulkOperationControlIdentity;
import org.praxisplatform.uischema.bulk.BulkOperationLifecycle;
import org.praxisplatform.uischema.bulk.BulkResourceOperationBindings;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.id.SchemaIdBuilder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.EnumSet;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactConsumerHttpTest {

    private static final String NAMESPACE = "artifact-consumer-test";
    private static final String DEPLOYMENT = "artifact-consumer-deployment";
    private static final String RUNTIME_ROLE = "bulk_consumer_runtime";
    private static final String CONTROL_ROLE = "bulk_consumer_control";
    private static final BulkOperationControlIdentity OPERATION =
            new BulkOperationControlIdentity(NAMESPACE, ArtifactBulkController.CONFIRMATION);

    /** Test-only native writer against an exclusive PostgreSQL owned by the current parent test.
     * Registration, grants and HTTP publication pattern derive from the rc149 consumer fixture.
     * No candidate classes, SQL proposal/ledger inserts or fabricated READY control participate.
     */
    @Test
    void writesNativeHistoricalProposalForCutover() throws Exception {
        Path jar = Path.of(requiredProperty("historical.jar")).toRealPath();
        for (Class<?> type : List.of(BulkExecutionMigrator.class, JdbcBulkProposalStore.class,
                BulkOperationLifecycle.class, BulkIntentSnapshot.class, BulkStoredProposal.class,
                BulkEvaluationSnapshot.class, BulkTargetEvidence.class, BulkEvaluationGovernance.class,
                BulkPolicyObservation.class, BulkPreviewProjection.class)) {
            assertThat(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath())
                    .isEqualTo(jar);
        }
        assertThat(sha256(jar)).isEqualTo("a0bd4137726acdced16fb1c193a8db7e6c6c1e26b23a4ba9294ccb8da5ddb2cc");
        assertPublishedPomVersion("8.0.0-rc.149");
        assertThat(BulkExecutionMigrator.class.getResource("/db/praxis-bulk-migrations/V16__bulk_atomic_set_execution.sql"))
                .isNull();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        assertThat(classPath).doesNotContain(requiredProperty("candidate.source.root") + "/target/classes");
        String jdbcUrl = requiredProperty("historical.jdbc.url");
        var deployment = new DriverManagerDataSource(jdbcUrl, "postgres", "");
        createLoginRole(deployment, RUNTIME_ROLE);
        createLoginRole(deployment, CONTROL_ROLE);
        assertThat(BulkExecutionMigrator.migrate(deployment, Map.of(NAMESPACE, DEPLOYMENT))).isEqualTo(15);
        provisionRuntimeRole(deployment, RUNTIME_ROLE);
        provisionControlRole(deployment, CONTROL_ROLE);
        var roles = new BulkExecutionRoleConfiguration("postgres", Set.of(RUNTIME_ROLE), Set.of(), Set.of(CONTROL_ROLE));
        assertThat(BulkExecutionMigrator.migrate(deployment, Map.of(NAMESPACE, DEPLOYMENT), roles, List.of(OPERATION))).isZero();
        BulkExecutionMigrator.validate(deployment, roles);
        Properties evidence = new Properties();
        try (ConfigurableApplicationContext application = startApplication(jdbcUrl, true)) {
            int port = ((WebServerApplicationContext) application).getWebServer().getPort();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            ObjectMapper mapper = application.getBean(ObjectMapper.class);
            var response = httpPost(http, "http://127.0.0.1:" + port + "/_test/bulk-lifecycle/publish-and-verify", null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var published = mapper.readTree(response.body());
            assertThat(published.path("verified").asBoolean()).isTrue();
            var expectation = new BulkOperationControlExpectation(published.path("generation").asLong(),
                    published.path("descriptorFingerprint").asText(), published.path("structuralRevision").asText());
            assertThat(expectation.generation()).isEqualTo(1);
            var context = new BulkFingerprintContext(NAMESPACE, "historical-subject", "artifact.items",
                    new CanonicalOperationRef("artifact-consumer", ArtifactBulkController.CONFIRMATION,
                            "/artifact-items/actions/bulk-approve", "POST"),
                    expectation.structuralRevision(), ActionCollectionAtomicity.PER_ITEM);
            var request = new BulkCommandEvaluationRequest<JsonNode, String, JsonNode>(BulkExecutionMode.SYNC,
                    new BulkSelection<>(BulkSelectionMode.EXPLICIT, List.of(new BulkTarget<>("historical-item", "v1")),
                            null, null), mapper.createObjectNode());
            var snapshot = BulkIntentSnapshot.command(context, BulkIdentityCodecs.strings(), request,
                    JsonNode::deepCopy, JsonNode::deepCopy);
            var created = Instant.now();
            var proposal = new BulkStoredProposal(UUID.randomUUID(), created, created.plusSeconds(300), snapshot, expectation);
            // Deterministic observations owned by this test provider, not corporate authorization evidence.
            var evaluation = new BulkEvaluationSnapshot(proposal, proposal.createdAt(),
                    List.of(new BulkTargetEvidence<>(new BulkTarget<>("historical-item", "v1"), "v1",
                            mapper.createObjectNode().put("fixtureVersion", "v1"),
                            mapper.createObjectNode().put("fixtureAction", "approve"), BulkTargetEligibility.executable())),
                    new BulkEvaluationGovernance("historical-test-evaluator-r1", "historical-test-grants-r1",
                            List.of(new BulkPolicyObservation("historical-test-tenant", "test", "domain", "action",
                                    ArtifactBulkController.CONFIRMATION, "TEST_RESOLVED", "historical-test-policy-r1",
                                    proposal.createdAt()))));
            var preview = new BulkPreviewProjection(evaluation, "historical-test-preview-r1", List.of());
            var runtime = application.getBean(BulkExecutionInfrastructure.class);
            assertThat(new JdbcTemplate(runtime.dataSource()).queryForObject("select current_user", String.class))
                    .isEqualTo(RUNTIME_ROLE);
            var store = new JdbcBulkProposalStore(runtime);
            new TransactionTemplate(runtime.transactionManager()).executeWithoutResult(tx -> store.insertEvaluated(evaluation, preview));
            // Independent observer connection, after the runtime transaction has committed.
            var sql = new JdbcTemplate(deployment);
            byte[] payload = sql.queryForObject("select payload from praxis_bulk.praxis_bulk_proposal where proposal_id=?",
                    byte[].class, proposal.id());
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_allocation where proposal_id=? "
                    + "and kind='PROPOSAL_PENDING' and state='PENDING'", Integer.class, proposal.id())).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution", Integer.class)).isZero();
            new TransactionTemplate(runtime.transactionManager()).executeWithoutResult(tx ->
                    assertThat(store.find(context, proposal.id()).orElseThrow().snapshot().fingerprint())
                            .isEqualTo(snapshot.fingerprint()));
            byte[] evaluationPayload = sql.queryForObject("select payload from praxis_bulk.praxis_bulk_evaluation "
                    + "where proposal_id=?", byte[].class, proposal.id());
            new TransactionTemplate(runtime.transactionManager()).executeWithoutResult(tx -> {
                var retained = store.findEvaluation(context, proposal.id()).orElseThrow();
                assertThat(retained.fingerprint()).isEqualTo(evaluation.fingerprint());
                assertThat(retained.targets()).hasSize(1);
                assertThat(retained.targets().get(0).target().id()).isEqualTo("historical-item");
                assertThat(retained.targets().get(0).observedVersion()).isEqualTo("v1");
            });
            for (var table : List.of("praxis_bulk_target_manifest", "praxis_bulk_preview_state", "praxis_bulk_target_preview",
                    "praxis_bulk_preview_item_integrity")) {
                assertThat(sql.queryForObject("select count(*) from praxis_bulk." + table + " where proposal_id=?",
                        Integer.class, proposal.id())).isEqualTo(1);
            }
            evidence.setProperty("evaluation.fingerprint", evaluation.fingerprint());
            evidence.setProperty("evaluation.payload.sha256", sha256(evaluationPayload));
            evidence.setProperty("proposal.id", proposal.id().toString());
            evidence.setProperty("proposal.payload.sha256", sha256(payload));
            evidence.setProperty("proposal.fingerprint", snapshot.fingerprint());
            evidence.setProperty("namespace", NAMESPACE); evidence.setProperty("deployment", DEPLOYMENT);
            evidence.setProperty("subject", context.subjectId()); evidence.setProperty("resource", context.resourceKey());
            evidence.setProperty("operation.group", context.operationRef().group());
            evidence.setProperty("operation.id", context.operationRef().operationId());
            evidence.setProperty("operation.path", context.operationRef().path());
            evidence.setProperty("operation.method", context.operationRef().method());
            evidence.setProperty("schema.revision", context.schemaRevision());
            evidence.setProperty("control.generation", Long.toString(expectation.generation()));
            evidence.setProperty("control.fingerprint", expectation.descriptorFingerprint());
            evidence.setProperty("control.revision", expectation.structuralRevision());
            evidence.setProperty("historical.jar", jar.toString()); evidence.setProperty("historical.jar.sha256", sha256(jar));
            evidence.setProperty("historical.classpath", classPath);
            evidence.setProperty("historical.codeSource", jar.toString());
            boolean suspended = Boolean.parseBoolean(System.getProperty("historical.suspend.before.close", "false"));
            if (suspended) {
                long generation = application.getBean(BulkOperationLifecycle.class).suspend(OPERATION, 1);
                assertThat(generation).isEqualTo(2);
                assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_operation_control where state='READY'",
                        Integer.class)).isZero();
                assertThat(sql.queryForObject("select state from praxis_bulk.praxis_bulk_openapi_publication",
                        String.class)).isEqualTo("SUSPENDED");
            }
            evidence.setProperty("historical.control.state", suspended ? "SUSPENDED" : "READY");
            evidence.setProperty("historical.control.generation", suspended ? "2" : "1");
            evidence.setProperty("historical.version", "8.0.0-rc.149");
        }
        // Closing the child application cannot close the parent-owned PostgreSQL.
        BulkExecutionMigrator.validate(deployment, roles);
        try (var output = Files.newOutputStream(Path.of(requiredProperty("historical.evidence.file")))) {
            evidence.store(output, "rc149 native writer committed state; child context closed");
        }
    }

    private static ConfigurableApplicationContext startApplication(String jdbcUrl, boolean bulkOptIn) {
        SpringApplicationBuilder builder = bulkOptIn
                ? new SpringApplicationBuilder(ArtifactConsumerApplication.class, ArtifactBulkTestConfiguration.class)
                : new SpringApplicationBuilder(ArtifactConsumerApplication.class);
        String username = bulkOptIn ? RUNTIME_ROLE : "postgres";
        return builder
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "spring.datasource.url=" + requiredProperty("historical.jdbc.runtime.url"),
                        "spring.datasource.username=" + username,
                        "spring.datasource.password=",
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.datasource.hikari.maximum-pool-size=4",
                        "spring.jpa.open-in-view=false",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "spring.flyway.enabled=false",
                        "springdoc.api-docs.enabled=true",
                        "springdoc.api-docs.version=OPENAPI_3_0",
                        "springdoc.cache.disabled=true",
                        "consumer.bulk.control-url=" + requiredProperty("historical.jdbc.control.url"),
                        "consumer.bulk.namespace=" + NAMESPACE,
                        "consumer.bulk.deployment=" + DEPLOYMENT,
                        "logging.level.root=WARN")
                .run();
    }

    private static void createLoginRole(DataSource admin, String role) {
        assertThat(role).matches("[a-z][a-z0-9_]{0,62}");
        new org.springframework.jdbc.core.JdbcTemplate(admin).execute(
                "do $$ begin create role " + role + " login; exception when duplicate_object then null; end $$");
    }

    private static void provisionRuntimeRole(DataSource schemaOwner, String role) {
        assertThat(role).matches("[a-z][a-z0-9_]{0,62}");
        var admin = new org.springframework.jdbc.core.JdbcTemplate(schemaOwner);
        admin.execute("grant usage on schema praxis_bulk to " + role);
        admin.execute("grant select on praxis_bulk.praxis_bulk_namespace_binding to " + role);
        admin.execute("grant update (deployment_id) on praxis_bulk.praxis_bulk_namespace_binding to " + role);
        admin.execute("grant select, update (deployment_id) on praxis_bulk.praxis_bulk_deployment_bucket to " + role);
        admin.execute("grant select, insert, update (deployment_id) on praxis_bulk.praxis_bulk_subject_bucket to " + role);
        admin.execute("grant select, insert, update (proposal_id) on praxis_bulk.praxis_bulk_proposal to " + role);
        admin.execute("grant select, insert on praxis_bulk.praxis_bulk_evaluation to " + role);
        for (String table : Set.of("praxis_bulk_target_manifest", "praxis_bulk_preview_state",
                "praxis_bulk_target_preview", "praxis_bulk_preview_item_integrity")) {
            if (Boolean.TRUE.equals(admin.queryForObject(
                    "select to_regclass(?) is not null", Boolean.class, "praxis_bulk." + table))) {
                admin.execute("grant select, insert on praxis_bulk." + table + " to " + role);
            }
        }
        admin.execute("grant select, insert, update on praxis_bulk.praxis_bulk_execution to " + role);
        admin.execute("grant select, insert on praxis_bulk.praxis_bulk_item_receipt to " + role);
        admin.execute("grant select, insert on praxis_bulk.praxis_bulk_admission to " + role);
        admin.execute("grant select, insert, update (state, released_at, release_reason) "
                + "on praxis_bulk.praxis_bulk_allocation to " + role);
        admin.execute("grant select on praxis_bulk.praxis_bulk_tombstone to " + role);
        admin.execute("grant execute on function praxis_bulk.lock_operation_control(text,text) to " + role);
        admin.execute("grant execute on function praxis_bulk.lock_openapi_publication(text,text) to " + role);
        if (Boolean.TRUE.equals(admin.queryForObject(
                "select to_regprocedure('praxis_bulk.assert_preview_integrity_complete()') is not null", Boolean.class))) {
            admin.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to " + role);
        }
    }

    private static void provisionControlRole(DataSource schemaOwner, String role) {
        assertThat(role).matches("[a-z][a-z0-9_]{0,62}");
        var admin = new org.springframework.jdbc.core.JdbcTemplate(schemaOwner);
        admin.execute("grant usage on schema praxis_bulk to " + role);
        admin.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text,bigint,text) to "
                + role);
        admin.execute("grant execute on function praxis_bulk.transition_openapi_publication(text,text,bigint,text,text) to "
                + role);
    }

    private static HttpResponse<String> httpPost(HttpClient client, String uri, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json").POST(publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void assertPublishedPomVersion(String expectedVersion) throws Exception {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/META-INF/maven/io.github.codexrodrigues/praxis-metadata-starter/pom.properties")) {
            assertThat(input).isNotNull();
            Properties properties = new Properties();
            properties.load(input);
            assertThat(properties.getProperty("version")).isEqualTo(expectedVersion);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            input.transferTo(new java.security.DigestOutputStream(OutputStreamSink.INSTANCE, digest));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertThat(value).as("required system property %s", name).isNotBlank();
        return value;
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();
        @Override public void write(int ignored) { }
        @Override public void write(byte[] bytes, int offset, int length) { }
    }
}
