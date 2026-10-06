package org.praxisplatform.uischema.bulk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;

/** Deployment-wide rights issuer. A right is not a local job or an accepted execution. */
final class JdbcBulkCapacityIssuer {
    public enum CapacityClass { ACTIVE, QUEUE }

    public enum Failure { CONFLICT, PENDING, UNAVAILABLE }

    public static final class CapacityFailure extends IllegalStateException {
        private final Failure reason;

        private CapacityFailure(Failure reason) {
            super("Capacity authority " + reason.name().toLowerCase(java.util.Locale.ROOT));
            this.reason = reason;
        }

        public Failure reason() { return reason; }
    }

    public record Request(UUID requestId, String deploymentId, String tenantId, String bindingId,
                          CapacityClass capacityClass, int requestedCount) {
        public Request {
            Objects.requireNonNull(requestId, "requestId");
            deploymentId = BulkCapacityAuthorityMigrator.canonical(deploymentId);
            tenantId = BulkCapacityAuthorityMigrator.canonical(tenantId);
            bindingId = BulkCapacityAuthorityMigrator.canonical(bindingId);
            Objects.requireNonNull(capacityClass, "capacityClass");
            if (requestedCount < 1 || requestedCount > (capacityClass == CapacityClass.ACTIVE ? 2 : 20))
                throw new IllegalArgumentException("Invalid capacity request count");
        }
    }

    public record Issue(UUID requestId, String deploymentId, String tenantId, String bindingId,
                        CapacityClass capacityClass, int requestedCount, int issuedCount,
                        String payloadDigest, List<UUID> tokenIds) {
        public Issue {
            Objects.requireNonNull(requestId, "requestId");
            deploymentId = BulkCapacityAuthorityMigrator.canonical(deploymentId);
            tenantId = BulkCapacityAuthorityMigrator.canonical(tenantId);
            bindingId = BulkCapacityAuthorityMigrator.canonical(bindingId);
            Objects.requireNonNull(capacityClass, "capacityClass");
            Objects.requireNonNull(payloadDigest, "payloadDigest");
            tokenIds = List.copyOf(tokenIds);
            if (requestedCount < 1 || issuedCount < 0 || issuedCount > requestedCount
                    || tokenIds.size() != issuedCount)
                throw new IllegalArgumentException("Invalid capacity issue snapshot");
        }
    }

    public record Token(UUID tokenId, UUID requestId, String deploymentId, String tenantId, String bindingId,
                        CapacityClass capacityClass, long bindingGeneration, int ordinal) {
        public Token {
            Objects.requireNonNull(tokenId, "tokenId");
            Objects.requireNonNull(requestId, "requestId");
            deploymentId = BulkCapacityAuthorityMigrator.canonical(deploymentId);
            tenantId = BulkCapacityAuthorityMigrator.canonical(tenantId);
            bindingId = BulkCapacityAuthorityMigrator.canonical(bindingId);
            Objects.requireNonNull(capacityClass, "capacityClass");
            if (bindingGeneration < 1 || ordinal < 1)
                throw new IllegalArgumentException("Invalid capacity token");
        }
    }

    /** Immutable copy of the authority's registered physical binding; never caller supplied. */
    public record Attestation(UUID attestationId, UUID databaseId, String bindingId,
                              String deploymentId, String tenantId, String environment,
                              long bindingGeneration, UUID authorityId, long authorityEpoch) {
        public Attestation {
            Objects.requireNonNull(attestationId, "attestationId");
            Objects.requireNonNull(databaseId, "databaseId");
            bindingId = BulkCapacityAuthorityMigrator.canonical(bindingId);
            deploymentId = BulkCapacityAuthorityMigrator.canonical(deploymentId);
            tenantId = BulkCapacityAuthorityMigrator.canonical(tenantId);
            environment = BulkCapacityAuthorityMigrator.canonical(environment);
            Objects.requireNonNull(authorityId, "authorityId");
            if (bindingGeneration <= 0 || authorityEpoch <= 0)
                throw new IllegalArgumentException("Invalid binding attestation");
        }
    }

    /** Complete individual issued right read through the protected authority reader function. */
    public record IssuedToken(UUID tokenId, UUID requestId, String deploymentId, String tenantId,
                              String environment, String bindingId, long bindingGeneration,
                              CapacityClass capacityClass, int ordinal, String payloadDigest,
                              String tokenState, UUID databaseId, UUID attestationId,
                              UUID authorityId, long authorityEpoch) {
        public IssuedToken {
            Objects.requireNonNull(tokenId, "tokenId");
            Objects.requireNonNull(requestId, "requestId");
            deploymentId = BulkCapacityAuthorityMigrator.canonical(deploymentId);
            tenantId = BulkCapacityAuthorityMigrator.canonical(tenantId);
            environment = BulkCapacityAuthorityMigrator.canonical(environment);
            bindingId = BulkCapacityAuthorityMigrator.canonical(bindingId);
            Objects.requireNonNull(capacityClass, "capacityClass");
            Objects.requireNonNull(databaseId, "databaseId");
            Objects.requireNonNull(attestationId, "attestationId");
            Objects.requireNonNull(authorityId, "authorityId");
            if (bindingGeneration <= 0 || ordinal <= 0 || authorityEpoch <= 0
                    || payloadDigest == null || !payloadDigest.matches("sha256:[0-9a-f]{64}")
                    || !"ISSUED".equals(tokenState))
                throw new IllegalArgumentException("Invalid issued token snapshot");
        }
    }

    /** Reader-only entry point used by installation; no allocator credential or caller proof. */
    static final class CapacityReader {
        private final BulkCapacityAuthorityInfrastructure reader;

        CapacityReader(BulkCapacityAuthorityInfrastructure reader) {
            this.reader = Objects.requireNonNull(reader, "reader");
            if (reader.access() != BulkCapacityAuthorityInfrastructure.Access.READER)
                throw new IllegalArgumentException("Issued rights require authority reader credentials");
        }

        BulkCapacityAuthorityMigrator.Identity identity() { return reader.identity(); }

        Optional<Attestation> readAttestation(UUID attestationId) {
            Objects.requireNonNull(attestationId, "attestationId");
            return reader.withConnection(connection -> {
                try (var statement = connection.prepareStatement(
                        "select * from praxis_bulk_capacity.read_attestation(?)")) {
                    statement.setObject(1, attestationId);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) return Optional.<Attestation>empty();
                        Attestation result = new Attestation(rows.getObject("attestation_id", UUID.class),
                                rows.getObject("database_id", UUID.class), rows.getString("binding_id"),
                                rows.getString("deployment_id"), rows.getString("tenant_id"),
                                rows.getString("environment"), rows.getLong("binding_generation"),
                                rows.getObject("authority_id", UUID.class), rows.getLong("authority_epoch"));
                        if (rows.next() || !identity().deploymentId().equals(result.deploymentId())
                                || !identity().environment().equals(result.environment())
                                || !identity().authorityId().equals(result.authorityId())
                                || identity().expectedAuthorityEpoch() != result.authorityEpoch())
                            throw new IllegalStateException("Authority attestation identity changed");
                        return Optional.of(result);
                    }
                }
            });
        }

        Optional<IssuedToken> readIssuedToken(UUID tokenId) {
            Objects.requireNonNull(tokenId, "tokenId");
            return reader.withConnection(connection -> {
                try (var statement = connection.prepareStatement(
                        "select * from praxis_bulk_capacity.read_issued_token(?)")) {
                    statement.setObject(1, tokenId);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) return Optional.<IssuedToken>empty();
                        IssuedToken result = new IssuedToken(rows.getObject("token_id", UUID.class),
                                rows.getObject("request_id", UUID.class), rows.getString("deployment_id"),
                                rows.getString("tenant_id"), rows.getString("environment"),
                                rows.getString("binding_id"), rows.getLong("binding_generation"),
                                CapacityClass.valueOf(rows.getString("capacity_class")),
                                rows.getInt("token_ordinal"), rows.getString("payload_digest"),
                                rows.getString("token_state"), rows.getObject("database_id", UUID.class),
                                rows.getObject("attestation_id", UUID.class),
                                rows.getObject("authority_id", UUID.class), rows.getLong("authority_epoch"));
                        if (rows.next() || !identity().deploymentId().equals(result.deploymentId())
                                || !identity().environment().equals(result.environment())
                                || !identity().authorityId().equals(result.authorityId())
                                || identity().expectedAuthorityEpoch() != result.authorityEpoch())
                            throw new IllegalStateException("Issued token authority identity changed");
                        return Optional.of(result);
                    }
                }
            });
        }
    }

    private final BulkCapacityAuthorityInfrastructure allocator;
    private final BulkCapacityAuthorityInfrastructure reader;

    public JdbcBulkCapacityIssuer(BulkCapacityAuthorityInfrastructure allocator,
                                  BulkCapacityAuthorityInfrastructure reader) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.reader = Objects.requireNonNull(reader, "reader");
        if (allocator.access() != BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR
                || reader.access() != BulkCapacityAuthorityInfrastructure.Access.READER
                || !allocator.identity().equals(reader.identity()))
            throw new IllegalArgumentException("Capacity allocator and reader must share one authority identity");
    }

    /** Records a bounded, immutable demand. Repeating the same UUID and typed payload is idempotent. */
    public void requestCapacity(Request request) {
        Objects.requireNonNull(request, "request");
        if (!allocator.identity().deploymentId().equals(request.deploymentId()))
            throw new CapacityFailure(Failure.UNAVAILABLE);
        String digest = digest(request);
        try {
            allocator.withConnection(connection -> {
                try (var statement = connection.prepareStatement(
                        "select praxis_bulk_capacity.request_capacity(?,?,?,?,?,?,?)")) {
                    statement.setObject(1, request.requestId());
                    statement.setString(2, request.deploymentId());
                    statement.setString(3, request.tenantId());
                    statement.setString(4, request.bindingId());
                    statement.setString(5, request.capacityClass().name());
                    statement.setInt(6, request.requestedCount());
                    statement.setString(7, digest);
                    statement.execute();
                }
                return null;
            });
        } catch (DataAccessException failure) {
            throw translate(failure);
        } catch (IllegalStateException unavailable) {
            throw new CapacityFailure(Failure.UNAVAILABLE);
        }
    }

    /** Issues one right to the next eligible tenant; no lease expiry or implicit reclamation. */
    public Optional<Token> allocateNext(CapacityClass capacityClass) {
        Objects.requireNonNull(capacityClass, "capacityClass");
        try {
            return allocator.withConnection(connection -> {
                try (var statement = connection.prepareStatement(
                        "select * from praxis_bulk_capacity.allocate_next(?,?)")) {
                    statement.setString(1, allocator.identity().deploymentId());
                    statement.setString(2, capacityClass.name());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) return Optional.<Token>empty();
                        Token token;
                        try {
                            token = new Token(rows.getObject("token_id", UUID.class),
                                    rows.getObject("request_id", UUID.class), rows.getString("deployment_id"),
                                    rows.getString("tenant_id"),
                                    rows.getString("binding_id"), CapacityClass.valueOf(rows.getString("capacity_class")),
                                    rows.getLong("binding_generation"), rows.getInt("token_ordinal"));
                            if (!allocator.identity().deploymentId().equals(token.deploymentId()))
                                throw new CapacityFailure(Failure.UNAVAILABLE);
                        } catch (IllegalArgumentException | NullPointerException corrupt) {
                            throw new CapacityFailure(Failure.UNAVAILABLE);
                        }
                        if (rows.next()) throw new IllegalStateException("Capacity issuer returned multiple rights");
                        return Optional.of(token);
                    }
                }
            });
        } catch (DataAccessException failure) {
            throw translate(failure);
        } catch (IllegalStateException unavailable) {
            throw new CapacityFailure(Failure.UNAVAILABLE);
        }
    }

    /** Typed readback uses reader credentials; it never accepts a caller-supplied token as authority. */
    public Optional<Issue> findIssue(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        try {
            return reader.withConnection(connection -> {
                try (var statement = connection.prepareStatement(
                        "select * from praxis_bulk_capacity.find_issue(?)")) {
                    statement.setObject(1, requestId);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) return Optional.<Issue>empty();
                        List<UUID> tokens = tokens(rows.getArray("token_ids"));
                        Issue issue;
                        try {
                            issue = new Issue(requestId, rows.getString("deployment_id"),
                                    rows.getString("tenant_id"), rows.getString("binding_id"),
                                    CapacityClass.valueOf(rows.getString("capacity_class")),
                                    rows.getInt("requested_count"), rows.getInt("issued_count"),
                                    rows.getString("payload_digest"), tokens);
                        } catch (IllegalArgumentException | NullPointerException corrupt) {
                            throw new CapacityFailure(Failure.UNAVAILABLE);
                        }
                        if (rows.next()) throw new IllegalStateException("Capacity readback returned multiple requests");
                        return Optional.of(issue);
                    }
                }
            });
        } catch (DataAccessException failure) {
            throw translate(failure);
        } catch (IllegalStateException unavailable) {
            throw new CapacityFailure(Failure.UNAVAILABLE);
        }
    }

    private static List<UUID> tokens(Array sqlArray) throws SQLException {
        if (sqlArray == null) throw new IllegalStateException("Capacity token array missing");
        try {
            Object raw = sqlArray.getArray();
            if (!(raw instanceof Object[] values)) throw new IllegalStateException("Invalid capacity token array");
            var result = new ArrayList<UUID>(values.length);
            for (Object value : values) {
                if (!(value instanceof UUID uuid)) throw new IllegalStateException("Invalid capacity token ID");
                result.add(uuid);
            }
            return List.copyOf(result);
        } finally {
            sqlArray.free();
        }
    }

    private static String digest(Request request) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            append(sha, request.requestId().toString());
            append(sha, request.deploymentId());
            append(sha, request.tenantId());
            append(sha, request.bindingId());
            append(sha, request.capacityClass().name());
            append(sha, Integer.toString(request.requestedCount()));
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void append(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static CapacityFailure translate(DataAccessException failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state == null) return new CapacityFailure(Failure.UNAVAILABLE);
                return switch (state) {
                    case "PZ001" -> new CapacityFailure(Failure.CONFLICT);
                    case "PZ002" -> new CapacityFailure(Failure.PENDING);
                    default -> new CapacityFailure(Failure.UNAVAILABLE);
                };
            }
            cursor = cursor.getCause();
        }
        return new CapacityFailure(Failure.UNAVAILABLE);
    }
}
