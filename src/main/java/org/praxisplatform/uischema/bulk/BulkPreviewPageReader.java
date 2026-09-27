package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;

/** Internal bounded RS2 read. Authorization and HTTP status decisions belong to the host. */
@JsonIgnoreType
final class BulkPreviewPageReader {
    private static final long MAX_PAGE_BYTES = 20L * 1024 * 1024;
    private final BulkExecutionInfrastructure infrastructure;

    BulkPreviewPageReader(BulkExecutionInfrastructure infrastructure) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
    }

    enum Kind { ABSENT, NOT_EVALUATED, COMPLETE, UNAVAILABLE, UNAVAILABLE_LEGACY }

    @JsonIgnoreType
    record Item(int ordinal, BulkTargetEligibility.Decision decision,
            List<ResourceCommandMessage> diagnostics) {
        Item { diagnostics = List.copyOf(diagnostics); }
    }

    @JsonIgnoreType
    record Page(Kind kind, int targetCount, String projectorRevision, List<Item> items,
            boolean hasMore, int nextOrdinal) {
        Page { items = List.copyOf(items); }
        static Page state(Kind kind) { return new Page(kind, 0, null, List.of(), false, -1); }
    }

    /** A cursor is not accepted here: position and scope must come from a future authorized caller. */
    Page read(BulkFingerprintContext scope, UUID proposalId, int lastOrdinal,
            int watermarkExclusive, int size) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(proposalId, "proposalId");
        if (!infrastructure.namespace().equals(scope.namespaceId()) || size < 1 || size > 200
                || lastOrdinal < -1 || lastOrdinal > 9999 || watermarkExclusive < 0
                || watermarkExclusive > 10000) throw new IllegalArgumentException("Invalid preview window");
        try {
            return infrastructure.withConsistentRead(connection -> {
                try {
                    return read(connection, scope, proposalId, lastOrdinal, watermarkExclusive, size);
                } catch (BulkProposalStorageException failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    throw corrupt();
                }
            });
        } catch (BulkProposalStorageException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
        }
    }

    private Page read(Connection connection, BulkFingerprintContext scope, UUID proposalId,
            int lastOrdinal, int watermarkExclusive, int size) throws SQLException {
        requireReadGate(connection);
        Header header = header(connection, scope, proposalId);
        if (header == null) return Page.state(Kind.ABSENT);
        if (header.kind() == Kind.NOT_EVALUATED) {
            requireNoRows(connection, proposalId, List.of("praxis_bulk_target_manifest",
                    "praxis_bulk_target_preview", "praxis_bulk_preview_item_integrity"));
            return Page.state(Kind.NOT_EVALUATED);
        }
        if (header.kind() != Kind.COMPLETE) {
            requireNoRows(connection, proposalId, List.of("praxis_bulk_target_preview",
                    "praxis_bulk_preview_item_integrity"));
            return Page.state(header.kind());
        }
        if (watermarkExclusive != header.targetCount() || lastOrdinal >= watermarkExclusive)
            throw corrupt();
        requireNoManifestSuffix(connection, proposalId, watermarkExclusive);
        byte[] parentDigest = BulkPreviewItemIntegrity.contextDigest(proposalId,
                header.fingerprint(), header.revision(), header.targetCount(),
                header.allowlist(), header.projectionDigest());
        Map<String, String> allowlist;
        try { allowlist = BulkPreviewStorage.validatedAllowlist(header.allowlist()); }
        catch (RuntimeException failure) { throw corrupt(); }
        long budget = header.selectedBytes();
        List<Item> items = new ArrayList<>(size);
        int expected = lastOrdinal + 1;
        boolean hasMore = false;
        try (var query = connection.prepareStatement("""
                select m.ordinal, m.evaluation_fingerprint, m.target_count,
                       m.wire_identity, m.wire_identity_digest, m.expected_version, m.target_digest,
                       p.evaluation_fingerprint, p.decision, p.diagnostics,
                       i.digest_version, i.item_digest
                  from praxis_bulk.praxis_bulk_target_manifest m
                  left join praxis_bulk.praxis_bulk_target_preview p
                    on p.proposal_id=m.proposal_id and p.ordinal=m.ordinal
                  left join praxis_bulk.praxis_bulk_preview_item_integrity i
                    on i.proposal_id=m.proposal_id and i.ordinal=m.ordinal
                 where m.proposal_id=? and m.ordinal>? and m.ordinal<?
                 order by m.ordinal limit ?
                """)) {
            query.setObject(1, proposalId);
            query.setInt(2, lastOrdinal);
            query.setInt(3, watermarkExclusive);
            query.setInt(4, size + 1);
            // V8 permits up to 8 MiB each for wire identity and expected version.
            // Fetch one row at a time so the page budget can reject it before more rows arrive.
            query.setFetchSize(1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    int ordinal = rows.getInt(1);
                    byte[] wire = rows.getBytes(4);
                    byte[] version = rows.getBytes(6);
                    byte[] diagnostics = rows.getBytes(10);
                    String decision = rows.getString(9);
                    String wireDigest = rows.getString(5);
                    String targetDigest = rows.getString(7);
                    String itemDigest = rows.getString(12);
                    if (ordinal != expected++ || !header.fingerprint().equals(rows.getString(2))
                            || rows.getInt(3) != header.targetCount()
                            || !header.fingerprint().equals(rows.getString(8))
                            || wire == null || version == null || diagnostics == null
                            || wireDigest == null || targetDigest == null || decision == null
                            || rows.getObject(11, Integer.class) == null || rows.getInt(11) != 1)
                        throw corrupt();
                    budget += wire.length + version.length + diagnostics.length
                            + utf8(rows.getString(2)) + utf8(wireDigest) + utf8(targetDigest)
                            + utf8(rows.getString(8)) + utf8(decision) + utf8(itemDigest)
                            + 3L * Integer.BYTES + 128L;
                    if (budget > MAX_PAGE_BYTES) throw unavailable();
                    try {
                        if (!BulkTargetDigest.wireIdentity(wire).equals(wireDigest)) throw corrupt();
                    } catch (BulkProposalStorageException failure) {
                        throw failure;
                    } catch (RuntimeException failure) { throw corrupt(); }
                    String expectedDigest;
                    try {
                        expectedDigest = BulkPreviewItemIntegrity.itemDigest(parentDigest, ordinal,
                                wire, wireDigest, version, targetDigest, decision, diagnostics);
                    } catch (RuntimeException failure) { throw corrupt(); }
                    if (!expectedDigest.equals(itemDigest)) throw corrupt();
                    List<ResourceCommandMessage> safe = diagnostics(decision, diagnostics, allowlist);
                    if (items.size() == size) hasMore = true;
                    else items.add(new Item(ordinal,
                            BulkTargetEligibility.Decision.valueOf(decision), safe));
                }
            }
        }
        if (!hasMore && expected != watermarkExclusive) throw corrupt();
        return new Page(Kind.COMPLETE, header.targetCount(), header.revision(), items,
                hasMore, items.isEmpty() ? lastOrdinal : items.getLast().ordinal());
    }

    private static void requireReadGate(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("select praxis_bulk.assert_preview_integrity_complete()");
             var rows = query.executeQuery()) {
            if (!rows.next() || !Boolean.TRUE.equals(rows.getObject(1, Boolean.class)) || rows.next())
                throw unavailable();
        }
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void requireNoManifestSuffix(Connection connection, UUID proposalId,
            int watermarkExclusive) throws SQLException {
        try (var query = connection.prepareStatement("""
                select 1 from praxis_bulk.praxis_bulk_target_manifest
                 where proposal_id=? and ordinal>=? limit 1
                """)) {
            query.setObject(1, proposalId);
            query.setInt(2, watermarkExclusive);
            try (var rows = query.executeQuery()) {
                if (rows.next()) throw corrupt();
            }
        }
    }

    private static void requireNoRows(Connection connection, UUID proposalId,
            List<String> tables) throws SQLException {
        for (String table : tables) {
            try (var query = connection.prepareStatement("select 1 from praxis_bulk."
                    + table + " where proposal_id=? limit 1")) {
                query.setObject(1, proposalId);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) throw corrupt();
                }
            }
        }
    }

    private static Header header(Connection connection, BulkFingerprintContext scope, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("""
                select p.fingerprint, e.input_fingerprint, e.evaluation_fingerprint,
                       s.evaluation_fingerprint, s.projection_state, s.integrity_version,
                       s.projector_revision, s.target_count, s.public_allowlist, s.projection_digest
                  from praxis_bulk.praxis_bulk_proposal p
                  left join praxis_bulk.praxis_bulk_evaluation e on e.proposal_id=p.proposal_id
                  left join praxis_bulk.praxis_bulk_preview_state s on s.proposal_id=p.proposal_id
                 where p.proposal_id=? and p.namespace_id=? and p.subject_id=?
                   and p.resource_key=? and p.operation_id=?
                """)) {
            query.setObject(1, id);
            query.setString(2, scope.namespaceId());
            query.setString(3, scope.subjectId());
            query.setString(4, scope.resourceKey());
            query.setString(5, scope.operationRef().operationId());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                String fingerprint = rows.getString(3);
                if (fingerprint == null) {
                    if (rows.getString(4) != null || rows.next()) throw corrupt();
                    return new Header(Kind.NOT_EVALUATED, null, null, 0, null, null, 0);
                }
                if (!rows.getString(1).equals(rows.getString(2)) || !fingerprint.equals(rows.getString(4)))
                    throw corrupt();
                String state = rows.getString(5);
                if (state == null || rows.getObject(6, Integer.class) == null || rows.getInt(6) != 11)
                    throw corrupt();
                Kind kind;
                try { kind = Kind.valueOf(state); }
                catch (IllegalArgumentException failure) { throw corrupt(); }
                String revision = rows.getString(7);
                Integer count = rows.getObject(8, Integer.class);
                byte[] allowlist = rows.getBytes(9);
                String digest = rows.getString(10);
                if (kind == Kind.COMPLETE) {
                    if (revision == null || revision.isBlank() || count == null || count < 1
                            || count > 10000 || allowlist == null || allowlist.length > 65536
                            || digest == null || !digest.matches("sha256:[0-9a-f]{64}")) throw corrupt();
                    long selectedBytes = utf8(rows.getString(1)) + utf8(rows.getString(2))
                            + utf8(fingerprint) + utf8(rows.getString(4)) + utf8(state)
                            + utf8(revision) + allowlist.length + utf8(digest)
                            + 2L * Integer.BYTES + 128L;
                    if (rows.next()) throw corrupt();
                    return new Header(kind, fingerprint, revision, count, allowlist, digest, selectedBytes);
                }
                if ((kind != Kind.UNAVAILABLE && kind != Kind.UNAVAILABLE_LEGACY)
                        || revision != null || count != null || allowlist != null || digest != null) throw corrupt();
                if (rows.next()) throw corrupt();
                return new Header(kind, fingerprint, null, 0, null, null, 0);
            }
        }
    }

    private static List<ResourceCommandMessage> diagnostics(String decision, byte[] bytes,
            Map<String, String> allowlist) {
        if (!"EXECUTABLE".equals(decision) && !"BLOCKED".equals(decision)) throw corrupt();
        if (bytes.length < 2 || bytes.length > 65536) throw corrupt();
        JsonNode document;
        try { document = BulkSnapshotStorageCodec.readDocument(bytes); }
        catch (RuntimeException failure) { throw corrupt(); }
        byte[] canonical;
        try { canonical = BulkSnapshotStorageCodec.json(document); }
        catch (RuntimeException failure) { throw corrupt(); }
        if (!document.isArray() || document.size() > 16
                || ("EXECUTABLE".equals(decision) && !document.isEmpty())
                || ("BLOCKED".equals(decision) && document.isEmpty())
                || !Arrays.equals(bytes, canonical)) throw corrupt();
        List<ResourceCommandMessage> result = new ArrayList<>(document.size());
        for (JsonNode value : document) {
            if (!value.isObject() || value.size() != 3 || !value.path("category").isTextual()
                    || !value.path("code").isTextual() || !value.path("message").isTextual()) throw corrupt();
            String category = value.get("category").textValue();
            String code = value.get("code").textValue();
            String message = value.get("message").textValue();
            if (!message.equals(allowlist.get(category + "\u0000" + code))) throw corrupt();
            try {
                result.add(new ResourceCommandMessage(ResourceCommandErrorCategory.valueOf(category),
                        code, message, null, Map.of()));
            } catch (RuntimeException failure) { throw corrupt(); }
        }
        return List.copyOf(result);
    }

    private record Header(Kind kind, String fingerprint, String revision, int targetCount,
            byte[] allowlist, String projectionDigest, long selectedBytes) { }

    private static BulkProposalStorageException corrupt() {
        return new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
    }

    private static BulkProposalStorageException unavailable() {
        return new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
    }
}
