package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Protected observation from the canonical policy owner. State and fingerprint are opaque to
 * Metadata: the host must validate the complete resolution, its scope and required target set.
 * An observation, including initial absence, is never a grant or an executable decision.
 */
@JsonIgnoreType
public record BulkPolicyObservation(String tenantId, String environment, String targetLayer,
        String targetArtifactType, String targetArtifactKey, String resolutionState,
        String resolutionFingerprint, Instant observedAt) {
    public BulkPolicyObservation {
        BulkContractChecks.text(tenantId, "tenantId");
        BulkContractChecks.text(environment, "environment");
        BulkContractChecks.text(targetLayer, "targetLayer");
        BulkContractChecks.text(targetArtifactType, "targetArtifactType");
        BulkContractChecks.text(targetArtifactKey, "targetArtifactKey");
        BulkContractChecks.text(resolutionState, "resolutionState");
        BulkContractChecks.text(resolutionFingerprint, "resolutionFingerprint");
        observedAt = Objects.requireNonNull(observedAt, "observedAt").truncatedTo(ChronoUnit.MICROS);
    }
    List<String> coordinate() {
        return List.of(tenantId, environment, targetLayer, targetArtifactType, targetArtifactKey);
    }
    ObjectNode document(boolean includeObservationTime) {
        var value = JsonNodeFactory.instance.objectNode();
        value.put("tenantId", tenantId); value.put("environment", environment);
        value.put("targetLayer", targetLayer); value.put("targetArtifactType", targetArtifactType);
        value.put("targetArtifactKey", targetArtifactKey); value.put("resolutionState", resolutionState);
        value.put("resolutionFingerprint", resolutionFingerprint);
        if (includeObservationTime) value.put("observedAt", observedAt.toString());
        return value;
    }
    @Override public String toString() { return "BulkPolicyObservation[protected]"; }
}
