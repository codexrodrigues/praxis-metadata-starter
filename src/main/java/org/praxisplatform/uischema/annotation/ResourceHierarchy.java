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
    String effectiveFromField() default "dtini";
    String effectiveToField() default "dtfim";
    String extinctField() default "";
    String referenceDateFilterField() default "dataReferencia";
    String subtreeFilterField() default "caminhoSubarvore";
    String includeSubtreeFilterField() default "incluirSubordinados";
    String hierarchyKindFilterField() default "tipoHierarquia";
    String[] auxiliaryParentFields() default {};
}
