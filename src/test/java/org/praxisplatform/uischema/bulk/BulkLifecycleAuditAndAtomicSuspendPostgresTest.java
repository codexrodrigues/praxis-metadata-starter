package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentCapture;
import org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real PostgreSQL 17 integration tests for V21 durable lifecycle audit ledger, single-transaction
 * atomic suspension, immutability triggers, and least-privilege role enforcement.
 */
class BulkLifecycleAuditAndAtomicSuspendPostgresTest {
    private static final String NAMESPACE = "test-namespace";
    private static final String DEPLOYMENT = "deployment-v21-test";

    @Test
    void freshV21InstallIdempotentReplayAndV19ToV21UpgradeValidateStrictCatalog() throws Exception {
        try (var freshPg = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
             var upgradePg = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
             var historicalSdk = historicalRc155Sdk()) {
            DataSource freshOwner = freshPg.getPostgresDatabase();
            JdbcTemplate freshSql = new JdbcTemplate(freshOwner);
            BulkExecutionRoleConfiguration freshRoles = BulkExecutionRoleConfiguration.none("postgres");

            assertThat(BulkExecutionMigrator.migrate(freshOwner, Map.of(NAMESPACE, DEPLOYMENT), freshRoles))
                    .isEqualTo(21);
            BulkExecutionMigrator.validate(freshOwner, freshRoles);
            assertThat(BulkExecutionMigrator.migrate(freshOwner, Map.of(NAMESPACE, DEPLOYMENT), freshRoles))
                    .isZero();
            assertThat(freshSql.queryForObject(
                    "select phase from praxis_bulk.praxis_bulk_lifecycle_audit_bootstrap where bootstrap_version = 21",
                    String.class)).isEqualTo("COMPLETE");

            DataSource upgradeOwner = upgradePg.getPostgresDatabase();
            JdbcTemplate upgradeSql = new JdbcTemplate(upgradeOwner);
            assertThat(invokeHistoricalV19Migrate(historicalSdk, upgradeOwner)).isEqualTo(19);
            List<Map<String, Object>> v19History = upgradeSql.queryForList(
                    "select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");

            assertThat(BulkExecutionMigrator.migrate(upgradeOwner, Map.of(NAMESPACE, DEPLOYMENT), freshRoles))
                    .isEqualTo(2);
            assertThat(upgradeSql.queryForList(
                    "select * from praxis_bulk.praxis_bulk_schema_history where version is null or version::int <= 19 order by installed_rank"))
                    .isEqualTo(v19History);
            assertThat(upgradeSql.queryForObject(
                    "select phase from praxis_bulk.praxis_bulk_lifecycle_audit_bootstrap where bootstrap_version = 21",
                    String.class)).isEqualTo("COMPLETE");
            BulkExecutionMigrator.validate(upgradeOwner, freshRoles);
            assertThat(BulkExecutionMigrator.migrate(upgradeOwner, Map.of(NAMESPACE, DEPLOYMENT), freshRoles))
                    .isZero();
        }
    }

    @Test
    void auditTableIsImmutableAndEnforcesLeastPrivilegeAcrossRuntimeAndControlRoles() throws Exception {
        try (var pg = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start()) {
            DataSource admin = pg.getPostgresDatabase();
            JdbcTemplate adminSql = new JdbcTemplate(admin);
            BulkExecutionMigrator.migrateWithOperations(
                    admin, Map.of(NAMESPACE, DEPLOYMENT), List.of(identity("crud.uniform"), identity("crud.items")));
            BulkPostgresTestSupport.grantRuntimeRole(admin, "bulk_runtime_test");
            BulkPostgresTestSupport.grantControlRole(admin, "bulk_control_test");
            BulkExecutionRoleConfiguration roles = new BulkExecutionRoleConfiguration(
                    "postgres", Set.of("bulk_runtime_test"), Set.of(), Set.of("bulk_control_test"));
            BulkExecutionMigrator.validate(admin, roles);

            DataSource controlDs = new DriverManagerDataSource(pg.getJdbcUrl("bulk_control_test", "postgres"), "bulk_control_test", "");
            DataSource runtimeDs = BulkPostgresTestSupport.runtimeDataSource(pg);
            DataSourceTransactionManager runtimeTxManager = new DataSourceTransactionManager(runtimeDs);
            TransactionTemplate runtimeTx = new TransactionTemplate(runtimeTxManager);
            BulkExecutionInfrastructure runtime = new BulkExecutionInfrastructure(
                    runtimeDs, runtimeTxManager, NAMESPACE, DEPLOYMENT, roles);
            BulkControlPlaneInfrastructure control = new BulkControlPlaneInfrastructure(
                    controlDs, new DataSourceTransactionManager(controlDs), NAMESPACE, DEPLOYMENT, "bulk_control_test", runtime);

            UUID auditId = UUID.randomUUID();
            control.withConnection(connection -> {
                JdbcBulkLifecycleAudit.record(connection, auditId, NAMESPACE, DEPLOYMENT, "crud.uniform",
                        "PUBLISH", BulkLifecycleAuditContext.of("alice", "platform", "corr-1"),
                        0L, 1L, "READY");
                return null;
            });

            List<BulkLifecycleAuditEntry> readEntries = runtime.withLifecycleRead(
                    connection -> JdbcBulkLifecycleAudit.read(connection, NAMESPACE, 10));
            assertThat(readEntries).hasSize(1);
            assertThat(readEntries.getFirst().auditId()).isEqualTo(auditId);

            // Runtime role must not write audit or mutate control/publication via functions or direct SQL
            assertSqlErrorCode(() -> runtimeTx.executeWithoutResult(status -> runtime.withConnection(connection -> {
                try {
                    JdbcBulkLifecycleAudit.record(connection, UUID.randomUUID(), NAMESPACE, DEPLOYMENT, "crud.uniform",
                            "PUBLISH", BulkLifecycleAuditContext.system(), 1L, 2L, "READY");
                    return null;
                } catch (SQLException sqlEx) {
                    throw new IllegalStateException(sqlEx);
                }
            })), "42501");
            assertSqlErrorCode(() -> runtimeTx.executeWithoutResult(status -> runtime.withConnection(connection -> {
                try {
                    return JdbcBulkOperationControl.suspendWithPublication(
                            connection, NAMESPACE, DEPLOYMENT, "crud.uniform", 0L, 0L);
                } catch (SQLException sqlEx) {
                    throw new IllegalStateException(sqlEx);
                }
            })), "42501");

            JdbcTemplate runtimeSql = new JdbcTemplate(runtimeDs);
            assertDataAccessSqlState(() -> runtimeSql.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_lifecycle_audit", Integer.class), "42501");
            assertDataAccessSqlState(() -> runtimeSql.update(
                    "delete from praxis_bulk.praxis_bulk_lifecycle_audit"), "42501");

            JdbcTemplate controlSql = new JdbcTemplate(controlDs);
            assertDataAccessSqlState(() -> controlSql.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_lifecycle_audit", Integer.class), "42501");
            assertDataAccessSqlState(() -> controlSql.update(
                    "update praxis_bulk.praxis_bulk_lifecycle_audit set actor = 'mallory'"), "42501");

            // Even table owner/superuser UPDATE or DELETE is denied by trigger (55000)
            assertDataAccessSqlState(() -> adminSql.update(
                    "update praxis_bulk.praxis_bulk_lifecycle_audit set actor = 'mallory' where audit_id = ?", auditId),
                    "55000");
            assertDataAccessSqlState(() -> adminSql.update(
                    "delete from praxis_bulk.praxis_bulk_lifecycle_audit where audit_id = ?", auditId),
                    "55000");
            BulkExecutionMigrator.validate(admin, roles);
        }
    }

    @Test
    void atomicSuspendRollsBackOperationAndAuditOnGlobalFailureAndPersistsAuditTrailAcrossAllIntents()
            throws Exception {
        try (var fixture = createFixture()) {
            BulkLifecycleAuditContext publishAudit = BulkLifecycleAuditContext.of("alice", "ops", "corr-pub-1");
            BulkOperationControlExpectation ready = fixture.lifecycle.publish(
                    identity("crud.uniform"), 0L, publishAudit);
            assertThat(ready.generation()).isEqualTo(1L);
            assertThat(fixture.state("crud.uniform").state()).isEqualTo("READY");
            assertThat(fixture.publication().state()).isEqualTo("PUBLISHED");
            assertThat(fixture.publication().generation()).isEqualTo(2L);

            BulkLifecycleAuditContext reconcileAudit = BulkLifecycleAuditContext.of("reconciler", "ops", "corr-rec-1");
            assertThat(fixture.lifecycle.reconcilePublished(identity("crud.uniform"), 1L, reconcileAudit).generation())
                    .isEqualTo(1L);

            // Inject a failure on the global publication table update inside the SAME transaction
            JdbcTemplate adminSql = new JdbcTemplate(fixture.postgres.getPostgresDatabase());
            adminSql.execute("""
                    create or replace function praxis_bulk.test_fail_publication_update()
                    returns trigger language plpgsql as $$
                    begin
                        raise exception 'injected publication failure' using errcode = '55000';
                    end;
                    $$
                    """);
            adminSql.execute("""
                    create trigger z_test_fail_publication_update
                    before update on praxis_bulk.praxis_bulk_openapi_publication
                    for each row execute function praxis_bulk.test_fail_publication_update()
                    """);
            try {
                BulkLifecycleAuditContext failedSuspendAudit = BulkLifecycleAuditContext.of(
                        "bob", "sec", "corr-susp-rollback");
                assertThatThrownBy(() -> fixture.lifecycle.suspend(identity("crud.uniform"), 1L, failedSuspendAudit))
                        .isInstanceOf(RuntimeException.class);
                // Because suspension runs in a single transaction, operation state must NOT advance to SUSPENDED
                assertThat(fixture.state("crud.uniform").state()).isEqualTo("READY");
                assertThat(fixture.state("crud.uniform").generation()).isEqualTo(1L);
                assertThat(fixture.publication().state()).isEqualTo("PUBLISHED");
                assertThat(fixture.publication().generation()).isEqualTo(2L);
                assertThat(fixture.lifecycle.listAuditEntries(20))
                        .extracting(BulkLifecycleAuditEntry::intent)
                        .containsExactly("RECONCILE", "PUBLISH");
            } finally {
                adminSql.execute("drop trigger if exists z_test_fail_publication_update on praxis_bulk.praxis_bulk_openapi_publication");
                adminSql.execute("drop function if exists praxis_bulk.test_fail_publication_update()");
            }

            // CAS stale generation conflict must reject without mutating or appending audit
            BulkLifecycleAuditContext suspendAudit = BulkLifecycleAuditContext.of("bob", "sec", "corr-susp-ok");
            assertThatThrownBy(() -> fixture.lifecycle.suspend(identity("crud.uniform"), 99L, suspendAudit))
                    .isInstanceOf(BulkPublicationConflictException.class);

            long suspendedGen = fixture.lifecycle.suspend(identity("crud.uniform"), 1L, suspendAudit);
            assertThat(suspendedGen).isEqualTo(2L);
            assertThat(fixture.state("crud.uniform").state()).isEqualTo("SUSPENDED");
            assertThat(fixture.state("crud.uniform").generation()).isEqualTo(2L);
            assertThat(fixture.publication().state()).isEqualTo("SUSPENDED");
            assertThat(fixture.publication().generation()).isEqualTo(3L);

            // UNCOMPOSED -> SUSPENDED transition works cleanly and records SUSPEND audit
            BulkLifecycleAuditContext uncomposedSuspendAudit = BulkLifecycleAuditContext.of(
                    "charlie", "ops", "corr-uncomp");
            long itemsSuspendedGen = fixture.lifecycle.suspend(identity("crud.items"), 0L, uncomposedSuspendAudit);
            assertThat(itemsSuspendedGen).isEqualTo(1L);
            assertThat(fixture.state("crud.items").state()).isEqualTo("SUSPENDED");
            assertThat(fixture.publication().generation()).isEqualTo(4L);

            List<BulkLifecycleAuditEntry> entries = fixture.lifecycle.listAuditEntries(10);
            assertThat(entries).hasSize(4);
            assertThat(entries)
                    .extracting(BulkLifecycleAuditEntry::intent)
                    .containsExactly("SUSPEND", "SUSPEND", "RECONCILE", "PUBLISH");

            BulkLifecycleAuditEntry latest = entries.get(0);
            assertThat(latest.namespaceId()).isEqualTo(NAMESPACE);
            assertThat(latest.deploymentId()).isEqualTo(DEPLOYMENT);
            assertThat(latest.operationId()).isEqualTo("crud.items");
            assertThat(latest.actor()).isEqualTo("charlie");
            assertThat(latest.realm()).isEqualTo("ops");
            assertThat(latest.previousGeneration()).isEqualTo(0L);
            assertThat(latest.resultingGeneration()).isEqualTo(1L);
            assertThat(latest.resultingState()).isEqualTo("SUSPENDED");
            assertThat(latest.correlationId()).isEqualTo("corr-uncomp");
            assertThat(latest.executedAt()).isNotNull();

            assertThat(entries.get(1).operationId()).isEqualTo("crud.uniform");
            assertThat(entries.get(1).previousGeneration()).isEqualTo(1L);
            assertThat(entries.get(1).resultingGeneration()).isEqualTo(2L);
            assertThat(entries.get(1).resultingState()).isEqualTo("SUSPENDED");
            assertThat(entries.get(2).previousGeneration()).isEqualTo(1L);
            assertThat(entries.get(2).resultingGeneration()).isEqualTo(1L);
            assertThat(entries.get(2).resultingState()).isEqualTo("READY");
            assertThat(entries.get(3).previousGeneration()).isEqualTo(0L);
            assertThat(entries.get(3).resultingGeneration()).isEqualTo(1L);
            assertThat(entries.get(3).resultingState()).isEqualTo("READY");

            assertThatThrownBy(() -> fixture.lifecycle.listAuditEntries(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.lifecycle.listAuditEntries(501))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void assertSqlErrorCode(ThrowingCallable action, String sqlState) {
        assertThatThrownBy(() -> {
            try {
                action.run();
            } catch (RuntimeException wrapped) {
                Throwable cursor = wrapped;
                while (cursor != null) {
                    if (cursor instanceof SQLException sqlEx && sqlState.equals(sqlEx.getSQLState())) {
                        throw sqlEx;
                    }
                    cursor = cursor.getCause();
                }
                throw wrapped;
            }
        }).isInstanceOf(SQLException.class);
    }

    private static void assertDataAccessSqlState(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String sqlState) {
        assertThatThrownBy(action)
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(((DataAccessException) ex).getMostSpecificCause())
                        .isInstanceOfSatisfying(SQLException.class,
                                sqlEx -> assertThat(sqlEx.getSQLState()).isEqualTo(sqlState)));
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void run() throws Exception;
    }

    private static BulkOperationControlIdentity identity(String operationId) {
        return new BulkOperationControlIdentity(NAMESPACE, operationId);
    }

    private static Object invokeHistoricalV19Migrate(URLClassLoader sdk, DataSource owner) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(sdk);
            return sdk.loadClass("org.praxisplatform.uischema.bulk.BulkExecutionMigrator")
                    .getMethod("migrate", DataSource.class, Map.class)
                    .invoke(null, owner, Map.of(NAMESPACE, DEPLOYMENT));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static URLClassLoader historicalRc155Sdk() throws Exception {
        Path jar = Path.of(System.getProperty("user.dir"), "target", "historical-bulk-sdk", "metadata-v19-rc155.jar")
                .toRealPath();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))))
                .isEqualTo("8be69c98a109567118eb12d5f8b29ed955fe54335876a388ca3350265c71c371");
        var paths = new java.util.LinkedHashSet<Path>();
        paths.add(jar);
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String part : cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path dependency = Path.of(part);
            if (!Files.isRegularFile(dependency) || !part.endsWith(".jar")) continue;
            try (var archive = new JarFile(dependency.toFile())) {
                if (archive.getJarEntry("org/praxisplatform/uischema/bulk/BulkExecutionMigrator.class") != null) continue;
            }
            paths.add(dependency.toRealPath());
        }
        var urls = new java.net.URL[paths.size()];
        int ordinal = 0;
        for (Path path : paths) urls[ordinal++] = path.toUri().toURL();
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static LifecycleFixture createFixture() throws Exception {
        EmbeddedPostgres pg = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
        try {
            DataSource admin = pg.getPostgresDatabase();
            BulkExecutionMigrator.migrateWithOperations(admin, Map.of(NAMESPACE, DEPLOYMENT),
                    List.of(identity("crud.uniform"), identity("crud.items")));
            BulkPostgresTestSupport.grantRuntimeRole(admin, "bulk_runtime_test");
            BulkPostgresTestSupport.grantControlRole(admin, "bulk_control_test");
            var roles = new BulkExecutionRoleConfiguration(
                    "postgres", Set.of("bulk_runtime_test"), Set.of(), Set.of("bulk_control_test"));
            DataSource runtimeDs = BulkPostgresTestSupport.runtimeDataSource(pg);
            DataSource controlDs = new DriverManagerDataSource(
                    pg.getJdbcUrl("bulk_control_test", "postgres"), "bulk_control_test", "");
            var runtime = new BulkExecutionInfrastructure(
                    runtimeDs, new DataSourceTransactionManager(runtimeDs), NAMESPACE, DEPLOYMENT, roles);
            var control = new BulkControlPlaneInfrastructure(
                    controlDs, new DataSourceTransactionManager(controlDs), NAMESPACE, DEPLOYMENT, "bulk_control_test", runtime);

            var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class);
            ObjectNode value = BulkCrudOperationalCompositionTest.document();
            value.put("openapi", "3.0.3");
            value.putObject("info").put("title", "V21 audit fixture").put("version", "1");
            var filterHolder = new GovernedOpenApiPublicationFilter[1];
            var registration = mock(jakarta.servlet.FilterRegistration.class);
            when(registration.getClassName()).thenReturn(GovernedOpenApiPublicationFilter.class.getName());
            when(registration.getUrlPatternMappings()).thenReturn(List.of("/*"));
            when(registration.getServletNameMappings()).thenReturn(List.of());
            var servletContext = new MockServletContext() {
                @Override
                public jakarta.servlet.FilterRegistration getFilterRegistration(String name) {
                    return registration;
                }
            };
            ObjectMapper mapper = BulkCrudStructuralCompilerTest.mapper();
            OpenApiDocsSupport source = new OpenApiDocsSupport() {
                @Override
                public String localPublicationContextPath() {
                    return "";
                }

                @Override
                public OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(
                        RestTemplate client, String path, Logger logger, ObjectMapper m) {
                    var request = new MockHttpServletRequest(servletContext, "GET", path);
                    request.setServletPath(path);
                    request.addHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER,
                            ((OpenApiInternalRestTemplate) client).producerCaptureToken(path));
                    var response = new MockHttpServletResponse();
                    try {
                        filterHolder[0].doFilter(request, response, (input, output) -> {
                            byte[] bytes = path.endsWith("/swagger-config")
                                    ? "{\"urls\":[{\"name\":\"crud\",\"url\":\"/v3/api-docs/crud\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                    : m.writeValueAsBytes(value);
                            output.getOutputStream().write(bytes);
                        });
                        return OpenApiDocumentCapture.parse(response.getContentAsByteArray(), m);
                    } catch (Exception ex) {
                        throw new IllegalStateException("Servlet producer fixture failed", ex);
                    }
                }

                @Override
                public String resolveGroupFromPath(String path) {
                    return "crud";
                }

                @Override
                public JsonNode fetchOpenApiDocument(RestTemplate client, String base, String group, Logger logger) {
                    return value.deepCopy();
                }

                @Override
                public JsonNode fetchOpenApiGroupDocument(RestTemplate client, String base, String group, Logger logger) {
                    return value.deepCopy();
                }

                @Override
                public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate client, String base, String group, Logger logger) {
                    return value.deepCopy();
                }
            };
            var client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10));
            var documents = new CachedOpenApiDocumentService(client, new ObjectMapper(), source, true);
            ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");
            filterHolder[0] = new GovernedOpenApiPublicationFilter(documents);
            filterHolder[0].init(new MockFilterConfig(servletContext, "bulkPublication"));

            var mvc = context.getBean(RequestMappingHandlerMapping.class);
            var bindings = BulkResourceOperationBindings.from(mvc);
            var resolver = new OpenApiCanonicalOperationResolver(documents, mvc, bindings, List.of("crud"));
            List<BulkOperationDescriptorProvider> providers = List.of(
                    new BulkCrudOperationalCompositionTest.Provider("crud.uniform", "r1",
                            BulkCrudOperationalCompositionTest.UNIFORM_POINTER, BulkMode.UNIFORM_UPDATE, runtime),
                    new BulkCrudOperationalCompositionTest.Provider("crud.items", "r1",
                            BulkCrudOperationalCompositionTest.ITEMS_POINTER, BulkMode.PER_ITEM_UPDATE, runtime));
            var lifecycle = new BulkOperationLifecycle(bindings, resolver, documents, mock(ActionDefinitionRegistry.class),
                    mapper, new FilteredSchemaReferenceResolver(), runtime, control, providers);
            return new LifecycleFixture(pg, runtime, context, client, lifecycle);
        } catch (Exception | Error failure) {
            pg.close();
            throw failure;
        }
    }

    private record LifecycleFixture(
            EmbeddedPostgres postgres,
            BulkExecutionInfrastructure runtime,
            AnnotationConfigWebApplicationContext context,
            OpenApiInternalRestTemplate client,
            BulkOperationLifecycle lifecycle) implements AutoCloseable {

        JdbcBulkOpenApiPublication.Snapshot publication() {
            return runtime.withLifecycleRead(connection ->
                    JdbcBulkOpenApiPublication.lockForRead(connection, NAMESPACE, DEPLOYMENT));
        }

        JdbcBulkOperationControl.Snapshot state(String operationId) {
            return runtime.withLifecycleRead(connection ->
                    JdbcBulkOperationControl.lockForAdmission(connection, NAMESPACE, operationId));
        }

        @Override
        public void close() throws Exception {
            try {
                context.close();
            } finally {
                try {
                    client.close();
                } finally {
                    postgres.close();
                }
            }
        }
    }
}
