package org.praxisplatform.uischema.bulk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/** Every safe kernel refusal has an intentional public status, without a protected cause. */
class BulkHttpFailureContractTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());

    @ParameterizedTest
    @CsvSource({"NOT_FOUND,404", "CONFLICT,409", "CAPACITY,429", "EXPIRED,410", "RESULT_PURGED,410",
        "NOT_EXECUTABLE,422", "FENCED,503", "DEADLINE_EXCEEDED,503", "RECONCILIATION_REQUIRED,503",
        "CORRUPT,503", "UNAVAILABLE,503"})
    void executionFailure(BulkDurableExecutionException.Reason reason, int status) {
        var result = handler.handleBulkExecution(new BulkDurableExecutionException(reason), request);
        assertEquals(status, result.getStatusCode().value());
        assertNotNull(result.getBody());
        assertEquals("Bulk execution cannot proceed in its current state.", result.getBody().getMessage());
    }

    @ParameterizedTest
    @CsvSource({"CONFLICT,409", "CAPACITY,429", "INVALID_SELECTION,422", "CORRUPT,503", "UNAVAILABLE,503"})
    void proposalFailure(BulkProposalStorageException.Reason reason, int status) {
        var result = handler.handleBulkProposal(new BulkProposalStorageException(reason), request);
        assertEquals(status, result.getStatusCode().value());
        assertNotNull(result.getBody());
        assertEquals("Bulk proposal cannot be accepted in its current state.", result.getBody().getMessage());
    }

    @Test
    void invalidBulkProtocolPayloadMapsToSanitizedBulkCodeWithoutLeakingInput() {
        var converter = new PraxisBulkProtocolHttpMessageConverter(new ObjectMapper(), List.of());
        var input = new MockHttpInputMessage(
                "{\"proposalId\":\"p\",\"unexpected\":\"secret-value\"}".getBytes(StandardCharsets.UTF_8));

        BulkProtocolPayloadException thrown = assertThrows(
                BulkProtocolPayloadException.class,
                () -> converter.read(BulkConfirmationRequest.class, null, input));
        assertNull(thrown.getCause());
        assertFalse(thrown.getMessage().contains("secret-value"));

        var result = handler.handleHttpMessageNotReadable(thrown, request);
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals("failure", result.getBody().getStatus());
        assertEquals(BulkProtocolPayloadException.PUBLIC_MESSAGE, result.getBody().getMessage());
        assertFalse(result.getBody().getMessage().contains("secret-value"));
        assertNotNull(result.getBody().getErrors());
        var error = result.getBody().getErrors().get(0);
        assertEquals(ErrorCategory.VALIDATION, error.getCategory());
        assertEquals("BULK_PROTOCOL_PAYLOAD_INVALID", error.getCode());
        assertEquals(BulkProtocolPayloadException.PUBLIC_MESSAGE, error.getMessage());
        assertFalse(error.getMessage().contains("secret-value"));
        assertNull(error.getDetail());
    }
}
