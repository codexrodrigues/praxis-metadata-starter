package org.praxisplatform.uischema.options;

/**
 * Raised when a resource does not expose the requested option source.
 */
public class UnknownOptionSourceException extends IllegalArgumentException {

    public UnknownOptionSourceException(Class<?> resourceClass, String sourceKey) {
        super("Option source is not registered for resource "
                + (resourceClass == null ? "<unknown>" : resourceClass.getSimpleName())
                + ": " + sourceKey);
    }
}
