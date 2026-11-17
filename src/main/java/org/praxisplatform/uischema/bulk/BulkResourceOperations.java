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

    /** Global operationId for reading one retained bulk proposal; defaults to {@code <resourceKey>.bulk.proposal}. */
    String proposalOperationId() default "";

    /** Global operationId for reading the paged results of one bulk proposal; defaults to {@code <resourceKey>.bulk.proposal-results}. */
    String proposalResultsOperationId() default "";

    /** Global operationId for reading one bulk execution; defaults to {@code <resourceKey>.bulk.execution}. */
    String executionOperationId() default "";

    /** Global operationId for reading the paged results of one bulk execution; defaults to {@code <resourceKey>.bulk.execution-results}. */
    String executionResultsOperationId() default "";

    /** Global operationId for requesting cancellation of one bulk execution; defaults to {@code <resourceKey>.bulk.cancel}. */
    String cancelOperationId() default "";
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
