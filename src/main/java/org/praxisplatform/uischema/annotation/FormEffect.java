package org.praxisplatform.uischema.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a backend-owned reactive form effect for a concrete request operation.
 *
 * <p>The declaration references a real {@link FormDetermination} by OpenAPI operation id. It
 * contains no executable script, URL or transport override; those values are resolved from the
 * canonical OpenAPI operation.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(FormEffects.class)
public @interface FormEffect {
    String id();
    FormEffectTrigger trigger() default FormEffectTrigger.VALUE_CHANGE;
    String[] triggerFields();
    int debounceMs() default 300;
    boolean requiresValidSources() default true;
    String operationId();
    FormEffectInput[] inputs();
    FormEffectOutput[] outputs();
}
