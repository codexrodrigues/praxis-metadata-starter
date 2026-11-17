package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes the row fields and filter parameters of a hierarchical resource. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceHierarchy {
    String parentField() default "";
    String parentLabelField() default "";
    String hierarchyPathField() default "";
    String levelField() default "";
    String childrenCountField() default "";
    String leafIndicatorField() default "";
    String effectiveFromField() default "";
    String effectiveToField() default "";
    String extinctField() default "";
    String referenceDateFilterField() default "";
    String subtreeFilterField() default "";
    String includeSubtreeFilterField() default "";
    String hierarchyKindFilterField() default "";
    String[] auxiliaryParentFields() default {};
}
