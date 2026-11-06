package org.praxisplatform.uischema.service.base;

import java.util.Objects;
import java.time.Instant;
import org.praxisplatform.uischema.bulk.BulkMode;

/** Server-owned origin of a native mutation admission; never supplied by an HTTP payload. */
public record ResourceMutationContext(Kind kind, Stage stage, BulkMode bulkMode, boolean collection, Instant referenceInstant) {
    /** Native resource lifecycle. */
    public enum Kind { CREATE, UPDATE, DELETE }
    /** Direct request, proposal evaluation, or confirmation (including locked revalidation). */
    public enum Stage { DIRECT, EVALUATION, CONFIRMATION }

    /** Rejects impossible combinations before a domain callback is invoked. */
    public ResourceMutationContext {
        Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(stage, "stage");
        if (bulkMode == null && stage != Stage.DIRECT
                || bulkMode != null && (stage == Stage.DIRECT || kind != Kind.UPDATE || collection)
                || bulkMode == BulkMode.DOMAIN_COMMAND || collection && kind != Kind.DELETE
                || stage != Stage.DIRECT && referenceInstant == null)
            throw new IllegalArgumentException("Invalid native mutation context");
    }
    /** @return direct create admission */
    public static ResourceMutationContext create() { return new ResourceMutationContext(Kind.CREATE, Stage.DIRECT, null, false, null); }
    /** @return direct update admission */
    public static ResourceMutationContext update() { return new ResourceMutationContext(Kind.UPDATE, Stage.DIRECT, null, false, null); }
    /** @return item delete admission */
    public static ResourceMutationContext delete() { return new ResourceMutationContext(Kind.DELETE, Stage.DIRECT, null, false, null); }
    /** @return collection admission with one captured instant, shared by every target */
    public static ResourceMutationContext deleteBatch() { return new ResourceMutationContext(Kind.DELETE, Stage.DIRECT, null, true, Instant.now()); }
    /** @param mode field-update mode @return proposal context */
    public static ResourceMutationContext bulkEvaluation(BulkMode mode, Instant referenceInstant) {
        return new ResourceMutationContext(Kind.UPDATE, Stage.EVALUATION, Objects.requireNonNull(mode), false, referenceInstant);
    }
    /** @param mode field-update mode @return confirmation/revalidation context */
    public static ResourceMutationContext bulkConfirmation(BulkMode mode, Instant referenceInstant) {
        return new ResourceMutationContext(Kind.UPDATE, Stage.CONFIRMATION, Objects.requireNonNull(mode), false, referenceInstant);
    }
    /** Requires a field-update bulk context. */
    public void requireBulk() { if (bulkMode == null) throw new IllegalArgumentException("Bulk context required"); }
}
