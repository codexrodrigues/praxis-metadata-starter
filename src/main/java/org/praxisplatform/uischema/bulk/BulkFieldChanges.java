package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Structural validation and application of uniform bulk field changes.
 *
 * <p>This class validates only the supplied field allowlists and creates a candidate JSON object.
 * It does not perform persistence, bean validation, domain validation, or authorization for an
 * actor. Callers must perform those checks in their respective evaluation and mutation phases.</p>
 */
public final class BulkFieldChanges {

    private BulkFieldChanges() {
    }

    /**
     * Validates field eligibility for changes using exact literal field names.
     */
    public static void validate(
            List<BulkFieldChange> changes,
            Set<String> writableFields,
            Set<String> clearableFields
    ) {
        requireAllowlist("Writable", writableFields);
        requireAllowlist("Clearable", clearableFields);

        if (!writableFields.containsAll(clearableFields)) {
            throw new IllegalArgumentException("Clearable fields must be a subset of writable fields.");
        }
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("At least one bulk field change is required.");
        }

        Set<String> changedFields = new HashSet<>();
        for (BulkFieldChange change : changes) {
            if (change == null) {
                throw new IllegalArgumentException("Bulk field changes must not contain null entries.");
            }

            String field = change.field();
            if (!changedFields.add(field)) {
                throw new IllegalArgumentException("Bulk field changes must not repeat fields.");
            }
            if (!writableFields.contains(field)) {
                throw new IllegalArgumentException("Bulk field is not writable.");
            }
            if (change.operator() == BulkChangeOperator.CLEAR && !clearableFields.contains(field)) {
                throw new IllegalArgumentException("Bulk field is not clearable.");
            }
        }
    }

    /**
     * Applies structural changes to a deep-copied candidate. Existing omitted fields remain
     * omitted; {@code CLEAR} writes a JSON null at the exact literal field key.
     */
    public static ObjectNode applyTo(
            ObjectNode current,
            List<BulkFieldChange> changes,
            Set<String> writableFields,
            Set<String> clearableFields
    ) {
        Objects.requireNonNull(current, "Current bulk resource JSON must not be null.");
        BulkJsonValues.validate(current);
        validate(changes, writableFields, clearableFields);

        ObjectNode candidate = current.deepCopy();
        for (BulkFieldChange change : changes) {
            if (change.operator() == BulkChangeOperator.CLEAR) {
                candidate.putNull(change.field());
            } else {
                candidate.set(change.field(), change.value());
            }
        }
        return candidate;
    }

    private static void requireAllowlist(String name, Set<String> fields) {
        Objects.requireNonNull(fields, name + " bulk field allowlist must not be null.");
        if (fields.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " bulk field allowlist must not contain null fields.");
        }
    }
}
