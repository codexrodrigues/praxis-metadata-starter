package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkResponseContractTest {
    private static final Instant CREATED = Instant.parse("2026-09-13T16:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-09-13T16:01:00Z");
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    @Test
    void proposalRoundTripsWithJsr310AndOnlyPublicRedactedIntent() throws Exception {
        BulkProposal proposal = readyProposal(mapper.readTree("{\"reason\":\"redacted\",\"amount\":1.25}"));

        BulkProposal read = mapper.readValue(mapper.writeValueAsBytes(proposal), BulkProposal.class);

        assertEquals(proposal, read);
        assertEquals(CREATED, read.createdAt());
        assertFalse(mapper.writeValueAsString(proposal).contains("expectedVersion"));
    }

    @Test
    void proposalDeepCopiesRedactedIntentAtBothBoundariesAndDoesNotLogIt() throws Exception {
        JsonNode supplied = mapper.readTree("{\"amount\":1.25,\"nested\":{\"keep\":true}}");
        BulkProposal proposal = readyProposal(supplied);
        ((ObjectNode) supplied.get("nested")).put("keep", false);

        JsonNode returned = proposal.redactedIntent();
        ((ObjectNode) returned.get("nested")).put("keep", false);

        assertTrue(proposal.redactedIntent().path("nested").path("keep").asBoolean());
        assertNotSame(returned, proposal.redactedIntent());
        assertFalse(proposal.toString().contains("amount"));
        assertFalse(proposal.toString().contains("redacted"));
    }

    @Test
    void rejectsIncoherentProposalTotalsAndBlockedProposalWithoutDiagnostic() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new BulkProposalTotals(3, 2, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new BulkProposalTotals(Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> new BulkProposal(
                "proposal", operation(), BulkMode.UNIFORM_UPDATE, BulkExecutionMode.SYNC,
                ActionCollectionAtomicity.PER_ITEM, BulkProposalStatus.READY, CREATED, UPDATED,
                new BulkProposalTotals(2, 1, 1, 0), mapper.createObjectNode(), List.of(), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new BulkProposal(
                "proposal", operation(), BulkMode.UNIFORM_UPDATE, BulkExecutionMode.SYNC,
                ActionCollectionAtomicity.PER_ITEM, BulkProposalStatus.BLOCKED, CREATED, UPDATED,
                new BulkProposalTotals(0, 0, 0, 0), mapper.createObjectNode(), List.of(), List.of()
        ));
    }

    @Test
    void executionRoundTripsAndEnforcesTerminalAndStateCounters() throws Exception {
        BulkExecution execution = completedExecution(new BulkExecutionTotals(2, 0, 1, 1, 0, 0, 0, 0, 0));
        assertEquals(execution, mapper.readValue(mapper.writeValueAsBytes(execution), BulkExecution.class));

        assertThrows(IllegalArgumentException.class, () -> execution(
                BulkExecutionStatus.COMPLETED, null,
                new BulkExecutionTotals(1, 0, 1, 0, 0, 0, 0, 0, 0), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> execution(
                BulkExecutionStatus.QUEUED, null,
                new BulkExecutionTotals(1, 0, 1, 0, 0, 0, 0, 0, 0), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> execution(
                BulkExecutionStatus.COMPLETED_WITH_ERRORS, UPDATED,
                new BulkExecutionTotals(1, 0, 1, 0, 0, 0, 0, 0, 0), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> execution(
                BulkExecutionStatus.STOPPED, UPDATED,
                new BulkExecutionTotals(1, 0, 0, 0, 0, 0, 0, 1, 0), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> execution(
                BulkExecutionStatus.RECONCILIATION_REQUIRED, UPDATED,
                new BulkExecutionTotals(1, 0, 0, 0, 0, 0, 0, 1, 0), List.of(diagnostic())
        ));
    }

    @Test
    void atomicResultsCannotConfirmAnyTargetAfterFailureOrIncompleteWork() {
        assertThrows(IllegalArgumentException.class, () -> new BulkExecution(
                "execution", "proposal", operation(), BulkMode.DOMAIN_COMMAND, BulkExecutionMode.SYNC,
                ActionCollectionAtomicity.ATOMIC, BulkExecutionStatus.COMPLETED_WITH_ERRORS,
                CREATED, UPDATED, UPDATED, new BulkExecutionTotals(2, 0, 1, 0, 1, 0, 0, 0, 0), List.of(diagnostic())
        ));
        assertEquals(BulkItemStatus.UNCHANGED, new BulkItemResult<>("id", BulkItemStatus.UNCHANGED, List.of()).status());
    }

    @Test
    void itemResultsPreserveSupportedWireIdentityAndRequireSafeFailureDiagnostics() throws Exception {
        BulkItemResult<String> result = new BulkItemResult<>("9007199254740993", BulkItemStatus.CONFLICT, List.of(diagnostic()));
        assertEquals(result, mapper.readValue(mapper.writeValueAsBytes(result), BulkItemResult.class));
        assertEquals(42, new BulkItemResult<>(42, BulkItemStatus.CONFIRMED, List.of()).id());
        assertThrows(IllegalArgumentException.class, () -> new BulkItemResult<>(42L, BulkItemStatus.CONFIRMED, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BulkItemResult<>("", BulkItemStatus.CONFLICT, List.of(diagnostic())));
        assertThrows(IllegalArgumentException.class, () -> new BulkItemResult<>("id", BulkItemStatus.CONFLICT, List.of()));
        assertFalse(result.toString().contains("9007199254740993"));
    }

    @Test
    void diagnosticsAndEvidenceRejectArbitraryMetadataAndPreserveImmutableLists() throws Exception {
        ResourceCommandMessage unsafe = new ResourceCommandMessage(ResourceCommandErrorCategory.VALIDATION,
                "INVALID", "Invalid value", null, Map.of("secret", "value"));
        assertThrows(IllegalArgumentException.class, () -> new BulkItemResult<>("id", BulkItemStatus.INVALID, List.of(unsafe)));
        assertThrows(IllegalArgumentException.class, () -> new BulkEvidenceReference(BulkEvidenceKind.FACT, " ", "r1", "f1"));
        BulkProposal proposal = readyProposal(mapper.createObjectNode());
        assertThrows(UnsupportedOperationException.class, () -> proposal.diagnostics().add(diagnostic()));
        assertThrows(UnsupportedOperationException.class, () -> proposal.evidence().add(
                new BulkEvidenceReference(BulkEvidenceKind.FACT, "fact", "r1", "f1")
        ));
    }

    @Test
    void rejectsInvalidOperationReferencesAtomicityAndResponseCounterOverflow() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new BulkProposal(
                "proposal", new CanonicalOperationRef(null, "", "/work", "POST"), BulkMode.DOMAIN_COMMAND,
                BulkExecutionMode.SYNC, ActionCollectionAtomicity.PER_ITEM, BulkProposalStatus.READY,
                CREATED, UPDATED, new BulkProposalTotals(1, 1, 1, 0), mapper.createObjectNode(), List.of(), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new BulkProposal(
                "proposal", operation(), BulkMode.DOMAIN_COMMAND, BulkExecutionMode.SYNC,
                ActionCollectionAtomicity.NOT_APPLICABLE, BulkProposalStatus.READY,
                CREATED, UPDATED, new BulkProposalTotals(1, 1, 1, 0), mapper.createObjectNode(), List.of(), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new BulkExecutionTotals(10_001, 10_001, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BulkExecutionTotals(1, Long.MAX_VALUE,
                1, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void responseLimitsAndIdentitiesAgreeWithInputContract() {
        assertEquals(" ", new BulkItemResult<>(" ", BulkItemStatus.CONFIRMED, List.of()).id());
        assertThrows(IllegalArgumentException.class, () -> new BulkProposalTotals(10001, 10001, 10001, 0));
        assertThrows(IllegalArgumentException.class, () -> new BulkProposalTotals(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0));
        for (var status : List.of(BulkExecutionStatus.CANCELLED, BulkExecutionStatus.STOPPED)) {
            assertThrows(IllegalArgumentException.class, () -> execution(status, UPDATED,
                    new BulkExecutionTotals(1, 0, 1, 0, 0, 0, 0, 0, 0), List.of(diagnostic())));
            assertEquals(status, execution(status, UPDATED,
                    new BulkExecutionTotals(1, 0, 0, 0, 0, 0, 0, 1, 0), List.of(diagnostic())).status());
        }
    }

    private BulkProposal readyProposal(JsonNode intent) {
        return new BulkProposal("proposal", operation(), BulkMode.UNIFORM_UPDATE, BulkExecutionMode.SYNC,
                ActionCollectionAtomicity.PER_ITEM, BulkProposalStatus.READY, CREATED, UPDATED,
                new BulkProposalTotals(2, 2, 2, 0), intent, List.of(diagnostic()), List.of(
                new BulkEvidenceReference(BulkEvidenceKind.POLICY, "policy", "r1", "fingerprint")
        ));
    }

    private BulkExecution completedExecution(BulkExecutionTotals totals) {
        return execution(BulkExecutionStatus.COMPLETED, UPDATED, totals, List.of());
    }

    private BulkExecution execution(BulkExecutionStatus status, Instant terminalAt, BulkExecutionTotals totals,
                                    List<ResourceCommandMessage> diagnostics) {
        return new BulkExecution("execution", "proposal", operation(), BulkMode.UNIFORM_UPDATE,
                BulkExecutionMode.SYNC, ActionCollectionAtomicity.PER_ITEM, status, CREATED, UPDATED, terminalAt,
                totals, diagnostics);
    }

    private CanonicalOperationRef operation() {
        return new CanonicalOperationRef("group", "bulk-evaluate", "/work/bulk/evaluate", "POST");
    }

    private ResourceCommandMessage diagnostic() {
        return new ResourceCommandMessage(ResourceCommandErrorCategory.VALIDATION,
                "BULK_BLOCKED", "Bulk evaluation is blocked.", null, Map.of());
    }
}
