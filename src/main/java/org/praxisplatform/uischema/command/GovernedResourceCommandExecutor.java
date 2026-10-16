package org.praxisplatform.uischema.command;

import jakarta.persistence.EntityNotFoundException;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.NoOpResourceOperationAvailabilityProvider;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityContext;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityProvider;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.praxisplatform.uischema.rest.failure.ResourceOperationFailureException;
import org.praxisplatform.uischema.rest.failure.ResourceOperationFailureKind;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;

/**
 * Executes resource commands through a host-provided private boundary while keeping the public
 * Praxis lifecycle stable and sanitized.
 */
public class GovernedResourceCommandExecutor {

    private final ResourceCommandExecutionProvider provider;
    private final ResourceOperationAvailabilityProvider availabilityProvider;
    private final ResourceCommandEvidenceSanitizer evidenceSanitizer;

    public GovernedResourceCommandExecutor(ResourceCommandExecutionProvider provider) {
        this(provider, new NoOpResourceOperationAvailabilityProvider(), ResourceCommandEvidenceSanitizer.defaults());
    }

    public GovernedResourceCommandExecutor(
            ResourceCommandExecutionProvider provider,
            ResourceOperationAvailabilityProvider availabilityProvider,
            ResourceCommandEvidenceSanitizer evidenceSanitizer
    ) {
        this.provider = Objects.requireNonNull(provider, "provider is required");
        this.availabilityProvider = availabilityProvider == null
                ? new NoOpResourceOperationAvailabilityProvider()
                : availabilityProvider;
        this.evidenceSanitizer = evidenceSanitizer == null
                ? ResourceCommandEvidenceSanitizer.defaults()
                : evidenceSanitizer;
    }

    public ResourceCommandExecutionResult execute(ResourceCommandExecutionRequest request) {
        Objects.requireNonNull(request, "request is required");
        AvailabilityDecision availability = availabilityProvider.evaluate(availabilityContext(request));
        if (availability == null) {
            availability = AvailabilityDecision.allowAll();
        }
        if (!availability.allowed()) {
            return ResourceCommandExecutionResult.denied(
                    request,
                    availability.reason(),
                    evidenceSanitizer.sanitize(availability.metadata())
            );
        }

        try {
            ResourceCommandExecutionResult result = provider.execute(request);
            if (result == null) {
                return ResourceCommandExecutionResult.unexpected(
                        request,
                        "provider-returned-null",
                        Map.of()
                );
            }
            return result.withEvidence(evidenceSanitizer.sanitize(result.evidence()));
        } catch (ResourceCommandExecutionException ex) {
            return ResourceCommandExecutionResult.failure(
                    request,
                    ex.outcome(),
                    ex.publicMessage(),
                    evidenceSanitizer.sanitize(ex.evidence())
            );
        } catch (ResourceOperationFailureException ex) {
            if (ex.failure().kind() == ResourceOperationFailureKind.UNAVAILABLE) {
                return ResourceCommandExecutionResult.failure(request, ResourceCommandOutcome.UNAVAILABLE,
                        ResourceCommandExecutionResult.UNAVAILABLE_PUBLIC_MESSAGE, Map.of("status", 503));
            }
            // Existing functional kinds have no command translation in this boundary.
            return ResourceCommandExecutionResult.unexpected(request, "provider-exception",
                    Map.of("exceptionType", ex.getClass().getSimpleName()));
        } catch (ResourceVersionPreconditionException ex) {
            // Preserve the canonical typed failure so GlobalExceptionHandler can materialize its
            // exact 400/412/428 status and stable public code. Converting it to a generic command
            // outcome would collapse distinct concurrency decisions into one HTTP response.
            throw ex;
        } catch (EntityNotFoundException ex) {
            return ResourceCommandExecutionResult.failure(
                    request,
                    ResourceCommandOutcome.NOT_FOUND,
                    ex.getMessage() == null || ex.getMessage().isBlank() ? "Resource not found." : ex.getMessage(),
                    Map.of("status", HttpStatus.NOT_FOUND.value())
            );
        } catch (ResponseStatusException ex) {
            ResourceCommandOutcome outcome = outcomeFromStatus(ex.getStatusCode().value());
            if (outcome == ResourceCommandOutcome.UNEXPECTED_SANITIZED) {
                // Unmapped statuses do not certify their reason as a safe public message.
                return ResourceCommandExecutionResult.failure(request, outcome, "Command execution failed.",
                        Map.of("status", ex.getStatusCode().value()));
            }
            return ResourceCommandExecutionResult.failure(
                    request,
                    outcome,
                    outcome == ResourceCommandOutcome.UNAVAILABLE
                            ? ResourceCommandExecutionResult.UNAVAILABLE_PUBLIC_MESSAGE : publicMessage(ex),
                    Map.of("status", ex.getStatusCode().value())
            );
        } catch (ObjectOptimisticLockingFailureException ex) {
            return ResourceCommandExecutionResult.failure(
                    request,
                    ResourceCommandOutcome.PRECONDITION_FAILED,
                    "Resource version is stale.",
                    Map.of("status", HttpStatus.PRECONDITION_FAILED.value())
            );
        } catch (RuntimeException ex) {
            return ResourceCommandExecutionResult.unexpected(
                    request,
                    "provider-exception",
                    Map.of("exceptionType", ex.getClass().getSimpleName())
            );
        }
    }

    private ResourceCommandOutcome outcomeFromStatus(int status) {
        return switch (status) {
            case 400 -> ResourceCommandOutcome.VALIDATION_FAILED;
            case 403 -> ResourceCommandOutcome.PERMISSION_DENIED;
            case 404 -> ResourceCommandOutcome.NOT_FOUND;
            case 409 -> ResourceCommandOutcome.CONFLICT_DEPENDENCY;
            case 412 -> ResourceCommandOutcome.PRECONDITION_FAILED;
            case 503 -> ResourceCommandOutcome.UNAVAILABLE;
            default -> ResourceCommandOutcome.UNEXPECTED_SANITIZED;
        };
    }

    private String publicMessage(ResponseStatusException ex) {
        return ex.getReason() == null || ex.getReason().isBlank()
                ? "Command execution failed."
                : ex.getReason();
    }

    private ResourceOperationAvailabilityContext availabilityContext(ResourceCommandExecutionRequest request) {
        if (request.scope() == ResourceCommandScope.ITEM) {
            return new ResourceOperationAvailabilityContext(
                    request.resourceKey(),
                    request.resourcePath(),
                    request.commandId(),
                    request.scope().name(),
                    request.resourceId(),
                    null,
                    request.publicMetadata()
            );
        }
        return new ResourceOperationAvailabilityContext(
                request.resourceKey(),
                request.resourcePath(),
                request.commandId(),
                request.scope().name(),
                null,
                null,
                request.publicMetadata()
        );
    }
}
