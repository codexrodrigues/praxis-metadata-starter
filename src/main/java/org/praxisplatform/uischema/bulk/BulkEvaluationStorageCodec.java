package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;

/** Uses the bounded exact-number encoding shared with protected input storage. */
final class BulkEvaluationStorageCodec {
    private BulkEvaluationStorageCodec() { }
    static byte[] encode(BulkEvaluationSnapshot snapshot) { return BulkSnapshotStorageCodec.json(snapshot.storageDocument()); }
    static BulkEvaluationSnapshot decode(BulkStoredProposal proposal, byte[] payload, String fingerprint) {
        try {
            JsonNode root = BulkSnapshotStorageCodec.readDocument(payload);
            exact(root, Set.of("proposalId", "inputFingerprint", "createdAt", "expiresAt", "evaluatedAt", "targets", "governance"));
            if (!text(root,"proposalId").equals(proposal.id().toString())
                    || !text(root,"inputFingerprint").equals(proposal.snapshot().fingerprint())
                    || !text(root,"createdAt").equals(proposal.createdAt().toString())
                    || !text(root,"expiresAt").equals(proposal.expiresAt().toString())) throw invalid();
            JsonNode values = root.get("targets");
            if (!values.isArray() || values.isEmpty() || values.size()>10000) throw invalid();
            var codec = BulkSnapshotStorageCodec.codec(proposal.snapshot().codecId());
            var targets = new ArrayList<BulkTargetEvidence<?>>();
            for (var value : values) {
                exact(value, Set.of("id", "expectedVersion", "observedVersion", "facts", "plan"));
                Object id = codec.readWire(value.get("id"));
                targets.add(new BulkTargetEvidence<>(new BulkTarget<>(id, text(value,"expectedVersion")),
                        text(value,"observedVersion"), value.get("facts"), value.get("plan")));
            }
            JsonNode governance = root.get("governance");
            exact(governance, Set.of("evaluatorRevision", "authorizationFingerprint", "policies"));
            var policies = governance.get("policies");
            if (!policies.isArray() || policies.isEmpty() || policies.size() > 64) throw invalid();
            var observations = new ArrayList<BulkPolicyObservation>();
            for (var policy : policies) {
                exact(policy, Set.of("tenantId", "environment", "targetLayer", "targetArtifactType",
                        "targetArtifactKey", "resolutionState", "resolutionFingerprint", "observedAt"));
                observations.add(new BulkPolicyObservation(text(policy,"tenantId"), text(policy,"environment"),
                        text(policy,"targetLayer"), text(policy,"targetArtifactType"), text(policy,"targetArtifactKey"),
                        text(policy,"resolutionState"), text(policy,"resolutionFingerprint"), Instant.parse(text(policy,"observedAt"))));
            }
            var binding = new BulkEvaluationGovernance(text(governance,"evaluatorRevision"),
                    text(governance,"authorizationFingerprint"), observations);
            var restored = new BulkEvaluationSnapshot(proposal, Instant.parse(text(root,"evaluatedAt")), targets, binding);
            if (!restored.fingerprint().equals(fingerprint)) throw invalid();
            return restored;
        } catch (RuntimeException error) { throw invalid(); }
    }
    private static void exact(JsonNode node, Set<String> names) {
        if (node==null || !node.isObject() || node.size()!=names.size()) throw invalid();
        node.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) throw invalid(); });
    }
    private static String text(JsonNode node,String name) {
        JsonNode value=node.get(name);
        if (value==null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid protected evaluation content"); }
}
