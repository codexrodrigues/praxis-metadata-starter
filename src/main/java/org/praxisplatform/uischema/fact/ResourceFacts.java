package org.praxisplatform.uischema.fact;
import java.lang.annotation.*;
/** Container for repeatable fact declarations. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceFacts { ResourceFact[] value(); }
