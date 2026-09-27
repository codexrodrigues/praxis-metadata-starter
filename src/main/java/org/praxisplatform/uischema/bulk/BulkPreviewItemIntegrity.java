package org.praxisplatform.uischema.bulk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;

/** Private V11 integrity leaves for bounded reads of the safe V9 preview. */
final class BulkPreviewItemIntegrity {
    private static final int INTEGRITY_VERSION = 11;
    private static final int DIGEST_VERSION = 1;
    private static final String PARENT_DOMAIN = "praxis.bulk.preview-parent/1";
    private static final String ITEM_DOMAIN = "praxis.bulk.preview-item/1";

    private BulkPreviewItemIntegrity() { }

    static void insertForProposal(Connection connection, UUID proposalId) throws SQLException {
        Header header = loadHeader(connection, proposalId);
        if (!"COMPLETE".equals(header.state())) return;
        byte[] context = contextDigest(header);
        try (var query = connection.prepareStatement("""
                select p.ordinal, p.decision, p.diagnostics,
                       m.wire_identity, m.wire_identity_digest,
                       m.expected_version, m.target_digest, m.evaluation_fingerprint,
                       m.target_count
                  from praxis_bulk.praxis_bulk_target_preview p
                  join praxis_bulk.praxis_bulk_target_manifest m
                    on m.proposal_id=p.proposal_id and m.ordinal=p.ordinal
                 where p.proposal_id=? order by p.ordinal
                """);
             var insert = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_preview_item_integrity
                    (proposal_id, ordinal, digest_version, item_digest)
                values (?, ?, 1, ?)
                """)) {
            query.setObject(1, proposalId);
            query.setFetchSize(64);
            try (var rows = query.executeQuery()) {
                int expectedOrdinal = 0;
                while (rows.next()) {
                    if (rows.getInt(1) != expectedOrdinal
                            || !header.evaluationFingerprint().equals(rows.getString(8))
                            || rows.getInt(9) != header.targetCount()) throw invalid();
                    insert.setObject(1, proposalId);
                    insert.setInt(2, expectedOrdinal);
                    insert.setString(3, itemDigest(context, expectedOrdinal, rows.getBytes(4),
                            rows.getString(5), rows.getBytes(6), rows.getString(7),
                            rows.getString(2), rows.getBytes(3)));
                    insert.addBatch();
                    expectedOrdinal++;
                }
                if (expectedOrdinal != header.targetCount()) throw invalid();
            }
            insert.executeBatch();
        }
    }

    static void backfillAndValidate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity
                """)) {
            if (!rows.next() || rows.getLong(1) != 0 || rows.next()) throw invalid();
        }
        try (var statement = connection.createStatement()) {
            statement.setFetchSize(64);
            try (var rows = statement.executeQuery("""
                select proposal_id from praxis_bulk.praxis_bulk_preview_state
                 where projection_state='COMPLETE' order by proposal_id
                """)) {
                while (rows.next()) insertForProposal(connection, rows.getObject(1, UUID.class));
            }
        }
        validateAll(connection);
    }

    static void validateAll(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setFetchSize(64);
            try (var parents = statement.executeQuery("""
                select proposal_id, evaluation_fingerprint, projection_state, integrity_version,
                       projector_revision, target_count, public_allowlist, projection_digest
                  from praxis_bulk.praxis_bulk_preview_state order by proposal_id
                """)) {
              while (parents.next()) {
                Header header = header(parents.getObject(1, UUID.class), parents.getString(2),
                        parents.getString(3), parents.getInt(4), parents.getString(5),
                        parents.getObject(6, Integer.class), parents.getBytes(7), parents.getString(8));
                if (!"COMPLETE".equals(header.state())) {
                    requireNoLeaves(connection, header.proposalId());
                    continue;
                }
                byte[] context = contextDigest(header);
                try (var query = connection.prepareStatement("""
                        select p.ordinal, p.decision, p.diagnostics,
                               m.wire_identity, m.wire_identity_digest,
                               m.expected_version, m.target_digest, m.evaluation_fingerprint,
                               m.target_count, i.digest_version, i.item_digest
                          from praxis_bulk.praxis_bulk_target_preview p
                          join praxis_bulk.praxis_bulk_target_manifest m
                            on m.proposal_id=p.proposal_id and m.ordinal=p.ordinal
                          left join praxis_bulk.praxis_bulk_preview_item_integrity i
                            on i.proposal_id=p.proposal_id and i.ordinal=p.ordinal
                         where p.proposal_id=? order by p.ordinal
                        """)) {
                    query.setObject(1, header.proposalId());
                    query.setFetchSize(64);
                    try (var rows = query.executeQuery()) {
                        for (int ordinal = 0; ordinal < header.targetCount(); ordinal++) {
                            if (!rows.next() || rows.getInt(1) != ordinal
                                    || !header.evaluationFingerprint().equals(rows.getString(8))
                                    || rows.getInt(9) != header.targetCount()
                                    || rows.getObject(10, Integer.class) == null
                                    || rows.getInt(10) != DIGEST_VERSION
                                    || !itemDigest(context, ordinal, rows.getBytes(4), rows.getString(5),
                                            rows.getBytes(6), rows.getString(7), rows.getString(2),
                                            rows.getBytes(3)).equals(rows.getString(11))) throw invalid();
                        }
                        if (rows.next()) throw invalid();
                    }
                }
            }
            }
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity i
                 left join praxis_bulk.praxis_bulk_preview_state s on s.proposal_id=i.proposal_id
                 left join praxis_bulk.praxis_bulk_target_preview p
                   on p.proposal_id=i.proposal_id and p.ordinal=i.ordinal
                 where s.proposal_id is null or s.projection_state<>'COMPLETE' or p.proposal_id is null
                """)) {
            if (!rows.next() || rows.getLong(1) != 0 || rows.next()) throw invalid();
        }
    }

    private static void requireNoLeaves(Connection connection, UUID proposalId) throws SQLException {
        try (var query = connection.prepareStatement("""
                select count(*) from praxis_bulk.praxis_bulk_preview_item_integrity where proposal_id=?
                """)) {
            query.setObject(1, proposalId);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != 0 || rows.next()) throw invalid();
            }
        }
    }

    private static Header loadHeader(Connection connection, UUID proposalId) throws SQLException {
        try (var query = connection.prepareStatement("""
                select proposal_id, evaluation_fingerprint, projection_state, integrity_version,
                       projector_revision, target_count, public_allowlist, projection_digest
                  from praxis_bulk.praxis_bulk_preview_state where proposal_id=?
                """)) {
            query.setObject(1, proposalId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw invalid();
                Header header = header(rows.getObject(1, UUID.class), rows.getString(2),
                        rows.getString(3), rows.getInt(4), rows.getString(5),
                        rows.getObject(6, Integer.class), rows.getBytes(7), rows.getString(8));
                if (rows.next()) throw invalid();
                return header;
            }
        }
    }

    private static Header header(UUID proposalId, String evaluationFingerprint, String state,
            int integrityVersion, String revision, Integer targetCount, byte[] allowlist,
            String projectionDigest) {
        if (proposalId == null || evaluationFingerprint == null || integrityVersion != INTEGRITY_VERSION
                || !("COMPLETE".equals(state) || "UNAVAILABLE".equals(state)
                    || "UNAVAILABLE_LEGACY".equals(state))) throw invalid();
        if ("COMPLETE".equals(state) && (revision == null || targetCount == null
                || targetCount < 1 || targetCount > 10000 || allowlist == null || projectionDigest == null)) throw invalid();
        return new Header(proposalId, evaluationFingerprint, state, revision, targetCount,
                allowlist, projectionDigest);
    }

    static byte[] contextDigest(UUID proposalId, String evaluationFingerprint, String revision,
            int targetCount, byte[] allowlist, String projectionDigest) {
        return contextDigest(header(proposalId, evaluationFingerprint, "COMPLETE", INTEGRITY_VERSION,
                revision, targetCount, allowlist, projectionDigest));
    }

    private static byte[] contextDigest(Header header) {
        Hash hash = new Hash();
        hash.string(PARENT_DOMAIN);
        hash.string(header.proposalId().toString());
        hash.integer(INTEGRITY_VERSION);
        hash.string(header.evaluationFingerprint());
        hash.string(header.revision());
        hash.integer(header.targetCount());
        hash.bytes(sha256(header.allowlist()));
        hash.string(header.projectionDigest());
        hash.string("COMPLETE");
        return hash.value();
    }

    static String itemDigest(byte[] context, int ordinal, byte[] wireIdentity,
            String wireIdentityDigest, byte[] expectedVersion, String targetDigest,
            String decision, byte[] diagnostics) {
        Hash hash = new Hash();
        hash.string(ITEM_DOMAIN);
        hash.bytes(context);
        hash.integer(DIGEST_VERSION);
        hash.integer(ordinal);
        hash.bytes(wireIdentity);
        hash.string(wireIdentityDigest);
        hash.bytes(expectedVersion);
        hash.string(targetDigest);
        hash.string(decision);
        hash.bytes(diagnostics);
        return "sha256:" + HexFormat.of().formatHex(hash.value());
    }

    private record Header(UUID proposalId, String evaluationFingerprint, String state,
            String revision, Integer targetCount, byte[] allowlist, String projectionDigest) { }

    private static byte[] sha256(byte[] bytes) {
        if (bytes == null) throw invalid();
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    private static final class Hash {
        private final MessageDigest digest;

        private Hash() {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
        }

        private Hash integer(int value) { return bytes(ByteBuffer.allocate(4).putInt(value).array()); }
        private Hash string(String value) { return bytes(value.getBytes(StandardCharsets.UTF_8)); }
        private Hash bytes(byte[] value) {
            if (value == null) throw invalid();
            digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
            digest.update(value);
            return this;
        }
        private byte[] value() { return digest.digest(); }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Bulk preview item integrity is incomplete or corrupt");
    }
}
