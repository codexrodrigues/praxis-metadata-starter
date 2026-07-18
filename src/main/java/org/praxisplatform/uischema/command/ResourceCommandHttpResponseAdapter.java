package org.praxisplatform.uischema.command;

import org.praxisplatform.uischema.rest.failure.ResourceOperationFailure;
import org.praxisplatform.uischema.rest.failure.ResourceOperationFailureKind;
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
        ResourceOperationFailure failure = resourceFailure(outcome, message, commandMessage);
        HttpStatus status = failure.kind().status();
        CustomProblemDetail problem = new CustomProblemDetail(failure.safeMessage());
        problem.setStatus(status);
        problem.setTitle(title(outcome));
        problem.setType(URI.create(PROBLEM_BASE + outcome.name().toLowerCase().replace('_', '-')));
        problem.setCategory(failure.kind().category());
        problem.setCode(failure.code());
        problem.setTarget(failure.target());
        problem.setProperty("outcome", outcome.name());
        return ResponseEntity.status(status).body(RestApiResponse.failure(message, List.of(problem)));
    }

    private ResourceOperationFailure resourceFailure(
            ResourceCommandOutcome outcome,
            String message,
            ResourceCommandMessage commandMessage
    ) {
        ResourceOperationFailureKind kind = switch (outcome) {
            case VALIDATION_FAILED -> ResourceOperationFailureKind.INVALID_INPUT;
            case PERMISSION_DENIED -> ResourceOperationFailureKind.PERMISSION_DENIED;
            case NOT_FOUND -> ResourceOperationFailureKind.NOT_FOUND;
            case CONFLICT_DUPLICATE -> ResourceOperationFailureKind.CONFLICT_DUPLICATE;
            case CONFLICT_DEPENDENCY -> ResourceOperationFailureKind.CONFLICT_DEPENDENCY;
            case PRECONDITION_FAILED -> ResourceOperationFailureKind.PRECONDITION_FAILED;
            case SUCCESS, ACCEPTED, UNEXPECTED_SANITIZED -> ResourceOperationFailureKind.UNEXPECTED_SANITIZED;
        };
        String code = commandMessage == null ? outcome.name() : commandMessage.code();
        String target = commandMessage == null ? null : commandMessage.target();
        return new ResourceOperationFailure(kind, code, message, target);
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
}
