package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Bounded private diagnostics at the capture's existing sanitization boundary. */
final class BulkProposalCaptureDiagnostics {
    enum Stage { ADMISSION, CONNECTION, BUDGET, QUOTA, CAPTURE, PROJECTION, ENCODING,
        PROPOSAL, EVALUATION, MANIFEST, PREVIEW, ALLOCATION }
    Stage stage = Stage.ADMISSION;
    private Stage failedStage;
    private Throwable failure;

    void record(Throwable original) {
        if (failure == null) { failure = original; failedStage = stage; }
    }

    ObjectNode event() {
        if (failure == null) return null;
        var event = BulkReadFailureDiagnostics.project(BulkReadFailureDiagnostics.Reader.PROPOSAL,
                BulkReadFailureDiagnostics.Stage.SNAPSHOT_TRANSACTION,
                BulkReadFailureDiagnostics.Reason.EXCEPTION, failure);
        event.put("reader", "PROPOSAL_CAPTURE").put("stage", failedStage.name());
        // Preserve index zero before any frame filtering; a later frame is not an origin.
        var trace = failure.getStackTrace();
        var origin = event.putObject("originalOrigin");
        if (trace.length == 0) { origin.put("present", false); return event; }
        var first = trace[0];
        boolean trusted = first.getClassName().matches("[A-Za-z_$][A-Za-z0-9_$.]{0,299}")
                && first.getMethodName().matches("(?:[A-Za-z_$][A-Za-z0-9_$]{0,199}|<init>|<clinit>)")
                && (first.getClassName().startsWith("org.praxisplatform.")
                || first.getClassName().startsWith("org.springframework.")
                || first.getClassName().startsWith("org.hibernate.")
                || first.getClassName().startsWith("org.postgresql.")
                || first.getClassName().startsWith("java.")
                || first.getClassName().startsWith("com.fasterxml.jackson."));
        origin.put("present", true).put("trusted", trusted)
                .put("className", trusted ? first.getClassName() : "OTHER")
                .put("methodName", trusted ? first.getMethodName() : "OTHER")
                .put("lineNumber", trusted ? first.getLineNumber() : -1);
        return event;
    }

    void emit() { emit(value -> LoggerFactory.getLogger(BulkProposalCaptureDiagnostics.class)
            .error("BULK_CAPTURE_FAILURE {}", value)); }

    void emit(Consumer<String> sink) {
        if (failure == null) return;
        try { sink.accept(event().toString()); }
        catch (Throwable ignoredObserverFailure) { /* The original sanitized outcome wins. */ }
    }
}
