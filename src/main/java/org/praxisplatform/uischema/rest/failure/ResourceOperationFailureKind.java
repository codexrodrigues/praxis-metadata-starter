package org.praxisplatform.uischema.rest.failure;

import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import org.springframework.http.HttpStatus;

/**
 * Governed public failure kinds for resource operations.
 *
 * <p>The kind is the canonical decision. HTTP status and broad error category are
 * derived materializations and cannot be freely combined by a host.</p>
 */
public enum ResourceOperationFailureKind {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, ErrorCategory.VALIDATION, "Invalid request", "validation-error"),
    BUSINESS_RULE_VIOLATION(HttpStatus.BAD_REQUEST, ErrorCategory.BUSINESS_LOGIC, "Business rule violation", "business-logic"),
    INVALID_ENTITY(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCategory.VALIDATION, "Invalid entity", "validation-error"),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, ErrorCategory.SECURITY, "Access denied", "security"),
    NOT_FOUND(HttpStatus.NOT_FOUND, ErrorCategory.BUSINESS_LOGIC, "Resource not found", "resource-not-found"),
    CONFLICT_DUPLICATE(HttpStatus.CONFLICT, ErrorCategory.BUSINESS_LOGIC, "Conflict", "conflict"),
    CONFLICT_DEPENDENCY(HttpStatus.CONFLICT, ErrorCategory.BUSINESS_LOGIC, "Conflict", "conflict"),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, ErrorCategory.VALIDATION, "Precondition failed", "precondition-failed"),
    UNEXPECTED_SANITIZED(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SYSTEM, "Internal server error", "internal-server-error");

    private final HttpStatus status;
    private final ErrorCategory category;
    private final String title;
    private final String problemSlug;

    ResourceOperationFailureKind(HttpStatus status, ErrorCategory category, String title, String problemSlug) {
        this.status = status;
        this.category = category;
        this.title = title;
        this.problemSlug = problemSlug;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCategory category() {
        return category;
    }

    public String title() {
        return title;
    }

    public String problemSlug() {
        return problemSlug;
    }

    public boolean sanitizedInternalFailure() {
        return this == UNEXPECTED_SANITIZED;
    }
}
