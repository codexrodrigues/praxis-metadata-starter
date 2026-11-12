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
 * static reviewers ({@link org.praxisplatform.uischema.validation.SemanticMetadataReviewer}) and
 * downstream policy compilers can reject unverified or client-overridable inputs in fail-closed flows.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ResourceFacts.class)
public @interface ResourceFact {

    /** Stable canonical key of the fact within the resource (e.g., {@code "diasAfastadoNoAno"}). */
    String key();

    /** Canonical {@code resourceKey} of the owning resource (e.g., {@code "ergon.afastamentos"}). */
    String resourceKey();

    /** Short UI/executive label (1 to 4 words, max ~35 chars, no trailing period). */
    String label() default "";

    /** Product-grade semantic description of what the fact represents in the domain. */
    String description() default "";

    /** Canonical scalar/value data type (e.g., {@code "string"}, {@code "integer"}, {@code "number"}, {@code "boolean"}, {@code "date"}). */
    String dataType() default "string";

    /** Optional measurement unit (e.g., {@code "days"}, {@code "BRL"}, {@code "%"}). */
    String unit() default "";

    /** Optional Material icon identifier for executive cards and cockpit catalogs. */
    String icon() default "";

    /** Optional semantic tone hint (e.g., {@code "info"}, {@code "warning"}, {@code "danger"}, {@code "success"}). */
    String tone() default "";

    /** Optional scalar presentation preset or format hint. */
    String valuePresentation() default "";

    /** Optional semantic thresholds associated with this indicator. */
    Threshold[] thresholds() default {};

    /** Optional parameter names required when computing parameterized facts. */
    String[] parameters() default {};

    /** Whether the fact is deterministic for the same resource state and parameters. */
    boolean deterministic() default true;

    /** Provenance kind of the fact implementation (e.g., {@code "computed"}, {@code "column"}, {@code "aggregate"}). */
    String sourceKind() default "computed";

    /**
     * Authoritative origin of the fact value. Must be one of {@code "server_computed"},
     * {@code "persisted_column"}, or {@code "verified_principal_claim"} when consumed by governed
     * decisions; {@code "client_supplied"} is rejected for authorization/business enforcement.
     */
    String authority() default "server_computed";

    /**
     * Lifecycle phase in which the fact is available ({@code "pre_write"}, {@code "post_read"},
     * {@code "query_projection"}, or {@code "request_admission"}).
     */
    String evaluationPhase() default "post_read";

    /**
     * Behavior when the fact cannot be resolved at runtime ({@code "fail_closed"},
     * {@code "not_applicable"}, or {@code "default_value"}).
     */
    String missingValuePolicy() default "fail_closed";

    /** Cardinality scope of the fact ({@code "item"} or {@code "collection"}). */
    String scope() default "item";
}
