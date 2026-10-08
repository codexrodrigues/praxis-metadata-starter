package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

/** Private derived index; every row is checked against the protected evaluation before use. */
final class BulkOrdinalManifest {
    private BulkOrdinalManifest() { }

    static void insert(Connection connection, BulkEvaluationSnapshot evaluation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_target_manifest
                    (proposal_id, evaluation_fingerprint, ordinal, wire_identity,
                     wire_identity_digest, expected_version, target_count, target_digest)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int ordinal = 0;
            for (var evidence : evaluation.targets()) {
                Object id = evidence.target().id();
                var wire = id instanceof Integer number ? JsonNodeFactory.instance.numberNode(number)
                        : JsonNodeFactory.instance.textNode((String) id);
                statement.setObject(1, evaluation.proposal().id());
                statement.setString(2, evaluation.fingerprint());
                statement.setInt(3, ordinal);
                byte[] identity = BulkSnapshotStorageCodec.json(wire);
                statement.setBytes(4, identity);
                statement.setString(5, BulkTargetDigest.wireIdentity(identity));
                statement.setBytes(6, evidence.target().expectedVersion().getBytes(StandardCharsets.UTF_8));
                statement.setInt(7, evaluation.targets().size());
                statement.setString(8, BulkTargetDigest.of(evaluation.fingerprint(), ordinal, id,
                        evidence.target().expectedVersion()));
                statement.addBatch();
                ordinal++;
            }
            statement.executeBatch();
        }
    }

    /** Called inside the migrator's single bootstrap transaction, before final catalog validation. */
    static void backfillAndValidate(Connection connection) throws SQLException {
        inspect(connection, true);
    }

    static void validateAll(Connection connection) throws SQLException {
        inspect(connection, false);
    }

    private static void inspect(Connection connection, boolean backfill) throws SQLException {
        try (var query = connection.prepareStatement("""
                select p.proposal_id, p.created_at, p.expires_at, p.fingerprint, p.payload,
                       p.control_generation, p.control_descriptor_fingerprint, p.control_structural_revision,
                       e.input_fingerprint, e.evaluation_fingerprint, e.payload
                  from praxis_bulk.praxis_bulk_proposal p
                  join praxis_bulk.praxis_bulk_evaluation e on e.proposal_id = p.proposal_id
                 order by p.proposal_id
                """)) {
            query.setFetchSize(1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    UUID id = rows.getObject(1, UUID.class);
                    try {
                        var snapshot = BulkSnapshotStorageCodec.decode(rows.getBytes(5), rows.getString(4));
                        var proposal = BulkStoredProposal.decoded(id, rows.getObject(2, OffsetDateTime.class).toInstant(),
                                rows.getObject(3, OffsetDateTime.class).toInstant(), snapshot,
                                JdbcBulkProposalStore.expectation(rows.getObject(6, Long.class),
                                        rows.getString(7), rows.getString(8)));
                        if (!snapshot.fingerprint().equals(rows.getString(9))) throw invalid();
                        var evaluation = BulkEvaluationStorageCodec.decode(proposal, rows.getBytes(11), rows.getString(10));
                        int count = count(connection, id);
                        if (count == 0 && backfill) insert(connection, evaluation);
                        else if (count != evaluation.targets().size()) throw invalid();
                        validateOne(connection, evaluation);
                    } catch (RuntimeException failure) {
                        throw new IllegalStateException("Protected bulk manifest differs from evaluation: " + id, failure);
                    }
                }
            }
        }
    }

    static void validateOne(Connection connection, BulkEvaluationSnapshot evaluation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select ordinal, evaluation_fingerprint, wire_identity, wire_identity_digest,
                       expected_version, target_count, target_digest
                  from praxis_bulk.praxis_bulk_target_manifest
                 where proposal_id=? order by ordinal
                """)) {
            statement.setObject(1, evaluation.proposal().id());
            try (var rows = statement.executeQuery()) {
                for (int ordinal = 0; ordinal < evaluation.targets().size(); ordinal++) {
                    if (!rows.next()) throw invalid();
                    var target = evaluation.targets().get(ordinal).target();
                    var wire = target.id() instanceof Integer number ? JsonNodeFactory.instance.numberNode(number)
                            : JsonNodeFactory.instance.textNode((String) target.id());
                    byte[] identity = BulkSnapshotStorageCodec.json(wire);
                    if (rows.getInt(1) != ordinal || !evaluation.fingerprint().equals(rows.getString(2))
                            || !Arrays.equals(identity, rows.getBytes(3))
                            || !BulkTargetDigest.wireIdentity(identity).equals(rows.getString(4))
                            || !Arrays.equals(target.expectedVersion().getBytes(StandardCharsets.UTF_8), rows.getBytes(5))
                            || rows.getInt(6) != evaluation.targets().size()
                            || !BulkTargetDigest.of(evaluation.fingerprint(), ordinal, target.id(),
                                    target.expectedVersion()).equals(rows.getString(7))) throw invalid();
                }
                if (rows.next()) throw invalid();
            }
        }
    }

    private static int count(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*) from praxis_bulk.praxis_bulk_target_manifest where proposal_id=?
                """)) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Protected bulk ordinal manifest is incomplete or corrupt");
    }
}
