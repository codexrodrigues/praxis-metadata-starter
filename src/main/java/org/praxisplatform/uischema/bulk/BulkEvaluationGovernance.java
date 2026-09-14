package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Protected provenance supplied by a trusted evaluator, not an authorization implementation.
 * The host owns current grants, required policy coordinates, scope and consistent capture.
 * Never synthesize authorizationFingerprint from JWT claims to imply revocable grants.
 */
@JsonIgnoreType
public record BulkEvaluationGovernance(String evaluatorRevision, String authorizationFingerprint,
        List<BulkPolicyObservation> policies) {
    public BulkEvaluationGovernance {
        BulkContractChecks.text(evaluatorRevision, "evaluatorRevision");
        BulkContractChecks.text(authorizationFingerprint, "authorizationFingerprint");
        policies = BulkContractChecks.nonEmpty(policies);
        if (policies.size() > 64) throw new IllegalArgumentException("Too many policy observations");
        policies.forEach(value -> Objects.requireNonNull(value, "policy observation"));
        BulkContractChecks.unique(policies.stream().map(BulkPolicyObservation::coordinate).toList());
        // Field-wise ordering avoids ambiguity from delimiters inside canonical identifiers.
        policies = policies.stream().sorted(Comparator.comparing(BulkPolicyObservation::tenantId)
                .thenComparing(BulkPolicyObservation::environment).thenComparing(BulkPolicyObservation::targetLayer)
                .thenComparing(BulkPolicyObservation::targetArtifactType).thenComparing(BulkPolicyObservation::targetArtifactKey)).toList();
    }
    ObjectNode document(boolean includeObservationTime) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("evaluatorRevision", evaluatorRevision); root.put("authorizationFingerprint", authorizationFingerprint);
        var observations = root.putArray("policies");
        policies.forEach(policy -> observations.add(policy.document(includeObservationTime)));
        return root;
    }
    @Override public String toString() { return "BulkEvaluationGovernance[protected]"; }
}
