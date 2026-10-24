package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.stats.StatsDimensionExtraction;

import java.util.Locale;

/**
 * Especificacao canonica de uma dimensao de agrupamento em {@code group-by} (1D ou multidimensional).
 *
 * @param field campo canonico publicado pelo {@code StatsFieldRegistry}
 * @param extraction extracao/transformacao opcional aplicada sobre o campo (default {@link StatsDimensionExtraction#NONE})
 * @param alias identificador opcional da coordenada no resultado; obrigatorio para desambiguar quando o mesmo campo participa com a mesma extracao
 */
public record StatsDimensionRequest(
        String field,
        StatsDimensionExtraction extraction,
        String alias
) {
    public StatsDimensionRequest(String field, StatsDimensionExtraction extraction) {
        this(field, extraction, null);
    }

    public StatsDimensionRequest(String field) {
        this(field, StatsDimensionExtraction.NONE, null);
    }

    public StatsDimensionExtraction effectiveExtraction() {
        return extraction == null ? StatsDimensionExtraction.NONE : extraction;
    }

    public String effectiveAlias() {
        if (alias != null && !alias.isBlank()) {
            return alias.trim();
        }
        if (field == null || field.isBlank()) {
            return "";
        }
        StatsDimensionExtraction ext = effectiveExtraction();
        return ext == StatsDimensionExtraction.NONE
                ? field.trim()
                : field.trim() + "_" + ext.name().toLowerCase(Locale.ROOT);
    }
}
