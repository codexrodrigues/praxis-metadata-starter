package org.praxisplatform.uischema.bulk;

/** Trusted domain mutation that participates in the kernel-owned operational transaction. */
@FunctionalInterface
public interface BulkUnitMutationCallback {
    BulkUnitMutationResult apply(BulkExecutionUnit unit);
}
