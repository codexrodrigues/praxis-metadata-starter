package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Protected durable input, not a public READY evaluation, execution or authorization decision. */
@JsonIgnoreType
public final class BulkStoredProposal {
    private final UUID id;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final BulkIntentSnapshot snapshot;
    private final BulkOperationControlExpectation controlExpectation;

    public BulkStoredProposal(UUID id, Instant createdAt, Instant expiresAt, BulkIntentSnapshot snapshot) {
        this(id, createdAt, expiresAt, snapshot, null);
    }

    public BulkStoredProposal(UUID id, Instant createdAt, Instant expiresAt, BulkIntentSnapshot snapshot,
            BulkOperationControlExpectation controlExpectation) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt").truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt").truncatedTo(ChronoUnit.MICROS);
        if (!this.expiresAt.isAfter(this.createdAt)
                || this.createdAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || this.expiresAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw new IllegalArgumentException("Invalid protected proposal validity window");
        }
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.controlExpectation = controlExpectation;
        var intent = snapshot.intent();
        boolean query = "QUERY".equals(intent.path("selection").path("mode").asText());
        if (!"SYNC".equals(intent.path("executionMode").asText())
                || query && (snapshot.mode() != BulkMode.UNIFORM_UPDATE
                    || snapshot.context().atomicity() != org.praxisplatform.uischema.action.ActionCollectionAtomicity.PER_ITEM)
                || snapshot.mode() != BulkMode.PER_ITEM_UPDATE && !query
                    && !"EXPLICIT".equals(intent.path("selection").path("mode").asText())) {
            throw new IllegalArgumentException("Protected storage requires a supported SYNC selection");
        }
        if (query) {
            if (!intent.path("selection").path("filter").isObject()
                    || !intent.path("selection").path("excludedIds").isArray()
                    || intent.path("selection").has("targets"))
                throw new IllegalArgumentException("QUERY requires canonical filter and exclusions");
            var excluded = new java.util.HashSet<Object>();
            var queryCodec = BulkSnapshotStorageCodec.codec(snapshot.codecId());
            for (JsonNode excludedId : intent.path("selection").path("excludedIds"))
                if (!excluded.add(queryCodec.readWire(excludedId)))
                    throw new IllegalArgumentException("QUERY exclusions must be unique");
            return;
        }
        var canonicalCodec = BulkSnapshotStorageCodec.codec(snapshot.codecId());
        JsonNode targets = snapshot.mode() == BulkMode.PER_ITEM_UPDATE
                ? intent.path("items") : intent.path("selection").path("targets");
        if (!targets.isArray()) throw new IllegalArgumentException("Protected storage requires canonical wire identities");
        for (JsonNode target : targets) {
            try {
                canonicalCodec.readWire(target.get("id"));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Protected storage requires canonical wire identities");
            }
        }
    }
    public UUID id() { return id; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public BulkIntentSnapshot snapshot() { return snapshot; }
    /** Null only for legacy/read-only inputs; new storage writes require a complete expectation. */
    public BulkOperationControlExpectation controlExpectation() { return controlExpectation; }
    @Override public String toString() { return "BulkStoredProposal[protected]"; }
}
