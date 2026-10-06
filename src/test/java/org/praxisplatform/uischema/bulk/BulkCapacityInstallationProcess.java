package org.praxisplatform.uischema.bulk;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Actual independent JVM for the installation proof. Credentials are read from an exclusive
 * test fixture file, never command arguments or output. This process is an owner provisioner,
 * not a worker, job runtime, authority allocator or proof of safe restore.
 */
public final class BulkCapacityInstallationProcess {
    private BulkCapacityInstallationProcess() { }

    /** Parent supplies config, READY signal, shared start barrier and exclusive result paths. */
    public static void main(String[] args) {
        Path result = null;
        try {
            if (args == null || args.length != 4)
                throw new IllegalArgumentException("Four test fixture paths required");
            result = Path.of(args[3]);
            Path config = Path.of(args[0]);
            Path ready = Path.of(args[1]);
            Path start = Path.of(args[2]);
            Properties values = new Properties();
            try (InputStream input = Files.newInputStream(config)) { values.load(input); }
            var authorityIdentity = new BulkCapacityAuthorityMigrator.Identity(
                    required(values, "deployment"), required(values, "environment"),
                    uuid(values, "authorityId"), number(values, "authorityEpoch"));
            var authorityRoles = new BulkCapacityAuthorityMigrator.RoleConfiguration(
                    required(values, "authorityOwner"), required(values, "authorityProvisioner"),
                    required(values, "authorityAllocator"), required(values, "authorityReader"));
            var provisionerSource = source(values, "provisioner");
            var readerSource = source(values, "reader");
            var provisioner = new BulkCapacityAuthorityInfrastructure(provisionerSource,
                    new DataSourceTransactionManager(provisionerSource), authorityIdentity,
                    BulkCapacityAuthorityInfrastructure.Access.PROVISIONER, authorityRoles);
            var readerInfrastructure = new BulkCapacityAuthorityInfrastructure(readerSource,
                    new DataSourceTransactionManager(readerSource), authorityIdentity,
                    BulkCapacityAuthorityInfrastructure.Access.READER, authorityRoles);
            var ownerSource = source(values, "owner");
            var runtimeSource = source(values, "runtime");
            var localRoles = new BulkExecutionRoleConfiguration(required(values, "ownerUser"),
                    roles(values, "runtimeRoles"), roles(values, "retentionRoles"),
                    roles(values, "controlRoles"));
            var runtime = new BulkExecutionInfrastructure(runtimeSource,
                    new DataSourceTransactionManager(runtimeSource), required(values, "namespace"),
                    required(values, "deployment"), localRoles);
            var expected = new JdbcBulkCapacityInstallation.ExpectedBinding(
                    required(values, "deployment"), required(values, "tenant"),
                    required(values, "environment"), required(values, "binding"),
                    number(values, "bindingGeneration"), uuid(values, "databaseId"),
                    uuid(values, "attestationId"), uuid(values, "authorityId"),
                    number(values, "authorityEpoch"));
            var installation = new JdbcBulkCapacityInstallation(expected, ownerSource,
                    new DataSourceTransactionManager(ownerSource), required(values, "ownerUser"),
                    runtime, provisioner, new JdbcBulkCapacityIssuer.CapacityReader(readerInfrastructure),
                    Duration.ofMillis(number(values, "transactionBudgetMillis")),
                    Duration.ofMillis(number(values, "lockBudgetMillis")));
            // Readiness proves construction only. The actual operation starts after the barrier.
            Files.writeString(ready, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            long remaining = Duration.ofMillis(number(values, "barrierBudgetMillis")).toNanos();
            long began = System.nanoTime();
            while (!Files.exists(start)) {
                if (System.nanoTime() - began >= remaining)
                    throw new IllegalStateException("Test start barrier expired");
                Thread.sleep(10);
            }
            boolean inserted = installation.install(uuid(values, "tokenId"));
            Files.writeString(result, "OK\n" + ProcessHandle.current().pid() + "\n" + inserted + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception failure) {
            if (result != null) {
                try {
                    Files.writeString(result, "FAIL\n" + ProcessHandle.current().pid() + "\n"
                            + failure.getClass().getSimpleName() + "\n", StandardCharsets.UTF_8);
                } catch (Exception ignored) { /* Exit remains sanitized even if the result cannot be written. */ }
            }
            // Setup failures, too, must never print message/cause, JDBC URLs or credentials.
            System.exit(21);
        }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing test property");
        return value;
    }

    private static long number(Properties values, String key) {
        long value = Long.parseLong(required(values, key));
        if (value <= 0) throw new IllegalArgumentException("Positive test property required");
        return value;
    }

    private static UUID uuid(Properties values, String key) {
        return UUID.fromString(required(values, key));
    }

    private static Set<String> roles(Properties values, String key) {
        String value = values.getProperty(key, "");
        return Arrays.stream(value.split(",")).filter(role -> !role.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static DriverManagerDataSource source(Properties values, String prefix) {
        return new DriverManagerDataSource(required(values, prefix + "Url"),
                required(values, prefix + "User"), values.getProperty(prefix + "Password", ""));
    }
}
