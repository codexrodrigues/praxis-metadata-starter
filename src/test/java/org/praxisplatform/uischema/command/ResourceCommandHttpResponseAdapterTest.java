package org.praxisplatform.uischema.command;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import org.praxisplatform.uischema.rest.response.CustomProblemDetail;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceCommandHttpResponseAdapterTest {

    private final ResourceCommandHttpResponseAdapter adapter = new ResourceCommandHttpResponseAdapter();

    @Test
    void mapsReadAfterWriteSuccessToOkEnvelope() {
        ResourceCommandExecutionRequest request = ResourceCommandExecutionRequest.item(
                "example.people",
                "/api/example/people",
                "update",
                42L,
                Map.of("name", "Ada"),
                ResourceCommandResponsePolicy.READ_AFTER_WRITE
        );

        ResponseEntity<?> response = adapter.toResponse(ResourceCommandExecutionResult.success(
                request,
                42L,
                Map.of("id", 42L, "name", "Ada"),
                Map.of()
        ));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        RestApiResponse<?> body = assertInstanceOf(RestApiResponse.class, response.getBody());
        assertEquals("success", body.getStatus());
        assertEquals(Map.of("id", 42L, "name", "Ada"), body.getData());
    }

    @Test
    void mapsNoContentSuccessToNoContentWithoutEnvelope() {
        ResourceCommandExecutionRequest request = ResourceCommandExecutionRequest.item(
                "example.people",
                "/api/example/people",
                "delete",
                42L,
                null,
                ResourceCommandResponsePolicy.NO_CONTENT
        );

        ResponseEntity<?> response = adapter.toResponse(ResourceCommandExecutionResult.success(
                request,
                42L,
                null,
                Map.of()
        ));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void mapsAcceptedAsyncToAcceptedEnvelope() {
        ResourceCommandExecutionRequest request = ResourceCommandExecutionRequest.collection(
                "example.people",
                "/api/example/people",
                "import",
                Map.of("batch", "people"),
                ResourceCommandResponsePolicy.ACCEPTED_ASYNC
        );

        ResponseEntity<?> response = adapter.toResponse(ResourceCommandExecutionResult.accepted(
                request,
                "job-1",
                Map.of()
        ));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        RestApiResponse<?> body = assertInstanceOf(RestApiResponse.class, response.getBody());
        assertEquals("success", body.getStatus());
        assertEquals("job-1", body.getData());
    }

    @Test
    void mapsPermissionDeniedToForbiddenProblemEnvelope() {
        ResourceCommandExecutionRequest request = ResourceCommandExecutionRequest.item(
                "example.people",
                "/api/example/people",
                "delete",
                42L,
                null,
                ResourceCommandResponsePolicy.NO_CONTENT
        );

        ResponseEntity<?> response = adapter.toResponse(ResourceCommandExecutionResult.denied(
                request,
                "missing-authority",
                Map.of()
        ));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        RestApiResponse<?> body = assertInstanceOf(RestApiResponse.class, response.getBody());
        assertEquals("failure", body.getStatus());
        assertEquals("missing-authority", body.getMessage());
        CustomProblemDetail problem = body.getErrors().getFirst();
        assertEquals(HttpStatus.FORBIDDEN.value(), problem.getStatus());
        assertEquals(ErrorCategory.SECURITY, problem.getCategory());
        assertEquals("PERMISSION_DENIED", problem.getProperties().get("outcome"));
    }

    @Test
    void mapsConflictDuplicateToConflictProblemEnvelope() {
        ResourceCommandExecutionRequest request = ResourceCommandExecutionRequest.collection(
                "example.people",
                "/api/example/people",
                "create",
                Map.of("name", "Ada"),
                ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT
        );

        ResponseEntity<?> response = adapter.toResponse(ResourceCommandExecutionResult.failure(
                request,
                ResourceCommandOutcome.CONFLICT_DUPLICATE,
                "Duplicated public key.",
                Map.of()
        ));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        RestApiResponse<?> body = assertInstanceOf(RestApiResponse.class, response.getBody());
        CustomProblemDetail problem = body.getErrors().getFirst();
        assertEquals(ErrorCategory.BUSINESS_LOGIC, problem.getCategory());
        assertEquals("CONFLICT_DUPLICATE", problem.getProperties().get("outcome"));
        assertEquals("CONFLICT_DUPLICATE", problem.getProperties().get("code"));
    }

    @Test
    void mapsUnexpectedToInternalServerErrorProblemEnvelope() {
        ResourceCommandExecutionRequest request = ResourceCommandExecutionRequest.collection(
                "example.people",
                "/api/example/people",
                "create",
                Map.of("name", "Ada"),
                ResourceCommandResponsePolicy.RETURN_COMMAND_RESULT
        );

        ResponseEntity<?> response = adapter.toResponse(ResourceCommandExecutionResult.unexpected(
                request,
                "provider-exception",
                Map.of()
        ));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        RestApiResponse<?> body = assertInstanceOf(RestApiResponse.class, response.getBody());
        CustomProblemDetail problem = body.getErrors().getFirst();
        assertEquals(ErrorCategory.SYSTEM, problem.getCategory());
        assertEquals("UNEXPECTED_SANITIZED", problem.getProperties().get("outcome"));
        assertEquals("provider-exception", problem.getProperties().get("code"));
    }
}
