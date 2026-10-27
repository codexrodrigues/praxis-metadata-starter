package org.praxisplatform.uischema.fact;

import java.lang.annotation.*;

/** Declarative fact metadata; catalog discovery never invokes the annotated method. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ResourceFacts.class)
public @interface ResourceFact {
    String key();
    String resourceKey();
    String label() default "";
    String description() default "";
    String dataType() default "string";
    String unit() default "";
    String[] parameters() default {};
    boolean deterministic() default true;
    String sourceKind() default "computed";
}
