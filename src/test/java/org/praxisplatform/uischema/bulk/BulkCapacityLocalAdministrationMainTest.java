package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Closed-input/output checks; all valid owner execution is covered by the PostgreSQL class. */
class BulkCapacityLocalAdministrationMainTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void invalidArgumentsNeverEchoRawOperationOrPrivatePath() throws Exception {
        for (String[] arguments : List.of(new String[]{"SECRET_OPERATION", "/private/secret"},
                new String[]{"FENCE"}, new String[]{"INSPECT", "/private/secret", "extra"},
                new String[]{null, "/private/secret"})) {
            var result = run(arguments);
            assertThat(result.exit()).isEqualTo(64);
            assertClosed(result.output(), arguments[0] != null && List.of("FENCE", "INSPECT")
                    .contains(arguments[0]) ? arguments[0] : null, "INVALID_INPUT");
            assertThat(result.output()).doesNotContain("SECRET_OPERATION", "/private/secret", "extra");
        }
    }

    @Test
    void strictConfigurationPreservesAllNineTypedBindingComponentsAndExplicitRoles() throws Exception {
        try (var input = new PrivateInput()) {
            ObjectNode root = configuration();
            input.write(root.toString());
            var parsed = BulkCapacityLocalAdministrationMain.readConfiguration(input.path);
            assertThat(parsed.binding().generation()).isEqualTo(3);
            assertThat(parsed.binding().authorityEpoch()).isEqualTo(11);
            assertThat(parsed.binding().deploymentId()).isEqualTo("deployment");
            assertThat(parsed.roles().runtimeGranteeRoles()).containsExactly("runtime_role");
            assertThat(parsed.toString()).doesNotContain("credential", "jdbc:");
        }
    }

    @Test
    void duplicateTrailingUnknownAndMissingFieldsAreInvalidWithoutSecretOutput() throws Exception {
        try (var input = new PrivateInput()) {
            String valid = configuration().toString();
            for (String malformed : List.of(valid + " {}", valid.replace("\"owner\":", "\"owner\":{},\"owner\":"),
                    valid.replace("\"generation\":3", "\"generation\":3,\"unknown\":1"),
                    valid.replace("\"generation\":3,", ""))) {
                input.write(malformed);
                var result = run(new String[]{"FENCE", input.path.toString()});
                assertThat(result.exit()).isEqualTo(64);
                assertClosed(result.output(), "FENCE", "INVALID_INPUT");
                assertThat(result.output()).doesNotContain("credential", "jdbc:", input.path.toString());
            }
        }
    }

    @Test
    void integralOverflowCoercionDuplicateRolesAndNoncanonicalUuidAreRejected() throws Exception {
        try (var input = new PrivateInput()) {
            for (String value : List.of("9223372036854775808", "1.0", "\"3\"", "0", "-1")) {
                input.write(configuration().toString().replace("\"generation\":3", "\"generation\":" + value));
                assertThat(run(new String[]{"INSPECT", input.path.toString()}).exit()).isEqualTo(64);
            }
            ObjectNode duplicate = configuration();
            duplicate.with("roles").withArray("runtimeGranteeRoles").add("runtime_role");
            input.write(duplicate.toString());
            assertThat(run(new String[]{"INSPECT", input.path.toString()}).exit()).isEqualTo(64);
            ObjectNode uuid = configuration();
            uuid.with("binding").put("databaseId", "1-1-1-1-1");
            input.write(uuid.toString());
            assertThat(run(new String[]{"INSPECT", input.path.toString()}).exit()).isEqualTo(64);
        }
    }

    @Test
    void permissionsSymlinkAndOversizedFilesFailClosed() throws Exception {
        try (var input = new PrivateInput()) {
            input.write(configuration().toString());
            Files.setPosixFilePermissions(input.path, PosixFilePermissions.fromString("rw-r--r--"));
            assertThat(run(new String[]{"FENCE", input.path.toString()}).exit()).isEqualTo(64);
            Files.setPosixFilePermissions(input.path, PosixFilePermissions.fromString("rw-------"));
            Path link = input.directory.resolve("link.json");
            Files.createSymbolicLink(link, input.path);
            try { assertThat(run(new String[]{"FENCE", link.toString()}).exit()).isEqualTo(64); }
            finally { Files.delete(link); }
            input.write(" ".repeat(16385));
            assertThat(run(new String[]{"FENCE", input.path.toString()}).exit()).isEqualTo(64);
        }
    }

    @Test
    void postgresUrlTimeoutOverrideAmbiguitiesAreRejectedAndStricterLimitsSurvive() throws Exception {
        assertThat(BulkCapacityLocalAdministrationMain.boundedOwnerUrl(
                "jdbc:postgresql://127.0.0.1:5432/database", 20)).contains("connectTimeout=10", "socketTimeout=20");
        assertThat(BulkCapacityLocalAdministrationMain.boundedOwnerUrl(
                "jdbc:postgresql://127.0.0.1:5432/database?connectTimeout=1&socketTimeout=2", 10))
                .contains("connectTimeout=1", "socketTimeout=2");
        for (String query : List.of("connectTimeout=0", "socketTimeout=21", "connectTimeout=1&connectTimeout=2",
                "%63onnectTimeout=1", "connectTimeout=%31", "user=other", "password=secret")) {
            assertThatThrownBy(() -> BulkCapacityLocalAdministrationMain.boundedOwnerUrl(
                    "jdbc:postgresql://127.0.0.1:5432/database?" + query, 20))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        try (var input = new PrivateInput()) {
            ObjectNode wrong = configuration();
            wrong.with("owner").put("url", "jdbc:h2:mem:private");
            input.write(wrong.toString());
            assertThat(run(new String[]{"FENCE", input.path.toString()}).exit()).isEqualTo(64);
        }
    }

    static ObjectNode configuration() {
        ObjectNode root = JSON.createObjectNode();
        root.putObject("owner").put("url", "jdbc:postgresql://127.0.0.1:5432/database")
                .put("username", "postgres").put("password", "credential");
        ObjectNode roles = root.putObject("roles").put("expectedSchemaOwnerRole", "postgres");
        roles.putArray("runtimeGranteeRoles").add("runtime_role");
        roles.putArray("retentionExecutorMembers");
        roles.putArray("controlPlaneGranteeRoles");
        root.putObject("binding").put("deploymentId", "deployment").put("tenantId", "tenant")
                .put("environment", "production").put("bindingId", "binding").put("generation", 3)
                .put("databaseId", UUID.randomUUID().toString()).put("attestationId", UUID.randomUUID().toString())
                .put("authorityId", UUID.randomUUID().toString()).put("authorityEpoch", 11);
        return root;
    }

    static Result run(String[] arguments) {
        var bytes = new ByteArrayOutputStream();
        int exit;
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            exit = BulkCapacityLocalAdministrationMain.run(arguments, output);
        }
        return new Result(exit, bytes.toString(StandardCharsets.UTF_8));
    }

    static void assertClosed(String output, String operation, String code) throws Exception {
        JsonNode json = JSON.readTree(output);
        assertThat(json.size()).isEqualTo(5);
        assertThat(json.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(json.get("operation")).isEqualTo(operation == null ? JSON.getNodeFactory().nullNode() : JSON.valueToTree(operation));
        assertThat(json.path("result").textValue()).isEqualTo("DENIED");
        assertThat(json.get("markerState").isNull()).isTrue();
        assertThat(json.path("code").textValue()).isEqualTo(code);
    }

    record Result(int exit, String output) { }

    static final class PrivateInput implements AutoCloseable {
        final Path directory;
        final Path path;
        PrivateInput() throws Exception {
            directory = Files.createTempDirectory("local-administration-input-").toRealPath();
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            path = directory.resolve("owner.json");
        }
        void write(String input) throws Exception {
            Files.writeString(path, input);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
        @Override public void close() throws Exception {
            Files.deleteIfExists(path);
            try (var remaining = Files.newDirectoryStream(directory)) {
                if (!remaining.iterator().hasNext()) Files.delete(directory);
            }
        }
    }
}
