package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.praxisplatform.uischema.bulk.BulkSnapshotStorageCodecTest.*;

class BulkEvaluationSnapshotTest {
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    // Explicit test-only evidence: not a production grants/provider implementation.
    static BulkEvaluationGovernance governance() {
        return new BulkEvaluationGovernance("test-evaluator-r1", "test-grants-r1", List.of(
                new BulkPolicyObservation("tenant", "test", "approval_policy", "resource-action-approval",
                        "resource:approve", "NEVER_APPLIED", "test-policy-r1", proposal().createdAt())));
    }
    static BulkEvaluationSnapshot evaluation(BulkStoredProposal proposal) {
        var intent=proposal.snapshot().intent();
        var selected=proposal.snapshot().mode()==BulkMode.PER_ITEM_UPDATE ? intent.get("items") : intent.at("/selection/targets");
        var codec=BulkSnapshotStorageCodec.codec(proposal.snapshot().codecId());
        var targets=new ArrayList<BulkTargetEvidence<?>>();
        for(var target:selected) targets.add(new BulkTargetEvidence<>(new BulkTarget<>(codec.readWire(target.get("id")),target.get("expectedVersion").asText()),
                "observed-v2", JSON.objectNode().put("dependentRevision","r7"), JSON.objectNode().put("amount",new BigDecimal("1.0"))));
        return new BulkEvaluationSnapshot(proposal,proposal.createdAt().plusSeconds(1),targets, governance());
    }
    @Test void evaluationUsesIndependentVersionedFraming() {
        var value=JSON.objectNode().put("value",1);
        // Independent Python struct/hashlib reference: version string + O/count/key/I/value.
        assertThat(BulkCanonicalJson.evaluationDigest(value)).isEqualTo("sha256:e67bd9aa76306b82ce842e740a3ab06ae04d9896835ef29fdcb07c98c87185b7");
        assertThat(BulkCanonicalJson.evaluationDigest(value)).isNotEqualTo(BulkCanonicalJson.digest(value));
    }
    @Test void allModesAndCodecsRoundtripAndNoPublicDecisionIsFabricated() {
        for(var mode:BulkMode.values()) {
            roundtrip(evaluation(proposal(snapshot(mode,BulkIdentityCodecs.integers(),"42","1.0"))));
            roundtrip(evaluation(proposal(snapshot(mode,BulkIdentityCodecs.longs(),"\"9223372036854775807\"","1.0"))));
            roundtrip(evaluation(proposal(snapshot(mode,BulkIdentityCodecs.strings(),"\"ação😀\"","1.0"))));
            roundtrip(evaluation(proposal(snapshot(mode,BulkIdentityCodecs.uuids(),"\"123e4567-e89b-12d3-a456-426614174000\"","1.0"))));
        }
    }
    @Test void rejectsMissingExtraDuplicateForeignTypedTargetsAndChangedExpectedVersion() {
        var value=evaluation(proposal()); var target=value.targets().getFirst();
        for(var targets:List.of(List.<BulkTargetEvidence<?>>of(),List.of(target,target),
                List.of(new BulkTargetEvidence<>(new BulkTarget<>(101,"v1"),"v",JSON.objectNode(),JSON.objectNode())),
                List.of(new BulkTargetEvidence<>(new BulkTarget<>("other","v1"),"v",JSON.objectNode(),JSON.objectNode())),
                List.of(new BulkTargetEvidence<>(new BulkTarget<>("101","changed"),"v",JSON.objectNode(),JSON.objectNode()))))
            assertThatThrownBy(()->new BulkEvaluationSnapshot(value.proposal(),value.evaluatedAt(),targets, governance())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bindsUuidInputValidityInstantFactsObservedVersionAndPlan() {
        var original=evaluation(proposal()); var target=original.targets().getFirst();
        var variants=new ArrayList<BulkEvaluationSnapshot>();
        variants.add(new BulkEvaluationSnapshot(new BulkStoredProposal(UUID.randomUUID(),original.proposal().createdAt(),original.proposal().expiresAt(),original.proposal().snapshot()),original.evaluatedAt(),original.targets(), governance()));
        variants.add(new BulkEvaluationSnapshot(new BulkStoredProposal(original.proposal().id(),original.proposal().createdAt(),original.proposal().expiresAt().plusSeconds(1),original.proposal().snapshot()),original.evaluatedAt(),original.targets(), governance()));
        variants.add(new BulkEvaluationSnapshot(original.proposal(),original.evaluatedAt().plusSeconds(1),original.targets(), governance()));
        variants.add(new BulkEvaluationSnapshot(original.proposal(),original.evaluatedAt(),List.of(new BulkTargetEvidence<>(target.target(),"changed",target.facts(),target.plan())), governance()));
        variants.add(new BulkEvaluationSnapshot(original.proposal(),original.evaluatedAt(),List.of(new BulkTargetEvidence<>(target.target(),target.observedVersion(),JSON.objectNode().put("dependentRevision","r8"),target.plan())), governance()));
        variants.add(new BulkEvaluationSnapshot(original.proposal(),original.evaluatedAt(),List.of(new BulkTargetEvidence<>(target.target(),target.observedVersion(),target.facts(),JSON.objectNode().put("amount",2))), governance()));
        for(var value:variants)assertThat(value.fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(original.targets().getFirst().observedVersion()).isEqualTo("observed-v2"); // records a conflict, not READY
    }
    @Test void evidenceOrderFollowsOriginalPerItemOrderAndNotCallerOrder() {
        var reader=new BulkProtocolReader<>(BulkIdentityCodecs.strings());
        var input=BulkIntentSnapshot.items(CONTEXT,BulkIdentityCodecs.strings(),reader.readItems(bytes("{\"executionMode\":\"SYNC\",\"items\":[{\"id\":\"2\",\"expectedVersion\":\"v2\",\"changes\":[{\"field\":\"x\",\"operator\":\"CLEAR\"}]},{\"id\":\"1\",\"expectedVersion\":\"v1\",\"changes\":[{\"field\":\"x\",\"operator\":\"CLEAR\"}]}]}")));
        var original=evaluation(proposal(input));var reversed=new ArrayList<>(original.targets());Collections.reverse(reversed);
        var reordered=new BulkEvaluationSnapshot(original.proposal(),original.evaluatedAt(),reversed, governance());
        assertThat(reordered.targets().getFirst().target().id()).isEqualTo("2");assertThat(reordered.fingerprint()).isEqualTo(original.fingerprint());
        assertThatThrownBy(()->new BulkEvaluationSnapshot(original.proposal(),original.evaluatedAt(),List.of(original.targets().getFirst()), governance())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void evaluationInstantIsNormalizedAndMustBeInsideBoundValidity() {
        var value=evaluation(proposal());
        for(var invalid:List.of(value.proposal().createdAt().minusNanos(1),value.proposal().expiresAt()))
            assertThatThrownBy(()->new BulkEvaluationSnapshot(value.proposal(),invalid,value.targets(), governance())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new BulkEvaluationSnapshot(value.proposal(),value.evaluatedAt().plusNanos(1234),value.targets(), governance()).evaluatedAt().getNano()).isEqualTo(1000);
    }
    @Test void factsAndPlansStayDefensiveAndNumericTypesRemainExact() {
        var original=evaluation(proposal()); var first=original.targets().getFirst();
        for(var number:List.of(new BigDecimal("1"),new BigDecimal("1e256"),new BigDecimal("10e256"),
                new BigDecimal("1"+"0".repeat(255)).scaleByPowerOfTen(256),new BigDecimal("9".repeat(256)).scaleByPowerOfTen(256))) {
            var plan=JSON.objectNode().put("amount",number);var evidence=new BulkTargetEvidence<>(first.target(),first.observedVersion(),first.facts(),plan);
            plan.put("amount",99);
            var value=new BulkEvaluationSnapshot(original.proposal(),original.evaluatedAt(),List.of(evidence), governance());
            var restored=roundtrip(value);
            assertThat(restored.targets().getFirst().plan().get("amount").decimalValue()).isEqualByComparingTo(number);
            ((ObjectNode)evidence.plan()).put("amount",88);assertThat(evidence.plan().get("amount").decimalValue()).isEqualByComparingTo(number);
        }
        var integer=new BulkEvaluationSnapshot(original.proposal(),original.evaluatedAt(),List.of(new BulkTargetEvidence<>(first.target(),first.observedVersion(),first.facts(),JSON.objectNode().put("amount",1))), governance());
        assertThat(integer.fingerprint()).isNotEqualTo(original.fingerprint());
        assertThatThrownBy(()->original.targets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(original.toString()).isEqualTo("BulkEvaluationSnapshot[protected]");
        assertThat(first.toString()).isEqualTo("BulkTargetEvidence[protected]");
    }
    @Test void corruptedAndCrossProposalDocumentsAreRejectedWithoutPayloadCause() {
        var original=evaluation(proposal());String document=new String(BulkEvaluationStorageCodec.encode(original),java.nio.charset.StandardCharsets.UTF_8);
        for(String corrupted:List.of(document+" {}",document.replace("r7","SECRET-corrupt"),document.replace("\"targets\":","\"targets\":[],\"targets\":"),"{}","null"))
            assertThatThrownBy(()->BulkEvaluationStorageCodec.decode(original.proposal(),bytes(corrupted),original.fingerprint()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid protected evaluation content").hasNoCause();
        assertThatThrownBy(()->BulkEvaluationStorageCodec.decode(proposal(),bytes(document),original.fingerprint())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnsafeOrOversizedEvidence() {
        var target=evaluation(proposal()).targets().getFirst();
        assertThatThrownBy(()->new BulkTargetEvidence<>(target.target(),"v",JSON.objectNode().put("amount",1.0d),JSON.objectNode())).isInstanceOf(IllegalArgumentException.class);
        var huge=JSON.objectNode().put("value","x".repeat(5*1024*1024));
        var evidence=new BulkTargetEvidence<>(target.target(),"v",huge,huge);
        var proposal=proposal();
        assertThatThrownBy(()->new BulkEvaluationSnapshot(proposal,proposal.createdAt(),List.of(evidence), governance())).isInstanceOf(IllegalArgumentException.class);
    }
    private static BulkEvaluationSnapshot roundtrip(BulkEvaluationSnapshot value) {
        var restored=BulkEvaluationStorageCodec.decode(value.proposal(),BulkEvaluationStorageCodec.encode(value),value.fingerprint());
        assertThat(restored.fingerprint()).isEqualTo(value.fingerprint());assertThat(restored.evaluatedAt()).isEqualTo(value.evaluatedAt());
        assertThat(restored.proposal()).isSameAs(value.proposal());return restored;
    }
}
