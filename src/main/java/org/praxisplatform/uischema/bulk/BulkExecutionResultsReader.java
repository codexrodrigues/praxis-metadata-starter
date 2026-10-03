package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded, protected RS4 observation. The host still owns authorization and redaction. */
@JsonIgnoreType
final class BulkExecutionResultsReader {
    private static final long MAX_PAGE_BYTES = 20L * 1024 * 1024;
    private final BulkExecutionInfrastructure infrastructure;

    BulkExecutionResultsReader(BulkExecutionInfrastructure infrastructure) {
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
    }

    enum Kind { ABSENT, LIVE, TOMBSTONE }

    @JsonIgnoreType
    static final class Item {
        private final int ordinal;
        private final byte[] wireIdentity;
        private final BulkItemStatus status;
        private final BulkUnitReasonCode reasonCode;

        private Item(int ordinal, byte[] wireIdentity, BulkItemStatus status,
                BulkUnitReasonCode reasonCode) {
            this.ordinal = ordinal;
            this.wireIdentity = wireIdentity.clone();
            this.status = status;
            this.reasonCode = reasonCode;
        }
        int ordinal() { return ordinal; }
        byte[] wireIdentity() { return wireIdentity.clone(); }
        Object decodedWireIdentity() { return BulkExecutionResultsReader.wireIdentity(wireIdentity); }
        BulkItemStatus status() { return status; }
        BulkUnitReasonCode reasonCode() { return reasonCode; }
        @Override public String toString() { return "BulkExecutionResultItem[protected]"; }
    }

    @JsonIgnoreType
    static final class Page {
        private final Kind kind;
        private final BulkDurableExecutionStatus executionStatus;
        private final String tombstoneTerminalStatus;
        private final int nextOrdinal;
        private final int targetCount;
        private final List<Item> items;
        private final boolean hasMore;
        private final int lastReturnedOrdinal;
        private final int watermarkExclusive;

        private Page(Kind kind, BulkDurableExecutionStatus executionStatus,
                String tombstoneTerminalStatus, int nextOrdinal, int targetCount,
                List<Item> items, boolean hasMore, int lastReturnedOrdinal,
                int watermarkExclusive) {
            this.kind = kind;
            this.executionStatus = executionStatus;
            this.tombstoneTerminalStatus = tombstoneTerminalStatus;
            this.nextOrdinal = nextOrdinal;
            this.targetCount = targetCount;
            this.items = List.copyOf(items);
            this.hasMore = hasMore;
            this.lastReturnedOrdinal = lastReturnedOrdinal;
            this.watermarkExclusive = watermarkExclusive;
        }
        Kind kind() { return kind; }
        BulkDurableExecutionStatus executionStatus() { return executionStatus; }
        String tombstoneTerminalStatus() { return tombstoneTerminalStatus; }
        int nextOrdinal() { return nextOrdinal; }
        int targetCount() { return targetCount; }
        List<Item> items() { return items; }
        boolean hasMore() { return hasMore; }
        int lastReturnedOrdinal() { return lastReturnedOrdinal; }
        int watermarkExclusive() { return watermarkExclusive; }
        @Override public String toString() { return "BulkExecutionResultsPage[protected]"; }
    }

    /** Starts one internal result window. A future authorized cursor must bind its watermark. */
    Page read(BulkFingerprintContext scope, UUID executionId, int lastOrdinal, int size) {
        if (lastOrdinal != -1) throw new IllegalArgumentException("Continuation requires a fixed watermark");
        return read(scope, executionId, lastOrdinal, size, null);
    }

    /** Continues a previously observed window; the caller must preserve its first watermark. */
    Page read(BulkFingerprintContext scope, UUID executionId, int lastOrdinal, int size,
            int fixedWatermarkExclusive) {
        return read(scope, executionId, lastOrdinal, size, Integer.valueOf(fixedWatermarkExclusive));
    }

    private Page read(BulkFingerprintContext scope, UUID executionId, int lastOrdinal, int size,
            Integer fixedWatermarkExclusive) {
        validateWindow(scope, executionId, lastOrdinal, size, fixedWatermarkExclusive);
        try {
            return infrastructure.withConsistentRead(connection -> readPage(connection, scope,
                    executionId, lastOrdinal, size, fixedWatermarkExclusive));
        } catch (BulkDurableExecutionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE);
        }
    }

    Page read(Connection connection, BulkFingerprintContext scope, UUID executionId,
            int lastOrdinal, int size) throws SQLException {
        validateWindow(scope, executionId, lastOrdinal, size, null);
        if (lastOrdinal != -1) throw new IllegalArgumentException("Continuation requires a fixed watermark");
        return readPage(connection, scope, executionId, lastOrdinal, size, null);
    }

    Page read(Connection connection, BulkFingerprintContext scope, UUID executionId,
            int lastOrdinal, int size, int fixedWatermarkExclusive) throws SQLException {
        validateWindow(scope, executionId, lastOrdinal, size, fixedWatermarkExclusive);
        return readPage(connection, scope, executionId, lastOrdinal, size,
                Integer.valueOf(fixedWatermarkExclusive));
    }

    private void validateWindow(BulkFingerprintContext scope, UUID executionId,
            int lastOrdinal, int size, Integer fixedWatermarkExclusive) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(executionId, "executionId");
        if (!infrastructure.namespace().equals(scope.namespaceId()) || lastOrdinal < -1
                || lastOrdinal > 9999 || size < 1 || size > 200
                || (fixedWatermarkExclusive != null && (fixedWatermarkExclusive < 0
                    || fixedWatermarkExclusive > 10000 || lastOrdinal >= fixedWatermarkExclusive)))
            throw new IllegalArgumentException("Invalid result window");
    }

    private static Page readPage(Connection connection, BulkFingerprintContext scope, UUID executionId,
            int lastOrdinal, int size, Integer fixedWatermarkExclusive) throws SQLException {
        Header header = header(connection, scope, executionId);
        String tombstone = JdbcBulkDurableExecution.scopedTombstone(connection, scope, executionId);
        if (header == null) return new Page(tombstone == null ? Kind.ABSENT : Kind.TOMBSTONE,
                null, tombstone, 0, 0, List.of(), false, lastOrdinal,
                fixedWatermarkExclusive == null ? 0 : fixedWatermarkExclusive);
        if (tombstone != null) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (header.atomicity() == ActionCollectionAtomicity.ATOMIC)
            return readAtomicPage(connection, header, executionId, lastOrdinal, size, fixedWatermarkExclusive);
        if (lastOrdinal >= header.targetCount())
            throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE);
        int currentlyEligibleExclusive = header.status() == BulkDurableExecutionStatus.STOPPED
                || header.status() == BulkDurableExecutionStatus.COMPLETED
                || header.status() == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS
                ? header.targetCount() : header.nextOrdinal();
        if (fixedWatermarkExclusive != null && fixedWatermarkExclusive > currentlyEligibleExclusive)
            throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE);
        int exclusive = fixedWatermarkExclusive == null
                ? currentlyEligibleExclusive : fixedWatermarkExclusive;
        if (lastOrdinal >= exclusive - 1)
            return new Page(Kind.LIVE, header.status(), null, header.nextOrdinal(),
                    header.targetCount(), List.of(), false, lastOrdinal, exclusive);

        long budget = header.selectedBytes();
        var items = new ArrayList<Item>(size);
        int expected = lastOrdinal + 1;
        boolean hasMore = false;
        try (var query = connection.prepareStatement("""
                select m.ordinal, m.evaluation_fingerprint, m.target_count,
                       m.wire_identity, m.wire_identity_digest, m.expected_version, m.target_digest,
                       r.unit_ordinal, r.target_digest, r.expected_version, r.attempt_id,
                       r.owner_epoch, r.outcome, r.confirmed_at, r.unit_deadline_at,
                       a.unit_ordinal, a.target_digest, a.expected_version, a.attempt_id,
                       a.owner_epoch, a.outcome, a.reason_code, a.recorded_at
                  from praxis_bulk.praxis_bulk_target_manifest m
                  left join praxis_bulk.praxis_bulk_item_receipt r
                    on r.execution_id=? and r.unit_ordinal=m.ordinal
                  left join praxis_bulk.praxis_bulk_admission a
                    on a.execution_id=? and a.unit_ordinal=m.ordinal
                 where m.proposal_id=? and m.ordinal>? and m.ordinal<?
                 order by m.ordinal limit ?
                """)) {
            query.setObject(1, executionId);
            query.setObject(2, executionId);
            query.setObject(3, header.proposalId());
            query.setInt(4, lastOrdinal);
            query.setInt(5, exclusive);
            query.setInt(6, size + 1);
            query.setFetchSize(1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    int ordinal = rows.getInt(1);
                    byte[] wire = rows.getBytes(4);
                    byte[] version = rows.getBytes(6);
                    String wireDigest = rows.getString(5);
                    String targetDigest = rows.getString(7);
                    String receiptDigest = rows.getString(9);
                    String receiptVersion = rows.getString(10);
                    String receiptOutcome = rows.getString(13);
                    String admissionDigest = rows.getString(17);
                    String admissionVersion = rows.getString(18);
                    String admissionOutcome = rows.getString(21);
                    String admissionReason = rows.getString(22);
                    budget += 256L + bytes(wire) + bytes(version)
                            + utf8(rows.getString(2)) + utf8(wireDigest) + utf8(targetDigest)
                            + utf8(receiptDigest) + utf8(receiptVersion) + utf8(receiptOutcome)
                            + utf8(admissionDigest) + utf8(admissionVersion)
                            + utf8(admissionOutcome) + utf8(admissionReason);
                    if (budget > MAX_PAGE_BYTES)
                        throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE);
                    if (ordinal != expected++ || !header.evaluationFingerprint().equals(rows.getString(2))
                            || rows.getInt(3) != header.targetCount() || wire == null || version == null
                            || wireDigest == null || targetDigest == null)
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    Object id = wireIdentity(wire);
                    String expectedVersion = new String(version, StandardCharsets.UTF_8);
                    try {
                        if (!Arrays.equals(expectedVersion.getBytes(StandardCharsets.UTF_8), version)
                                || !BulkTargetDigest.wireIdentity(wire).equals(wireDigest)
                                || !BulkTargetDigest.of(header.evaluationFingerprint(), ordinal, id,
                                        expectedVersion).equals(targetDigest))
                            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    } catch (BulkDurableExecutionException invalid) {
                        throw invalid;
                    } catch (RuntimeException invalid) {
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    }

                    Item item = item(rows, header, ordinal, wire, version, targetDigest,
                            receiptDigest, receiptVersion, receiptOutcome,
                            admissionDigest, admissionVersion, admissionOutcome, admissionReason);
                    if (items.size() == size) hasMore = true;
                    else items.add(item);
                }
            }
        }
        if (!hasMore && expected != exclusive)
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        return new Page(Kind.LIVE, header.status(), null, header.nextOrdinal(),
                header.targetCount(), items, hasMore,
                items.isEmpty() ? lastOrdinal : items.getLast().ordinal(), exclusive);
    }

    private static Page readAtomicPage(Connection connection, Header header, UUID executionId,
            int lastOrdinal, int size, Integer fixedWatermarkExclusive) throws SQLException {
        if (header.targetCount() > 50 || header.nextOrdinal() != 0
                && header.nextOrdinal() != header.targetCount())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        validateAtomicWindowEvidence(connection, header, executionId);
        int eligible = header.status() == BulkDurableExecutionStatus.COMPLETED
                ? header.targetCount() : header.status() == BulkDurableExecutionStatus.STOPPED
                ? header.targetCount() : 0;
        if (fixedWatermarkExclusive != null && fixedWatermarkExclusive > eligible)
            throw failure(BulkDurableExecutionException.Reason.UNAVAILABLE);
        int exclusive = fixedWatermarkExclusive == null ? eligible : fixedWatermarkExclusive;
        if (lastOrdinal >= exclusive - 1)
            return new Page(Kind.LIVE, header.status(), null, header.nextOrdinal(),
                    header.targetCount(), List.of(), false, lastOrdinal, exclusive);
        boolean complete = header.status() == BulkDurableExecutionStatus.COMPLETED;
        long budget = header.selectedBytes();
        var items = new ArrayList<Item>(size);
        int expected = lastOrdinal + 1;
        boolean hasMore = false;
        try (var query = connection.prepareStatement("""
                select m.ordinal,m.evaluation_fingerprint,m.target_count,m.wire_identity,
                       m.wire_identity_digest,m.expected_version,m.target_digest,
                       i.unit_ordinal,i.target_digest,i.expected_version,i.outcome,
                       h.owner_epoch,h.confirmed_at,h.unit_deadline_at,h.target_count
                  from praxis_bulk.praxis_bulk_target_manifest m
                  left join praxis_bulk.praxis_bulk_atomic_item_result i
                    on i.execution_id=? and i.unit_ordinal=m.ordinal
                  left join praxis_bulk.praxis_bulk_atomic_receipt h
                    on h.execution_id=i.execution_id
                 where m.proposal_id=? and m.ordinal>? and m.ordinal<?
                 order by m.ordinal limit ?
                """)) {
            query.setObject(1, executionId); query.setObject(2, header.proposalId());
            query.setInt(3, lastOrdinal); query.setInt(4, exclusive); query.setInt(5, size + 1);
            query.setFetchSize(1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    int ordinal = rows.getInt(1);
                    byte[] wire = rows.getBytes(4), version = rows.getBytes(6);
                    String digest = rows.getString(7);
                    budget += 256L + bytes(wire) + bytes(version) + utf8(digest)
                            + utf8(rows.getString(9)) + utf8(rows.getString(10));
                    if (budget > MAX_PAGE_BYTES || ordinal != expected++ || wire == null || version == null
                            || !header.evaluationFingerprint().equals(rows.getString(2))
                            || rows.getInt(3) != header.targetCount()
                            || !BulkTargetDigest.wireIdentity(wire).equals(rows.getString(5)))
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    Object identity = wireIdentity(wire);
                    String expectedVersion = new String(version, StandardCharsets.UTF_8);
                    if (!Arrays.equals(expectedVersion.getBytes(StandardCharsets.UTF_8), version)
                            || !BulkTargetDigest.of(header.evaluationFingerprint(), ordinal,
                                    identity, expectedVersion).equals(digest))
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    BulkItemStatus status = BulkItemStatus.NOT_PROCESSED;
                    if (complete) {
                        if (rows.getObject(8, Integer.class) == null || rows.getInt(8) != ordinal
                                || !digest.equals(rows.getString(9))
                                || !expectedVersion.equals(rows.getString(10))
                                || rows.getObject(12, Long.class) == null
                                || rows.getLong(12) > header.ownerEpoch()
                                || rows.getObject(13, OffsetDateTime.class) == null
                                || rows.getObject(14, OffsetDateTime.class) == null
                                || !rows.getObject(13, OffsetDateTime.class)
                                    .isBefore(rows.getObject(14, OffsetDateTime.class))
                                || rows.getObject(15, Integer.class) == null
                                || rows.getInt(15) != header.targetCount())
                            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                        status = switch (rows.getString(11)) {
                            case "CONFIRMED" -> BulkItemStatus.CONFIRMED;
                            case "UNCHANGED" -> BulkItemStatus.UNCHANGED;
                            default -> throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                        };
                    } else if (rows.getObject(8, Integer.class) != null)
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    if (items.size() == size) hasMore = true;
                    else items.add(new Item(ordinal, wire, status, null));
                }
            }
        }
        if (!hasMore && expected != exclusive)
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        return new Page(Kind.LIVE, header.status(), null, header.nextOrdinal(),
                header.targetCount(), items, hasMore,
                items.isEmpty() ? lastOrdinal : items.getLast().ordinal(), exclusive);
    }

    private static void validateAtomicWindowEvidence(Connection connection, Header header,
            UUID executionId) throws SQLException {
        int required = header.status() == BulkDurableExecutionStatus.COMPLETED
                || header.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK
                ? header.targetCount() : 0;
        if (header.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED)
            required = -1;
        if (required >= 0) {
            try (var complete = connection.prepareStatement("""
                    select praxis_bulk.atomic_evidence_complete(?,?)
                    """)) {
                complete.setObject(1, executionId); complete.setInt(2, required);
                try (var rows = complete.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1) || rows.next())
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                }
            }
        }
        try (var query = connection.prepareStatement("""
                select (select count(*) from praxis_bulk.praxis_bulk_atomic_receipt h
                         where h.execution_id=?),
                       (select count(*) from praxis_bulk.praxis_bulk_atomic_item_result i
                         where i.execution_id=?),
                       (select count(*) from praxis_bulk.praxis_bulk_atomic_rejection j
                         where j.execution_id=?),
                       (select count(*) from praxis_bulk.praxis_bulk_item_receipt r
                         where r.execution_id=?),
                       (select count(*) from praxis_bulk.praxis_bulk_admission a
                         where a.execution_id=?)
                """)) {
            for (int index = 1; index <= 5; index++) query.setObject(index, executionId);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || rows.getInt(4) != 0 || rows.getInt(5) != 0)
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                int headers = rows.getInt(1), children = rows.getInt(2), rejections = rows.getInt(3);
                if (headers > 1 || rejections > 1 || rows.next())
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                if (header.status() == BulkDurableExecutionStatus.COMPLETED
                        || header.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK) {
                    if (headers != 1 || children != header.targetCount() || rejections != 0)
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                } else if (header.status() == BulkDurableExecutionStatus.STOPPED) {
                    boolean requiresRejection = header.terminalReasonCode() == BulkUnitReasonCode.UNIT_ROLLED_BACK;
                    if (headers != 0 || children != 0 || rejections != (requiresRejection ? 1 : 0)
                            && !(header.terminalReasonCode() == BulkUnitReasonCode.DEADLINE_EXCEEDED
                                && rejections <= 1))
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                } else if (header.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED) {
                    if (rejections != 0 || !(headers == 0 && children == 0
                            || headers == 1 && children == header.targetCount()))
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                } else if (headers != 0 || children != 0 || rejections != 0)
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
        if (header.status() == BulkDurableExecutionStatus.COMPLETED
                || header.status() == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK)
            validateAtomicReceipt(connection, header, executionId);
        if (header.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED) {
            try (var present = connection.prepareStatement("""
                    select exists(select 1 from praxis_bulk.praxis_bulk_atomic_receipt
                                  where execution_id=?)
                    """)) {
                present.setObject(1, executionId);
                try (var row = present.executeQuery()) {
                    if (!row.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    if (row.getBoolean(1)) {
                        try (var complete = connection.prepareStatement("""
                                select praxis_bulk.atomic_evidence_complete(?,?)
                                """)) {
                            complete.setObject(1, executionId);
                            complete.setInt(2, header.targetCount());
                            try (var checked = complete.executeQuery()) {
                                if (!checked.next() || !checked.getBoolean(1) || checked.next())
                                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                            }
                        }
                        validateAtomicReceipt(connection, header, executionId);
                    }
                    if (row.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                }
            }
        }
        if (header.status() == BulkDurableExecutionStatus.STOPPED)
            validateAtomicRejection(connection, header, executionId);
    }

    private static void validateAtomicReceipt(Connection connection, Header header,
            UUID executionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select h.set_digest,h.target_count,h.effect_count,h.effect_digest,
                       exists(select 1 from praxis_bulk.praxis_bulk_atomic_effect_ref f
                              join praxis_bulk.praxis_bulk_atomic_item_result i
                                on i.execution_id=f.execution_id and i.unit_ordinal=f.unit_ordinal
                             where f.execution_id=h.execution_id and i.outcome='UNCHANGED')
                  from praxis_bulk.praxis_bulk_atomic_receipt h where h.execution_id=?
                """)) {
            statement.setObject(1, executionId);
            try (var row = statement.executeQuery()) {
                if (!row.next() || row.getInt(2) != header.targetCount() || row.getBoolean(5)
                        || !row.getString(1).equals(atomicManifestDigest(connection, header))
                        || !row.getString(4).equals(atomicEffectDigest(connection, executionId,
                                row.getInt(3)))
                        || row.next())
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
    }

    private static String atomicEffectDigest(Connection connection, UUID executionId,
            int expectedCount) throws SQLException {
        var effects = new ArrayList<BulkTargetDigest.EffectReference>();
        try (var statement = connection.prepareStatement("""
                select unit_ordinal,effect_ref from praxis_bulk.praxis_bulk_atomic_effect_ref
                 where execution_id=? order by unit_ordinal,effect_ref collate "C"
                """)) {
            statement.setObject(1, executionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (effects.size() >= 400)
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    effects.add(new BulkTargetDigest.EffectReference(rows.getInt(1), rows.getString(2)));
                }
            }
        }
        if (effects.size() != expectedCount)
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        try { return BulkTargetDigest.effectsOf(effects); }
        catch (IllegalArgumentException invalid) {
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        }
    }

    private static String atomicManifestDigest(Connection connection, Header header) throws SQLException {
        var targetDigests = new ArrayList<String>(header.targetCount());
        try (var targets = connection.prepareStatement("""
                select ordinal,target_digest from praxis_bulk.praxis_bulk_target_manifest
                 where proposal_id=? order by ordinal
                """)) {
            targets.setObject(1, header.proposalId());
            try (var rows = targets.executeQuery()) {
                while (rows.next()) {
                    if (targetDigests.size() >= header.targetCount()
                            || rows.getInt(1) != targetDigests.size())
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    targetDigests.add(rows.getString(2));
                }
            }
        }
        if (targetDigests.size() != header.targetCount())
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        try { return BulkTargetDigest.setOf(header.evaluationFingerprint(), targetDigests); }
        catch (IllegalArgumentException invalid) {
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        }
    }

    private static void validateAtomicRejection(Connection connection, Header header,
            UUID executionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select attempt_id,set_digest,reason_code
                  from praxis_bulk.praxis_bulk_atomic_rejection where execution_id=?
                """)) {
            statement.setObject(1, executionId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    if (header.terminalReasonCode() == BulkUnitReasonCode.UNIT_ROLLED_BACK)
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                    return;
                }
                UUID attempt = row.getObject(1, UUID.class);
                String digest = row.getString(2);
                String reason = row.getString(3);
                if (attempt == null || digest == null || reason == null || row.next())
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                BulkUnitReasonCode typed;
                try { typed = BulkUnitReasonCode.valueOf(reason); }
                catch (IllegalArgumentException invalid) {
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                }
                if (header.terminalReasonCode() == BulkUnitReasonCode.DEADLINE_EXCEEDED) {
                    if (typed != BulkUnitReasonCode.DEADLINE_EXCEEDED)
                        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                } else if (header.terminalReasonCode() != BulkUnitReasonCode.UNIT_ROLLED_BACK
                        || typed == BulkUnitReasonCode.DEADLINE_EXCEEDED)
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                if (!digest.equals(atomicManifestDigest(connection, header)))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            }
        }
    }

    private static Item item(ResultSet rows, Header header, int ordinal, byte[] wire,
            byte[] version, String targetDigest, String receiptDigest, String receiptVersion,
            String receiptOutcome, String admissionDigest, String admissionVersion,
            String admissionOutcome, String admissionReason) throws SQLException {
        boolean receipt = rows.getObject(8, Integer.class) != null;
        boolean admission = rows.getObject(16, Integer.class) != null;
        if (receipt && admission) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (ordinal >= header.nextOrdinal()) {
            if (header.status() != BulkDurableExecutionStatus.STOPPED || receipt || admission)
                throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            return new Item(ordinal, wire, BulkItemStatus.NOT_PROCESSED, null);
        }
        if (!receipt && !admission) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        if (receipt) {
            if (rows.getInt(8) != ordinal || !targetDigest.equals(receiptDigest)
                    || !Arrays.equals(version, utf8bytes(receiptVersion))
                    || rows.getObject(11, UUID.class) == null
                    || rows.getObject(12, Long.class) == null
                    || rows.getLong(12) < 1 || rows.getLong(12) > header.ownerEpoch()
                    || rows.getObject(14, OffsetDateTime.class) == null
                    || !rows.getObject(14, OffsetDateTime.class).toInstant().isBefore(header.deadlineAt()))
                throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            var unitDeadline = rows.getObject(15, OffsetDateTime.class);
            if (unitDeadline != null && (!rows.getObject(14, OffsetDateTime.class).isBefore(unitDeadline)
                    || unitDeadline.toInstant().isAfter(header.deadlineAt())))
                throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            BulkItemStatus status;
            if ("CONFIRMED".equals(receiptOutcome)) status = BulkItemStatus.CONFIRMED;
            else if ("UNCHANGED".equals(receiptOutcome)) status = BulkItemStatus.UNCHANGED;
            else throw failure(BulkDurableExecutionException.Reason.CORRUPT);
            return new Item(ordinal, wire, status, null);
        }
        if (rows.getInt(16) != ordinal || !targetDigest.equals(admissionDigest)
                || !Arrays.equals(version, utf8bytes(admissionVersion))
                || rows.getObject(19, UUID.class) == null
                || rows.getObject(20, Long.class) == null
                || rows.getLong(20) < 1 || rows.getLong(20) > header.ownerEpoch()
                || rows.getObject(23, OffsetDateTime.class) == null
                || !rows.getObject(23, OffsetDateTime.class).toInstant().isBefore(header.deadlineAt()))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        BulkItemStatus status;
        BulkUnitReasonCode reason;
        try {
            status = BulkItemStatus.valueOf(admissionOutcome);
            reason = BulkUnitReasonCode.valueOf(admissionReason);
        } catch (RuntimeException invalid) {
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        }
        if (!((status == BulkItemStatus.DENIED && reason == BulkUnitReasonCode.TARGET_DENIED)
                || (status == BulkItemStatus.INVALID && (reason == BulkUnitReasonCode.TARGET_NOT_FOUND
                    || reason == BulkUnitReasonCode.TARGET_INVALID))
                || (status == BulkItemStatus.CONFLICT && (reason == BulkUnitReasonCode.TARGET_VERSION_CONFLICT
                    || reason == BulkUnitReasonCode.TARGET_STATE_CONFLICT
                    || reason == BulkUnitReasonCode.TARGET_DEPENDENCY_CHANGED))))
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        return new Item(ordinal, wire, status, reason);
    }

    private static Header header(Connection connection, BulkFingerprintContext scope,
            UUID executionId) throws SQLException {
        try (var query = connection.prepareStatement("""
                select e.proposal_id, e.status, e.next_ordinal, e.target_count,
                       e.input_fingerprint, e.evaluation_fingerprint, e.owner_epoch,
                       e.deadline_at, e.terminal_at, p.fingerprint,
                       v.input_fingerprint, v.evaluation_fingerprint,
                       p.namespace_id, p.subject_id, p.resource_key, p.operation_id,
                       e.active_attempt_id, e.active_attempt_ordinal, e.active_target_digest,
                       e.active_attempt_epoch, e.active_unit_deadline_at, e.terminal_reason_code,
                       exists (select 1 from praxis_bulk.praxis_bulk_admission a
                                where a.execution_id=e.execution_id), e.atomicity, e.active_set_digest
                  from praxis_bulk.praxis_bulk_execution e
                  left join praxis_bulk.praxis_bulk_proposal p on p.proposal_id=e.proposal_id
                  left join praxis_bulk.praxis_bulk_evaluation v on v.proposal_id=e.proposal_id
                 where e.execution_id=? and e.namespace_id=? and e.subject_id=?
                   and e.resource_key=? and e.operation_id=?
                """)) {
            query.setObject(1, executionId);
            query.setString(2, scope.namespaceId());
            query.setString(3, scope.subjectId());
            query.setString(4, scope.resourceKey());
            query.setString(5, scope.operationRef().operationId());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                UUID proposalId = rows.getObject(1, UUID.class);
                String statusText = rows.getString(2);
                int nextOrdinal = rows.getInt(3);
                int targetCount = rows.getInt(4);
                String input = rows.getString(5);
                String evaluation = rows.getString(6);
                Long ownerEpoch = rows.getObject(7, Long.class);
                OffsetDateTime deadline = rows.getObject(8, OffsetDateTime.class);
                OffsetDateTime terminal = rows.getObject(9, OffsetDateTime.class);
                String proposalFingerprint = rows.getString(10);
                String evaluationInput = rows.getString(11);
                String evaluationStored = rows.getString(12);
                if (proposalId == null || input == null || evaluation == null
                        || !input.equals(proposalFingerprint) || !input.equals(evaluationInput)
                        || !evaluation.equals(evaluationStored)
                        || !scope.namespaceId().equals(rows.getString(13))
                        || !scope.subjectId().equals(rows.getString(14))
                        || !scope.resourceKey().equals(rows.getString(15))
                        || !scope.operationRef().operationId().equals(rows.getString(16))
                        || ownerEpoch == null
                        || ownerEpoch < 1 || deadline == null || targetCount < 1
                        || targetCount > 10000 || nextOrdinal < 0 || nextOrdinal > targetCount)
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                BulkDurableExecutionStatus status;
                try { status = BulkDurableExecutionStatus.valueOf(statusText); }
                catch (RuntimeException invalid) {
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                }
                boolean isTerminal = status == BulkDurableExecutionStatus.COMPLETED
                        || status == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS
                        || status == BulkDurableExecutionStatus.STOPPED;
                UUID activeAttempt = rows.getObject(17, UUID.class);
                Integer activeOrdinal = rows.getObject(18, Integer.class);
                String activeDigest = rows.getString(19);
                Long activeEpoch = rows.getObject(20, Long.class);
                OffsetDateTime activeDeadline = rows.getObject(21, OffsetDateTime.class);
                String terminalReason = rows.getString(22);
                boolean hasAdmission = rows.getBoolean(23);
                ActionCollectionAtomicity atomicity;
                try { atomicity = ActionCollectionAtomicity.valueOf(rows.getString(24)); }
                catch (RuntimeException invalid) { throw failure(BulkDurableExecutionException.Reason.CORRUPT); }
                String activeSetDigest = rows.getString(25);
                boolean inFlight = status == BulkDurableExecutionStatus.UNIT_IN_FLIGHT
                        || status == BulkDurableExecutionStatus.UNIT_COMMITTED_PENDING_ACK;
                if (atomicity == ActionCollectionAtomicity.PER_ITEM && inFlight && (activeAttempt == null || activeOrdinal == null
                        || activeOrdinal != nextOrdinal || nextOrdinal >= targetCount
                        || activeDigest == null || activeEpoch == null || activeEpoch < 1
                        || activeEpoch > ownerEpoch || activeDeadline == null
                        || activeDeadline.toInstant().isAfter(deadline.toInstant())))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                if (atomicity == ActionCollectionAtomicity.ATOMIC && inFlight && (activeAttempt == null
                        || activeOrdinal != null || activeDigest != null || activeSetDigest == null
                        || activeEpoch == null || activeDeadline == null || nextOrdinal != 0))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                if (!inFlight && status != BulkDurableExecutionStatus.RECONCILIATION_REQUIRED
                        && (activeAttempt != null || activeOrdinal != null || activeDigest != null
                            || activeSetDigest != null || activeEpoch != null || activeDeadline != null))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                if ((terminal != null) != isTerminal
                        || ((status == BulkDurableExecutionStatus.COMPLETED
                            || status == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS)
                            && nextOrdinal != targetCount)
                        || (status == BulkDurableExecutionStatus.STOPPED
                            && (nextOrdinal >= targetCount || terminalReason == null))
                        || (status == BulkDurableExecutionStatus.COMPLETED && hasAdmission)
                        || (status == BulkDurableExecutionStatus.COMPLETED_WITH_ERRORS && !hasAdmission))
                    throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                long selected = 256L + 16L + 4L * Integer.BYTES + 2L * Long.BYTES
                        + utf8(statusText) + utf8(input) + utf8(evaluation)
                        + utf8(proposalFingerprint) + utf8(evaluationInput)
                        + utf8(evaluationStored) + utf8(rows.getString(13))
                        + utf8(rows.getString(14)) + utf8(rows.getString(15))
                        + utf8(rows.getString(16)) + utf8(activeDigest)
                        + utf8(terminalReason) + 32L;
                if (rows.next()) throw failure(BulkDurableExecutionException.Reason.CORRUPT);
                BulkUnitReasonCode reason;
                try { reason = terminalReason == null ? null : BulkUnitReasonCode.valueOf(terminalReason); }
                catch (RuntimeException invalid) { throw failure(BulkDurableExecutionException.Reason.CORRUPT); }
                return new Header(proposalId, status, nextOrdinal, targetCount,
                        evaluation, ownerEpoch, deadline.toInstant(), selected, atomicity, reason);
            }
        }
    }

    private static Object wireIdentity(byte[] wire) {
        try {
            JsonNode node = BulkSnapshotStorageCodec.readDocument(wire);
            if (!Arrays.equals(BulkSnapshotStorageCodec.json(node), wire))
                throw new IllegalArgumentException("Noncanonical protected identity");
            if (node.isTextual() && !node.textValue().isEmpty()) return node.textValue();
            if (node.isIntegralNumber()) return node.bigIntegerValue().intValueExact();
        } catch (RuntimeException invalid) {
            throw failure(BulkDurableExecutionException.Reason.CORRUPT);
        }
        throw failure(BulkDurableExecutionException.Reason.CORRUPT);
    }

    private static byte[] utf8bytes(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    private static int bytes(byte[] value) { return value == null ? 0 : value.length; }
    private static int utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
    private static BulkDurableExecutionException failure(BulkDurableExecutionException.Reason reason) {
        return new BulkDurableExecutionException(reason);
    }

    private record Header(UUID proposalId, BulkDurableExecutionStatus status, int nextOrdinal,
            int targetCount, String evaluationFingerprint, long ownerEpoch,
            java.time.Instant deadlineAt, long selectedBytes,
            ActionCollectionAtomicity atomicity, BulkUnitReasonCode terminalReasonCode) { }
}
