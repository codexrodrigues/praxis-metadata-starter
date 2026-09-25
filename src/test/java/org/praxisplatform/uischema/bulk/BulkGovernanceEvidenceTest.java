package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.CONTEXT;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.bytes;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.proposal;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.snapshot;

class BulkGovernanceEvidenceTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void snapshotRequiresGovernanceAsItsMandatoryFinalConstructorArgument() {
        var value = evidence();

        assertThatThrownBy(() -> new BulkEvaluationSnapshot(
                value.proposal(), value.evaluatedAt(), value.targets(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void comparisonRequiresExactContextAndProposalLifetime() {
        var value = evidence();
        var checkedAt = value.evaluatedAt().plusSeconds(1);

        assertThat(value.matchesCurrentEvidence(CONTEXT, checkedAt, value.targets(), governance(checkedAt))).isTrue();
        assertThat(value.matchesCurrentEvidence(context("namespace-other", CONTEXT.subjectId(), CONTEXT.schemaRevision()),
                checkedAt, value.targets(), governance(checkedAt))).isFalse();
        assertThat(value.matchesCurrentEvidence(context(CONTEXT.namespaceId(), CONTEXT.subjectId(), "schema-other"),
                checkedAt, value.targets(), governance(checkedAt))).isFalse();
        for (var changed : List.of(
                new BulkFingerprintContext(CONTEXT.namespaceId(), "subject-other", CONTEXT.resourceKey(),
                        CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity()),
                new BulkFingerprintContext(CONTEXT.namespaceId(), CONTEXT.subjectId(), "resource-other",
                        CONTEXT.operationRef(), CONTEXT.schemaRevision(), CONTEXT.atomicity()),
                new BulkFingerprintContext(CONTEXT.namespaceId(), CONTEXT.subjectId(), CONTEXT.resourceKey(),
                        new CanonicalOperationRef(CONTEXT.operationRef().group(), "operation-other",
                                CONTEXT.operationRef().path(), CONTEXT.operationRef().method()),
                        CONTEXT.schemaRevision(), CONTEXT.atomicity()),
                new BulkFingerprintContext(CONTEXT.namespaceId(), CONTEXT.subjectId(), CONTEXT.resourceKey(),
                        CONTEXT.operationRef(), CONTEXT.schemaRevision(), ActionCollectionAtomicity.PER_ITEM))) {
            assertThat(value.matchesCurrentEvidence(changed, checkedAt, value.targets(), governance(checkedAt))).isFalse();
        }
        assertThat(value.matchesCurrentEvidence(CONTEXT, value.evaluatedAt().minusNanos(1),
                value.targets(), governance(value.evaluatedAt()))).isFalse();
        assertThat(value.matchesCurrentEvidence(CONTEXT, value.proposal().expiresAt(),
                value.targets(), governance(value.proposal().expiresAt()))).isFalse();
        assertThatThrownBy(() -> value.matchesCurrentEvidence(CONTEXT, checkedAt, List.of(), governance(checkedAt)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparisonBindsEvidenceAndAllGovernanceValuesButExcludesCaptureTimestamps() {
        var value = evidence();
        var checkedAt = value.evaluatedAt().plusSeconds(1);
        var original = value.governance().policies().getFirst();
        var target = value.targets().getFirst();
        var sameEvidenceAtCheckedAt = new BulkEvaluationSnapshot(value.proposal(), checkedAt, value.targets(), governance(checkedAt));

        assertThat(value.matchesCurrentEvidence(CONTEXT, checkedAt, value.targets(), governance(checkedAt))).isTrue();
        assertThat(value.matchesCurrentEvidence(CONTEXT, checkedAt,
                List.of(new BulkTargetEvidence<>(target.target(), target.observedVersion(),
                        JSON.objectNode().put("dependentRevision", "changed"), target.plan(), BulkTargetEligibility.executable())), governance(checkedAt))).isFalse();
        assertThat(value.matchesCurrentEvidence(CONTEXT, checkedAt,
                List.of(new BulkTargetEvidence<>(target.target(), target.observedVersion(), target.facts(),
                        JSON.objectNode().put("amount", new BigDecimal("2.0")), BulkTargetEligibility.executable())), governance(checkedAt))).isFalse();

        for (var changed : List.of(
                governance("evaluator-r2", "authorization-r1", List.of(policyAt(checkedAt))),
                governance("evaluator-r1", "authorization-r2", List.of(policyAt(checkedAt))),
                governance("evaluator-r1", "authorization-r1", List.of(policy(
                        original.tenantId(), original.environment(), original.targetLayer(),
                        original.targetArtifactType(), original.targetArtifactKey(), "OPAQUE_CHANGED", original.resolutionFingerprint(), checkedAt))),
                governance("evaluator-r1", "authorization-r1", List.of(policy(
                        original.tenantId(), original.environment(), original.targetLayer(),
                        original.targetArtifactType(), original.targetArtifactKey(), original.resolutionState(), "policy-r2", checkedAt))),
                governance("evaluator-r1", "authorization-r1", List.of(policy(
                        original.tenantId(), original.environment(), original.targetLayer(),
                        original.targetArtifactType(), "artifact-other", original.resolutionState(), original.resolutionFingerprint(), checkedAt))),
                governance("evaluator-r1", "authorization-r1", List.of(policy(
                        original.tenantId(), original.environment(), "layer-other",
                        original.targetArtifactType(), original.targetArtifactKey(), original.resolutionState(), original.resolutionFingerprint(), checkedAt))),
                governance("evaluator-r1", "authorization-r1", List.of(policy(
                        original.tenantId(), original.environment(), original.targetLayer(),
                        "type-other", original.targetArtifactKey(), original.resolutionState(), original.resolutionFingerprint(), checkedAt))),
                governance("evaluator-r1", "authorization-r1", List.of(policy(
                        "tenant-other", original.environment(), original.targetLayer(),
                        original.targetArtifactType(), original.targetArtifactKey(), original.resolutionState(), original.resolutionFingerprint(), checkedAt))),
                governance("evaluator-r1", "authorization-r1", List.of(policy(
                        original.tenantId(), "environment-other", original.targetLayer(),
                        original.targetArtifactType(), original.targetArtifactKey(), original.resolutionState(), original.resolutionFingerprint(), checkedAt))))) {
            assertThat(value.matchesCurrentEvidence(CONTEXT, checkedAt, value.targets(), changed)).isFalse();
            assertThat(new BulkEvaluationSnapshot(value.proposal(), checkedAt, value.targets(), changed).fingerprint())
                    .isNotEqualTo(sameEvidenceAtCheckedAt.fingerprint());
        }
    }

    @Test
    void revalidationAcceptsLoadedIntegralEvidenceButKeepsIntegersDistinctFromDecimals() {
        var proposal = proposal(snapshot(BulkMode.DOMAIN_COMMAND, BulkIdentityCodecs.integers(), "42", "1"));
        var seed = BulkEvaluationSnapshotTest.evaluation(proposal);
        var target = seed.targets().getFirst();
        var original = new BulkEvaluationSnapshot(proposal, proposal.createdAt().plusSeconds(1),
                List.of(new BulkTargetEvidence<>(target.target(), target.observedVersion(),
                        JSON.objectNode().put("count", 1), JSON.objectNode().put("amount", 1), BulkTargetEligibility.executable())),
                governance(proposal.createdAt()));
        var loaded = BulkEvaluationStorageCodec.decode(proposal, BulkEvaluationStorageCodec.encode(original), original.fingerprint());
        var checkedAt = original.evaluatedAt().plusSeconds(1);
        var freshInteger = new BulkTargetEvidence<>(target.target(), target.observedVersion(),
                JSON.objectNode().put("count", 1), JSON.objectNode().put("amount", 1), BulkTargetEligibility.executable());
        var freshDecimal = new BulkTargetEvidence<>(target.target(), target.observedVersion(),
                JSON.objectNode().put("count", 1), JSON.objectNode().put("amount", new BigDecimal("1.0")), BulkTargetEligibility.executable());

        assertThat(loaded.targets().getFirst().facts().get("count").isBigInteger()).isTrue();
        assertThat(freshInteger.facts().get("count").isInt()).isTrue();
        assertThat(loaded.matchesCurrentEvidence(CONTEXT, checkedAt, List.of(freshInteger), governance(checkedAt))).isTrue();
        assertThat(loaded.matchesCurrentEvidence(CONTEXT, checkedAt, List.of(freshDecimal), governance(checkedAt))).isFalse();
    }

    @Test
    void policyObservationsRequireAWindowAndPreserveOpaqueStateWithoutGrantSemantics() {
        var value = evidence();
        var observedAt = value.evaluatedAt();
        var opaque = policy("tenant", "test", "approval_policy", "resource-action-approval", "resource:approve",
                "owner-defined / needs-human-review", "policy-r1", observedAt);

        assertThat(value.matchesCurrentEvidence(CONTEXT, observedAt, value.targets(),
                governance("evaluator-r1", "authorization-r1", List.of(opaque)))).isTrue();
        var denied = policy("tenant", "test", "approval_policy", "resource-action-approval", "resource:approve",
                "DENIED", "policy-r1", observedAt);
        var deniedEvidence = new BulkEvaluationSnapshot(value.proposal(), value.evaluatedAt(), value.targets(),
                governance("evaluator-r1", "authorization-r1", List.of(denied)));
        // A true result only means that unchanged opaque evidence was compared; it is never permission or READY.
        assertThat(deniedEvidence.matchesCurrentEvidence(CONTEXT, observedAt, value.targets(),
                governance("evaluator-r1", "authorization-r1", List.of(denied)))).isTrue();

        assertThatThrownBy(() -> new BulkEvaluationSnapshot(value.proposal(), value.evaluatedAt(), value.targets(),
                governance(value.proposal().createdAt().minusNanos(1_000)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkEvaluationSnapshot(value.proposal(), value.evaluatedAt(), value.targets(),
                governance(value.evaluatedAt().plusNanos(1_000)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkPolicyObservation("tenant", "test", "layer", "type", "key", "state", "fingerprint", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void governanceIsCanonicalImmutablePrivateAndCollisionSafe() {
        var first = policy("north", "prod|test", "layer", "type", "key", "OPAQUE", "f1", Instant.parse("2026-09-13T12:00:00Z"));
        var second = policy("north|prod", "test", "layer", "type", "key", "OPAQUE", "f2", Instant.parse("2026-09-13T12:00:00Z"));
        var supplied = new ArrayList<>(List.of(second, first));
        var governance = governance("evaluator-secret", "authorization-secret", supplied);
        supplied.clear();

        assertThat(governance.policies()).extracting(BulkPolicyObservation::tenantId).containsExactly("north", "north|prod");
        assertThatThrownBy(() -> governance.policies().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(governance.toString()).isEqualTo("BulkEvaluationGovernance[protected]");
        assertThat(first.toString()).isEqualTo("BulkPolicyObservation[protected]");
        assertThat(governance.toString()).doesNotContain("evaluator-secret", "authorization-secret");

        assertThatThrownBy(() -> governance("e", "a", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> governance("e", "a", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> governance("e", "a", Arrays.asList(first, null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> governance("e", "a", List.of(first,
                policy("north", "prod|test", "layer", "type", "key", "OTHER", "f3", first.observedAt()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storageCodecRejectsLegacyAndGovernanceTamperedDocumentsWithoutLeakingDetails() {
        var value = evidence();
        var legacy = (ObjectNode) value.storageDocument();
        legacy.remove("governance");
        var missingAuthorization = (ObjectNode) value.storageDocument();
        ((ObjectNode) missingAuthorization.get("governance")).remove("authorizationFingerprint");
        var changedEvaluator = (ObjectNode) value.storageDocument();
        ((ObjectNode) changedEvaluator.get("governance")).put("evaluatorRevision", "tampered-secret");
        var changedPolicy = (ObjectNode) value.storageDocument();
        ((ObjectNode) changedPolicy.at("/governance/policies/0")).put("resolutionFingerprint", "tampered-secret");

        for (var corrupted : List.of(legacy, missingAuthorization, changedEvaluator, changedPolicy)) {
            assertThatThrownBy(() -> BulkEvaluationStorageCodec.decode(value.proposal(), bytes(corrupted.toString()), value.fingerprint()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid protected evaluation content")
                    .hasNoCause();
        }
    }

    private static BulkEvaluationSnapshot evidence() {
        var proposal = proposal();
        var source = BulkEvaluationSnapshotTest.evaluation(proposal);
        return new BulkEvaluationSnapshot(proposal, proposal.createdAt().plusSeconds(1), source.targets(),
                governance(proposal.createdAt()));
    }

    private static BulkEvaluationGovernance governance(Instant observedAt) {
        return governance("evaluator-r1", "authorization-r1", List.of(policyAt(observedAt)));
    }

    private static BulkEvaluationGovernance governance(String evaluator, String authorization,
            List<BulkPolicyObservation> policies) {
        return new BulkEvaluationGovernance(evaluator, authorization, policies);
    }

    private static BulkPolicyObservation policyAt(Instant observedAt) {
        return policy("tenant", "test", "approval_policy", "resource-action-approval", "resource:approve",
                "owner-defined / needs-human-review", "policy-r1", observedAt);
    }

    private static BulkPolicyObservation policy(String tenant, String environment, String layer, String type,
            String key, String state, String fingerprint, Instant observedAt) {
        return new BulkPolicyObservation(tenant, environment, layer, type, key, state, fingerprint, observedAt);
    }

    private static BulkFingerprintContext context(String namespace, String subject, String schema) {
        return new BulkFingerprintContext(namespace, subject, CONTEXT.resourceKey(),
                new CanonicalOperationRef(CONTEXT.operationRef().group(), CONTEXT.operationRef().operationId(),
                        CONTEXT.operationRef().path(), CONTEXT.operationRef().method()),
                schema, ActionCollectionAtomicity.ATOMIC);
    }
}
