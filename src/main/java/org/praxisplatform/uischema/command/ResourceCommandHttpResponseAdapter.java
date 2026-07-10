package org.praxisplatform.uischema.command;

import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import org.praxisplatform.uischema.rest.response.CustomProblemDetail;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.hateoas.Links;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;

/**
 * Converts governed command outcomes into the canonical Praxis HTTP response envelope.
 */
public class ResourceCommandHttpResponseAdapter {

    private static final String PROBLEM_BASE = "https://example.com/probs/resource-command/";

    public ResponseEntity<?> toResponse(ResourceCommandExecutionResult result) {
        return toResponse(result, null);
    }

    public ResponseEntity<?> toResponse(ResourceCommandExecutionResult result, Links successLinks) {
        if (result == null) {
            return failure(ResourceCommandOutcome.UNEXPECTED_SANITIZED, "Command execution failed.", null);
        }
        if (!result.successful()) {
            ResourceCommandMessage message = result.messages().isEmpty() ? null : result.messages().getFirst();
            return failure(result.outcome(), message == null ? "Command execution failed." : message.message(), message);
        }
        return switch (result.responsePolicy()) {
            case NO_CONTENT -> ResponseEntity.noContent().build();
            case ACCEPTED_ASYNC -> ResponseEntity.accepted().body(RestApiResponse.success(
                    result.body() == null ? result.publicId() : result.body(),
                    successLinks
            ));
            case READ_AFTER_WRITE, RETURN_COMMAND_RESULT -> ResponseEntity.ok(RestApiResponse.success(result.body(), successLinks));
        };
    }

    private ResponseEntity<RestApiResponse<Object>> failure(
            ResourceCommandOutcome outcome,
            String message,
            ResourceCommandMessage commandMessage
    ) {
        HttpStatus status = status(outcome);
        CustomProblemDetail problem = new CustomProblemDetail(message);
        problem.setStatus(status);
        problem.setTitle(title(outcome));
        problem.setType(URI.create(PROBLEM_BASE + outcome.name().toLowerCase().replace('_', '-')));
        problem.setCategory(errorCategory(commandMessage == null ? outcome.errorCategory() : commandMessage.category()));
        problem.setProperty("outcome", outcome.name());
        if (commandMessage != null) {
            problem.setProperty("code", commandMessage.code());
            if (commandMessage.target() != null && !commandMessage.target().isBlank()) {
                problem.setProperty("target", commandMessage.target());
            }
        }
        return ResponseEntity.status(status).body(RestApiResponse.failure(message, List.of(problem)));
    }

    private HttpStatus status(ResourceCommandOutcome outcome) {
        return switch (outcome) {
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT_DUPLICATE, CONFLICT_DEPENDENCY -> HttpStatus.CONFLICT;
            case PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED;
            case SUCCESS, ACCEPTED, UNEXPECTED_SANITIZED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String title(ResourceCommandOutcome outcome) {
        return switch (outcome) {
            case VALIDATION_FAILED -> "Command validation failed";
            case PERMISSION_DENIED -> "Command is not available";
            case NOT_FOUND -> "Command target not found";
            case CONFLICT_DUPLICATE -> "Command conflicts with existing data";
            case CONFLICT_DEPENDENCY -> "Command conflicts with dependent data";
            case PRECONDITION_FAILED -> "Command precondition failed";
            case SUCCESS, ACCEPTED, UNEXPECTED_SANITIZED -> "Command execution failed";
        };
    }

    private ErrorCategory errorCategory(ResourceCommandErrorCategory category) {
        if (category == null) {
            return ErrorCategory.UNKNOWN;
        }
        return switch (category) {
            case VALIDATION, PRECONDITION -> ErrorCategory.VALIDATION;
            case PERMISSION -> ErrorCategory.SECURITY;
            case NOT_FOUND, CONFLICT_DUPLICATE, CONFLICT_DEPENDENCY -> ErrorCategory.BUSINESS_LOGIC;
            case UNEXPECTED_SANITIZED -> ErrorCategory.SYSTEM;
        };
    }
}
