package org.praxisplatform.uischema.command;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.NoOpResourceOperationAvailabilityProvider;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityContext;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityProvider;
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
        } catch (ResponseStatusException ex) {
            return ResourceCommandExecutionResult.failure(
                    request,
                    outcomeFromStatus(ex.getStatusCode().value()),
                    publicMessage(ex),
                    Map.of("status", ex.getStatusCode().value())
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
