package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Separate owner-credential process for local observation or terminal fencing.
 * Inspection is not a boot authorization; fencing does not withdraw SYNC writers,
 * existing sessions, global rights, or a restored database. No automatic retry is made.
 */
public final class BulkCapacityLocalAdministrationMain {
    private static final int MAX_BYTES = 16 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private BulkCapacityLocalAdministrationMain() { }

    public static void main(String[] arguments) {
        System.exit(run(arguments, System.out));
    }

    static int run(String[] arguments, PrintStream output) {
        String operation = arguments != null && arguments.length > 0
                && arguments[0] != null && Set.of("INSPECT", "FENCE").contains(arguments[0]) ? arguments[0] : null;
        Configuration configuration;
        try {
            if (operation == null || arguments.length != 2) throw new IllegalArgumentException();
            configuration = readConfiguration(Path.of(arguments[1]));
        } catch (IOException | RuntimeException invalid) {
            publish(output, operation, "DENIED", null, "INVALID_INPUT");
            return 64;
        }
        // One deadline covers acquisition, mutation, known commit, and independent readback.
        // Transport cancellation and DNS/multi-host behavior are not a wall-clock guarantee.
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        try {
            var source = new DeadlineOwnerSource(configuration, deadline);
            var administration = new JdbcBulkCapacityLocalAdministration(configuration.binding(), source,
                    new DataSourceTransactionManager(source), configuration.username(), configuration.roles(),
                    Duration.ofSeconds(20), Duration.ofSeconds(3));
            if ("INSPECT".equals(operation)) {
                String state = administration.inspect(deadline);
                JdbcBulkCapacityLocalAdministration.requireRemaining(deadline);
                publish(output, operation, "INSPECTED", state, "OK");
            } else {
                boolean changed = administration.fence(deadline);
                JdbcBulkCapacityLocalAdministration.requireRemaining(deadline);
                publish(output, operation, changed ? "FENCED" : "FENCE_REPLAYED", "FENCED", "OK");
            }
            return 0;
        } catch (RuntimeException notConfirmed) {
            // Never expose JDBC failures or claim rollback for an uncertain commit/readback.
            publish(output, operation, "DENIED", null, "LOCAL_OPERATION_NOT_CONFIRMED");
            return 2;
        }
    }

    private static void publish(PrintStream output, String operation, String result,
            String state, String code) {
        output.println("{\"schemaVersion\":1,\"operation\":" + quoted(operation)
                + ",\"result\":" + quoted(result) + ",\"markerState\":" + quoted(state)
                + ",\"code\":" + quoted(code) + "}");
    }

    private static String quoted(String closedValue) {
        return closedValue == null ? "null" : "\"" + closedValue + "\"";
    }

    static Configuration readConfiguration(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalArgumentException();
        for (Path component = path; component != null; component = component.getParent())
            if (Files.isSymbolicLink(component)) throw new IllegalArgumentException();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(FILE_PERMISSIONS)
                || !Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path.getParent(), LinkOption.NOFOLLOW_LINKS)
                        .equals(DIRECTORY_PERMISSIONS)) throw new IllegalArgumentException();
        ByteBuffer bytes = ByteBuffer.allocate(MAX_BYTES + 1);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
        }
        if (bytes.position() > MAX_BYTES) throw new IllegalArgumentException();
        JsonNode root = JSON.readTree(java.util.Arrays.copyOf(bytes.array(), bytes.position()));
        exact(root, "owner", "roles", "binding");
        JsonNode owner = root.get("owner");
        exact(owner, "url", "username", "password");
        String url = text(owner, "url");
        if (!url.startsWith("jdbc:postgresql://") || url.indexOf('#') >= 0)
            throw new IllegalArgumentException();
        String username = text(owner, "username");
        JsonNode passwordNode = owner.get("password");
        if (!passwordNode.isTextual() || passwordNode.textValue().isBlank()) throw new IllegalArgumentException();
        String password = passwordNode.textValue();
        JsonNode roles = root.get("roles");
        exact(roles, "expectedSchemaOwnerRole", "runtimeGranteeRoles", "retentionExecutorMembers",
                "controlPlaneGranteeRoles");
        var roleConfiguration = new BulkExecutionRoleConfiguration(text(roles, "expectedSchemaOwnerRole"),
                roleSet(roles, "runtimeGranteeRoles"), roleSet(roles, "retentionExecutorMembers"),
                roleSet(roles, "controlPlaneGranteeRoles"));
        JsonNode binding = root.get("binding");
        exact(binding, "deploymentId", "tenantId", "environment", "bindingId", "generation",
                "databaseId", "attestationId", "authorityId", "authorityEpoch");
        var expected = new BulkCapacityBinding(text(binding, "deploymentId"),
                text(binding, "tenantId"), text(binding, "environment"), text(binding, "bindingId"),
                positiveLong(binding, "generation"), uuid(binding, "databaseId"),
                uuid(binding, "attestationId"), uuid(binding, "authorityId"),
                positiveLong(binding, "authorityEpoch"));
        if (!username.equals(roleConfiguration.expectedSchemaOwnerRole())) throw new IllegalArgumentException();
        // Reject ambiguous URL properties before any connection is attempted.
        DeadlineOwnerSource.validateUrl(url);
        return new Configuration(url, username, password, roleConfiguration, expected);
    }

    private static void exact(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) throw new IllegalArgumentException();
        for (String name : names) if (!node.has(name)) throw new IllegalArgumentException();
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || !value.textValue().equals(value.textValue().strip())
                || value.textValue().codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException();
        return value.textValue();
    }

    private static Set<String> roleSet(JsonNode node, String name) {
        JsonNode array = node.get(name);
        if (!array.isArray()) throw new IllegalArgumentException();
        Set<String> result = new HashSet<>();
        for (JsonNode value : array) {
            if (!value.isTextual() || !result.add(value.textValue())) throw new IllegalArgumentException();
        }
        return result;
    }

    private static long positiveLong(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
            throw new IllegalArgumentException();
        return value.longValue();
    }

    private static UUID uuid(JsonNode node, String name) {
        String value = text(node, name);
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException();
        return uuid;
    }

    // Package-local for source-owned tests; never serialize this credential-bearing record.
    record Configuration(String url, String username, String password,
                         BulkExecutionRoleConfiguration roles,
                         BulkCapacityBinding binding) {
        @Override public String toString() { return "Local administration configuration [private]"; }
    }

    static String boundedOwnerUrl(String url, long remainingSeconds) {
        return DeadlineOwnerSource.properties(url, remainingSeconds);
    }

    private static final class DeadlineOwnerSource extends DriverManagerDataSource {
        private final long deadline;
        private final String originalUrl;

        DeadlineOwnerSource(Configuration configuration, long deadline) {
            super(configuration.url(), configuration.username(), configuration.password());
            this.originalUrl = configuration.url();
            this.deadline = deadline;
        }

        static void validateUrl(String url) {
            properties(url, 20);
        }

        private static String properties(String url, long remainingSeconds) {
            int separator = url.indexOf('?');
            String base = separator < 0 ? url : url.substring(0, separator);
            java.util.Map<String, String> options = new java.util.LinkedHashMap<>();
            if (separator >= 0) {
                for (String entry : url.substring(separator + 1).split("&", -1)) {
                    String[] pair = entry.split("=", 2);
                    if (pair.length != 2 || pair[0].isEmpty()) throw new IllegalArgumentException();
                    String key = java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8);
                    if (!key.equals(pair[0])) throw new IllegalArgumentException();
                    String value = java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
                    if (Set.of("connectTimeout", "socketTimeout").contains(key)
                            && !value.equals(pair[1])) throw new IllegalArgumentException();
                    if (options.putIfAbsent(key, value) != null || Set.of("user", "password", "loginTimeout")
                            .contains(key)) throw new IllegalArgumentException();
                }
            }
            for (String key : Set.of("connectTimeout", "socketTimeout")) {
                String value = options.get(key);
                long configured = value == null ? ("connectTimeout".equals(key)
                        ? Math.min(10, remainingSeconds) : remainingSeconds) : Long.parseLong(value);
                if (value != null && (configured <= 0 || configured > 20
                        || !Long.toString(configured).equals(value))) throw new IllegalArgumentException();
                options.put(key, Long.toString(Math.min(configured, remainingSeconds)));
            }
            return base + "?" + options.entrySet().stream().map(entry ->
                    java.net.URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8) + "="
                    + java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                    .collect(java.util.stream.Collectors.joining("&"));
        }

        @Override
        protected Connection getConnectionFromDriver(java.util.Properties properties)
                throws SQLException {
            long remaining = JdbcBulkCapacityLocalAdministration.requireRemaining(deadline);
            // Rewrite URL timeouts too: PG URL options override Properties values.
            // Bypass DriverManagerDataSource debug URL logging in this credential-private process.
            return java.sql.DriverManager.getConnection(properties(originalUrl,
                    Math.max(1, remaining / 1000)), properties);
        }
    }
}
