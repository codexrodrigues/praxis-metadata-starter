package org.praxisplatform.uischema.concurrency;

import org.springframework.http.HttpStatus;

/** Base class for predictable HTTP failures of record-version preconditions. */
public final class ResourceVersionPreconditionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ResourceVersionPreconditionException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ResourceVersionPreconditionException required() {
        return new ResourceVersionPreconditionException(
                HttpStatus.PRECONDITION_REQUIRED,
                "RESOURCE_VERSION_REQUIRED",
                "If-Match is required for this resource action."
        );
    }

    public static ResourceVersionPreconditionException invalid() {
        return new ResourceVersionPreconditionException(
                HttpStatus.BAD_REQUEST,
                "INVALID_RESOURCE_VERSION",
                "If-Match must contain exactly one strong resource ETag."
        );
    }

    public static ResourceVersionPreconditionException stale() {
        return new ResourceVersionPreconditionException(
                HttpStatus.PRECONDITION_FAILED,
                "STALE_RESOURCE_VERSION",
                "The resource has changed since it was read."
        );
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
