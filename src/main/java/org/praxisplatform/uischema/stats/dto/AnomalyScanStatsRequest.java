package org.praxisplatform.uischema.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyColumnMapping;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyMethod;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanRequest;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Contrato canonico de request para varredura estatistica de anomalias sobre o conjunto filtrado
 * do recurso.
 *
 * <p>
 * Combina o filtro tipado do recurso ({@code FD}) com os metodos de deteccao, mapeamento de
 * colunas e limiares robustos de {@link AnomalyScanRequest}, aplicando o escopo de acesso do
 * servidor e a pseudonimizacao de identidades de entidade pela fronteira de streaming.
 * </p>
 *
 * @param <FD> tipo do filtro do recurso
 */
public record AnomalyScanStatsRequest<FD extends GenericFilterDTO>(
        @Schema(description = "Filtro estruturado do recurso aplicado antes da extracao tabular e sujeito ao escopo de acesso do servidor.")
        FD filter,
        @Schema(description = "Identificador opcional de correlacao da varredura; gerado automaticamente quando omitido.")
        String requestId,
        @Schema(description = "Identificador logico opcional do dataset; assume o resourceKey do recurso quando omitido.")
        String datasetKey,
        @Schema(description = "Conjunto nao vazio de metodos de deteccao de anomalias a executar.")
        Set<AnomalyMethod> methods,
        @Schema(description = "Mapeamento explicito de papeis semanticos de coluna do frame tabular; utiliza AnomalyColumnMapping.canonical() quando omitido.")
        AnomalyColumnMapping columnMapping,
        @Schema(description = "Nivel de significancia estatistica no intervalo aberto (0, 1) para controle BH FDR; padrao 0.05.")
        Double alpha,
        @Schema(description = "Piso minimo opcional de materialidade financeira para elevar achados a alerta ou critico.")
        BigDecimal materialityThreshold,
        @Schema(description = "Limiar positivo de escore robusto (ex.: MAD z-score) para sinalizacao individual; padrao 3.5.")
        Double madThreshold,
        @Schema(description = "Tamanho minimo de coorte exigido para avaliacao individual robusta; padrao 15.")
        Integer minCohortSize,
        @Schema(description = "Tamanho minimo de grupo exigido para avaliacao coletiva de deslocamento pareado; padrao 20.")
        Integer minGroupSize,
        @Schema(description = "Habilita auditoria forense de primeiro digito de Nigrini-Benford quando houver coluna elegivel; habilitado automaticamente quando omitido e methods contem NIGRINI_BENFORD, caso contrario false.")
        Boolean enableBenford,
        @Schema(description = "Amostra minima de lancamentos positivos elegiveis exigida pelo teste de Benford; padrao 500.")
        Integer minBenfordSamples,
        @Schema(description = "Limiar minimo de k-anonimato por grupo/coorte; nunca inferior ao piso governado pelo servidor.")
        Integer minBucketCount,
        @Schema(description = "Limite maximo de achados retornados pela varredura; padrao 200.")
        Integer maxFindings,
        @Schema(description = "Semente deterministica opcional para reproducibilidade numerica.")
        Long seed,
        @Schema(description = "Data de referencia analitica opcional para rastreabilidade de proveniencia.")
        LocalDate asOfDate
) {

    /**
     * Converte este request de recurso para o contrato canonico {@link AnomalyScanRequest},
     * preenchendo defaults seguros e preservando o limiar efetivo de k-anonimato resolvido pelo servidor.
     */
    public AnomalyScanRequest toAnomalyScanRequest(String canonicalResourceKey, int effectiveMinBucketCount) {
        String resolvedDatasetKey = isNonBlank(canonicalResourceKey) ? canonicalResourceKey.trim() : "";
        if (resolvedDatasetKey.isEmpty()) {
            throw new IllegalArgumentException("Canonical resource key must not be blank.");
        }
        if (isNonBlank(datasetKey) && !datasetKey.trim().equals(resolvedDatasetKey)) {
            throw new IllegalArgumentException(
                    "datasetKey must match the target resource key: " + resolvedDatasetKey);
        }
        String resolvedRequestId = isNonBlank(requestId) ? requestId.trim() : UUID.randomUUID().toString();
        AnomalyColumnMapping resolvedMapping = columnMapping != null ? columnMapping : AnomalyColumnMapping.canonical();
        double resolvedAlpha = alpha == null ? 0.05d : alpha;
        double resolvedMad = madThreshold == null ? 3.5d : madThreshold;
        int resolvedMinCohort = minCohortSize == null ? 15 : minCohortSize;
        int resolvedMinGroup = minGroupSize == null ? 20 : minGroupSize;
        boolean resolvedBenford = enableBenford != null
                ? enableBenford
                : (methods != null && methods.contains(AnomalyMethod.NIGRINI_BENFORD));
        int resolvedMinBenfordSamples = minBenfordSamples == null ? 500 : minBenfordSamples;
        int resolvedMinBucket = Math.max(1, effectiveMinBucketCount);
        int resolvedMaxFindings = maxFindings == null ? 200 : maxFindings;
        return new AnomalyScanRequest(
                resolvedRequestId,
                resolvedDatasetKey,
                methods,
                resolvedMapping,
                resolvedAlpha,
                materialityThreshold,
                resolvedMad,
                resolvedMinCohort,
                resolvedMinGroup,
                resolvedBenford,
                resolvedMinBenfordSamples,
                resolvedMinBucket,
                resolvedMaxFindings,
                seed,
                asOfDate
        );
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
