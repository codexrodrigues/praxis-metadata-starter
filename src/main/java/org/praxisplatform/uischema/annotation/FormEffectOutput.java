package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Maps one determination response field into a form field. */
@Target({ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface FormEffectOutput {
    String operationField();
    String formField();
    FormEffectWritePolicy writePolicy() default FormEffectWritePolicy.IF_PRISTINE;
}
