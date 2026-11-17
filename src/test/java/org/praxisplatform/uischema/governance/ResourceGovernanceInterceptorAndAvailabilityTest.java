package org.praxisplatform.uischema.governance;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.CompositeResourceOperationAvailabilityEvaluator;
import org.praxisplatform.uischema.capability.NoOpResourceOperationAvailabilityProvider;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityContext;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityProvider;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityRule;
import org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.exporting.CollectionExportField;
import org.praxisplatform.uischema.exporting.CollectionExportFormat;
import org.praxisplatform.uischema.exporting.CollectionExportRequest;
import org.praxisplatform.uischema.exporting.CollectionExportScope;
import org.praxisplatform.uischema.exporting.CsvCollectionExportEngine;
import org.praxisplatform.uischema.exporting.DefaultCollectionExportExecutor;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.service.base.ResourceFilterAccessScope;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceGovernanceInterceptorAndAvailabilityTest {

    @Test
    void governanceInterceptorsAreOrderedAndInvokeAllTenHooksAcrossReadsExportsStatsAndMutations() {
        @SuppressWarnings("unchecked")
        BaseCrudRepository<SampleEntity, Long> repository = mock(BaseCrudRepository.class);
        SampleEntity entityOne = new SampleEntity(1L, "Alice", "PUBLIC");
        SampleEntity entityTwo = new SampleEntity(2L, "Bob", "RESTRICTED");

        when(repository.findById(1L)).thenReturn(Optional.of(entityOne));
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entityOne), PageRequest.of(0, 10), 1));
        when(repository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(entityOne));
        when(repository.save(any(SampleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        List<String> events = new ArrayList<>();
        AtomicReference<MutationContext> capturedMutationContext = new AtomicReference<>();
        AtomicReference<SampleEntity> capturedPrevious = new AtomicReference<>();

        class SecondInterceptor implements ResourceGovernanceInterceptor, Ordered {
            @Override
            public int getOrder() {
                return 20;
            }

            @Override
            public <E> ResourceFilterAccessScope<E> customizeFilterAccessScope(
                    BaseResourceQueryService<?, ?, ?> service,
                    ResourceFilterAccessScope<E> baseScope
            ) {
                events.add("scope-2");
                return ResourceFilterAccessScope.restricted((root, query, cb) -> cb.conjunction());
            }

            @Override
            public <E> void verifyEntityAccess(
                    BaseResourceQueryService<?, ?, ?> service,
                    E entity,
                    AccessMode mode
            ) {
                events.add("verify-" + mode.name() + "-2");
                if (entity instanceof SampleEntity s && "RESTRICTED".equals(s.classification)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "restricted");
                }
            }

            @Override
            public <E, R> R decorateResponse(
                    BaseResourceQueryService<?, ?, ?> service,
                    E entity,
                    R dto,
                    String surfaceOrOperation
            ) {
                events.add("decorateItem-" + surfaceOrOperation + "-2");
                if (dto instanceof SampleDto d) {
                    return (R) new SampleDto(d.id, d.name + "[masked]", d.classification);
                }
                return dto;
            }

            @Override
            public <R> List<R> decorateResponses(
                    BaseResourceQueryService<?, ?, ?> service,
                    List<R> dtos,
                    String surfaceOrOperation
            ) {
                events.add("decorateBatch-" + surfaceOrOperation + "-2");
                return dtos;
            }

            @Override
            public List<CollectionExportField> filterExportFields(
                    BaseResourceQueryService<?, ?, ?> service,
                    List<CollectionExportField> fields
            ) {
                events.add("filterExportFields-2");
                return fields.stream().filter(f -> !"classification".equals(f.key())).toList();
            }

            @Override
            public void authorizeExport(
                    BaseResourceQueryService<?, ?, ?> service,
                    CollectionExportRequest<?> request
            ) {
                events.add("authorizeExport-2");
            }

            @Override
            public StatsFieldRegistry filterStatsRegistry(
                    BaseResourceQueryService<?, ?, ?> service,
                    StatsFieldRegistry registry
            ) {
                events.add("filterStats-2");
                return StatsFieldRegistry.empty();
            }

            @Override
            public <E> void beforeMutation(
                    BaseResourceQueryService<?, ?, ?> service,
                    MutationContext mutationContext,
                    E candidate,
                    E previous
            ) {
                events.add("beforeMutation-" + mutationContext.kind().name() + "-2");
                capturedMutationContext.set(mutationContext);
                capturedPrevious.set((SampleEntity) previous);
            }

            @Override
            public <E> void afterMutationBeforeCommit(
                    BaseResourceQueryService<?, ?, ?> service,
                    MutationContext mutationContext,
                    E persistedEntity
            ) {
                events.add("afterMutation-" + mutationContext.kind().name() + "-2");
            }
        }

        class FirstInterceptor implements ResourceGovernanceInterceptor, Ordered {
            @Override
            public int getOrder() {
                return 10;
            }

            @Override
            public <E> ResourceFilterAccessScope<E> customizeFilterAccessScope(
                    BaseResourceQueryService<?, ?, ?> service,
                    ResourceFilterAccessScope<E> baseScope
            ) {
                events.add("scope-1");
                return baseScope;
            }

            @Override
            public <E> void verifyEntitiesAccess(
                    BaseResourceQueryService<?, ?, ?> service,
                    Collection<E> entities,
                    AccessMode mode
            ) {
                events.add("verifyBatch-" + mode.name() + "-1");
                ResourceGovernanceInterceptor.super.verifyEntitiesAccess(service, entities, mode);
            }
        }

        SampleService service = new SampleService(repository);
        service.setResourceGovernanceInterceptors(List.of(new SecondInterceptor(), new FirstInterceptor()));
        DefaultCollectionExportExecutor exportExecutor = new DefaultCollectionExportExecutor(
                List.of(new CsvCollectionExportEngine()));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "collectionExportExecutor", exportExecutor);

        // 1. Read single item + verify order 10 then 20
        var found = service.findById(1L);
        assertThat(found.body().name).isEqualTo("Alice[masked]");
        assertThat(events).contains("verify-READ-2", "decorateItem-findById-2", "decorateBatch-findById-2");

        // 2. Filter collection checks scope-1 before scope-2 and batch verify + decorate
        events.clear();
        var page = service.filter(new SampleFilter(), PageRequest.of(0, 10), List.of());
        assertThat(page.getContent()).singleElement().extracting(dto -> dto.name).isEqualTo("Alice[masked]");
        assertThat(events.indexOf("scope-1")).isLessThan(events.indexOf("scope-2"));
        assertThat(events).contains("verifyBatch-READ-1", "decorateItem-filter-2", "decorateBatch-filter-2");

        // 3. Stats filtering hides stats when governance empties registry
        assertThat(service.getGroupByStatsSupportMode()).isEqualTo(StatsSupportMode.DISABLED);
        assertThat(events).contains("filterStats-2");

        // 4. Export hooks: filterExportFields removes classification, authorizeExport runs, READ/EXPORT verify runs
        events.clear();
        var exportResult = service.exportCollection(new CollectionExportRequest<SampleFilter>(
                "table", "sample", "/api/workforce/sample",
                CollectionExportFormat.CSV, CollectionExportScope.ALL, null,
                List.of(), new SampleFilter(), null, null, Map.of(),
                true, true, 10, null, null, null, Map.of()
        ));
        assertThat(exportResult.rowCount()).isEqualTo(1);
        assertThat(new String(exportResult.content())).doesNotContain("Classification");
        assertThat(events).contains("authorizeExport-2", "filterExportFields-2", "verifyBatch-EXPORT-1", "decorateItem-export-2");

        // 5. Mutations invoke beforeMutation & afterMutationBeforeCommit with previous snapshot
        events.clear();
        service.create(new SampleDto(null, "Carol", "PUBLIC"));
        assertThat(events).contains("beforeMutation-CREATE-2", "afterMutation-CREATE-2");

        events.clear();
        service.update(1L, new SampleDto(1L, "Alice Updated", "PUBLIC"));
        assertThat(events).contains("verify-WRITE-2", "beforeMutation-UPDATE-2", "afterMutation-UPDATE-2");
        assertThat(capturedMutationContext.get().kind()).isEqualTo(MutationKind.UPDATE);
        assertThat(capturedPrevious.get().name).isEqualTo("Alice");

        events.clear();
        service.deleteById(1L);
        assertThat(events).contains("verify-DELETE-2", "beforeMutation-DELETE-2", "afterMutation-DELETE-2");

        // 6. Access denial from verifyEntityAccess rejects restricted record
        when(repository.findById(2L)).thenReturn(Optional.of(entityTwo));
        assertThatThrownBy(() -> service.findById(2L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("restricted");
        assertThat(CursorPage.class).isNotNull();
    }

    @Test
    void compositeAvailabilityEvaluatorCombinesOrderedRulesWithAndDenyWinsSemanticsAndWiresInAutoConfig() {
        record OrderedRule(int order, ResourceOperationAvailabilityRule delegate)
                implements ResourceOperationAvailabilityRule, Ordered {
            @Override public int getOrder() { return order; }
            @Override public AvailabilityDecision evaluate(ResourceOperationAvailabilityContext context) {
                return delegate.evaluate(context);
            }
        }

        ResourceOperationAvailabilityRule ruleOne = new OrderedRule(1, ctx ->
                AvailabilityDecision.allow(Map.of("policy", "rbac", "trace", "r1")));
        ResourceOperationAvailabilityRule ruleTwo = new OrderedRule(2, ctx -> "delete".equals(ctx.operationId())
                ? AvailabilityDecision.deny("opa-denied", Map.of("policyId", "authz-del-1"))
                : AvailabilityDecision.allow(Map.of("evaluatedBy", "opa")));

        CompositeResourceOperationAvailabilityEvaluator evaluator =
                new CompositeResourceOperationAvailabilityEvaluator(List.of(ruleTwo, ruleOne));

        AvailabilityDecision allowed = evaluator.evaluate(ResourceOperationAvailabilityContext.collection(
                "workforce.employees", "/api/workforce/employees", "filter"));
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.metadata()).containsEntry("policy", "rbac").containsEntry("evaluatedBy", "opa");

        AvailabilityDecision denied = evaluator.evaluate(ResourceOperationAvailabilityContext.item(
                "workforce.employees", "/api/workforce/employees", "delete", "10"));
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo("opa-denied");
        assertThat(denied.metadata()).containsEntry("policy", "rbac").containsEntry("policyId", "authz-del-1");

        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class));

        runner.run(ctx -> assertThat(ctx.getBean(ResourceOperationAvailabilityProvider.class))
                .isInstanceOf(NoOpResourceOperationAvailabilityProvider.class));

        runner.withBean("ruleOne", ResourceOperationAvailabilityRule.class, () -> ruleOne)
                .withBean("ruleTwo", ResourceOperationAvailabilityRule.class, () -> ruleTwo)
                .run(ctx -> {
                    ResourceOperationAvailabilityProvider provider = ctx.getBean(ResourceOperationAvailabilityProvider.class);
                    assertThat(provider).isInstanceOf(CompositeResourceOperationAvailabilityEvaluator.class);
                    assertThat(provider.evaluate(ResourceOperationAvailabilityContext.item(
                            "workforce.employees", "/api/workforce/employees", "delete", "10"
                    )).allowed()).isFalse();
                });
    }

    public static class SampleEntity {
        public Long id;
        public String name;
        public String classification;

        public SampleEntity() {
        }

        public SampleEntity(Long id, String name, String classification) {
            this.id = id;
            this.name = name;
            this.classification = classification;
        }
    }

    public static class SampleDto {
        @UISchema(label = "ID", order = 1)
        public Long id;
        @UISchema(label = "Name", order = 2)
        public String name;
        @UISchema(label = "Classification", order = 3)
        public String classification;

        public SampleDto() {
        }

        public SampleDto(Long id, String name, String classification) {
            this.id = id;
            this.name = name;
            this.classification = classification;
        }
    }

    public static class SampleFilter implements GenericFilterDTO {
    }

    @ApiResource(
            value = "/api/workforce/sample",
            resourceKey = "workforce.sample",
            identity = @ResourceIdentity(keyField = "id", titleField = "name")
    )
    static class SampleService extends AbstractBaseResourceService<SampleEntity, SampleDto, Long, SampleFilter, SampleDto, SampleDto> {
        private final ResourceMapper<SampleEntity, SampleDto, SampleDto, SampleDto, Long> mapper = new ResourceMapper<>() {
            @Override
            public SampleDto toResponse(SampleEntity entity) {
                return new SampleDto(entity.id, entity.name, entity.classification);
            }

            @Override
            public SampleEntity newEntity(SampleDto dto) {
                return new SampleEntity(99L, dto.name, dto.classification);
            }

            @Override
            public void applyUpdate(SampleEntity entity, SampleDto dto) {
                entity.name = dto.name;
                entity.classification = dto.classification;
            }

            @Override
            public Long extractId(SampleEntity entity) {
                return entity.id;
            }
        };

        SampleService(BaseCrudRepository<SampleEntity, Long> repository) {
            super(repository, SampleEntity.class, "id");
        }

        @Override
        protected ResourceMapper<SampleEntity, SampleDto, SampleDto, SampleDto, Long> getResourceMapper() {
            return mapper;
        }

        @Override
        public StatsFieldRegistry getStatsFieldRegistry() {
            return StatsFieldRegistry.builder()
                    .categoricalGroupByBucket("classification", "classification")
                    .build();
        }
    }
}
