package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a typed HTTP operation as a safe form determination.
 *
 * <p>A determination enriches an in-memory form projection. It must be idempotent for the same
 * request, must not persist business state and must not be used as a workflow command.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FormDetermination {
}
