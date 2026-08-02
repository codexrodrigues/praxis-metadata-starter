package org.praxisplatform.uischema.annotation;

import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionInteractionMode;
import org.praxisplatform.uischema.action.ActionOutcomeMode;
import org.praxisplatform.uischema.action.ActionRequirement;
import org.praxisplatform.uischema.action.ActionResourceVersionTransport;
import org.praxisplatform.uischema.action.ActionRiskLevel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma operacao HTTP real como action de workflow ou comando de negocio.
 *
 * <p>
 * A anotacao nao define payload inline nem cria dispatcher generico. Ela apenas sinaliza que a
 * operacao deve aparecer no catalogo semantico de actions, sempre por referencia a endpoint,
 * request schema e response schema canonicos.
 * </p>
 *
 * <p>
 * Use esta anotacao quando a operacao representa um comando explicito de negocio, como
 * {@code approve}, {@code cancel}, {@code submit} ou {@code reopen}. Se a necessidade for
 * apenas discovery de uma experiencia visual ou de uma view especializada, a anotacao correta e
 * {@link UiSurface}.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkflowAction {

    /**
     * Identificador local da action dentro do recurso.
     */
    String id();

    /**
     * Titulo principal consumido pela UX e por clientes documentais.
     */
    String title();

    /**
     * Descricao opcional da action.
     */
    String description() default "";

    /**
     * Escopo semantico da action, como colecao ou item especifico.
     */
    ActionScope scope() default ActionScope.ITEM;

    /**
     * Ordem estavel para listagem.
     */
    int order() default 0;

    /**
     * Mensagem opcional de sucesso para UIs e clientes documentais.
     */
    String successMessage() default "";

    /**
     * Tags opcionais de organizacao semantica e navegacao documental.
     */
    String[] tags() default {};

    /**
     * Authorities/roles canonicamente exigidas para a action.
     */
    String[] requiredAuthorities() default {};

    /**
     * Estados canonicos do recurso em que a action pode ficar disponivel.
     */
    String[] allowedStates() default {};

    /** Interaction that runtimes should materialize before execution. */
    ActionInteractionMode interactionMode() default ActionInteractionMode.FORM;

    /** Public risk classification used by governed UX and authoring. */
    ActionRiskLevel riskLevel() default ActionRiskLevel.MEDIUM;

    /** Whether the user must explicitly confirm the command. */
    boolean confirmationRequired() default false;

    /** Whether the business transition has a declared inverse operation. */
    boolean reversible() default false;

    /** Requirement for the standard Idempotency-Key command header. */
    ActionRequirement idempotencyKey() default ActionRequirement.NONE;

    /** Requirement for the standard X-Correlation-ID command header. */
    ActionRequirement correlationId() default ActionRequirement.NONE;

    /** Requirement for the expected persisted resource version. */
    ActionRequirement resourceVersion() default ActionRequirement.NONE;

    /** Canonical transport for the expected persisted resource version. */
    ActionResourceVersionTransport resourceVersionTransport()
            default ActionResourceVersionTransport.NONE;

    /** Response-row field that exposes the persisted version used by If-Match or selection maps. */
    String resourceVersionField() default "";

    /** Request field that receives selected record identifiers for collection commands. */
    String selectionIdsField() default "";

    /** Request field that receives expected versions keyed by selected identifier. */
    String selectionVersionsField() default "";

    /** Maximum number of selected records accepted by the command; zero means unspecified. */
    int maxSelection() default 0;

    /** Whether the result represents one outcome or outcomes per selected item. */
    ActionOutcomeMode outcomeMode() default ActionOutcomeMode.SINGLE;

    /** Transaction semantics of a collection command. */
    ActionCollectionAtomicity atomicity() default ActionCollectionAtomicity.NOT_APPLICABLE;

    /** Whether the item projection becomes stale after success. */
    boolean refreshItem() default false;

    /** Whether the collection projection becomes stale after success. */
    boolean refreshCollection() default true;

    /** Whether the action catalog becomes stale after success. */
    boolean refreshActions() default true;

    /** Whether the capability snapshot becomes stale after success. */
    boolean refreshCapabilities() default true;

    /** Additional canonical resource keys whose projections become stale after success. */
    String[] invalidatesResourceKeys() default {};
}
