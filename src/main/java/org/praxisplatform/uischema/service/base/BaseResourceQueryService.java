package org.praxisplatform.uischema.service.base;

import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualResponse;
import org.praxisplatform.intelligence.contracts.inference.InferenceResponse;
import org.praxisplatform.intelligence.contracts.jobs.IntelligenceJobReceipt;
import org.praxisplatform.intelligence.contracts.simulation.StochasticSimulationResponse;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;

import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.capability.ResourceStructuralCapabilities;
import org.praxisplatform.uischema.exporting.CollectionExportCapability;
import org.praxisplatform.uischema.exporting.CollectionExportRequest;
import org.praxisplatform.uischema.exporting.CollectionExportResult;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.options.OptionSourceByIdsRequest;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.dto.CounterfactualStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.AnomalyScanStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.InferenceStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StochasticSimulationStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Boundary canonico de leitura para resources metadata-driven.
 *
 * <p>
 * Este contrato concentra query, discovery de options e stats. O lado de comando vive em
 * {@link BaseResourceCommandService}, evitando que o mesmo DTO de escrita contamine a superficie de
 * resposta do recurso. Metadados operacionais que impactam diretamente a surface canonica de
 * leitura e discovery, como sort default, dataset version, stats support e option-sources,
 * continuam expostos aqui por enquanto. Dependencias estruturais de infraestrutura, como
 * repositorio, entity class e builder de specifications, permanecem restritas a base abstrata.
 * </p>
 */
public interface BaseResourceQueryService<ResponseDTO, ID, FilterDTO extends GenericFilterDTO> {

    /** Stable semantic resource identity for service-backed availability discovery. */
    default String getResourceKey() {
        return org.praxisplatform.uischema.customfields.CustomFieldSupport.resourceKey(getClass(), getClass().getSimpleName());
    }

    /** Optional state snapshot, resolved inside the service's item-authorization boundary. */
    default Optional<org.praxisplatform.uischema.capability.ResourceStateSnapshot> findResourceStateSnapshot(ID id) {
        return Optional.empty();
    }

    /**
     * Publica o suporte estrutural executavel das operacoes opcionais do recurso.
     * Implementacoes diretas devem optar explicitamente pelas operacoes que realmente executam.
     * A porta {@link DuplicateDraftResourceCommandService} declara o opt-in de duplicacao,
     * sem exigir heranca JPA. Sobrescritas devem preservar esse sinal junto aos demais suportes.
     */
    default ResourceStructuralCapabilities getStructuralCapabilities() {
        return new ResourceStructuralCapabilities(
                false, false, false, false, false, false, false,
                this instanceof DuplicateDraftResourceCommandService<?, ?> duplicateDraftService
                        && duplicateDraftService.supportsDuplicateDraft(),
                supportsCursorPagination(),
                null, null
        );
    }

    default boolean supportsCursorPagination() {
        return false;
    }

    String getIdFieldName();

    Sort getDefaultSort();

    Optional<String> getDatasetVersion();

    default Optional<String> getOptionSourceDatasetVersion(String sourceKey) {
        return getDatasetVersion();
    }

    StatsSupportMode getGroupByStatsSupportMode();

    StatsSupportMode getTimeSeriesStatsSupportMode();

    StatsSupportMode getDistributionStatsSupportMode();

    default StatsSupportMode getInferenceStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    default StatsSupportMode getAnomalyStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    default StatsSupportMode getComparisonStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    default StatsSupportMode getSimulationStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    default StatsSupportMode getCounterfactualStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    StatsFieldRegistry getStatsFieldRegistry();

    OptionSourceRegistry getOptionSourceRegistry();

    boolean hasOptionSource(String sourceKey);

    OptionSourceDescriptor resolveOptionSource(String sourceKey);

    /**
     * Captures the response projection and its optional persisted revision from the same state.
     * Implementations must preserve their lookup/authorization boundary and must not assemble
     * this result from independent body and version queries.
     */
    ResourceRepresentationResult<ResponseDTO> findById(ID id);

    /**
     * Verifies existence and item access before contextual discovery or a child query.
     * The default preserves host-specific checks in {@link #findById(Object)};
     * implementations may omit representation mapping only when those checks are retained.
     */
    default void assertItemAccessible(ID id) {
        findById(id);
    }

    List<ResponseDTO> findAll();

    List<ResponseDTO> findAllById(Collection<ID> ids);

    Page<ResponseDTO> filter(FilterDTO filter, Pageable pageable, Collection<ID> includeIds);

    CursorPage<ResponseDTO> filterByCursor(FilterDTO filter, Sort sort, String after, String before, int size);

    OptionalLong locate(FilterDTO filter, Sort sort, ID id);

    Page<OptionDTO<ID>> filterOptions(FilterDTO filter, Pageable pageable);

    List<OptionDTO<ID>> byIdsOptions(Collection<ID> ids);

    Page<OptionDTO<Object>> filterOptionSourceOptions(
            String sourceKey,
            OptionSourceFilterRequest<FilterDTO> request,
            Pageable pageable
    );

    default Page<OptionDTO<Object>> filterOptionSourceOptions(
            String sourceKey,
            OptionSourceFilterRequest<FilterDTO> request,
            Pageable pageable,
            Object providerFilterPayload
    ) {
        return filterOptionSourceOptions(sourceKey, request, pageable);
    }

    List<OptionDTO<Object>> byIdsOptionSourceOptions(String sourceKey, Collection<Object> ids);

    default List<OptionDTO<Object>> byIdsOptionSourceOptions(
            String sourceKey,
            OptionSourceByIdsRequest<FilterDTO> request
    ) {
        return byIdsOptionSourceOptions(sourceKey, request == null ? List.of() : request.ids());
    }

    default List<OptionDTO<Object>> byIdsOptionSourceOptions(
            String sourceKey,
            OptionSourceByIdsRequest<FilterDTO> request,
            Object providerFilterPayload
    ) {
        return byIdsOptionSourceOptions(sourceKey, request);
    }

    GroupByStatsResponse groupByStats(GroupByStatsRequest<FilterDTO> request);

    TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<FilterDTO> request);

    DistributionStatsResponse distributionStats(DistributionStatsRequest<FilterDTO> request);

    ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<FilterDTO> request);

    default InferenceResponse inferenceStats(InferenceStatsRequest<FilterDTO> request) {
        throw new UnsupportedOperationException("Inference stats not implemented");
    }

    default AnomalyScanResponse anomalyScanStats(AnomalyScanStatsRequest<FilterDTO> request) {
        throw new UnsupportedOperationException("Anomaly scan stats not implemented");
    }

    default StochasticSimulationResponse stochasticSimulationStats(
            StochasticSimulationStatsRequest<FilterDTO> request
    ) {
        throw new UnsupportedOperationException("Stochastic simulation not implemented");
    }

    default IntelligenceJobReceipt submitStochasticSimulationJob(
            StochasticSimulationStatsRequest<FilterDTO> request
    ) {
        throw new UnsupportedOperationException("Asynchronous stochastic simulation not implemented");
    }

    default CounterfactualResponse counterfactualStats(CounterfactualStatsRequest<FilterDTO> request) {
        throw new UnsupportedOperationException("Counterfactual search not implemented");
    }

    default IntelligenceJobReceipt submitCounterfactualJob(CounterfactualStatsRequest<FilterDTO> request) {
        throw new UnsupportedOperationException("Asynchronous counterfactual search not implemented");
    }

    default boolean supportsCollectionExport() {
        return false;
    }

    default Optional<CollectionExportCapability> getCollectionExportCapability() {
        return Optional.empty();
    }

    default CollectionExportResult exportCollection(CollectionExportRequest<FilterDTO> request) {
        throw new UnsupportedOperationException("Collection export not implemented");
    }

    /**
     * Enforces caller, tenant, or domain read scope in memory before opening a persistence transaction
     * for {@link #readContextualSurfaceSummary(Object, org.praxisplatform.uischema.surface.ContextualSurfaceSummaryProjector.ProjectionPlan, java.util.Set)}.
     *
     * <p>Implementations must remain pure security/request-context checks without JDBC/JPA access so
     * unauthorized cross-tenant or out-of-scope requests fail closed before acquiring a pooled connection
     * or switching tenant schemas.</p>
     */
    default void assertContextualSurfaceReadScope(String surfaceId) {
    }
    /**
     * Reads and projects the canonical contextual Quick View summary for an item surface.
     */
    default java.util.Map<String, Object> readContextualSurfaceSummary(
            ID id,
            org.praxisplatform.uischema.surface.ContextualSurfaceSummaryProjector.ProjectionPlan plan,
            java.util.Set<String> principalAuthorities) {
        ResourceRepresentationResult<ResponseDTO> result = findById(id);
        return sanitizeContextualSurfaceSummary(
                id,
                result == null ? null : result.body(),
                plan,
                principalAuthorities);
    }

    /**
     * Applies host-level field sanitization or privacy redaction before contextual summary serialization.
     */
    default java.util.Map<String, Object> sanitizeContextualSurfaceSummary(
            ID id,
            ResponseDTO dto,
            org.praxisplatform.uischema.surface.ContextualSurfaceSummaryProjector.ProjectionPlan plan,
            java.util.Set<String> principalAuthorities) {
        return plan.project(dto, principalAuthorities, null);
    }
}
