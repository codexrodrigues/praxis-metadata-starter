package org.praxisplatform.uischema.bulk;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.praxisplatform.uischema.rest.exceptionhandler.GlobalExceptionHandler;
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
}
