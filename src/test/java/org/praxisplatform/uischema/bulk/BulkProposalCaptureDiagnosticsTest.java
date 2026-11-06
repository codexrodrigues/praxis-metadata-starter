package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.Test;

class BulkProposalCaptureDiagnosticsTest {
    @Test void preservesTheOriginalOriginAndSuppressionsWithoutMessagesOrPayloads() {
        var failure = new IllegalStateException("secret-password-and-tenant-payload");
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "org.praxisplatform.uischema.bulk.TrustedCapture", "original", "TrustedCapture.java", 42)});
        var suppressed = new IllegalStateException("private-cleanup-details"); failure.addSuppressed(suppressed);
        var observer = new BulkProposalCaptureDiagnostics();
        observer.stage = BulkProposalCaptureDiagnostics.Stage.CAPTURE; observer.record(failure);
        observer.stage = BulkProposalCaptureDiagnostics.Stage.ALLOCATION; observer.record(suppressed);
        var event = observer.event();
        assertThat(event.path("stage").asText()).isEqualTo("CAPTURE");
        assertThat(event.at("/originalOrigin/lineNumber").asInt()).isEqualTo(42);
        assertThat(event.at("/originalOrigin/methodName").asText()).isEqualTo("original");
        assertThat(event.toString()).doesNotContain("secret-password", "tenant-payload", "private-cleanup");
        assertThat(failure.getSuppressed()).containsExactly(suppressed);
    }
    @Test void neverUsesALaterTrustedFrameAsAnUntrustedOrigin() {
        var failure = new IllegalStateException();
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("customer.Private", "hidden", "Private.java", 7),
                new StackTraceElement("org.praxisplatform.uischema.bulk.Trusted", "later", "Trusted.java", 99)});
        var observer = new BulkProposalCaptureDiagnostics(); observer.record(failure);
        assertThat(observer.event().at("/originalOrigin/trusted").asBoolean()).isFalse();
        assertThat(observer.event().at("/originalOrigin/className").asText()).isEqualTo("OTHER");
    }
    @Test void failedObserversCannotReplaceTheFailureAndSuccessEmitsNothing() {
        var observer = new BulkProposalCaptureDiagnostics();
        observer.emit(value -> { throw new AssertionError("success must emit nothing"); });
        observer.record(new IllegalStateException());
        assertThatCode(() -> observer.emit(value -> { throw new AssertionError("sink failed"); })).doesNotThrowAnyException();
    }
    @Test void malformedTrustedLookingSymbolsCannotEnterTheDiagnosticOrigin() {
        var failure = new IllegalStateException();
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "org.praxisplatform.Private\nsecret", "secret\nmethod", "ignored", 1)});
        var observer = new BulkProposalCaptureDiagnostics(); observer.record(failure);
        assertThat(observer.event().at("/originalOrigin/trusted").asBoolean()).isFalse();
        assertThat(observer.event().toString()).doesNotContain("secret");
    }
}
