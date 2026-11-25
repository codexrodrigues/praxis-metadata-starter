package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.stats.RollingWindowUnit;

/**
 * Janela movel declarativa resolvida no servidor no fuso horario efetivo da consulta,
 * evitando deslocamentos de relogio do navegador em series historicas.
 *
 * @param unit unidade temporal da janela ({@code DAYS}, {@code WEEKS}, {@code MONTHS})
 * @param count quantidade de unidades na janela ({@code 1..120})
 */
public record RollingWindowRequest(
        RollingWindowUnit unit,
        int count
) {
    public RollingWindowRequest {
        if (unit == null) {
            throw new IllegalArgumentException("Rolling window unit is required.");
        }
        if (count < 1 || count > 120) {
            throw new IllegalArgumentException("Rolling window count must be between 1 and 120.");
        }
    }
}
