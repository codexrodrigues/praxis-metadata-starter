package org.praxisplatform.uischema.option;

import org.praxisplatform.uischema.annotation.QuickFilterTone;

/**
 * Contrato tipado para {@code enum}s de opcoes publicadas automaticamente em {@code x-ui.options},
 * catalogos semanticos e politicas de selecao de {@link org.praxisplatform.uischema.options.EntityLookupDescriptor}.
 *
 * <p>Quando o {@code enum} atribuido a {@code @UISchema(optionEnum = ...)} implementa esta interface,
 * o starter serializa {@code value}, {@code label}, {@code icon} (quando nao vazio) e {@code tone}
 * no mesmo formato canonico de {@link org.praxisplatform.uischema.annotation.UiOption}.</p>
 *
 * <pre>{@code
 * public enum ContractStatus implements PraxisUiOptionEnum {
 *     DRAFT("Rascunho", "edit_note", QuickFilterTone.NEUTRAL, false, true),
 *     ACTIVE("Ativo", "check_circle", QuickFilterTone.SUCCESS, true, false),
 *     CANCELLED("Cancelado", "cancel", QuickFilterTone.DANGER, false, false);
 *     ...
 * }
 * }</pre>
 */
public interface PraxisUiOptionEnum {

    /** Valor tecnico canonico da constante ({@code Enum.name()} por padrao). */
    default String value() {
        return ((Enum<?>) this).name();
    }

    /** Rotulo humano curto exibido para a opcao na interface. */
    String label();

    /** Ligadura opcional Material Icons em {@code snake_case} minusculo. */
    default String icon() {
        return "";
    }

    /** Tom semantico visual associado a opcao. */
    default QuickFilterTone tone() {
        return QuickFilterTone.NEUTRAL;
    }

    /** Indica se este estado/valor pode ser selecionado em lookups de referencia. */
    default boolean selectableInLookup() {
        return true;
    }

    /** Indica se esta constante representa um estado inicial de fluxo de trabalho. */
    default boolean workflowInitial() {
        return false;
    }
}
