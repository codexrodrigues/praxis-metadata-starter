package org.praxisplatform.uischema.service.base;

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
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
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

    /**
     * Publica o suporte estrutural executavel das operacoes opcionais da hierarquia de query.
     * Implementacoes diretas devem optar explicitamente pelas operacoes que realmente executam.
     */
    default ResourceStructuralCapabilities getStructuralCapabilities() {
        return ResourceStructuralCapabilities.unsupported();
    }

    String getIdFieldName();

    Sort getDefaultSort();

    Optional<String> getDatasetVersion();

    /**
     * Versao persistida de um item, quando este recurso opta pelo contrato de concorrencia de
     * registro. A ausencia preserva recursos que ainda nao publicam ETag de item.
     */
    default OptionalLong getResourceVersion(ID id) {
        return OptionalLong.empty();
    }

    default Optional<String> getOptionSourceDatasetVersion(String sourceKey) {
        return getDatasetVersion();
    }

    StatsSupportMode getGroupByStatsSupportMode();

    StatsSupportMode getTimeSeriesStatsSupportMode();

    StatsSupportMode getDistributionStatsSupportMode();

    default StatsSupportMode getComparisonStatsSupportMode() {
        return StatsSupportMode.DISABLED;
    }

    StatsFieldRegistry getStatsFieldRegistry();

    OptionSourceRegistry getOptionSourceRegistry();

    boolean hasOptionSource(String sourceKey);

    OptionSourceDescriptor resolveOptionSource(String sourceKey);

    ResponseDTO findById(ID id);

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

    default boolean supportsCollectionExport() {
        return false;
    }

    default Optional<CollectionExportCapability> getCollectionExportCapability() {
        return Optional.empty();
    }

    default CollectionExportResult exportCollection(CollectionExportRequest<FilterDTO> request) {
        throw new UnsupportedOperationException("Collection export not implemented");
    }
}
