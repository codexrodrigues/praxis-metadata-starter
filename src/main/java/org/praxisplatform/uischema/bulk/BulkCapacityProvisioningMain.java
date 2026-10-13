package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit, separate administrative JVM. Each invocation performs one existing capacity step. */
public final class BulkCapacityProvisioningMain {
    enum Operation { BOOTSTRAP, ENROLL, REGISTER_ATTESTATION, ACTIVATE, REQUEST, ALLOCATE_NEXT, READ_ISSUE, INSTALL }
    enum CompletionPhase { COMMAND, FINAL_BUDGET, RECEIPT_PUBLICATION, PUBLISH_BUDGET }
    enum FailureCategory { BUDGET_EXPIRED, TIMEOUT, SQL_ERROR, IO_ERROR, INVALID_STATE, OTHER }
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_INPUT = 32 * 1024;
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_MODE = PosixFilePermissions.fromString("rwx------");
    private BulkCapacityProvisioningMain() { }

    public static void main(String[] arguments) { System.exit(run(arguments, System.out)); }

    static int run(String[] arguments, PrintStream output) {
        Operation operation = null;
        Configuration configuration;
        Path receipt;
        try {
            if (arguments == null || arguments.length != 3) throw new IllegalArgumentException();
            operation = Operation.valueOf(arguments[0]);
            configuration = readConfiguration(operation, Path.of(arguments[1]));
            receipt = Path.of(arguments[2]);
            privatePath(receipt, false);
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalArgumentException();
        } catch (IOException | RuntimeException invalid) {
            publish(output, operation, "DENIED", "INVALID_INPUT");
            return 64;
        }
        ObjectNode evidence = JSON.createObjectNode().put("schemaVersion", 1)
                .put("operation", operation.name()).put("status", "PENDING_UNCONFIRMED");
        evidence.set("intent", configuration.intent());
        try {
            // CREATE_NEW and durable intent precede construction (and therefore any JDBC acquisition).
            createReceipt(receipt, evidence);
        } catch (IOException | RuntimeException denied) {
            publish(output, operation, "DENIED", "RECEIPT_NOT_CREATED");
            return 64;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        return completeCommand(operation, receipt, evidence, deadline, output,
                () -> execute(configuration, deadline));
    }

    /** Package-private completion seam; production always supplies the canonical command above. */
    static int completeCommand(Operation operation, Path receipt, ObjectNode evidence, long deadline,
            PrintStream output, Command command) {
        CompletionPhase phase = CompletionPhase.COMMAND;
        try {
            ObjectNode result = command.run();
            phase = CompletionPhase.FINAL_BUDGET;
            JdbcBulkCapacityLocalAdministration.requireRemaining(deadline);
            evidence.put("status", "CONFIRMED").set("result", result);
            phase = CompletionPhase.RECEIPT_PUBLICATION;
            replaceReceipt(receipt, evidence);
            phase = CompletionPhase.PUBLISH_BUDGET;
            JdbcBulkCapacityLocalAdministration.requireRemaining(deadline);
            publish(output, operation, "CONFIRMED", "OK");
            return 0;
        } catch (Exception notConfirmed) {
            // A lost commit/readback/receipt acknowledgement never becomes an assertion of rollback.
            evidence.put("status", "UNCONFIRMED").remove("result");
            evidence.set("diagnostic", diagnostic(phase, notConfirmed));
            try { replaceReceipt(receipt, evidence); }
            catch (IOException | RuntimeException custodyFailure) { notConfirmed.addSuppressed(custodyFailure); }
            publish(output, operation, "UNCONFIRMED", "STEP_NOT_CONFIRMED");
            return 2;
        }
    }

    @FunctionalInterface
    interface Command { ObjectNode run() throws Exception; }

    /** Closed private projection: never serializes a class name, message, stack or input. */
    static ObjectNode diagnostic(CompletionPhase phase, Exception failure) {
        FailureCategory category = FailureCategory.OTHER;
        String sqlState = null;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16 && seen.add(cause); depth++, cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof org.springframework.transaction.TransactionTimedOutException)
                category = FailureCategory.TIMEOUT;
            else if (cause instanceof SQLException && category != FailureCategory.TIMEOUT)
                category = FailureCategory.SQL_ERROR;
            else if (cause instanceof IOException && category == FailureCategory.OTHER)
                category = FailureCategory.IO_ERROR;
            else if (cause instanceof IllegalStateException && category == FailureCategory.OTHER)
                category = FailureCategory.INVALID_STATE;
            if (cause instanceof SQLException sql && sqlState == null && sql.getSQLState() != null
                    && sql.getSQLState().matches("[0-9A-Z]{5}")) sqlState = sql.getSQLState();
        }
        if (phase == CompletionPhase.FINAL_BUDGET || phase == CompletionPhase.PUBLISH_BUDGET)
            category = FailureCategory.BUDGET_EXPIRED;
        ObjectNode value = JSON.createObjectNode().put("phase", phase.name()).put("category", category.name());
        if (sqlState == null) value.putNull("sqlState"); else value.put("sqlState", sqlState);
        return value;
    }

    private static ObjectNode execute(Configuration c, long deadline) {
        ObjectNode result = JSON.createObjectNode();
        if (c.operation() == Operation.ENROLL) {
            authority(c, "provisioner", BulkCapacityAuthorityInfrastructure.Access.PROVISIONER, deadline)
                    .enrollBinding(c.binding().tenantId(), c.binding().bindingId(), c.binding().generation());
            return result.put("enrollmentCommitted", true);
        }
        if (Set.of(Operation.REQUEST, Operation.ALLOCATE_NEXT, Operation.READ_ISSUE).contains(c.operation())) {
            var issuer = new JdbcBulkCapacityIssuer(
                    authority(c, "allocator", BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR, deadline),
                    authority(c, "reader", BulkCapacityAuthorityInfrastructure.Access.READER, deadline));
            switch (c.operation()) {
                case REQUEST -> {
                    issuer.requestCapacity(c.request());
                    result.put("requestCommitted", true);
                }
                case ALLOCATE_NEXT -> {
                    // Fairness chooses the eligible tenant. Never loop until the caller's binding wins.
                    var token = issuer.allocateNext(JdbcBulkCapacityIssuer.CapacityClass.valueOf(text(c.root(), "capacityClass")));
                    result.put("issued", token.isPresent());
                    token.ifPresent(value -> result.set("token", JSON.valueToTree(value)));
                }
                case READ_ISSUE -> {
                    var issue = issuer.findIssue(c.request().requestId());
                    issue.ifPresent(value -> requireRequest(value, c.request()));
                    result.put("found", issue.isPresent());
                    issue.ifPresent(value -> result.set("issue", JSON.valueToTree(value)));
                }
                default -> throw new IllegalStateException();
            }
            return result;
        }
        JsonNode local = c.root().get("operational");
        var owner = source(local.get("owner"), deadline);
        var runtimeSource = source(local.get("runtime"), deadline);
        var roles = operationalRoles(local.get("roles"));
        var runtime = new BulkExecutionInfrastructure(runtimeSource, new DataSourceTransactionManager(runtimeSource),
                text(local, "namespace"), c.identity().deploymentId(), roles);
        var provisioner = authority(c, "provisioner", BulkCapacityAuthorityInfrastructure.Access.PROVISIONER, deadline);
        var reader = new JdbcBulkCapacityIssuer.CapacityReader(
                authority(c, "reader", BulkCapacityAuthorityInfrastructure.Access.READER, deadline));
        var installation = new JdbcBulkCapacityInstallation(c.binding(), owner,
                new DataSourceTransactionManager(owner), roles.expectedSchemaOwnerRole(), runtime, provisioner, reader,
                Duration.ofSeconds(20), Duration.ofSeconds(3));
        JdbcBulkCapacityLocalAdministration.requireRemaining(deadline);
        switch (c.operation()) {
            case BOOTSTRAP -> installation.bootstrap();
            case REGISTER_ATTESTATION -> installation.registerAttestation();
            case ACTIVATE -> installation.activate();
            case INSTALL -> result.put("inserted", installation.install(uuid(c.root(), "tokenId")));
            default -> throw new IllegalStateException();
        }
        // Local state observation is an explicit INSPECT command. It is not confirmation of
        // the authority mutation; INSTALL retains its own canonical installation readback.
        return result;
    }

    private static BulkCapacityAuthorityInfrastructure authority(Configuration c, String credential,
            BulkCapacityAuthorityInfrastructure.Access access, long deadline) {
        var ds = source(c.root().get(credential), deadline);
        return new BulkCapacityAuthorityInfrastructure(ds, new DataSourceTransactionManager(ds), c.identity(), access, c.roles());
    }

    static void requireRequest(JdbcBulkCapacityIssuer.Issue issue, JdbcBulkCapacityIssuer.Request request) {
        if (!issue.requestId().equals(request.requestId()) || !issue.deploymentId().equals(request.deploymentId())
                || !issue.tenantId().equals(request.tenantId()) || !issue.bindingId().equals(request.bindingId())
                || issue.capacityClass() != request.capacityClass() || issue.requestedCount() != request.requestedCount())
            throw new IllegalStateException("Capacity request readback differs");
    }

    static Configuration readConfiguration(Operation operation, Path path) throws IOException {
        privatePath(path, true);
        ByteBuffer bytes = ByteBuffer.allocate(MAX_INPUT + 1);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
        }
        if (bytes.position() > MAX_INPUT) throw new IllegalArgumentException();
        JsonNode root = JSON.readTree(java.util.Arrays.copyOf(bytes.array(), bytes.position()));
        boolean issuing = Set.of(Operation.REQUEST, Operation.ALLOCATE_NEXT, Operation.READ_ISSUE).contains(operation);
        if (issuing) exact(root, "authority", "allocator", "reader", operation == Operation.ALLOCATE_NEXT ? "capacityClass" : "request");
        else if (operation == Operation.ENROLL) exact(root, "authority", "provisioner", "binding");
        else if (operation == Operation.INSTALL) exact(root, "authority", "provisioner", "reader", "operational", "binding", "tokenId");
        else exact(root, "authority", "provisioner", "reader", "operational", "binding");
        JsonNode authority = root.get("authority");
        exact(authority, "deploymentId", "environment", "authorityId", "authorityEpoch", "roles");
        var identity = new BulkCapacityAuthorityMigrator.Identity(text(authority, "deploymentId"),
                text(authority, "environment"), uuid(authority, "authorityId"), positive(authority, "authorityEpoch"));
        JsonNode ar = authority.get("roles");
        exact(ar, "ownerLogin", "provisionerLogin", "allocatorLogin", "readerLogin");
        var roles = new BulkCapacityAuthorityMigrator.RoleConfiguration(text(ar, "ownerLogin"),
                text(ar, "provisionerLogin"), text(ar, "allocatorLogin"), text(ar, "readerLogin"));
        if (root.has("allocator")) credential(root.get("allocator"), roles.allocatorLogin());
        if (root.has("reader")) credential(root.get("reader"), roles.readerLogin());
        if (root.has("provisioner")) credential(root.get("provisioner"), roles.provisionerLogin());
        BulkCapacityBinding binding = null;
        if (root.has("binding")) {
            JsonNode b = root.get("binding");
            exact(b, "deploymentId", "tenantId", "environment", "bindingId", "generation", "databaseId", "attestationId", "authorityId", "authorityEpoch");
            binding = new BulkCapacityBinding(text(b, "deploymentId"), text(b, "tenantId"), text(b, "environment"),
                    text(b, "bindingId"), positive(b, "generation"), uuid(b, "databaseId"), uuid(b, "attestationId"),
                    uuid(b, "authorityId"), positive(b, "authorityEpoch"));
            if (!binding.deploymentId().equals(identity.deploymentId()) || !binding.environment().equals(identity.environment())
                    || !binding.authorityId().equals(identity.authorityId()) || binding.authorityEpoch() != identity.expectedAuthorityEpoch())
                throw new IllegalArgumentException();
        }
        if (root.has("operational")) {
            JsonNode local = root.get("operational");
            exact(local, "namespace", "roles", "owner", "runtime");
            text(local, "namespace");
            var localRoles = operationalRoles(local.get("roles"));
            credential(local.get("owner"), localRoles.expectedSchemaOwnerRole());
            credential(local.get("runtime"), text(local.get("runtime"), "username"));
            if (!localRoles.runtimeGranteeRoles().contains(text(local.get("runtime"), "username"))) throw new IllegalArgumentException();
        }
        JdbcBulkCapacityIssuer.Request request = null;
        if (root.has("request")) {
            JsonNode r = root.get("request");
            exact(r, "requestId", "tenantId", "bindingId", "capacityClass", "requestedCount");
            request = new JdbcBulkCapacityIssuer.Request(uuid(r, "requestId"), identity.deploymentId(), text(r, "tenantId"),
                    text(r, "bindingId"), JdbcBulkCapacityIssuer.CapacityClass.valueOf(text(r, "capacityClass")),
                    Math.toIntExact(positive(r, "requestedCount")));
        }
        if (root.has("capacityClass")) JdbcBulkCapacityIssuer.CapacityClass.valueOf(text(root, "capacityClass"));
        if (root.has("tokenId")) uuid(root, "tokenId");
        ObjectNode intent = JSON.createObjectNode();
        for (String key : Set.of("authority", "binding", "request", "capacityClass", "tokenId"))
            if (root.has(key)) intent.set(key, root.get(key).deepCopy());
        if (root.has("operational")) {
            intent.put("namespace", text(root.get("operational"), "namespace"));
            intent.set("operationalRoles", root.get("operational").get("roles").deepCopy());
        }
        return new Configuration(operation, root, identity, roles, binding, request, intent);
    }

    record Configuration(Operation operation, JsonNode root, BulkCapacityAuthorityMigrator.Identity identity,
            BulkCapacityAuthorityMigrator.RoleConfiguration roles, BulkCapacityBinding binding,
            JdbcBulkCapacityIssuer.Request request, ObjectNode intent) {
        @Override public String toString() { return "Capacity provisioning configuration [private]"; }
    }

    private static BulkExecutionRoleConfiguration operationalRoles(JsonNode r) {
        exact(r, "expectedSchemaOwnerRole", "runtimeGranteeRoles", "retentionExecutorMembers", "controlPlaneGranteeRoles");
        return new BulkExecutionRoleConfiguration(text(r, "expectedSchemaOwnerRole"), roleSet(r.get("runtimeGranteeRoles")),
                roleSet(r.get("retentionExecutorMembers")), roleSet(r.get("controlPlaneGranteeRoles")));
    }
    private static Set<String> roleSet(JsonNode array) {
        if (array == null || !array.isArray()) throw new IllegalArgumentException();
        Set<String> roles = new HashSet<>();
        for (JsonNode value : array) if (!value.isTextual() || !roles.add(value.textValue())) throw new IllegalArgumentException();
        return roles;
    }
    private static void credential(JsonNode c, String role) {
        exact(c, "url", "username", "password");
        if (!role.equals(text(c, "username"))) throw new IllegalArgumentException();
        String url = text(c, "url");
        if (!url.startsWith("jdbc:postgresql://") || url.contains("#")) throw new IllegalArgumentException();
        password(c);
        String bounded = BulkCapacityLocalAdministrationMain.boundedOwnerUrl(url, 20);
        java.util.Map<String, String> properties = new java.util.HashMap<>();
        // The existing URL guard has already rejected duplicate/encoded keys and credential overrides.
        for (String entry : bounded.substring(bounded.indexOf('?') + 1).split("&")) {
            String[] pair = entry.split("=", 2);
            properties.put(java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        if (properties.containsKey("sslhostnameverifier") || !"verify-full".equals(properties.get("sslmode"))
                || properties.containsKey("sslfactory") && !"org.postgresql.ssl.DefaultJavaSSLFactory"
                        .equals(properties.get("sslfactory"))) throw new IllegalArgumentException();
    }
    private static String password(JsonNode node) {
        JsonNode value = node.get("password");
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) throw new IllegalArgumentException();
        return value.textValue(); // Secret bytes are never normalized or used as canonical identity.
    }
    private static void exact(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) throw new IllegalArgumentException();
        for (String name : names) if (!node.has(name)) throw new IllegalArgumentException();
    }
    private static String text(JsonNode node, String name) {
        JsonNode v = node == null ? null : node.get(name);
        if (v == null || !v.isTextual() || v.textValue().isBlank() || !v.textValue().equals(v.textValue().strip())
                || v.textValue().codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return v.textValue();
    }
    private static long positive(JsonNode node, String name) {
        JsonNode v = node.get(name);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() <= 0) throw new IllegalArgumentException();
        return v.longValue();
    }
    private static UUID uuid(JsonNode node, String name) {
        String v = text(node, name); UUID id = UUID.fromString(v);
        if (!id.toString().equals(v)) throw new IllegalArgumentException();
        return id;
    }
    static void privatePath(Path path, boolean existing) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalArgumentException();
        for (Path part = path; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw new IllegalArgumentException();
        if (!Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path.getParent(), LinkOption.NOFOLLOW_LINKS).equals(DIR_MODE)) throw new IllegalArgumentException();
        if (existing && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(FILE_MODE))) throw new IllegalArgumentException();
        if (!existing && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
    }
    static void createReceipt(Path path, ObjectNode value) throws IOException {
        writeNew(path, value); syncDirectory(path.getParent());
    }
    static void replaceReceipt(Path path, ObjectNode value) throws IOException {
        privatePath(path, true);
        Path temporary = Files.createTempFile(path.getParent(), ".capacity-receipt-", ".tmp",
                PosixFilePermissions.asFileAttribute(FILE_MODE));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                write(channel, value);
            }
            Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void writeNew(Path path, ObjectNode value) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(FILE_MODE))) { write(channel, value); }
    }
    private static void write(FileChannel channel, ObjectNode value) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(value));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
    }
    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    private static void publish(PrintStream out, Operation operation, String result, String code) {
        out.println("{\"schemaVersion\":1,\"operation\":" + (operation == null ? "null" : "\"" + operation.name() + "\"")
                + ",\"result\":\"" + result + "\",\"code\":\"" + code + "\"}");
    }
    private static DriverManagerDataSource source(JsonNode credential, long deadline) {
        String url = text(credential, "url");
        return new DriverManagerDataSource(url, text(credential, "username"), password(credential)) {
            @Override protected Connection getConnectionFromDriver(java.util.Properties properties) throws SQLException {
                long remaining = JdbcBulkCapacityLocalAdministration.requireRemaining(deadline);
                return java.sql.DriverManager.getConnection(BulkCapacityLocalAdministrationMain.boundedOwnerUrl(url,
                        Math.max(1, remaining / 1000)), properties);
            }
        };
    }
}
