package org.praxisplatform.uischema.command;

/**
 * Public error vocabulary for governed resource command execution.
 */
public enum ResourceCommandErrorCategory {
    VALIDATION,
    PERMISSION,
    NOT_FOUND,
    CONFLICT_DUPLICATE,
    CONFLICT_DEPENDENCY,
    PRECONDITION,
    UNEXPECTED_SANITIZED
}
