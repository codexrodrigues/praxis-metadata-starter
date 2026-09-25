package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Optional;

/** Protected domain facts and candidate plan, not a permission or eligibility decision. */
@JsonIgnoreType
public final class BulkTargetEvidence<WI> {
    private final BulkTarget<WI> target;
    private final String observedVersion;
    private final JsonNode facts;
    private final JsonNode plan;
    private final BulkTargetEligibility eligibility;

    public BulkTargetEvidence(BulkTarget<WI> target, String observedVersion, JsonNode facts, JsonNode plan,
            BulkTargetEligibility eligibility) {
        this.target = Objects.requireNonNull(target, "target");
        if (!(target.id() instanceof String) && !(target.id() instanceof Integer))
            throw new IllegalArgumentException("Evidence requires a canonical wire identity");
        BulkContractChecks.text(observedVersion, "observedVersion");
        this.observedVersion = observedVersion;
        this.facts = object(facts);
        this.plan = object(plan);
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
    }
    private BulkTargetEvidence(BulkTarget<WI> target, String observedVersion, JsonNode facts, JsonNode plan) {
        this.target = Objects.requireNonNull(target, "target");
        if (!(target.id() instanceof String) && !(target.id() instanceof Integer))
            throw new IllegalArgumentException("Evidence requires a canonical wire identity");
        BulkContractChecks.text(observedVersion, "observedVersion");
        this.observedVersion = observedVersion;
        this.facts = object(facts);
        this.plan = object(plan);
        this.eligibility = null;
    }
    static <WI> BulkTargetEvidence<WI> legacy(BulkTarget<WI> target, String observedVersion, JsonNode facts, JsonNode plan) {
        return new BulkTargetEvidence<>(target, observedVersion, facts, plan);
    }
    public BulkTarget<WI> target() { return target; }
    public String observedVersion() { return observedVersion; }
    public JsonNode facts() { return facts.deepCopy(); }
    public JsonNode plan() { return plan.deepCopy(); }
    public Optional<BulkTargetEligibility> eligibility() { return Optional.ofNullable(eligibility); }
    @Override public String toString() { return "BulkTargetEvidence[protected]"; }

    private static JsonNode object(JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("Evidence requires a domain object");
        return BulkCanonicalJson.normalize(value);
    }
}
