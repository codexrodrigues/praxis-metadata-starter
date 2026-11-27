package org.praxisplatform.uischema.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.intelligence.contracts.simulation.DependenceMethod;
import org.praxisplatform.intelligence.contracts.simulation.FactorCorrelation;
import org.praxisplatform.intelligence.contracts.simulation.SamplingStrategy;
import org.praxisplatform.intelligence.contracts.simulation.SimulationModelPoint;
import org.praxisplatform.intelligence.contracts.simulation.SimulationThresholdSpec;
import org.praxisplatform.intelligence.contracts.simulation.StochasticFactorSpec;
import org.praxisplatform.intelligence.contracts.simulation.StochasticSimulationRequest;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contrato canonico de request para simulacao estocastica em duas camadas sobre o conjunto
 * filtrado do recurso ({@code /stats/simulation} e {@code /stats/simulation/jobs}).
 *
 * @param <FD> tipo do filtro do recurso
 */
public record StochasticSimulationStatsRequest<FD extends GenericFilterDTO>(
        @Schema(description = "Filtro estruturado do recurso aplicado antes da extracao ou construcao dos model points, sujeito ao escopo de acesso do servidor.")
        FD filter,
        @Schema(description = "Identificador opcional de correlacao da simulacao; gerado automaticamente quando omitido.")
        String requestId,
        @Schema(description = "Identificador logico opcional do dataset; deve coincidir com o resourceKey canonico quando informado.")
        String datasetKey,
        @Schema(description = "Quantidade de cenarios estocasticos a gerar entre 100 e 100.000; padrao 10.000.")
        Integer scenarioCount,
        @Schema(description = "Horizonte de projecao em periodos discretos (1 a 120); padrao 12.")
        Integer horizonPeriods,
        @Schema(description = "Semente deterministica opcional para reproducibilidade numerica; padrao 42.")
        Long seed,
        @Schema(description = "Data de referencia analitica opcional para rastreabilidade de proveniencia.")
        LocalDate asOfDate,
        @Schema(description = "Estrategia de amostragem estocastica (QUASI_MONTE_CARLO_SOBOL ou PSEUDO_RANDOM_PCG64); padrao QUASI_MONTE_CARLO_SOBOL.")
        SamplingStrategy samplingStrategy,
        @Schema(description = "Metodo de correlacao entre fatores estocasticos (ex.: IMAN_CONOVER ou INDEPENDENT); padrao IMAN_CONOVER.")
        DependenceMethod dependenceMethod,
        @Schema(description = "Pontos de modelo agregados opcionalmente fornecidos quando o servico nao os derivar do escopo filtrado.")
        List<SimulationModelPoint> modelPoints,
        @Schema(description = "Especificacoes marginais dos fatores de risco exogenos da simulacao.")
        List<StochasticFactorSpec> stochasticFactors,
        @Schema(description = "Correlacoes de Spearman especificadas entre pares distintos de fatores estocasticos.")
        List<FactorCorrelation> correlations,
        @Schema(description = "Limiares quantitativos avaliados para probabilidade de ruptura, janela segura de corte e cauda condicional.")
        List<SimulationThresholdSpec> thresholds,
        @Schema(description = "Quantis ordenados no intervalo aberto (0, 1) reportados para a serie fan chart e distribuicao terminal.")
        List<Double> quantiles,
        @Schema(description = "Quantis-ancora no intervalo aberto (0, 1) extraidos para revalidacao exata em BigDecimal.")
        List<Double> anchorQuantiles,
        @Schema(description = "Limiar minimo de k-anonimato por ponto de modelo; nunca inferior ao piso governado pelo servidor.")
        Integer minBucketCount
) {

    /**
     * Converte este request de recurso para o contrato canonico {@link StochasticSimulationRequest},
     * aplicando defaults seguros, validando coerencia de {@code datasetKey} e preservando o piso
     * efetivo de k-anonimato resolvido pelo servidor.
     */
    public StochasticSimulationRequest toStochasticSimulationRequest(
            String canonicalResourceKey,
            int effectiveMinBucketCount,
            List<SimulationModelPoint> resolvedModelPoints
    ) {
        String resolvedDatasetKey = isNonBlank(canonicalResourceKey) ? canonicalResourceKey.trim() : "";
        if (resolvedDatasetKey.isEmpty()) {
            throw new IllegalArgumentException("Canonical resource key must not be blank.");
        }
        if (isNonBlank(datasetKey) && !datasetKey.trim().equals(resolvedDatasetKey)) {
            throw new IllegalArgumentException(
                    "datasetKey must match the target resource key: " + resolvedDatasetKey);
        }
        List<SimulationModelPoint> effectiveModelPoints =
                resolvedModelPoints != null ? resolvedModelPoints : modelPoints;
        if (effectiveModelPoints == null || effectiveModelPoints.isEmpty()) {
            throw new IllegalArgumentException("At least one simulation model point is required.");
        }
        assertNoNullElements(effectiveModelPoints, "modelPoints");
        if (stochasticFactors == null || stochasticFactors.isEmpty()) {
            throw new IllegalArgumentException("At least one stochastic factor specification is required.");
        }
        assertNoNullElements(stochasticFactors, "stochasticFactors");
        assertNoNullElements(correlations, "correlations");
        assertNoNullElements(thresholds, "thresholds");
        assertNoNullElements(quantiles, "quantiles");
        assertNoNullElements(anchorQuantiles, "anchorQuantiles");
        String resolvedRequestId = isNonBlank(requestId) ? requestId.trim() : UUID.randomUUID().toString();
        int resolvedScenarioCount = scenarioCount != null ? scenarioCount : 10_000;
        int resolvedHorizonPeriods = horizonPeriods != null ? horizonPeriods : 12;
        long resolvedSeed = seed != null ? seed : 42L;
        SamplingStrategy resolvedSampling = samplingStrategy != null ? samplingStrategy : SamplingStrategy.QUASI_MONTE_CARLO_SOBOL;
        DependenceMethod resolvedDependence = dependenceMethod != null ? dependenceMethod : DependenceMethod.IMAN_CONOVER;
        int resolvedMinBucket = Math.max(1, effectiveMinBucketCount);

        return new StochasticSimulationRequest(
                resolvedRequestId,
                resolvedDatasetKey,
                resolvedScenarioCount,
                resolvedHorizonPeriods,
                resolvedSeed,
                asOfDate,
                resolvedSampling,
                resolvedDependence,
                effectiveModelPoints,
                stochasticFactors,
                correlations,
                thresholds,
                quantiles,
                anchorQuantiles,
                resolvedMinBucket
        );
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void assertNoNullElements(List<?> values, String fieldName) {
        if (values == null) {
            return;
        }
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException(fieldName + " must not contain null entries.");
            }
        }
    }
}
