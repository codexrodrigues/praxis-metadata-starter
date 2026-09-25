package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Uses the bounded exact-number encoding shared with protected input storage. */
final class BulkEvaluationStorageCodec {
    private BulkEvaluationStorageCodec() { }
    static byte[] encode(BulkEvaluationSnapshot snapshot) { return BulkSnapshotStorageCodec.json(snapshot.storageDocument()); }
    static JsonNode eligibilityDocument(BulkTargetEligibility eligibility) {
        var value = JsonNodeFactory.instance.objectNode();
        value.put("decision", eligibility.decision().name());
        var messages = value.putArray("diagnostics");
        for (var diagnostic : eligibility.diagnostics()) {
            var item = messages.addObject(); item.put("category", diagnostic.category().name());
            item.put("code", diagnostic.code()); item.put("message", diagnostic.message());
            if (diagnostic.target() == null) item.putNull("target"); else item.put("target", diagnostic.target());
        }
        return value;
    }
    private static BulkTargetEligibility eligibility(JsonNode value) {
        exact(value, Set.of("decision", "diagnostics"));
        String decision = text(value, "decision");
        JsonNode diagnostics = value.get("diagnostics");
        if (!diagnostics.isArray() || diagnostics.size() > 16) throw invalid();
        var messages = new ArrayList<ResourceCommandMessage>();
        for (var item : diagnostics) {
            exact(item, Set.of("category", "code", "message", "target"));
            String target = item.get("target").isNull() ? null : text(item, "target");
            messages.add(new ResourceCommandMessage(ResourceCommandErrorCategory.valueOf(text(item,"category")),
                    text(item,"code"), text(item,"message"), target, Map.of()));
        }
        return switch (BulkTargetEligibility.Decision.valueOf(decision)) {
            case EXECUTABLE -> {
                if (!messages.isEmpty()) throw invalid();
                yield BulkTargetEligibility.executable();
            }
            case BLOCKED -> BulkTargetEligibility.blocked(messages);
        };
    }
    static BulkEvaluationSnapshot decode(BulkStoredProposal proposal, byte[] payload, String fingerprint) {
        try {
            JsonNode root = BulkSnapshotStorageCodec.readDocument(payload);
            boolean typed = root.has("formatVersion");
            exact(root, typed ? Set.of("formatVersion", "proposalId", "inputFingerprint", "createdAt", "expiresAt", "evaluatedAt", "targets", "governance")
                    : Set.of("proposalId", "inputFingerprint", "createdAt", "expiresAt", "evaluatedAt", "targets", "governance"));
            if (typed && (!root.get("formatVersion").canConvertToInt() || root.get("formatVersion").intValue() != 2)) throw invalid();
            if (!text(root,"proposalId").equals(proposal.id().toString())
                    || !text(root,"inputFingerprint").equals(proposal.snapshot().fingerprint())
                    || !text(root,"createdAt").equals(proposal.createdAt().toString())
                    || !text(root,"expiresAt").equals(proposal.expiresAt().toString())) throw invalid();
            JsonNode values = root.get("targets");
            if (!values.isArray() || values.isEmpty() || values.size()>10000) throw invalid();
            var codec = BulkSnapshotStorageCodec.codec(proposal.snapshot().codecId());
            var targets = new ArrayList<BulkTargetEvidence<?>>();
            for (var value : values) {
                exact(value, typed ? Set.of("id", "expectedVersion", "observedVersion", "facts", "plan", "eligibility")
                        : Set.of("id", "expectedVersion", "observedVersion", "facts", "plan"));
                Object id = codec.readWire(value.get("id"));
                var target = new BulkTarget<>(id, text(value,"expectedVersion"));
                targets.add(typed ? new BulkTargetEvidence<>(target, text(value,"observedVersion"), value.get("facts"), value.get("plan"), eligibility(value.get("eligibility")))
                        : BulkTargetEvidence.legacy(target, text(value,"observedVersion"), value.get("facts"), value.get("plan")));
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
            var restored = typed ? new BulkEvaluationSnapshot(proposal, Instant.parse(text(root,"evaluatedAt")), targets, binding)
                    : BulkEvaluationSnapshot.legacy(proposal, Instant.parse(text(root,"evaluatedAt")), targets, binding);
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
