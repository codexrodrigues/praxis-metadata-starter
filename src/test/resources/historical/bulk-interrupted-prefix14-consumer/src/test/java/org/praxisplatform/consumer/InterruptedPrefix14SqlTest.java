package org.praxisplatform.consumer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.bulk.BulkExecutionMigrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Properties;
import static org.assertj.core.api.Assertions.assertThat;

/** SQL-only committed upgrade prefix, never a public14 initialization claim. */
class InterruptedPrefix14SqlTest {
    @Test
    void migrationCreatesCommittedPrefix14WithoutPublication() throws Exception {
        assertThat(System.getProperty("java.version")).isEqualTo("21.0.10");
        var jar = Path.of(System.getProperty("historical.jar")).toRealPath();
        var codeSource = Path.of(BulkExecutionMigrator.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        assertThat(codeSource).isEqualTo(jar);
        var jarHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        assertThat(jarHash).isEqualTo("a0bd4137726acdced16fb1c193a8db7e6c6c1e26b23a4ba9294ccb8da5ddb2cc");
        var publicPom = jar.resolveSibling("praxis-metadata-starter-8.0.0-rc.149.pom");
        var pomHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(publicPom)));
        assertThat(pomHash).isEqualTo("d5a16be5bab1db40ac6801ae60c233092c7f60b57c276f6d471f1695f48d2dcf");
        var candidate = Path.of(System.getProperty("candidate.source.root")).toRealPath();
        for (var entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            var entryPath = Path.of(entry);
            Path path;
            if (Files.exists(entryPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) path = entryPath.toRealPath();
            else {
                // Surefire lists the absent main output of this test-only fixture.
                // Only this exact own directory is allowed; other missing entries still fail.
                var ownTarget = Path.of(System.getProperty("basedir")).toRealPath().resolve("target").toRealPath();
                var ownEmptyMainOutput = ownTarget.resolve("classes");
                var absoluteEntry = entryPath.toAbsolutePath().normalize();
                assertThat(absoluteEntry.getParent()).isNotNull();
                var physicallyQualifiedMissingEntry = absoluteEntry.getParent().toRealPath().resolve(absoluteEntry.getFileName());
                assertThat(physicallyQualifiedMissingEntry).isEqualTo(ownEmptyMainOutput);
                path = physicallyQualifiedMissingEntry;
            }
            assertThat(path.startsWith(candidate.resolve("target"))).as("candidate target excluded: %s", path).isFalse();
            assertThat(path.startsWith(candidate.resolve("src/main"))).as("candidate source excluded: %s", path).isFalse();
        }
        var loader = BulkExecutionMigrator.class.getClassLoader();
        var resources = Collections.list(loader.getResources("db/praxis-bulk-migrations/V14__bulk_openapi_publication.sql"));
        assertThat(resources).hasSize(1);
        var resource = resources.getFirst();
        assertThat(resource.getProtocol()).isEqualTo("jar");
        var connection = (java.net.JarURLConnection) resource.openConnection();
        assertThat(Path.of(connection.getJarFileURL().toURI()).toRealPath()).isEqualTo(jar);
        String sqlHash;
        byte[] sqlBytes;
        try (var input = resource.openStream()) {
            sqlBytes = input.readAllBytes();
            sqlHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sqlBytes));
        }
        assertThat(sqlHash).isEqualTo("78ea1083dd006a74a9ae8f32780fce0f11f4c71293d4b773f3e323685aa48647");
        var evidencePath = Path.of(System.getProperty("historical.evidence.file"));
        Files.write(evidencePath.resolveSibling("original-public149-V14.sql"), sqlBytes);
        Files.copy(publicPom, evidencePath.resolveSibling("original-public149.pom"));
        Files.writeString(evidencePath.resolveSibling("artifact-provenance.txt"),
                "jar=" + jar + "\njarSHA256=" + jarHash + "\ncodeSource=" + codeSource
                        + "\npublicPOM=" + publicPom + "\npomSHA256=" + pomHash + "\nv14Resource=" + resource
                        + "\nv14SHA256=" + sqlHash + "\npeeledTag=075a517195d7ed4ea0e795df5a163458c2f2fa70\n");
        var datasource = new DriverManagerDataSource(System.getProperty("historical.jdbc.url"), "postgres", "");
        var sql = new JdbcTemplate(datasource);
        var before = sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
        assertThat(before).hasSize(14);
        assertThat(sql.queryForObject("select max(version::integer) from praxis_bulk.praxis_bulk_schema_history", Integer.class)).isEqualTo(13);
        assertThat(sql.queryForObject("select to_regclass('praxis_bulk.praxis_bulk_openapi_publication') is null", Boolean.class)).isTrue();
        var migration = Flyway.configure(loader).dataSource(datasource)
                .locations("classpath:db/praxis-bulk-migrations").schemas("praxis_bulk").defaultSchema("praxis_bulk")
                .table("praxis_bulk_schema_history").createSchemas(true).baselineOnMigrate(false)
                .cleanDisabled(true).validateOnMigrate(true).target("14").load().migrate();
        assertThat(migration.migrationsExecuted).isEqualTo(1);
        var after = sql.queryForList("select * from praxis_bulk.praxis_bulk_schema_history order by installed_rank");
        assertThat(after).hasSize(15);
        assertThat(after.subList(0, before.size())).isEqualTo(before);
        assertThat(after.getLast()).containsEntry("version", "14").containsEntry("success", true).containsEntry("type", "SQL");
        assertThat(sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_openapi_publication", Integer.class)).isZero();
        var receipt = new Properties();
        receipt.setProperty("historical.jar.sha256", jarHash);
        receipt.setProperty("historical.codeSource", codeSource.toString());
        receipt.setProperty("resource.v14.sha256", sqlHash);
        receipt.setProperty("historical.pom.sha256", pomHash);
        receipt.setProperty("migrations.executed", Integer.toString(migration.migrationsExecuted));
        receipt.setProperty("construction", "PUBLIC146_NATIVE13_THEN_PUBLIC149_SQL_INTERRUPTED14");
        try (var output = Files.newOutputStream(Path.of(System.getProperty("historical.evidence.file")))) {
            receipt.store(output, "Authenticated test-only interruption; not public14 initialized producer");
        }
    }
}
