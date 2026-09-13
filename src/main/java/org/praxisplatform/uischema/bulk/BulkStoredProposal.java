package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
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

    public BulkStoredProposal(UUID id, Instant createdAt, Instant expiresAt, BulkIntentSnapshot snapshot) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt").truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt").truncatedTo(ChronoUnit.MICROS);
        if (!this.expiresAt.isAfter(this.createdAt)
                || this.createdAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || this.expiresAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw new IllegalArgumentException("Invalid protected proposal validity window");
        }
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        var intent = snapshot.intent();
        if (!"SYNC".equals(intent.path("executionMode").asText())
                || (snapshot.mode() != BulkMode.PER_ITEM_UPDATE
                    && !"EXPLICIT".equals(intent.path("selection").path("mode").asText()))) {
            throw new IllegalArgumentException("Protected storage currently requires EXPLICIT SYNC input");
        }
        BulkSnapshotStorageCodec.codec(snapshot.codecId());
    }
    public UUID id() { return id; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public BulkIntentSnapshot snapshot() { return snapshot; }
    @Override public String toString() { return "BulkStoredProposal[protected]"; }
}
