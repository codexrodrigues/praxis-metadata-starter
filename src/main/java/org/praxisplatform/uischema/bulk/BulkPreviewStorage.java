package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

/** Private RS2 projection storage. Protected evaluation remains replay authority. */
final class BulkPreviewStorage {
    private BulkPreviewStorage() { }

    static void insert(Connection connection, BulkEvaluationSnapshot evaluation,
            BulkPreviewProjection projection) throws SQLException {
        projection.requireMatches(evaluation);
        UUID proposalId = evaluation.proposal().id();
        if (projection.state().equals("UNAVAILABLE")) {
            try (var state = connection.prepareStatement("""
                    insert into praxis_bulk.praxis_bulk_preview_state
                        (proposal_id, evaluation_fingerprint, projection_state)
                    values (?, ?, 'UNAVAILABLE')
                    """)) {
                state.setObject(1, proposalId);
                state.setString(2, evaluation.fingerprint());
                state.executeUpdate();
            }
            return;
        }
        byte[] allowlist = allowlist(projection.publicDiagnostics());
        Digest digest = new Digest(evaluation.fingerprint(), projection.revision(), allowlist);
        for (int ordinal = 0; ordinal < projection.items().size(); ordinal++) {
            var preview = projection.items().get(ordinal);
            digest.item(ordinal, preview.decision().name(), diagnostics(preview.diagnostics()));
        }
        try (var state = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_preview_state
                    (proposal_id, evaluation_fingerprint, projection_state, projector_revision,
                     target_count, public_allowlist, projection_digest)
                values (?, ?, 'COMPLETE', ?, ?, ?, ?)
                """)) {
            state.setObject(1, proposalId);
            state.setString(2, evaluation.fingerprint());
            state.setString(3, projection.revision());
            state.setInt(4, evaluation.targets().size());
            state.setBytes(5, allowlist);
            state.setString(6, digest.value());
            state.executeUpdate();
        }
        try (var item = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_target_preview
                    (proposal_id, evaluation_fingerprint, ordinal, decision, diagnostics)
                values (?, ?, ?, ?, ?)
                """)) {
            for (int ordinal = 0; ordinal < projection.items().size(); ordinal++) {
                var preview = projection.items().get(ordinal);
                item.setObject(1, proposalId);
                item.setString(2, evaluation.fingerprint());
                item.setInt(3, ordinal);
                item.setString(4, preview.decision().name());
                item.setBytes(5, diagnostics(preview.diagnostics()));
                item.addBatch();
            }
            item.executeBatch();
        }
    }

    static byte[] diagnostics(List<ResourceCommandMessage> diagnostics) {
        var array = JsonNodeFactory.instance.arrayNode();
        for (var message : diagnostics) {
            var value = array.addObject();
            value.put("category", message.category().name());
            value.put("code", message.code());
            value.put("message", message.message());
        }
        byte[] bytes = BulkSnapshotStorageCodec.json(array);
        if (bytes.length > 65536) throw new IllegalArgumentException("Preview diagnostics exceed storage limit");
        return bytes;
    }

    private static byte[] allowlist(List<BulkPreviewProjection.PublicDiagnostic> definitions) {
        var array = JsonNodeFactory.instance.arrayNode();
        definitions.stream().sorted(Comparator.comparing((BulkPreviewProjection.PublicDiagnostic d) -> d.category().name())
                .thenComparing(BulkPreviewProjection.PublicDiagnostic::code)).forEach(definition -> {
                    var value = array.addObject();
                    value.put("category", definition.category().name());
                    value.put("code", definition.code());
                    value.put("message", definition.message());
                });
        byte[] bytes = BulkSnapshotStorageCodec.json(array);
        if (bytes.length > 65536) throw new IllegalArgumentException("Preview allowlist exceeds storage limit");
        return bytes;
    }

    private static Map<String, String> validatedAllowlist(byte[] payload) {
        JsonNode document = BulkSnapshotStorageCodec.readDocument(payload);
        if (!document.isArray() || document.size() > 64) throw invalid();
        List<BulkPreviewProjection.PublicDiagnostic> definitions = new ArrayList<>();
        Map<String, String> result = new HashMap<>();
        for (JsonNode value : document) {
            if (!value.isObject() || value.size() != 3 || !value.path("category").isTextual()
                    || !value.path("code").isTextual() || !value.path("message").isTextual()) throw invalid();
            BulkPreviewProjection.PublicDiagnostic definition;
            try {
                definition = new BulkPreviewProjection.PublicDiagnostic(
                        org.praxisplatform.uischema.command.ResourceCommandErrorCategory.valueOf(
                                value.get("category").textValue()), value.get("code").textValue(),
                        value.get("message").textValue());
            } catch (IllegalArgumentException failure) { throw invalid(); }
            String key = definition.category().name() + "\u0000" + definition.code();
            if (result.putIfAbsent(key, definition.message()) != null) throw invalid();
            definitions.add(definition);
        }
        if (!java.util.Arrays.equals(payload, allowlist(definitions))) throw invalid();
        return result;
    }

    static void validateAll(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("""
                select e.proposal_id, e.evaluation_fingerprint, s.evaluation_fingerprint,
                       s.projection_state, s.projector_revision, s.target_count,
                       s.public_allowlist, s.projection_digest,
                       p.created_at, p.expires_at, p.fingerprint, p.payload,
                       p.control_generation, p.control_descriptor_fingerprint,
                       p.control_structural_revision, e.input_fingerprint, e.payload
                  from praxis_bulk.praxis_bulk_evaluation e
                  join praxis_bulk.praxis_bulk_proposal p on p.proposal_id=e.proposal_id
                  left join praxis_bulk.praxis_bulk_preview_state s on s.proposal_id=e.proposal_id
                 order by e.proposal_id
                """)) {
            query.setFetchSize(1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    UUID id = rows.getObject(1, UUID.class);
                    String fingerprint = rows.getString(2);
                    String state = rows.getString(4);
                    if (state == null || !fingerprint.equals(rows.getString(3))) throw invalid();
                    Integer count = rows.getObject(6, Integer.class);
                    var snapshot = BulkSnapshotStorageCodec.decode(rows.getBytes(12), rows.getString(11));
                    var proposal = new BulkStoredProposal(id,
                            rows.getObject(9, OffsetDateTime.class).toInstant(),
                            rows.getObject(10, OffsetDateTime.class).toInstant(), snapshot,
                            JdbcBulkProposalStore.expectation(rows.getObject(13, Long.class),
                                    rows.getString(14), rows.getString(15)));
                    if (!snapshot.fingerprint().equals(rows.getString(16))) throw invalid();
                    var evaluation = BulkEvaluationStorageCodec.decode(proposal, rows.getBytes(17), fingerprint);
                    try (var preview = connection.prepareStatement("""
                            select p.ordinal,p.evaluation_fingerprint,p.decision,p.diagnostics,
                                   m.target_count
                              from praxis_bulk.praxis_bulk_target_preview p
                              join praxis_bulk.praxis_bulk_target_manifest m
                                on m.proposal_id=p.proposal_id and m.ordinal=p.ordinal
                             where p.proposal_id=? order by p.ordinal
                            """)) {
                        preview.setObject(1, id);
                        try (var items = preview.executeQuery()) {
                            if (state.equals("UNAVAILABLE_LEGACY") || state.equals("UNAVAILABLE")) {
                                if (rows.getString(5) != null || count != null || rows.getBytes(7) != null
                                        || rows.getString(8) != null || items.next()
                                        || (state.equals("UNAVAILABLE") && !evaluation.hasTypedEligibility())) throw invalid();
                            } else if (state.equals("COMPLETE")) {
                                if (!evaluation.hasTypedEligibility() || rows.getString(5) == null
                                        || rows.getString(5).isBlank() || count == null
                                        || count < 1 || count > 10000
                                        || evaluation.targets().size() != count) throw invalid();
                                byte[] allowlistBytes = rows.getBytes(7);
                                if (allowlistBytes == null || rows.getString(8) == null) throw invalid();
                                Map<String, String> definitions = validatedAllowlist(allowlistBytes);
                                Digest digest = new Digest(fingerprint, rows.getString(5), allowlistBytes);
                                for (int ordinal = 0; ordinal < count; ordinal++) {
                                    if (!items.next() || items.getInt(1) != ordinal
                                            || !fingerprint.equals(items.getString(2))
                                            || items.getInt(5) != count) throw invalid();
                                    byte[] payload = items.getBytes(4);
                                    validateItem(evaluation.targets().get(ordinal).eligibility().orElseThrow(),
                                            items.getString(3), payload, definitions);
                                    digest.item(ordinal, items.getString(3), payload);
                                }
                                if (items.next() || !digest.value().equals(rows.getString(8))) throw invalid();
                            } else throw invalid();
                        }
                    }
                }
            }
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from praxis_bulk.praxis_bulk_preview_state s
                 left join praxis_bulk.praxis_bulk_evaluation e on e.proposal_id=s.proposal_id
                where e.proposal_id is null or s.evaluation_fingerprint<>e.evaluation_fingerprint
                """)) {
            if (!rows.next() || rows.getLong(1) != 0) throw invalid();
        }
    }

    private static void validateItem(BulkTargetEligibility original, String decision, byte[] payload,
            Map<String, String> allowlist) {
        JsonNode messages = BulkSnapshotStorageCodec.readDocument(payload);
        if (!messages.isArray() || messages.size() > 16) throw invalid();
        if ("EXECUTABLE".equals(decision) && !messages.isEmpty()) throw invalid();
        if ("BLOCKED".equals(decision) && messages.isEmpty()) throw invalid();
        if (!"EXECUTABLE".equals(decision) && !"BLOCKED".equals(decision)) throw invalid();
        if (!original.decision().name().equals(decision)
                || original.diagnostics().size() != messages.size()) throw invalid();
        for (int index = 0; index < messages.size(); index++) {
            JsonNode value = messages.get(index);
            if (!value.isObject() || value.size() != 3 || !value.path("category").isTextual()
                    || !value.path("code").isTextual() || !value.path("message").isTextual()) throw invalid();
            var protectedMessage = original.diagnostics().get(index);
            if (!protectedMessage.category().name().equals(value.get("category").textValue())
                    || !protectedMessage.code().equals(value.get("code").textValue())
                    || !value.get("message").textValue().equals(allowlist.get(
                            value.get("category").textValue() + "\u0000" + value.get("code").textValue()))) throw invalid();
        }
    }

    private static final class Digest {
        private final MessageDigest digest;

        Digest(String evaluationFingerprint, String revision, byte[] allowlist) {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
            string("praxis.bulk.preview/1");
            string(evaluationFingerprint);
            string(revision);
            bytes(allowlist);
        }

        void item(int ordinal, String decision, byte[] payload) {
            digest.update(ByteBuffer.allocate(4).putInt(ordinal).array());
            string(decision);
            bytes(payload);
        }

        String value() { return "sha256:" + HexFormat.of().formatHex(digest.digest()); }

        private void string(String value) { bytes(value.getBytes(StandardCharsets.UTF_8)); }
        private void bytes(byte[] value) {
            digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
            digest.update(value);
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Bulk preview projection is incomplete or corrupt");
    }
}
