package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

/** QUERY remains an input intent; the evaluator owns the frozen target sequence. */
class BulkUniformQueryContractTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test void onlyUniformProfileMayPublishBothSelections() {
        var profile = new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE),
                Set.of(BulkExecutionMode.SYNC), Set.of(BulkSelectionMode.EXPLICIT, BulkSelectionMode.QUERY),
                200, 65_536, Duration.ofMinutes(2), Duration.ofSeconds(5));
        assertThat(profile.selectionModes()).containsExactlyInAnyOrder(BulkSelectionMode.EXPLICIT, BulkSelectionMode.QUERY);
        for (var mode : List.of(BulkMode.PER_ITEM_UPDATE, BulkMode.DOMAIN_COMMAND))
            assertThatThrownBy(() -> new BulkOperationalProfile(Set.of(mode), Set.of(BulkExecutionMode.SYNC),
                    Set.of(BulkSelectionMode.EXPLICIT, BulkSelectionMode.QUERY), 200, 65_536,
                    Duration.ofMinutes(2), Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE),
                Set.of(BulkExecutionMode.SYNC), Set.of(BulkSelectionMode.QUERY), 200, 65_536,
                Duration.ofMinutes(2), Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void composedDescriptorPublishesQueryOnlyForUniformPerItem() {
        try (var context = BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.AtomicCrudController.class)) {
            var document = BulkCrudOperationalCompositionTest.atomicDocument();
            for (var suffix : List.of("uniform", "uniform-atomic")) {
                var selection = (com.fasterxml.jackson.databind.node.ObjectNode) document.at(
                        "/paths/~1crud-items~1bulk~1" + suffix + "~1evaluation/post/requestBody/content/application~1json/schema/properties/selection/properties");
                selection.putObject("filter").put("type", "object");
                selection.putObject("excludedIds").put("type", "array").set("items", BulkIdentityCodecs.integers().canonicalWireSchema());
            }
            var structures = BulkCrudStructuralCompilerTest.compile(context, BulkCrudStructuralCompilerTest.mapper(),
                    new BulkCrudStructuralCompilerTest.Documents(document));
            for (var structure : structures) {
                var base = BulkCrudOperationalCompositionTest.provider(structure);
                var provider = new BulkCrudOperationalCompositionTest.Provider(
                        structure.operation(BulkOperationStructuralDescriptor.Role.CONFIRMATION).reference().operationId(),
                        "query-r1", BulkCrudOperationalCompositionTest.pointer(structure.mode()),
                        structure.mode(), base.runtime) {
                    @Override public BulkOperationalProfile profile() {
                        return new BulkOperationalProfile(Set.of(mode), Set.of(BulkExecutionMode.SYNC),
                                Set.of(BulkSelectionMode.EXPLICIT, BulkSelectionMode.QUERY),
                                20, 65_536, Duration.ofMinutes(2), Duration.ofSeconds(2));
                    }
                };
                if (structure.mode() == BulkMode.UNIFORM_UPDATE
                        && structure.atomicity() == ActionCollectionAtomicity.PER_ITEM) {
                    var descriptor = BulkOperationalDescriptorComposer.compose(structure, provider);
                    assertThat(BulkExecutionContract.from(descriptor).selectionModes())
                            .containsExactly(BulkSelectionMode.EXPLICIT, BulkSelectionMode.QUERY);
                    assertThat(descriptor.descriptorFingerprint()).isNotEqualTo(
                            BulkOperationalDescriptorComposer.compose(structure, base).descriptorFingerprint());
                } else {
                    assertThatThrownBy(() -> BulkOperationalDescriptorComposer.compose(structure, provider))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            }
        }
    }

    @Test void queryBindsFilterExclusionsAndKeepsServerNumericOrder() {
        var context = context(ActionCollectionAtomicity.PER_ITEM);
        var input = snapshot(context, List.of(9));
        var changed = snapshot(context, List.of(8));
        assertThat(input.fingerprint()).isNotEqualTo(changed.fingerprint());
        var proposal = proposal(input);
        var evidence = List.of(target(1), target(2), target(10));
        var evaluation = new BulkEvaluationSnapshot(proposal, proposal.createdAt().plusSeconds(1),
                evidence, governance(proposal));
        assertThat(evaluation.targets().stream().map(value -> (Integer) value.target().id()).toList()).containsExactly(1, 2, 10);
        var restored = BulkEvaluationStorageCodec.decode(proposal, BulkEvaluationStorageCodec.encode(evaluation),
                evaluation.fingerprint());
        assertThat(restored.fingerprint()).isEqualTo(evaluation.fingerprint());
        assertThat(restored.targets().stream().map(value -> (Integer) value.target().id()).toList()).containsExactly(1, 2, 10);
        assertThatThrownBy(() -> new BulkEvaluationSnapshot(proposal, proposal.createdAt().plusSeconds(1),
                List.of(target(1), target(9)), governance(proposal)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BulkEvaluationSnapshot(proposal, proposal.createdAt().plusSeconds(1),
                List.of(target(1), target(1)), governance(proposal)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void queryCannotBecomeAtomicOrACommandProposal() {
        assertThatThrownBy(() -> proposal(snapshot(context(ActionCollectionAtomicity.ATOMIC), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        var safe = BulkProposalStorageException.invalidSelection();
        assertThat(safe.reason()).isEqualTo(BulkProposalStorageException.Reason.INVALID_SELECTION);
        assertThat(safe).hasNoCause().hasMessageNotContaining("target");
    }

    private static BulkFingerprintContext context(ActionCollectionAtomicity atomicity) {
        return new BulkFingerprintContext("namespace-a", "subject-a", "employees",
                new CanonicalOperationRef("admin", "employee-bulk-edit", "/employees/bulk", "POST"),
                "schema-a", atomicity);
    }

    private static BulkIntentSnapshot snapshot(BulkFingerprintContext context, List<Integer> excluded) {
        var selection = new BulkSelection<Integer, JsonNode>(BulkSelectionMode.QUERY, null,
                JSON.objectNode().put("active", true), excluded);
        var request = new BulkUniformEvaluationRequest<Integer, JsonNode>(BulkExecutionMode.SYNC, selection,
                List.of(new BulkFieldChange("amount", BulkChangeOperator.SET, JSON.numberNode(1))));
        return BulkIntentSnapshot.uniform(context, BulkIdentityCodecs.integers(), request, JsonNode::deepCopy);
    }

    private static BulkStoredProposal proposal(BulkIntentSnapshot snapshot) {
        return new BulkStoredProposal(UUID.randomUUID(), Instant.parse("2026-10-04T10:00:00Z"),
                Instant.parse("2026-10-04T10:02:00Z"), snapshot);
    }

    private static BulkTargetEvidence<Integer> target(int id) {
        return new BulkTargetEvidence<>(new BulkTarget<>(id, "v1"), "v1", JSON.objectNode(),
                JSON.objectNode(), BulkTargetEligibility.executable());
    }

    private static BulkEvaluationGovernance governance(BulkStoredProposal proposal) {
        return new BulkEvaluationGovernance("evaluator-r1", "grant-r1", List.of(
                new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                        "resource:approve", "NEVER_APPLIED", "policy-r1", proposal.createdAt())));
    }
}
