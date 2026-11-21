package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * Amparo normativo estruturado associado a uma regra ou slot governado no catálogo {@code /schemas/domain}.
 *
 * @param code código curto de referência normativa (ex.: {@code CF/88 Art. 37, XVI})
 * @param title título oficial da norma ou estatuto
 * @param article dispositivo específico aplicável (artigo, inciso, parágrafo)
 * @param jurisdiction esfera normativa (ex.: {@code Federal}, {@code Estadual}, {@code Municipal})
 * @param summary síntese didática de como a norma fundamenta a regra
 * @param url referência pública opcional para consulta do texto legal
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleLegalBasisDescriptor(
        String code,
        String title,
        String article,
        String jurisdiction,
        String summary,
        String url
) {
    public RuleLegalBasisDescriptor {
        code = Objects.requireNonNull(code, "code is required").trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        title = normalizeNullable(title);
        article = normalizeNullable(article);
        jurisdiction = normalizeNullable(jurisdiction);
        summary = normalizeNullable(summary);
        url = normalizeNullable(url);
    }

    public static RuleLegalBasisDescriptor of(String code, String title, String summary) {
        return new RuleLegalBasisDescriptor(code, title, null, null, summary, null);
    }

    public static RuleLegalBasisDescriptor of(
            String code,
            String title,
            String article,
            String jurisdiction,
            String summary
    ) {
        return new RuleLegalBasisDescriptor(code, title, article, jurisdiction, summary, null);
    }

    public static RuleLegalBasisDescriptor of(
            String code,
            String title,
            String article,
            String jurisdiction,
            String summary,
            String url
    ) {
        return new RuleLegalBasisDescriptor(code, title, article, jurisdiction, summary, url);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
