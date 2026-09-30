package org.praxisplatform.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.bulk.BulkControlPlaneInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionMigrator;
import org.praxisplatform.uischema.bulk.BulkExecutionRoleConfiguration;
import org.praxisplatform.uischema.bulk.BulkIdentityCodecs;
import org.praxisplatform.uischema.bulk.BulkOperationControlIdentity;
import org.praxisplatform.uischema.bulk.BulkOperationLifecycle;
import org.praxisplatform.uischema.bulk.BulkResourceOperationBindings;
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

    @TempDir
    Path temporaryDirectory;

    @Test
    void consumesJarRunsOptInMigrationsAndServesBulkHttp() throws Exception {
        Path expectedJar = Path.of(requiredProperty("candidate.jar")).toRealPath();
        String expectedJarSha256 = requiredProperty("candidate.jar.sha256");
        String candidateVersion = requiredProperty("praxis.metadata.version");
        Path candidateSource = Path.of(requiredProperty("candidate.source.root")).toRealPath();

        Path codeSource = Path.of(BulkExecutionMigrator.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        assertThat(codeSource).isEqualTo(expectedJar);
        assertThat(codeSource.toString()).endsWith(".jar").doesNotContain("target/classes");
        assertThat(sha256(codeSource)).isEqualTo(expectedJarSha256);
        assertThat(Path.of(ApiResource.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath())
                .isEqualTo(expectedJar);
        var migrationResource = BulkExecutionMigrator.class.getResource(
                "/db/praxis-bulk-migrations/V13__bulk_execution_time_order.sql");
        assertThat(migrationResource).isNotNull();
        assertThat(migrationResource.getProtocol()).isEqualTo("jar");
        assertThat(migrationResource.toString()).contains(expectedJar.getFileName().toString());
        assertThat(System.getProperty("java.class.path")).doesNotContain(candidateSource.toString())
                .doesNotContain(candidateSource.resolve("src/main").toString())
                .doesNotContain(candidateSource.resolve("target/classes").toString());
        assertPublishedPomVersion(candidateVersion);

        Properties evidence = new Properties();
        evidence.setProperty("fixture.source.sha256", requiredProperty("consumer.fixture.sha256"));
        evidence.setProperty("candidate.version", candidateVersion);
        evidence.setProperty("candidate.jar.sha256", expectedJarSha256);
        evidence.setProperty("candidate.codeSource", codeSource.toString());

        Path postgresWork = temporaryDirectory.resolve("postgres-work");
        Files.createDirectories(postgresWork);
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setOverrideWorkingDirectory(postgresWork.toFile())
                .setCleanDataDirectory(true)
                .setRegisterShutdownHook(false)
                .start()) {
            var deploymentDataSource = postgres.getPostgresDatabase();
            try (ConfigurableApplicationContext bootstrap = startApplication(postgres, false)) {
                assertThat(bootstrap.getBeansOfType(BulkOperationLifecycle.class))
                        .as("lifecycle is not enabled without explicit host infrastructures").isEmpty();
                assertThat(tableExists(deploymentDataSource.getConnection(), "praxis_bulk.praxis_bulk_proposal"))
                        .as("application bootstrap must not create the bulk schema").isFalse();
                evidence.setProperty("postgres.bootstrapDidNotMigrate", "true");
            }

            createLoginRole(deploymentDataSource, RUNTIME_ROLE);
            createLoginRole(deploymentDataSource, CONTROL_ROLE);
            int migrations = BulkExecutionMigrator.migrate(deploymentDataSource, Map.of(NAMESPACE, DEPLOYMENT));
            provisionRuntimeRole(deploymentDataSource, RUNTIME_ROLE);
            provisionControlRole(deploymentDataSource, CONTROL_ROLE);
            BulkExecutionRoleConfiguration roles = new BulkExecutionRoleConfiguration(
                    "postgres", Set.of(RUNTIME_ROLE), Set.of(), Set.of(CONTROL_ROLE));
            BulkExecutionMigrator.migrate(deploymentDataSource,
                    Map.of(NAMESPACE, DEPLOYMENT), roles, java.util.List.of(OPERATION));
            assertThat(migrations).isGreaterThanOrEqualTo(13);
            BulkExecutionMigrator.validate(deploymentDataSource, roles);
            assertThat(tableExists(deploymentDataSource.getConnection(), "praxis_bulk.praxis_bulk_proposal"))
                    .isTrue();
            evidence.setProperty("postgres.migrationsOptIn", "true");
            evidence.setProperty("postgres.migrationsExecuted", Integer.toString(migrations));
            evidence.setProperty("postgres.version", postgresVersion(deploymentDataSource.getConnection()));

            try (ConfigurableApplicationContext application = startApplication(postgres, true)) {
                assertThat(application.getBeansOfType(BulkOperationLifecycle.class)).hasSize(1);
                evidence.setProperty("bulk.lifecycleBeanCreated", "true");
                BulkResourceOperationBindings bindings = application.getBean(BulkResourceOperationBindings.class);
                assertThat(bindings.diagnostics()).isEmpty();
                assertThat(bindings.handlerFor(ArtifactBulkController.PROPOSAL)).isPresent();
                assertThat(bindings.handlerFor(ArtifactBulkController.PROPOSAL_RESULTS)).isPresent();
                assertThat(bindings.handlerFor(ArtifactBulkController.EXECUTION)).isPresent();
                assertThat(bindings.handlerFor(ArtifactBulkController.EXECUTION_RESULTS)).isPresent();
                assertThat(bindings.handlerFor(ArtifactBulkController.CANCEL)).isPresent();
                assertThat(bindings.handlerFor(ArtifactBulkController.CONFIRMATION)).isPresent();
                assertThat(bindings.handlerFor(ArtifactBulkController.EVALUATION)).isPresent();

                int port = ((WebServerApplicationContext) application).getWebServer().getPort();
                String baseUrl = "http://127.0.0.1:" + port;
                ObjectMapper mapper = application.getBean(ObjectMapper.class);
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

                JsonNode evaluationRequestSchema = responseJson(httpGet(http, baseUrl
                        + "/schemas/filtered?path=%2Fartifact-items%2Factions%2Fbulk-approve%2Fevaluation"
                        + "&operation=post&schemaType=request"), mapper);
                assertThat(evaluationRequestSchema.at("/properties/targetIds/items/type").asText())
                        .isEqualTo("string");
                assertThat(evaluationRequestSchema.at("/properties/targetIds/items/minLength").asInt())
                        .isEqualTo(1);

                JsonNode lifecyclePublication = responseJson(httpPost(http,
                        baseUrl + "/_test/bulk-lifecycle/publish-and-verify", null), mapper);
                assertThat(lifecyclePublication.path("verified").asBoolean()).isTrue();
                assertThat(lifecyclePublication.path("generation").asLong()).isEqualTo(1L);
                assertThat(new org.springframework.jdbc.core.JdbcTemplate(deploymentDataSource).queryForObject(
                        "select state from praxis_bulk.praxis_bulk_operation_control where namespace_id=? and operation_id=?",
                        String.class, NAMESPACE, ArtifactBulkController.CONFIRMATION)).isEqualTo("READY");
                evidence.setProperty("bulk.readyPublished", "true");

                assertJson(httpGet(http, baseUrl + "/artifact-items/bulk/proposals/proposal-1"),
                        mapper, "kind", "proposal");
                assertJson(httpGet(http, baseUrl + "/artifact-items/bulk/proposals/proposal-1/results"),
                        mapper, "kind", "proposal-results");
                assertJson(httpGet(http, baseUrl + "/artifact-items/bulk/executions/execution-1"),
                        mapper, "kind", "execution");
                assertJson(httpGet(http, baseUrl + "/artifact-items/bulk/executions/execution-1/results"),
                        mapper, "kind", "execution-results");
                assertJson(httpPost(http, baseUrl + "/artifact-items/bulk/executions/execution-1/cancel", null),
                        mapper, "kind", "cancel");
                evidence.setProperty("http.bulkLifecycle", "true");

                assertJson(httpPost(http, baseUrl + "/artifact-items/actions/bulk-approve/evaluation",
                                "{\"targetIds\":[\"10\",\"11\"]}"), mapper, "state", "route-dispatch-only");
                assertJson(httpPost(http, baseUrl + "/artifact-items/actions/bulk-approve",
                        "{\"proposalId\":\"proposal-1\"}"), mapper, "state", "route-dispatch-only");
                evidence.setProperty("http.bulkActionRouteDispatch", "true");

                JsonNode openApi = responseJson(awaitGet(http,
                        baseUrl + "/v3/api-docs/artifact-consumer"), mapper);
                assertOperation(openApi, "/artifact-items/bulk/proposals/{proposalId}", "get",
                        ArtifactBulkController.PROPOSAL);
                assertOperation(openApi, "/artifact-items/bulk/proposals/{proposalId}/results", "get",
                        ArtifactBulkController.PROPOSAL_RESULTS);
                assertOperation(openApi, "/artifact-items/bulk/executions/{executionId}", "get",
                        ArtifactBulkController.EXECUTION);
                assertOperation(openApi, "/artifact-items/bulk/executions/{executionId}/results", "get",
                        ArtifactBulkController.EXECUTION_RESULTS);
                assertOperation(openApi, "/artifact-items/bulk/executions/{executionId}/cancel", "post",
                        ArtifactBulkController.CANCEL);
                assertOperation(openApi, "/artifact-items/actions/bulk-approve/evaluation", "post",
                        ArtifactBulkController.EVALUATION);
                assertOperation(openApi, "/artifact-items/actions/bulk-approve", "post",
                        ArtifactBulkController.CONFIRMATION);
                evidence.setProperty("http.openApi", "true");
                evidence.setProperty("http.openApiReserializedJsonUtf8Sha256", sha256(
                        mapper.writeValueAsBytes(openApi)));

                HttpResponse<String> filteredSchema = httpGet(http,
                        baseUrl + "/schemas/filtered?path=%2Fartifact-items%2Factions%2Fbulk-approve"
                                + "&operation=post&schemaType=request");
                assertThat(filteredSchema.statusCode()).as(filteredSchema.body()).isEqualTo(200);
                JsonNode schema = mapper.readTree(filteredSchema.body());
                assertThat(schema.path("properties").has("proposalId")).isTrue();
                String structuralHash = filteredSchema.headers().firstValue("X-Schema-Hash").orElseThrow();
                assertThat(structuralHash).isNotBlank();
                assertThat(filteredSchema.headers().firstValue("ETag")).contains("\"" + structuralHash + "\"");
                evidence.setProperty("http.filteredSchemaHash", structuralHash);
                evidence.setProperty("http.filteredSchemaResponseUtf8Sha256", sha256(
                        filteredSchema.body().getBytes(StandardCharsets.UTF_8)));
                evidence.setProperty("http.filteredSchema", "true");

                JsonNode actions = responseJson(httpGet(http,
                        baseUrl + "/schemas/actions?resource=artifact.items"), mapper);
                assertThat(actions.path("actions").findValuesAsText("id"))
                        .contains("bulk-approve");
                evidence.setProperty("http.actionCatalog", "true");
            }
        }

        Path evidenceFile = Path.of(requiredProperty("consumer.evidence.file"));
        Files.createDirectories(evidenceFile.toAbsolutePath().getParent());
        try (var output = Files.newOutputStream(evidenceFile)) {
            evidence.store(output, "Independent consumer evidence");
        }
    }

    private static ConfigurableApplicationContext startApplication(EmbeddedPostgres postgres, boolean bulkOptIn) {
        SpringApplicationBuilder builder = bulkOptIn
                ? new SpringApplicationBuilder(ArtifactConsumerApplication.class, ArtifactBulkTestConfiguration.class)
                : new SpringApplicationBuilder(ArtifactConsumerApplication.class);
        String username = bulkOptIn ? RUNTIME_ROLE : "postgres";
        return builder
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "spring.datasource.url=" + postgres.getJdbcUrl(username, "postgres"),
                        "spring.datasource.username=" + username,
                        "spring.datasource.password=",
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.datasource.hikari.maximum-pool-size=4",
                        "spring.jpa.open-in-view=false",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "spring.flyway.enabled=false",
                        "springdoc.api-docs.enabled=true",
                        "springdoc.cache.disabled=true",
                        "consumer.bulk.control-url=" + postgres.getJdbcUrl(CONTROL_ROLE, "postgres"),
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
        if (Boolean.TRUE.equals(admin.queryForObject(
                "select to_regprocedure('praxis_bulk.assert_preview_integrity_complete()') is not null", Boolean.class))) {
            admin.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to " + role);
        }
    }

    private static void provisionControlRole(DataSource schemaOwner, String role) {
        assertThat(role).matches("[a-z][a-z0-9_]{0,62}");
        var admin = new org.springframework.jdbc.core.JdbcTemplate(schemaOwner);
        admin.execute("grant usage on schema praxis_bulk to " + role);
        admin.execute("grant execute on function praxis_bulk.transition_operation_control(text,text,bigint,text,text,text) to "
                + role);
    }

    private static boolean tableExists(Connection connection, String name) throws Exception {
        try (connection; var statement = connection.prepareStatement("select to_regclass(?) is not null")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                boolean exists = result.getBoolean(1);
                assertThat(result.next()).isFalse();
                return exists;
            }
        }
    }

    private static String postgresVersion(Connection connection) throws Exception {
        try (connection; var statement = connection.createStatement(); var result = statement.executeQuery(
                "select current_setting('server_version')")) {
            assertThat(result.next()).isTrue();
            String version = result.getString(1);
            assertThat(result.next()).isFalse();
            return version;
        }
    }

    private static HttpResponse<String> httpGet(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> httpPost(HttpClient client, String uri, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json").POST(publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> awaitGet(HttpClient client, String uri) throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            response = httpGet(client, uri);
            if (response.statusCode() == 200) return response;
            Thread.sleep(100L);
        }
        return response;
    }

    private static void assertJson(HttpResponse<String> response, ObjectMapper mapper,
            String field, String expected) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path(field).asText()).isEqualTo(expected);
    }

    private static JsonNode responseJson(HttpResponse<String> response, ObjectMapper mapper) throws Exception {
        assertThat(response).isNotNull();
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private static void assertOperation(JsonNode document, String path, String method, String operationId) {
        assertThat(document.path("paths").path(path).path(method).path("operationId").asText())
                .isEqualTo(operationId);
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
