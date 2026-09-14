package org.praxisplatform.uischema;

import io.zonky.test.db.postgres.embedded.DefaultPostgresBinaryResolver;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.zonky.test.db.postgres.embedded.PgBinaryResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedPostgresColdStartTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsAndStartsPostgresFromAnIsolatedWorkingDirectory() throws Exception {
        try (var postgres = EmbeddedPostgres.builder()
                .setOverrideWorkingDirectory(temporaryDirectory.toFile())
                .setCleanDataDirectory(true)
                .setRegisterShutdownHook(false)
                .setPgBinaryResolver(new ColdStartPgBinaryResolver())
                .start();
             var connection = postgres.getPostgresDatabase().getConnection();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select 1")) {

            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    private static final class ColdStartPgBinaryResolver implements PgBinaryResolver {

        @Override
        public InputStream getPgBinary(String system, String architecture) throws IOException {
            return DefaultPostgresBinaryResolver.INSTANCE.getPgBinary(system, architecture);
        }
    }
}
