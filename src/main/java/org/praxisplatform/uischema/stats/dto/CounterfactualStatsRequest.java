package org.praxisplatform.uischema.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualDirection;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualLeverSpec;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualRequest;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contrato canonico de request para busca contrafactual esparsa validada por regras de dominio
 * ({@code /stats/counterfactual} e {@code /stats/counterfactual/jobs}).
 *
 * @param <FD> tipo do filtro do recurso
 */
public record CounterfactualStatsRequest<FD extends GenericFilterDTO>(
        @Schema(description = "Filtro estruturado aplicado para validacao de escopo de acesso do recurso antes da busca contrafactual.")
        FD filter,
        @Schema(description = "Identificador opcional de correlacao da busca contrafactual; gerado automaticamente quando omitido.")
        String requestId,
        @Schema(description = "Identificador logico opcional do dataset; deve coincidir com o resourceKey canonico quando informado.")
        String datasetKey,
        @Schema(description = "Chave da metrica-alvo cuja trajetoria ou limite deseja ser alcancado.")
        String targetMetricKey,
        @Schema(description = "Direcao da meta contrafactual em relacao ao limite informado (DECREASE_TO_TARGET, INCREASE_TO_TARGET ou REACH_BAND).")
        CounterfactualDirection direction,
        @Schema(description = "Valor corrente de linha de base da metrica-alvo.")
        BigDecimal baselineValue,
        @Schema(description = "Valor ou piso alvo da metrica apos os ajustes propostos.")
        BigDecimal targetValue,
        @Schema(description = "Limite superior da banda-alvo, obrigatorio apenas quando direction for REACH_BAND.")
        BigDecimal targetUpperBound,
        @Schema(description = "Lista nao vazia de alavancas de decisao mutaveis e seus limites parametricos.")
        List<CounterfactualLeverSpec> mutableLevers,
        @Schema(description = "Quantidade maxima de propostas contrafactuais validadas retornadas; padrao 5.")
        Integer maxCandidates,
        @Schema(description = "Numero maximo de alavancas alteradas simultaneamente por candidato; padrao 3.")
        Integer maxChangedLevers,
        @Schema(description = "Semente deterministica opcional para reproducibilidade numerica; padrao 42.")
        Long seed,
        @Schema(description = "Data de referencia analitica opcional para validacao normativa e proveniencia.")
        LocalDate asOfDate
) {

    /**
     * Converte este request de recurso para o contrato canonico {@link CounterfactualRequest},
     * aplicando defaults seguros e validando campos obrigatorios e coerencia de {@code datasetKey}.
     */
    public CounterfactualRequest toCounterfactualRequest(String canonicalResourceKey) {
        String resolvedDatasetKey = isNonBlank(canonicalResourceKey) ? canonicalResourceKey.trim() : "";
        if (resolvedDatasetKey.isEmpty()) {
            throw new IllegalArgumentException("Canonical resource key must not be blank.");
        }
        if (isNonBlank(datasetKey) && !datasetKey.trim().equals(resolvedDatasetKey)) {
            throw new IllegalArgumentException(
                    "datasetKey must match the target resource key: " + resolvedDatasetKey);
        }
        if (!isNonBlank(targetMetricKey)) {
            throw new IllegalArgumentException("targetMetricKey must not be blank.");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null.");
        }
        if (baselineValue == null) {
            throw new IllegalArgumentException("baselineValue must not be null.");
        }
        if (targetValue == null) {
            throw new IllegalArgumentException("targetValue must not be null.");
        }
        if (mutableLevers == null || mutableLevers.isEmpty()) {
            throw new IllegalArgumentException("At least one mutable lever specification is required.");
        }
        for (CounterfactualLeverSpec lever : mutableLevers) {
            if (lever == null) {
                throw new IllegalArgumentException("mutableLevers must not contain null entries.");
            }
        }
        String resolvedRequestId = isNonBlank(requestId) ? requestId.trim() : UUID.randomUUID().toString();
        int resolvedMaxCandidates = maxCandidates != null ? maxCandidates : 5;
        int resolvedMaxChangedLevers = maxChangedLevers != null ? maxChangedLevers : 3;
        long resolvedSeed = seed != null ? seed : 42L;

        return new CounterfactualRequest(
                resolvedRequestId,
                resolvedDatasetKey,
                targetMetricKey.trim(),
                direction,
                baselineValue,
                targetValue,
                targetUpperBound,
                mutableLevers,
                resolvedMaxCandidates,
                resolvedMaxChangedLevers,
                resolvedSeed,
                asOfDate
        );
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
