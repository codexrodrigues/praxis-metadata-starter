package org.praxisplatform.uischema.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a single bounded adjustable parameter accepted by a {@link VerifiedAnalyticalRecipe}.
 *
 * <p>
 * Runtime execution in {@code POST /stats/recipes/{recipeId}} is fail-closed: only parameter
 * names declared here are accepted, and they may never override any field already fixed in
 * {@code lockedCriteria} or {@code lockedFilters}.
 * </p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface AnalyticalRecipeParameter {

    /**
     * Public parameter name accepted in the recipe execution payload (for example, {@code departmentId},
     * {@code from}, {@code to}, {@code limit}, {@code granularity}, {@code timezone}).
     */
    String name();

    /**
     * Optional target field name in the resource {@code FilterDTO}. When left blank and {@link #name()}
     * is not a reserved stats query option ({@code limit}, {@code from}, {@code to}, {@code granularity},
     * {@code timezone}, {@code fillGaps}, {@code periodPreset}, {@code periodMode}), {@link #name()} is
     * used as the target filter field name.
     */
    String filterField() default "";

    /**
     * Concise business description explaining how this parameter refines the analytical recipe.
     */
    String description() default "";

    /**
     * Canonical parameter type ({@code string}, {@code integer}, {@code number}, {@code boolean},
     * {@code date}, or {@code date-range}).
     */
    String dataType() default "string";

    /**
     * Whether callers must explicitly supply this parameter (unless a {@link #defaultValue()} is set).
     */
    boolean required() default false;

    /**
     * Optional default value serialized as string and coerced using {@link #dataType()} / target field type.
     */
    String defaultValue() default "";

    /**
     * Optional allowlist of accepted literal values. When non-empty, values outside this set are rejected.
     */
    String[] allowedValues() default {};

    /**
     * Optional inclusive lower bound formatted according to {@link #dataType()} (numeric or ISO date).
     */
    String minValue() default "";

    /**
     * Optional inclusive upper bound formatted according to {@link #dataType()} (numeric or ISO date).
     */
    String maxValue() default "";
}
