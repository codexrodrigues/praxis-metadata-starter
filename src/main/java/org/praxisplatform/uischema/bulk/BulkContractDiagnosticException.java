package org.praxisplatform.uischema.bulk;

/**
 * Typed lifecycle failure indicating malformed bulk declarations, missing descriptor providers, or
 * invalid control bindings, optionally carrying the offending {@code operationId}.
 */
public class BulkContractDiagnosticException extends IllegalStateException {

    private final String operationId;

    public BulkContractDiagnosticException(String message) {
        super(message);
        this.operationId = null;
    }

    public BulkContractDiagnosticException(String message, String operationId) {
        super(message);
        this.operationId = operationId;
    }

    public BulkContractDiagnosticException(String message, String operationId, Throwable cause) {
        super(message, cause);
        this.operationId = operationId;
    }

    public String operationId() {
        return operationId;
    }
}
