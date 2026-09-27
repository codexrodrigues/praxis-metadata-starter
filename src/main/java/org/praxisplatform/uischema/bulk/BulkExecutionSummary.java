package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.time.Instant;
import java.util.UUID;

/** Internal projection of one certified execution snapshot; never an HTTP response. */
@JsonIgnoreType
record BulkExecutionSummary(Kind kind, UUID executionId, UUID proposalId,
        BulkExecutionStatus status, Instant createdAt, Instant updatedAt, Instant terminalAt,
        BulkExecutionTotals totals, BulkExecutionStatus tombstoneStatus) {
    enum Kind { ABSENT, LIVE, TOMBSTONE }

    static BulkExecutionSummary from(BulkConsistentExecutionRead read) {
        if (read == null) throw corrupt();
        if (read.kind() == BulkConsistentExecutionRead.Kind.ABSENT) {
            if (read.execution() != null || read.tombstoneTerminalStatus() != null) throw corrupt();
            return new BulkExecutionSummary(Kind.ABSENT, null, null, null, null, null, null, null, null);
        }
        if (read.kind() == BulkConsistentExecutionRead.Kind.TOMBSTONE) {
            if (read.execution() != null) throw corrupt();
            BulkExecutionStatus terminal;
            try {
                terminal = BulkExecutionStatus.valueOf(read.tombstoneTerminalStatus());
            } catch (RuntimeException invalid) {
                throw corrupt();
            }
            if (!terminal.terminal()) throw corrupt();
            return new BulkExecutionSummary(Kind.TOMBSTONE, null, null, null, null, null, null,
                    null, terminal);
        }
        if (read.kind() != BulkConsistentExecutionRead.Kind.LIVE || read.execution() == null
                || read.tombstoneTerminalStatus() != null) throw corrupt();

        BulkExecutionSnapshot execution = read.execution();
        int remainder = execution.targetCount() - execution.nextOrdinal();
        if (remainder < 0 || execution.receiptCount() + execution.admissionCount() != execution.nextOrdinal()
                || execution.receiptCount() != read.confirmed() + read.unchanged()
                || execution.admissionCount() != read.denied() + read.invalid() + read.conflict()
                || read.unknown() != (execution.status() == BulkDurableExecutionStatus.RECONCILIATION_REQUIRED
                        ? remainder : 0)) throw corrupt();

        BulkExecutionStatus status = switch (execution.status()) {
            case RUNNING, UNIT_IN_FLIGHT, UNIT_COMMITTED_PENDING_ACK -> BulkExecutionStatus.RUNNING;
            case COMPLETED -> BulkExecutionStatus.COMPLETED;
            case COMPLETED_WITH_ERRORS -> BulkExecutionStatus.COMPLETED_WITH_ERRORS;
            case STOPPED -> execution.terminalReasonCode() == BulkUnitReasonCode.CANCELLED_BY_USER
                    ? BulkExecutionStatus.CANCELLED : BulkExecutionStatus.STOPPED;
            case RECONCILIATION_REQUIRED -> BulkExecutionStatus.RECONCILIATION_REQUIRED;
        };
        long pending = status == BulkExecutionStatus.RUNNING ? remainder : 0;
        long notProcessed = status == BulkExecutionStatus.STOPPED || status == BulkExecutionStatus.CANCELLED
                ? remainder : 0;
        if (status.terminal() != (read.terminalAt() != null)
                || read.createdAt() == null || read.updatedAt() == null
                || read.updatedAt().isBefore(read.createdAt())
                || (read.terminalAt() != null && read.terminalAt().isBefore(read.createdAt()))
                || (status == BulkExecutionStatus.COMPLETED || status == BulkExecutionStatus.COMPLETED_WITH_ERRORS)
                        && remainder != 0
                || (status == BulkExecutionStatus.CANCELLED || status == BulkExecutionStatus.STOPPED)
                        && remainder == 0) throw corrupt();
        try {
            BulkExecutionTotals totals = new BulkExecutionTotals(execution.targetCount(), pending,
                    read.confirmed(), read.unchanged(), read.denied(), read.invalid(), read.conflict(),
                    notProcessed, read.unknown());
            if (status == BulkExecutionStatus.COMPLETED && !totals.onlySuccessfulOutcomes()
                    || status == BulkExecutionStatus.COMPLETED_WITH_ERRORS && totals.failures() == 0)
                throw corrupt();
            return new BulkExecutionSummary(Kind.LIVE, execution.executionId(), execution.proposalId(),
                    status, read.createdAt(), read.updatedAt(), read.terminalAt(), totals, null);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw corrupt();
        }
    }

    private static BulkDurableExecutionException corrupt() {
        return new BulkDurableExecutionException(BulkDurableExecutionException.Reason.CORRUPT);
    }

    @Override public String toString() { return "BulkExecutionSummary[protected]"; }
}
