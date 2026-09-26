package org.praxisplatform.uischema.annotation;

import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.bulk.BulkMode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the bulk contract of a real workflow confirmation handler.
 *
 * <p>The evaluation operation is an explicit operationId on another POST handler of the same
 * {@code @ApiResource}. This annotation declares metadata only: it creates no route, permission,
 * provider, durable readiness or executable capability.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BulkOperation {
    BulkMode mode();

    String evaluationOperationId();

    ActionCollectionAtomicity atomicity();
}
