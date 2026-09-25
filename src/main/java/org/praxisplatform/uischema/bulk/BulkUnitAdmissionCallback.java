package org.praxisplatform.uischema.bulk;

@FunctionalInterface
public interface BulkUnitAdmissionCallback {
    BulkUnitAdmission admit(BulkExecutionUnit unit);
}
