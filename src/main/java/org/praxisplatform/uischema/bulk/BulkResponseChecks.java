package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Package-private invariants shared by public bulk response snapshots. */
final class BulkResponseChecks {
    private BulkResponseChecks() {
    }

    static void text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    static void count(long value, String name) {
        if (value < 0 || value > 10000) {
            throw new IllegalArgumentException(name + " must be between 0 and 10000");
        }
    }

    static CanonicalOperationRef operation(CanonicalOperationRef operationRef) {
        Objects.requireNonNull(operationRef, "operationRef is required");
        text(operationRef.operationId(), "operationRef.operationId");
        text(operationRef.path(), "operationRef.path");
        text(operationRef.method(), "operationRef.method");
        return operationRef;
    }

    static ActionCollectionAtomicity atomicity(ActionCollectionAtomicity atomicity) {
        if (atomicity == null || atomicity == ActionCollectionAtomicity.NOT_APPLICABLE) {
            throw new IllegalArgumentException("bulk atomicity must be ATOMIC or PER_ITEM");
        }
        return atomicity;
    }

    static JsonNode redactedIntent(JsonNode intent) {
        Objects.requireNonNull(intent, "redactedIntent is required");
        if (!intent.isObject()) {
            throw new IllegalArgumentException("redactedIntent must be a JSON object");
        }
        BulkCanonicalJson.preflight(intent);
        return intent.deepCopy();
    }

    static List<ResourceCommandMessage> diagnostics(List<ResourceCommandMessage> values, boolean required) {
        if (values == null || values.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("at least one diagnostic is required");
            }
            return List.of();
        }
        List<ResourceCommandMessage> copy = new ArrayList<>(values.size());
        for (ResourceCommandMessage message : values) {
            Objects.requireNonNull(message, "diagnostics must not contain null entries");
            Objects.requireNonNull(message.category(), "diagnostic category is required");
            text(message.code(), "diagnostic code");
            text(message.message(), "diagnostic message");
            if (message.metadata() != null && !message.metadata().isEmpty()) {
                throw new IllegalArgumentException("bulk diagnostics must not contain arbitrary metadata");
            }
            copy.add(new ResourceCommandMessage(
                    message.category(), message.code(), message.message(), message.target(), Map.of()
            ));
        }
        return List.copyOf(copy);
    }

    static List<BulkEvidenceReference> evidence(List<BulkEvidenceReference> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("evidence must not contain null entries");
        }
        return List.copyOf(values);
    }
}
