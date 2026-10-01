package org.praxisplatform.uischema.bulk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the five shared HTTP operation identities used by the bulk proposal and execution
 * lifecycle for one {@code @ApiResource} controller, and optionally its unit update source.
 *
 * <p>The annotation supplies identities only. Real Spring MVC handler methods must carry one
 * {@link BulkResourceOperation} role each; the binding is accepted only when those handlers map
 * unambiguously to the declared resource and HTTP methods. It does not create routes or make a
 * bulk action executable.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BulkResourceOperations {

    /** Global operationId for reading one retained bulk proposal. */
    String proposalOperationId();

    /** Global operationId for reading the paged results of one bulk proposal. */
    String proposalResultsOperationId();

    /** Global operationId for reading one bulk execution. */
    String executionOperationId();

    /** Global operationId for reading the paged results of one bulk execution. */
    String executionResultsOperationId();

    /** Global operationId for requesting cancellation of one bulk execution. */
    String cancelOperationId();
    /**
     * Explicit operationId assigned to the real UPDATE_SOURCE handler. Required for update
     * modes; empty leaves an inherited ordinary PUT untouched on command-only resources.
     */
    String updateSourceOperationId() default "";

    /**
     * Protected wire names in the unit update contract: identity, persisted version and
     * workflow-managed fields. No roles are inferred from names. Empty declares that none
     * of those protected fields is accepted by the DTO. This is not actor authorization.
     */
    String[] protectedUpdateFields() default {};
}
