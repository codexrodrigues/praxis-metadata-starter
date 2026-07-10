package org.praxisplatform.uischema.command;

/**
 * Public response policy requested for a governed resource command.
 */
public enum ResourceCommandResponsePolicy {
    READ_AFTER_WRITE,
    RETURN_COMMAND_RESULT,
    NO_CONTENT,
    ACCEPTED_ASYNC
}
