package org.praxisplatform.uischema.annotation;

import org.praxisplatform.uischema.bulk.BulkMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit structural opt-in for a property of the operation's update DTO.
 * The binding compiler intersects this declaration with Jackson wire names, the resolved
 * update schema and protected identity/version/workflow fields. It does not authorize an
 * actor, publish an executable capability or replace domain validation.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BulkEditable {
    /** Update families accepting this field; domain commands use their own parameters. */
    BulkMode[] modes() default {BulkMode.UNIFORM_UPDATE, BulkMode.PER_ITEM_UPDATE};

    /** CLEAR additionally requires explicit schema nullability and domain acceptance. */
    boolean allowClear() default false;
}
