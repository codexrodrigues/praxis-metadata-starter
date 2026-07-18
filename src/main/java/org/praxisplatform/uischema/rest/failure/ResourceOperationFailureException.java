package org.praxisplatform.uischema.rest.failure;

/**
 * Carries a governed public resource failure while retaining a private cause for diagnostics.
 */
public class ResourceOperationFailureException extends RuntimeException {

    private final ResourceOperationFailure failure;

    public ResourceOperationFailureException(ResourceOperationFailure failure) {
        this(failure, null);
    }

    public ResourceOperationFailureException(ResourceOperationFailure failure, Throwable cause) {
        super(requirePublicFailure(failure).safeMessage(), cause);
        this.failure = failure;
    }

    public ResourceOperationFailure failure() {
        return failure;
    }

    private static ResourceOperationFailure requirePublicFailure(ResourceOperationFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("Resource operation failure is required.");
        }
        if (failure.kind().sanitizedInternalFailure()) {
            throw new IllegalArgumentException("Unexpected internal failures cannot be raised as public functional exceptions.");
        }
        return failure;
    }
}
