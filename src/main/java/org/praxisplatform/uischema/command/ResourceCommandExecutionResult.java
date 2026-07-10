package org.praxisplatform.uischema.command;

import java.util.List;
import java.util.Map;

/**
 * Public, sanitized result of a governed resource command execution.
 */
public record ResourceCommandExecutionResult(
        ResourceCommandOutcome outcome,
        ResourceCommandResponsePolicy responsePolicy,
        Object publicId,
        Object body,
        List<ResourceCommandMessage> messages,
        Map<String, Object> evidence
) {

    public ResourceCommandExecutionResult {
        outcome = outcome == null ? ResourceCommandOutcome.UNEXPECTED_SANITIZED : outcome;
        responsePolicy = responsePolicy == null
                ? ResourceCommandResponsePolicy.READ_AFTER_WRITE
                : responsePolicy;
        messages = messages == null ? List.of() : List.copyOf(messages);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public boolean successful() {
        return outcome.successful();
    }

    public ResourceCommandExecutionResult withEvidence(Map<String, Object> sanitizedEvidence) {
        return new ResourceCommandExecutionResult(
                outcome,
                responsePolicy,
                publicId,
                body,
                messages,
                sanitizedEvidence
        );
    }

    public static ResourceCommandExecutionResult success(
            ResourceCommandExecutionRequest request,
            Object publicId,
            Object body,
            Map<String, Object> evidence
    ) {
        return new ResourceCommandExecutionResult(
                ResourceCommandOutcome.SUCCESS,
                request.responsePolicy(),
                publicId,
                body,
                List.of(),
                evidence
        );
    }

    public static ResourceCommandExecutionResult accepted(
            ResourceCommandExecutionRequest request,
            Object publicId,
            Map<String, Object> evidence
    ) {
        return new ResourceCommandExecutionResult(
                ResourceCommandOutcome.ACCEPTED,
                ResourceCommandResponsePolicy.ACCEPTED_ASYNC,
                publicId,
                null,
                List.of(),
                evidence
        );
    }

    public static ResourceCommandExecutionResult denied(
            ResourceCommandExecutionRequest request,
            String reason,
            Map<String, Object> evidence
    ) {
        return failure(
                request,
                ResourceCommandOutcome.PERMISSION_DENIED,
                reason == null ? "Command is not available." : reason,
                evidence
        );
    }

    public static ResourceCommandExecutionResult failure(
            ResourceCommandExecutionRequest request,
            ResourceCommandOutcome outcome,
            String message,
            Map<String, Object> evidence
    ) {
        ResourceCommandOutcome resolvedOutcome = outcome == null
                ? ResourceCommandOutcome.UNEXPECTED_SANITIZED
                : outcome;
        return new ResourceCommandExecutionResult(
                resolvedOutcome,
                request.responsePolicy(),
                request.resourceId(),
                null,
                List.of(new ResourceCommandMessage(
                        resolvedOutcome.errorCategory(),
                        resolvedOutcome.name(),
                        message == null ? "Command execution failed." : message,
                        null,
                        Map.of()
                )),
                evidence
        );
    }

    public static ResourceCommandExecutionResult unexpected(
            ResourceCommandExecutionRequest request,
            String code,
            Map<String, Object> evidence
    ) {
        return new ResourceCommandExecutionResult(
                ResourceCommandOutcome.UNEXPECTED_SANITIZED,
                request.responsePolicy(),
                request.resourceId(),
                null,
                List.of(new ResourceCommandMessage(
                        ResourceCommandErrorCategory.UNEXPECTED_SANITIZED,
                        code == null ? "UNEXPECTED_SANITIZED" : code,
                        "Command execution failed.",
                        null,
                        Map.of()
                )),
                evidence
        );
    }
}
