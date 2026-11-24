package org.praxisplatform.uischema.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.intelligence.contracts.inference.InferenceRequest;
import org.praxisplatform.intelligence.contracts.inference.InferenceTestKind;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contrato canonico de request para inferencia estatistica sobre o conjunto filtrado do recurso.
 *
 * <p>
 * Combina o filtro tipado do recurso ({@code FD}) com os parametros estatisticos de
 * {@link InferenceRequest} que serao aplicados sobre os dados extraidos dentro do escopo de acesso
 * do chamador e dos campos governados em {@code StatsFieldRegistry}.
 * </p>
 *
 * @param <FD> tipo do filtro do recurso
 */
public record InferenceStatsRequest<FD extends GenericFilterDTO>(
        @Schema(description = "Filtro estruturado do recurso aplicado antes da extracao tabular e sujeito ao escopo de acesso do servidor.")
        FD filter,
        @Schema(description = "Identificador opcional de correlacao do pedido analitico; gerado automaticamente quando omitido.")
        String requestId,
        @Schema(description = "Identificador logico opcional do dataset; assume o resourceKey do recurso quando omitido.")
        String datasetKey,
        @Schema(description = "Lista nao vazia e sem duplicatas de testes estatisticos a executar sobre o recorte filtrado.")
        List<InferenceTestKind> tests,
        @Schema(description = "Nome canonico do campo categorico usado para agrupar as coortes de inferencia (opcional).")
        String groupByField,
        @Schema(description = "Nome canonico do campo que representa o valor esperado ou baseline da comparacao.")
        String expectedField,
        @Schema(description = "Nome canonico do campo que representa o valor observado no periodo alvo.")
        String observedField,
        @Schema(description = "Nome canonico do campo pre-computado de resíduo (observado menos esperado).")
        String residualField,
        @Schema(description = "Indica elegibilidade dos registros para testes forenses sobre lancamentos variaveis ou retroativos.")
        Boolean variableOrRetroactiveEligible,
        @Schema(description = "Nivel de significancia estatistica no intervalo aberto (0, 1); padrao 0.05.")
        Double alpha,
        @Schema(description = "Piso minimo opcional de materialidade financeira para relevancia pratica.")
        BigDecimal materialityThreshold,
        @Schema(description = "Limiar minimo de k-anonimato por grupo/celula; nunca inferior ao piso governado pelo servidor.")
        Integer minBucketCount,
        @Schema(description = "Semente deterministica opcional para reproducibilidade numerica.")
        Long seed,
        @Schema(description = "Data de referencia analitica opcional para rastreabilidade de proveniencia.")
        LocalDate asOfDate
) {

    /**
     * Converte este request de recurso para o contrato canonico {@link InferenceRequest},
     * preenchendo defaults seguros e preservando o limiar efetivo de k-anonimato resolvido pelo servidor.
     */
    public InferenceRequest toInferenceRequest(String canonicalResourceKey, int effectiveMinBucketCount) {
        String resolvedDatasetKey = isNonBlank(canonicalResourceKey) ? canonicalResourceKey.trim() : "";
        if (resolvedDatasetKey.isEmpty()) {
            throw new IllegalArgumentException("Canonical resource key must not be blank.");
        }
        if (isNonBlank(datasetKey) && !datasetKey.trim().equals(resolvedDatasetKey)) {
            throw new IllegalArgumentException(
                    "datasetKey must match the target resource key: " + resolvedDatasetKey);
        }
        String resolvedRequestId = isNonBlank(requestId) ? requestId.trim() : UUID.randomUUID().toString();
        double resolvedAlpha = alpha == null ? 0.05d : alpha;
        boolean eligible = Boolean.TRUE.equals(variableOrRetroactiveEligible);
        int resolvedMinBucket = Math.max(1, effectiveMinBucketCount);
        return new InferenceRequest(
                resolvedRequestId,
                resolvedDatasetKey,
                tests,
                normalizeOptional(groupByField),
                normalizeOptional(expectedField),
                normalizeOptional(observedField),
                normalizeOptional(residualField),
                eligible,
                resolvedAlpha,
                materialityThreshold,
                resolvedMinBucket,
                seed,
                asOfDate
        );
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeOptional(String value) {
        return isNonBlank(value) ? value.trim() : null;
    }
}
