package org.praxisplatform.uischema.fact;

import org.praxisplatform.uischema.annotation.Threshold;
import java.lang.annotation.*;

/**
 * Declares a deterministic domain fact or computed indicator published by a Praxis resource in
 * {@code /schemas/domain} and inspectable by both the business rules track
 * ({@code decisionKind = BUSINESS} via {@code praxis-rules-engine}) and the authorization track
 * ({@code decisionKind = AUTHORIZATION} via {@code praxis-authz-*}).
 *
 * <p>Catalog discovery is strictly reflective and never invokes the annotated method. Every declared
 * fact must make its authority, evaluation phase, missing-value behavior, and scope explicit so that
 * static reviewers ({@link org.praxisplatform.uischema.authoring.SemanticMetadataReviewer}) and
 * downstream policy compilers can reject unverified or client-overridable inputs in fail-closed flows.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ResourceFacts.class)
public @interface ResourceFact {

    /** Stable canonical key of the fact within the resource (e.g., {@code "diasAfastadoNoAno"}). */
    String key();

    /**
     * Canonical {@code resourceKey} of the owning resource (e.g., {@code "ergon.afastamentos"}).
     * When omitted or blank on a type/method owned by an {@code @ApiResource} controller (or its
     * resolved {@code ResponseDTO}), {@link AnnotationResourceFactCatalogProvider} inherits
     * {@code @ApiResource.resourceKey()} automatically.
     */
    String resourceKey() default "";

    /** Short UI/executive label (1 to 4 words, max ~35 chars, no trailing period). */
    String label() default "";

    /** Product-grade semantic description of what the fact represents in the domain. */
    String description() default "";

    /**
     * Canonical scalar/value data type matching {@link ResourceFactDataType}
     * ({@code "string"}, {@code "number"}, {@code "integer"}, {@code "decimal"}, {@code "currency"},
     * {@code "percentage"}, {@code "boolean"}, {@code "date"}, {@code "datetime"}, {@code "enum"},
     * {@code "object"}, or {@code "array"}).
     */
    String dataType() default "string";

    /** Optional measurement unit (e.g., {@code "days"}, {@code "BRL"}, {@code "%"}). */
    String unit() default "";

    /** Optional Material icon identifier in lowercase {@code snake_case} for executive cards and cockpit catalogs. */
    String icon() default "";

    /** Optional semantic tone hint ({@code "neutral"}, {@code "info"}, {@code "success"}, {@code "warning"}, {@code "danger"}, {@code "primary"}, or {@code "secondary"}). */
    String tone() default "";

    /** Optional scalar presentation preset or format hint. */
    String valuePresentation() default "";

    /** Optional semantic thresholds associated with this indicator. */
    Threshold[] thresholds() default {};

    /** Optional parameter names required when computing parameterized facts. */
    String[] parameters() default {};

    /** Whether the fact is deterministic for the same resource state and parameters. */
    boolean deterministic() default true;

    /**
     * Provenance kind of the fact implementation matching {@link ResourceFactSourceKind}
     * ({@code "computed"}, {@code "field"}, {@code "property"}, {@code "column"}, {@code "expression"},
     * {@code "aggregate"}, {@code "context"}, or {@code "grant"}).
     */
    String sourceKind() default "computed";

    /**
     * Authoritative origin of the fact value matching {@link ResourceFactAuthority}. Must be one of
     * {@code "server_computed"}, {@code "persisted_state"}, {@code "authenticated_context"}, or
     * {@code "governed_grant"} as enforced by {@link ResourceFactDescriptor}; raw client inputs
     * and unknown authorities are rejected fail-closed.
     */
    String authority() default "server_computed";

    /**
     * Lifecycle phase in which the fact is available matching {@link ResourceFactEvaluationPhase}
     * ({@code "pre_authorize"}, {@code "post_read"}, {@code "pre_write"}, {@code "post_write"},
     * or {@code "query_projection"}).
     */
    String evaluationPhase() default "post_read";

    /**
     * Fail-closed behavior when the fact cannot be resolved at runtime matching
     * {@link ResourceFactMissingValuePolicy} ({@code "fail_closed"}, {@code "deny"},
     * {@code "explicit_null"}, or {@code "error"}).
     */
    String missingValuePolicy() default "fail_closed";

    /**
     * Cardinality scope of the fact matching {@link ResourceFactScope}
     * ({@code "item"}, {@code "collection"}, {@code "tenant"}, or {@code "principal"}).
     */
    String scope() default "item";
}
