package org.praxisplatform.uischema.customfields;

import java.lang.annotation.*;

/** Declares the explicit customFields container; never flattens customer keys into the DTO. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CustomFields {
    String resourceKey() default "";
}
