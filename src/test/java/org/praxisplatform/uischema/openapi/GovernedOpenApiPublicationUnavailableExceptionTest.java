package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedOpenApiPublicationUnavailableExceptionTest {

    @Test
    void privateObservationContainsTypesAndSqlStateWithoutExceptionTextOrStack() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                GovernedOpenApiPublicationUnavailableException.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var sql = new java.sql.SQLException("PRIVATE_SQL password=PRIVATE_SECRET", "08006");
            var cause = new IllegalStateException("PRIVATE_PAYLOAD", sql);
            var exception = new GovernedOpenApiPublicationUnavailableException(cause);
            assertThat(exception.getCause()).isSameAs(cause);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("java.lang.IllegalStateException", "java.sql.SQLException:08006")
                    .doesNotContain("PRIVATE", "password", "payload");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void diagnosticFailureCannotReplaceTheOriginalRefusal() {
        var hostile = new IllegalStateException("PRIVATE") {
            @Override public synchronized Throwable getCause() { throw new IllegalStateException("observer failure"); }
        };
        assertThat(new GovernedOpenApiPublicationUnavailableException(hostile).getCause()).isSameAs(hostile);
    }

    @Test
    void diagnosticTraversalIsBoundedAndRejectsNonSqlStateText() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                GovernedOpenApiPublicationUnavailableException.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var sql = new java.sql.SQLException("PRIVATE", "PRIVATE_SQL_STATE\nsecret");
            var cause = new IllegalStateException("PRIVATE", sql);
            sql.initCause(cause);
            new GovernedOpenApiPublicationUnavailableException(cause);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage()).doesNotContain("PRIVATE", "secret", "\n");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void unavailablePublicationReturnsSafe503WhileRetainingThePrivateDiagnosticCause() throws Exception {
        var privateCause = new IllegalStateException(
                "PRIVATE_SQL: select document_digest from praxis_bulk.praxis_bulk_openapi_publication; "
                        + "PRIVATE_SECRET=diagnostic-only-token");
        var exception = new GovernedOpenApiPublicationUnavailableException(privateCause);
        var request = new ServletWebRequest(new MockHttpServletRequest("GET", "/schemas/filtered"));

        var response = new GlobalExceptionHandler()
                .handleGovernedOpenApiPublicationUnavailable(exception, request);

        assertThat(exception).isInstanceOf(IllegalStateException.class);
        assertThat(exception.getCause()).isSameAs(privateCause);
        assertThat(exception.getCause().getMessage()).isEqualTo(privateCause.getMessage());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo("failure");
        assertThat(body.getMessage()).isEqualTo("Governed OpenAPI publication is temporarily unavailable.");
        assertThat(body.getData()).isNull();
        assertThat(body.getErrors()).hasSize(1);
        var problem = body.getErrors().getFirst();
        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getCategory()).isEqualTo(ErrorCategory.SYSTEM);
        assertThat(problem.getCode()).isEqualTo("GOVERNED_OPENAPI_PUBLICATION_UNAVAILABLE");
        assertThat(problem.getProperties()).doesNotContainKey("code");
        assertThat(problem.getInstance().toString()).isEqualTo("/schemas/filtered");
        assertThat(problem.getMessage()).isEqualTo(body.getMessage());
        assertThat(String.valueOf(problem.getDetail())).doesNotContain("PRIVATE_SQL", "PRIVATE_SECRET");

        String publicJson = new ObjectMapper().findAndRegisterModules().writeValueAsString(body);
        assertThat(publicJson).doesNotContain("PRIVATE_SQL", "PRIVATE_SECRET", "diagnostic-only-token",
                "praxis_bulk_openapi_publication", "\"cause\"", "\"stackTrace\"");
    }
}
