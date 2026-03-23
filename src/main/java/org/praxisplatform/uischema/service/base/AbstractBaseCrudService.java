package org.praxisplatform.uischema.service.base;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.stats.StatsEligibility;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsProperties;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.stats.service.ResolvedStatsMetric;
import org.praxisplatform.uischema.stats.service.StatsQueryExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Convenience base class that wires required components for {@link BaseCrudService} implementations
 * and applies transactional semantics to the generic CRUD read/write flows exposed by the controllers.
 *
 * @param <E>  Entity type
 * @param <D>  DTO type
 * @param <ID> Identifier type
 * @param <FD> Filter DTO type
 */
public abstract class AbstractBaseCrudService<E, D, ID, FD extends GenericFilterDTO>
        implements BaseCrudService<E, D, ID, FD> {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    private StatsQueryExecutor statsQueryExecutor;

    @Autowired(required = false)
    private StatsEligibility statsEligibility;

    @Autowired(required = false)
    private StatsProperties statsProperties;

    private final BaseCrudRepository<E, ID> repository;
    private final GenericSpecificationsBuilder<E> specificationsBuilder;
    private final Class<E> entityClass;

    protected AbstractBaseCrudService(BaseCrudRepository<E, ID> repository,
                                      GenericSpecificationsBuilder<E> specificationsBuilder,
                                      Class<E> entityClass) {
        this.repository = repository;
        this.specificationsBuilder = specificationsBuilder;
        this.entityClass = entityClass;
    }

    protected AbstractBaseCrudService(BaseCrudRepository<E, ID> repository,
                                      Class<E> entityClass) {
        this(repository, new GenericSpecificationsBuilder<>(), entityClass);
    }

    @Override
    public BaseCrudRepository<E, ID> getRepository() {
        return repository;
    }

    @Override
    public GenericSpecificationsBuilder<E> getSpecificationsBuilder() {
        return specificationsBuilder;
    }

    @Override
    public Class<E> getEntityClass() {
        return entityClass;
    }

    @Override
    @Transactional
    public E save(E entity) {
        return BaseCrudService.super.save(entity);
    }

    @Override
    @Transactional
    public <R> R saveMapped(E entity, Function<E, R> mapper) {
        E saved = BaseCrudService.super.save(entity);
        return mapper.apply(refreshManaged(saved));
    }

    @Override
    @Transactional
    public <R> SavedResult<ID, R> saveResultMapped(E entity, Function<E, R> mapper) {
        E saved = refreshManaged(BaseCrudService.super.save(entity));
        return new SavedResult<>(extractId(saved), mapper.apply(saved));
    }

    @Override
    @Transactional
    public E update(ID id, E entity) {
        return BaseCrudService.super.update(id, entity);
    }

    @Override
    @Transactional
    public <R> R updateMapped(ID id, E entity, Function<E, R> mapper) {
        E updated = refreshManaged(BaseCrudService.super.update(id, entity));
        return mapper.apply(updated);
    }

    @Override
    @Transactional
    public void deleteById(ID id) {
        BaseCrudService.super.deleteById(id);
    }

    @Override
    @Transactional
    public void deleteAllById(Iterable<ID> ids) {
        BaseCrudService.super.deleteAllById(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public <R> R findByIdMapped(ID id, Function<E, R> mapper) {
        return BaseCrudService.super.findByIdMapped(id, mapper);
    }

    @Override
    @Transactional(readOnly = true)
    public <R> List<R> findAllMapped(Function<E, R> mapper) {
        return BaseCrudService.super.findAllMapped(mapper);
    }

    @Override
    @Transactional(readOnly = true)
    public <R> List<R> findAllByIdMapped(Collection<ID> ids, Function<E, R> mapper) {
        return BaseCrudService.super.findAllByIdMapped(ids, mapper);
    }

    @Override
    @Transactional(readOnly = true)
    public <R> Page<R> filterMappedWithIncludeIds(FD filter, Pageable pageable, Collection<ID> includeIds, Function<E, R> mapper) {
        return BaseCrudService.super.filterMappedWithIncludeIds(filter, pageable, includeIds, mapper);
    }

    @Override
    @Transactional(readOnly = true)
    public <R> CursorPage<R> filterByCursorMapped(FD filter, org.springframework.data.domain.Sort sort, String after, String before, int size, Function<E, R> mapper) {
        return BaseCrudService.super.filterByCursorMapped(filter, sort, after, before, size, mapper);
    }

    @Override
    @Transactional(readOnly = true)
    public GroupByStatsResponse groupByStats(GroupByStatsRequest<FD> request) {
        StatsProperties properties = statsProperties != null ? statsProperties : StatsProperties.defaults();
        if (!properties.enabled()) {
            throw new UnsupportedOperationException("Group-by stats not implemented");
        }
        if (getGroupByStatsSupportMode() == StatsSupportMode.DISABLED) {
            throw new UnsupportedOperationException("Group-by stats not implemented");
        }
        if (statsQueryExecutor == null || statsEligibility == null) {
            throw new UnsupportedOperationException("Group-by stats not implemented");
        }

        StatsFieldDescriptor descriptor = statsEligibility.validateGroupBy(
                request,
                getStatsFieldRegistry(),
                properties.maxBuckets()
        );
        List<ResolvedStatsMetric> resolvedMetrics = resolveMetrics(
                request.effectiveMetrics(),
                "group-by"
        );
        var specification = getSpecificationsBuilder().buildSpecification(request.filter(), Pageable.unpaged());
        return statsQueryExecutor.executeGroupBy(
                entityManager,
                entityClass,
                specification.spec(),
                descriptor,
                resolvedMetrics,
                request,
                properties.maxBuckets()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<FD> request) {
        StatsProperties properties = statsProperties != null ? statsProperties : StatsProperties.defaults();
        if (!properties.enabled()) {
            throw new UnsupportedOperationException("Time-series stats not implemented");
        }
        if (getTimeSeriesStatsSupportMode() == StatsSupportMode.DISABLED) {
            throw new UnsupportedOperationException("Time-series stats not implemented");
        }
        if (statsQueryExecutor == null || statsEligibility == null) {
            throw new UnsupportedOperationException("Time-series stats not implemented");
        }

        StatsFieldDescriptor descriptor = statsEligibility.validateTimeSeries(
                request,
                getStatsFieldRegistry(),
                properties.maxSeriesPoints()
        );
        List<ResolvedStatsMetric> resolvedMetrics = resolveMetrics(
                request.effectiveMetrics(),
                "time-series"
        );
        var specification = getSpecificationsBuilder().buildSpecification(request.filter(), Pageable.unpaged());
        return statsQueryExecutor.executeTimeSeries(
                entityManager,
                entityClass,
                specification.spec(),
                descriptor,
                resolvedMetrics,
                request,
                properties.maxSeriesPoints()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public DistributionStatsResponse distributionStats(DistributionStatsRequest<FD> request) {
        StatsProperties properties = statsProperties != null ? statsProperties : StatsProperties.defaults();
        if (!properties.enabled()) {
            throw new UnsupportedOperationException("Distribution stats not implemented");
        }
        if (getDistributionStatsSupportMode() == StatsSupportMode.DISABLED) {
            throw new UnsupportedOperationException("Distribution stats not implemented");
        }
        if (statsQueryExecutor == null || statsEligibility == null) {
            throw new UnsupportedOperationException("Distribution stats not implemented");
        }

        StatsFieldDescriptor descriptor = statsEligibility.validateDistribution(
                request,
                getStatsFieldRegistry(),
                properties.maxBuckets()
        );
        StatsFieldDescriptor metricDescriptor = request.mode() == org.praxisplatform.uischema.stats.DistributionMode.TERMS
                ? statsEligibility.resolveMetricField(request.metric(), getStatsFieldRegistry(), "distribution")
                : null;
        var specification = getSpecificationsBuilder().buildSpecification(request.filter(), Pageable.unpaged());
        return statsQueryExecutor.executeDistribution(
                entityManager,
                entityClass,
                specification.spec(),
                descriptor,
                metricDescriptor,
                request,
                properties.maxBuckets()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<org.praxisplatform.uischema.dto.OptionDTO<ID>> filterOptions(FD filter, Pageable pageable) {
        return BaseCrudService.super.filterOptions(filter, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<org.praxisplatform.uischema.dto.OptionDTO<ID>> byIdsOptions(Collection<ID> ids) {
        return BaseCrudService.super.byIdsOptions(ids);
    }

    private E refreshManaged(E entity) {
        entityManager.flush();
        E managed = entityManager.contains(entity) ? entity : entityManager.merge(entity);
        entityManager.refresh(managed);
        return managed;
    }

    private List<ResolvedStatsMetric> resolveMetrics(
            List<StatsMetricRequest> metrics,
            String operationName
    ) {
        return metrics.stream()
                .map(metric -> new ResolvedStatsMetric(
                        metric,
                        statsEligibility.resolveMetricField(metric, getStatsFieldRegistry(), operationName)
                ))
                .toList();
    }
}
