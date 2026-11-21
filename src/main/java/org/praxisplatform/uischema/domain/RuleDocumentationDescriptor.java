package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/**
 * Documentação didática humana ("de humano para humano") de um conjunto de regras, slot ou regra governada.
 *
 * @param title título legível para analistas e gestores de negócio
 * @param summary explicação direta do que a regra verifica
 * @param whenApplies condições e contexto em que a regra é avaliada
 * @param rationale justificativa de negócio ou proteção jurídica da regra
 * @param legalBasis lista estruturada de dispositivos legais/normativos aplicáveis
 * @param customizationGuidance orientação sobre como o órgão/tenant pode parametrizar ou estender a regra no Studio
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record RuleDocumentationDescriptor(
        String title,
        String summary,
        String whenApplies,
        String rationale,
        List<RuleLegalBasisDescriptor> legalBasis,
        String customizationGuidance
) {
    public RuleDocumentationDescriptor {
        title = normalizeNullable(title);
        summary = normalizeNullable(summary);
        whenApplies = normalizeNullable(whenApplies);
        rationale = normalizeNullable(rationale);
        legalBasis = (legalBasis == null || legalBasis.isEmpty())
                ? List.of()
                : legalBasis.stream().filter(Objects::nonNull).toList();
        customizationGuidance = normalizeNullable(customizationGuidance);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return title == null
                && summary == null
                && whenApplies == null
                && rationale == null
                && legalBasis.isEmpty()
                && customizationGuidance == null;
    }

    /**
     * Combina esta documentação base com uma sobrescrita (ex.: customização de tenant),
     * preservando os campos base não sobrescritos.
     */
    public RuleDocumentationDescriptor mergeWith(RuleDocumentationDescriptor override) {
        if (override == null || override.isEmpty()) {
            return this;
        }
        if (this.isEmpty()) {
            return override;
        }
        return new RuleDocumentationDescriptor(
                override.title != null ? override.title : this.title,
                override.summary != null ? override.summary : this.summary,
                override.whenApplies != null ? override.whenApplies : this.whenApplies,
                override.rationale != null ? override.rationale : this.rationale,
                !override.legalBasis.isEmpty() ? override.legalBasis : this.legalBasis,
                override.customizationGuidance != null ? override.customizationGuidance : this.customizationGuidance
        );
    }

    public static RuleDocumentationDescriptor of(
            String title,
            String summary,
            String whenApplies,
            String rationale,
            List<RuleLegalBasisDescriptor> legalBasis,
            String customizationGuidance
    ) {
        return new RuleDocumentationDescriptor(title, summary, whenApplies, rationale, legalBasis, customizationGuidance);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
