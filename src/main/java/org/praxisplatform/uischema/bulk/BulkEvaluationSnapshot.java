package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Complete, immutable domain evidence bound to a protected EXPLICIT/SYNC input.
 * Coverage and integrity do not establish policy eligibility, READY or permission to execute.
 */
@JsonIgnoreType
public final class BulkEvaluationSnapshot {
    private final BulkStoredProposal proposal;
    private final Instant evaluatedAt;
    private final List<BulkTargetEvidence<?>> targets;
    private final String fingerprint;
    private final BulkEvaluationGovernance governance;

    public BulkEvaluationSnapshot(BulkStoredProposal proposal, Instant evaluatedAt,
            List<? extends BulkTargetEvidence<?>> targets, BulkEvaluationGovernance governance) {
        this.proposal = Objects.requireNonNull(proposal, "proposal");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt").truncatedTo(ChronoUnit.MICROS);
        if (this.evaluatedAt.isBefore(proposal.createdAt()) || !this.evaluatedAt.isBefore(proposal.expiresAt()))
            throw new IllegalArgumentException("Evaluation instant outside proposal validity");
        this.governance = Objects.requireNonNull(governance, "governance");
        for (var policy : governance.policies()) {
            if (policy.observedAt().isBefore(proposal.createdAt()) || policy.observedAt().isAfter(this.evaluatedAt))
                throw new IllegalArgumentException("Policy observation outside evaluation window");
        }
        Objects.requireNonNull(targets, "targets");
        if (targets.isEmpty() || targets.size() > 10000) throw invalid();
        JsonNode intent = proposal.snapshot().intent();
        JsonNode expected = proposal.snapshot().mode() == BulkMode.PER_ITEM_UPDATE ? intent.get("items") : intent.at("/selection/targets");
        if (expected == null || !expected.isArray() || expected.size() != targets.size()) throw invalid();
        var codec = BulkSnapshotStorageCodec.codec(proposal.snapshot().codecId());
        var byId = new HashMap<Object, BulkTargetEvidence<?>>();
        for (var target : targets) {
            Objects.requireNonNull(target, "target evidence");
            Object id = codec.readWire(wire(target.target().id()));
            if (byId.put(id, target) != null) throw invalid();
        }
        var ordered = new ArrayList<BulkTargetEvidence<?>>(targets.size());
        for (var selected : expected) {
            Object id = codec.readWire(selected.get("id"));
            var evidence = byId.remove(id);
            if (evidence == null || !evidence.target().expectedVersion().equals(selected.path("expectedVersion").asText())) throw invalid();
            ordered.add(evidence);
        }
        if (!byId.isEmpty()) throw invalid();
        this.targets = List.copyOf(ordered);
        this.fingerprint = BulkCanonicalJson.evaluationDigest(storageDocument());
    }
    public BulkStoredProposal proposal() { return proposal; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public List<BulkTargetEvidence<?>> targets() { return targets; }
    public String fingerprint() { return fingerprint; }
    public BulkEvaluationGovernance governance() { return governance; }

    /**
     * Compares freshly captured evidence in the exact current server context. True means only
     * unchanged evidence within the proposal lifetime, never authorization or READY. The host
     * must perform fresh policy/grant/domain reads and validate them before calling this method;
     * this method cannot distinguish a reused observation from a fresh read.
     * Invalid current evidence throws a validation exception; callers must handle it as an
     * operational impediment, never substitute a prior successful comparison.
     */
    public boolean matchesCurrentEvidence(BulkFingerprintContext currentContext, Instant checkedAt,
            List<? extends BulkTargetEvidence<?>> currentTargets, BulkEvaluationGovernance currentGovernance) {
        Objects.requireNonNull(currentContext, "currentContext");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (!proposal.snapshot().context().equals(currentContext) || checkedAt.isBefore(evaluatedAt)
                || !checkedAt.isBefore(proposal.expiresAt())) return false;
        var current = new BulkEvaluationSnapshot(proposal, checkedAt, currentTargets, currentGovernance);
        return BulkCanonicalJson.revalidationDigest(comparisonDocument())
                .equals(BulkCanonicalJson.revalidationDigest(current.comparisonDocument()));
    }
    private JsonNode comparisonDocument() {
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) storageDocument();
        value.remove("evaluatedAt");
        value.set("governance", governance.document(false));
        return value;
    }
    @Override public String toString() { return "BulkEvaluationSnapshot[protected]"; }

    JsonNode storageDocument() {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("proposalId", proposal.id().toString()); root.put("inputFingerprint", proposal.snapshot().fingerprint());
        root.put("createdAt", proposal.createdAt().toString()); root.put("expiresAt", proposal.expiresAt().toString());
        root.put("evaluatedAt", evaluatedAt.toString());
        root.set("governance", governance.document(true));
        var values = root.putArray("targets");
        for (var target : targets) {
            var value = values.addObject(); value.set("id", wire(target.target().id()));
            value.put("expectedVersion", target.target().expectedVersion()); value.put("observedVersion", target.observedVersion());
            value.set("facts", target.facts()); value.set("plan", target.plan());
        }
        return root;
    }
    private static JsonNode wire(Object id) {
        if (id instanceof String text) return JsonNodeFactory.instance.textNode(text);
        if (id instanceof Integer number) return JsonNodeFactory.instance.numberNode(number);
        throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Evaluation must cover exactly the canonical input targets and versions");
    }
}
