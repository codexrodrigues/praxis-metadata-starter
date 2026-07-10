package org.praxisplatform.uischema.command;

/**
 * Host-neutral SPI for executing resource commands behind a private backend boundary.
 */
@FunctionalInterface
public interface ResourceCommandExecutionProvider {

    ResourceCommandExecutionResult execute(ResourceCommandExecutionRequest request);
}
