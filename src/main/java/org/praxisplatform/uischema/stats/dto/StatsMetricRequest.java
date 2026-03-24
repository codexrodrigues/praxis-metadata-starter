package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.stats.StatsMetric;

/**
 * Define uma metrica agregada solicitada pelos endpoints estatisticos.
 *
 * <p>
 * O contrato separa a operacao agregada ({@code COUNT}, {@code SUM}, etc.) do campo alvo e de um
 * alias opcional. Isso permite que a mesma superficie suporte metrica unica e multi-metrica sem
 * introduzir formatos paralelos de payload.
 * </p>
 *
 * @param operation operacao agregada desejada
 * @param field campo numerico alvo quando a operacao exigir um campo explicito
 * @param alias nome opcional e estavel para exposicao na resposta
 */
public record StatsMetricRequest(
        StatsMetric operation,
        String field,
        String alias
) {
    /**
     * Construtor de compatibilidade para requests sem alias explicito.
     */
    public StatsMetricRequest(StatsMetric operation, String field) {
        this(operation, field, null);
    }

    /**
     * Resolve o alias efetivo da metrica.
     *
     * <p>
     * A precedencia e: {@code alias} explicito, depois {@code field}, e por fim o nome da
     * {@code operation}. Isso permite rotulos consistentes em respostas e contratos derivados.
     * </p>
     *
     * @return alias efetivo ou {@code null} quando a metrica estiver incompleta
     */
    public String effectiveAlias() {
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        if (field != null && !field.isBlank()) {
            return field;
        }
        return operation != null ? operation.name() : null;
    }
}
