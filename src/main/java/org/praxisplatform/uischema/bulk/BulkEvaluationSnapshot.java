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

    public BulkEvaluationSnapshot(BulkStoredProposal proposal, Instant evaluatedAt,
            List<? extends BulkTargetEvidence<?>> targets) {
        this.proposal = Objects.requireNonNull(proposal, "proposal");
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt").truncatedTo(ChronoUnit.MICROS);
        if (this.evaluatedAt.isBefore(proposal.createdAt()) || !this.evaluatedAt.isBefore(proposal.expiresAt()))
            throw new IllegalArgumentException("Evaluation instant outside proposal validity");
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
    @Override public String toString() { return "BulkEvaluationSnapshot[protected]"; }

    JsonNode storageDocument() {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("proposalId", proposal.id().toString()); root.put("inputFingerprint", proposal.snapshot().fingerprint());
        root.put("createdAt", proposal.createdAt().toString()); root.put("expiresAt", proposal.expiresAt().toString());
        root.put("evaluatedAt", evaluatedAt.toString());
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
