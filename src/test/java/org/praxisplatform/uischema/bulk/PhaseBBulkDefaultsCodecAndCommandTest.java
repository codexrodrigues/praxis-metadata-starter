package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.QuickFilter;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewer;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.praxisplatform.uischema.concurrency.ResourceVersionScope;
import org.praxisplatform.uischema.controller.base.AbstractBulkGovernedResourceController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.governance.AccessMode;
import org.praxisplatform.uischema.governance.MutationContext;
import org.praxisplatform.uischema.governance.ResourceGovernanceInterceptor;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.id.CompositeResourceId;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhaseBBulkDefaultsCodecAndCommandTest {

    @Test
    void bulkSnapshotStorageCodecRoundTripsCompositePrimaryKeys() {
        CompositeIdSupport.registerCompositeIdType(CompositeKey.class);
        BulkIdentityCodec<String, CompositeKey> codec = BulkIdentityCodecs.composite(CompositeKey.class);
        BulkFingerprintContext context = new BulkFingerprintContext(
                "ns",
                "sub-1",
                "workforce.items",
                new CanonicalOperationRef("workforce", "workforce.items.bulk.update", "/api/workforce/items/bulk/update", "POST"),
                "rev-1",
                ActionCollectionAtomicity.PER_ITEM
        );
        BulkUniformEvaluationRequest<String, GenericFilterDTO> evalRequest = new BulkUniformEvaluationRequest<>(
                BulkExecutionMode.SYNC,
                new BulkSelection<>(BulkSelectionMode.EXPLICIT, List.of(new BulkTarget<>("42:7", "v1")), null, null),
                List.of(BulkFieldChange.set("note", TextNode.valueOf("updated")))
        );
        BulkIntentSnapshot snapshot = BulkIntentSnapshot.uniform(
                context,
                codec,
                evalRequest,
                filter -> JsonNodeFactory.instance.objectNode()
        );

        byte[] stored = BulkSnapshotStorageCodec.encode(snapshot);
        BulkIntentSnapshot restored = BulkSnapshotStorageCodec.decode(stored, snapshot.fingerprint());

        assertThat(restored.codecId()).isEqualTo(codec.codecId());
        assertThat(new String(BulkSnapshotStorageCodec.encode(restored), StandardCharsets.UTF_8))
                .contains("\"codecId\":\"composite:CompositeKey\"")
                .contains("\"value\":\"updated\"")
                .contains("\"42:7\"");
        assertThat(codec.decode("42:7")).isEqualTo(new CompositeKey(42L, 7));
    }

    @Test
    void bulkResourceOperationsDefaultsDeriveFromResourceKeyAndDiscoverProvidersWithExplicitOverridePrecedence() {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(MvcConfig.class, DefaultIdsBulkController.class);
            context.refresh();
            RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);

            BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(mapping);
            assertThat(bindings.diagnostics()).isEmpty();
            assertThat(bindings.requiresBodylessLifecycle("workforce.default-items.bulk.proposal")).isTrue();
            assertThat(bindings.requiresBodylessLifecycle("workforce.default-items.bulk.proposal-results")).isTrue();
            assertThat(bindings.requiresBodylessLifecycle("workforce.default-items.bulk.execution")).isTrue();
            assertThat(bindings.requiresBodylessLifecycle("workforce.default-items.bulk.execution-results")).isTrue();
            assertThat(bindings.requiresBodylessLifecycle("workforce.default-items.bulk.cancel")).isTrue();
            assertThat(bindings.bulkOperations())
                    .extracting(BulkOperationBinding::confirmationOperationId)
                    .containsExactlyInAnyOrder(
                            "workforce.default-items.bulk.approve",
                            "workforce.default-items.bulk.update",
                            "workforce.default-items.bulk.update-items"
                    );

            var reviewerReport = new SemanticMetadataReviewer()
                    .reviewResourceController(DefaultIdsBulkController.class);
            assertThat(reviewerReport.issues().stream()
                    .filter(issue -> issue.code().startsWith("bulk-"))
                    .toList()).isEmpty();

            javax.sql.DataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:bulk_disc;DB_CLOSE_DELAY=-1", "sa", "");
            BulkExecutionInfrastructure runtime = new BulkExecutionInfrastructure(
                    ds,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
                    "workforce-ns",
                    "dep-1",
                    new BulkExecutionRoleConfiguration(
                            "postgres", Set.of("bulk_runtime_test"), Set.of(), Set.of("bulk_control_test"))
            );
            ItemService itemService = new ItemService(mock(BaseCrudRepository.class));

            BulkOperationDescriptorProvider explicitOverride = new BulkOperationDescriptorProvider() {
                @Override public String confirmationOperationId() { return "workforce.default-items.bulk.update"; }
                @Override public String providerId() { return "custom-host-override"; }
                @Override public String providerRevision() { return "custom-v2"; }
                @Override public BulkIdentityCodec<?, ?> identityCodec() { return BulkIdentityCodecs.composite(CompositeKey.class); }
                @Override public String identitySchemaPointer() { return "/properties/selection/properties/targets/items/properties/id"; }
                @Override public BulkOperationalProfile profile() {
                    return new BulkOperationalProfile(
                            Set.of(BulkMode.UNIFORM_UPDATE),
                            Set.of(BulkExecutionMode.SYNC),
                            Set.of(BulkSelectionMode.EXPLICIT, BulkSelectionMode.QUERY),
                            50, 100_000, Duration.ofMinutes(5), Duration.ofSeconds(5)
                    );
                }
                @Override public BulkExecutionInfrastructure executionInfrastructure() { return runtime; }
            };

            List<BulkOperationDescriptorProvider> merged = AnnotationDrivenBulkDescriptorDiscovery
                    .mergeWithDiscoveredProviders(bindings, runtime, List.of(itemService), List.of(explicitOverride));

            assertThat(merged).hasSize(3);
            assertThat(merged.stream().filter(p -> p.confirmationOperationId().equals("workforce.default-items.bulk.update"))
                    .findFirst().orElseThrow().providerId()).isEqualTo("custom-host-override");

            BulkOperationDescriptorProvider discoveredItems = merged.stream()
                    .filter(p -> p.confirmationOperationId().equals("workforce.default-items.bulk.update-items"))
                    .findFirst().orElseThrow();
            assertThat(discoveredItems.identityCodec().codecId()).isEqualTo("composite:CompositeKey");
            assertThat(discoveredItems.identitySchemaPointer()).isEqualTo("/properties/items/items/properties/id");

            BulkOperationDescriptorProvider discoveredCmd = merged.stream()
                    .filter(p -> p.confirmationOperationId().equals("workforce.default-items.bulk.approve"))
                    .findFirst().orElseThrow();
            assertThat(discoveredCmd.profile().operationModes()).containsExactly(BulkMode.DOMAIN_COMMAND);
            assertThat(discoveredCmd.identitySchemaPointer()).isEqualTo("/properties/selection/properties/targets/items/properties/id");
        }
    }

    @Test
    void abstractBaseResourceServiceBulkDomainCommandAdapterEvaluatesDetachedExecutesLockedAndRunsGovernance() {
        @SuppressWarnings("unchecked")
        BaseCrudRepository<CommandEntity, Long> repo = mock(BaseCrudRepository.class);
        CommandEntity existing = new CommandEntity(10L, "PENDING", 0L);
        when(repo.findById(10L)).thenReturn(Optional.of(existing));
        when(repo.save(any(CommandEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        List<String> hooks = new ArrayList<>();
        CommandService service = new CommandService(repo);
        service.setResourceGovernanceInterceptors(List.of(new ResourceGovernanceInterceptor() {
            @Override
            public <E> void verifyEntityAccess(BaseResourceQueryService<?, ?, ?> svc, E entity, AccessMode mode) {
                hooks.add("access:" + mode.name());
            }

            @Override
            public <E> void beforeMutation(BaseResourceQueryService<?, ?, ?> svc, MutationContext ctx, E candidate, E previous) {
                hooks.add("before:" + ctx.kind().name() + ":" + ((CommandEntity) previous).status + "->" + ((CommandEntity) candidate).status);
            }

            @Override
            public <E> void afterMutationBeforeCommit(BaseResourceQueryService<?, ?, ?> svc, MutationContext ctx, E persistedEntity) {
                hooks.add("after:" + ((CommandEntity) persistedEntity).status);
            }
        }));

        ObjectMapper mapper = new ObjectMapper();
        ResourceVersionEtagService etags = new ResourceVersionEtagService("bulk-command-secret-0123456789abcd");
        String v0 = etags.create(ResourceVersionScope.GLOBAL, "workforce.commands", 10L, 0L);

        BulkDomainCommandAdapter<Long> adapter = service.bulkDomainCommandAdapter(mapper, etags, () -> ResourceVersionScope.GLOBAL);

        // Evaluate does NOT mutate persisted entity
        BulkDomainResourceAdapter.Candidate candidate = adapter.evaluate(10L, v0, mapper.createObjectNode().put("targetStatus", "APPROVED"));
        assertThat(candidate.observedVersion()).isEqualTo(v0);
        assertThat(candidate.plan().path("computedTarget").asText()).isEqualTo("APPROVED");
        assertThat(existing.status).isEqualTo("PENDING");

        // Stale version fails fail-closed before command runs
        assertThatThrownBy(() -> adapter.execute(10L, "\"stale-etag\"", candidate.plan()))
                .isInstanceOf(ResourceVersionPreconditionException.class);
        assertThat(existing.status).isEqualTo("PENDING");

        // Execute runs locked command + governance hooks
        hooks.clear();
        adapter.execute(10L, v0, candidate.plan());
        assertThat(existing.status).isEqualTo("APPROVED");
        assertThat(hooks).contains("access:WRITE", "before:DOMAIN_COMMAND:PENDING->APPROVED", "after:APPROVED");
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        CanonicalOperationResolver canonicalOperationResolver() {
            return mock(CanonicalOperationResolver.class);
        }
    }

    @Embeddable
    public static final class CompositeKey implements CompositeResourceId {
        public Long orgId;
        public Integer seq;

        public CompositeKey() {}
        public CompositeKey(Long orgId, Integer seq) { this.orgId = orgId; this.seq = seq; }

        @Override public boolean equals(Object o) {
            return o instanceof CompositeKey other && Objects.equals(orgId, other.orgId) && Objects.equals(seq, other.seq);
        }
        @Override public int hashCode() { return Objects.hash(orgId, seq); }
    }

    @Entity
    public static class ItemEntity {
        @EmbeddedId public CompositeKey id;
        public String name;
    }

    public static class ItemDto {
        public CompositeKey id;
        @BulkEditable public String name;
    }

    @ApiResource(
            value = "/api/workforce/default-items",
            resourceKey = "workforce.default-items",
            identity = @ResourceIdentity(keyField = "id", titleField = "name")
    )
    static class ItemService extends AbstractBaseResourceService<ItemEntity, ItemDto, CompositeKey, GenericFilterDTO, ItemDto, ItemDto> {
        ItemService(BaseCrudRepository<ItemEntity, CompositeKey> repository) {
            super(repository, ItemEntity.class, "id.orgId", "id.seq");
        }

        @Override
        protected ResourceMapper<ItemEntity, ItemDto, ItemDto, ItemDto, CompositeKey> getResourceMapper() {
            return null;
        }
    }

    @ApiResource(
            value = "/api/workforce/default-items",
            resourceKey = "workforce.default-items",
            identity = @ResourceIdentity(keyField = "id", titleField = "name"),
            quickFilters = {
                    @QuickFilter(id = "all", label = "Todos"),
                    @QuickFilter(id = "active", label = "Ativos")
            }
    )
    @BulkResourceOperations(updateSourceOperationId = "workforce.default-items.update")
    public static class DefaultIdsBulkController
            extends AbstractBulkGovernedResourceController<ItemDto, CompositeKey, GenericFilterDTO, ItemDto, ItemDto> {

        @Override
        protected JpaResourceBulkEngine<CompositeKey, GenericFilterDTO> getBulkEngine() {
            return null;
        }

        @Override
        protected BaseResourceService<ItemDto, CompositeKey, GenericFilterDTO, ItemDto, ItemDto> getService() {
            return null;
        }

        @Override
        protected CompositeKey getResponseId(ItemDto dto) {
            return dto.id;
        }

        @UiSurface(id = "detail", kind = SurfaceKind.VIEW, scope = SurfaceScope.ITEM, title = "Detalhes", description = "Ficha 360")
        public void detail() {}

        @Override
        @PutMapping("/{id}")
        @BulkResourceOperation(BulkResourceOperation.Role.UPDATE_SOURCE)
        @Operation(operationId = "workforce.default-items.update", summary = "Update item")
        public ResponseEntity<RestApiResponse<ItemDto>> update(@PathVariable CompositeKey id, @RequestBody ItemDto body) {
            return ResponseEntity.ok(RestApiResponse.success(body, null));
        }

        @PostMapping("/bulk/approve/evaluation")
        @Operation(operationId = "workforce.default-items.bulk.approve.evaluate", summary = "Evaluate approve")
        public ResponseEntity<RestApiResponse<BulkProposal>> evaluateApprove(@RequestBody BulkCommandEvaluationRequest<JsonNode, String, GenericFilterDTO> body) {
            return ResponseEntity.ok(RestApiResponse.success(null, null));
        }

        @PostMapping("/bulk/approve")
        @Operation(operationId = "workforce.default-items.bulk.approve", summary = "Confirm approve")
        @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "workforce.default-items.bulk.approve.evaluate", atomicity = ActionCollectionAtomicity.PER_ITEM)
        @WorkflowAction(id = "bulk-approve", scope = ActionScope.COLLECTION, title = "Approve", description = "Approve items", atomicity = ActionCollectionAtomicity.PER_ITEM)
        public ResponseEntity<RestApiResponse<BulkExecution>> confirmApprove(@RequestBody BulkConfirmationRequest body) {
            return ResponseEntity.ok(RestApiResponse.success(null, null));
        }
    }

    public static class CommandEntity {
        @Id public Long id;
        public String status;
        @Version public Long revision;
        public CommandEntity() {}
        public CommandEntity(Long id, String status, Long revision) { this.id = id; this.status = status; this.revision = revision; }
    }

    public static class CommandDto {
        public Long id;
        public String status;
        public CommandDto() {}
        public CommandDto(Long id, String status) { this.id = id; this.status = status; }
    }

    @ApiResource(value = "/api/workforce/commands", resourceKey = "workforce.commands")
    static class CommandService extends AbstractBaseResourceService<CommandEntity, CommandDto, Long, GenericFilterDTO, CommandDto, CommandDto> {
        private final ResourceMapper<CommandEntity, CommandDto, CommandDto, CommandDto, Long> mapper = new ResourceMapper<>() {
            @Override public CommandDto toResponse(CommandEntity entity) { return new CommandDto(entity.id, entity.status); }
            @Override public CommandEntity newEntity(CommandDto dto) { return new CommandEntity(dto.id, dto.status, 0L); }
            @Override public void applyUpdate(CommandEntity entity, CommandDto dto) { entity.status = dto.status; }
            @Override public Long extractId(CommandEntity entity) { return entity.id; }
        };

        CommandService(BaseCrudRepository<CommandEntity, Long> repo) {
            super(repo, CommandEntity.class);
        }

        @Override
        protected ResourceMapper<CommandEntity, CommandDto, CommandDto, CommandDto, Long> getResourceMapper() {
            return mapper;
        }

        @Override
        protected JsonNode evaluateBulkDomainCommandCandidate(Long id, CommandEntity candidate, JsonNode parameters) {
            return JsonNodeFactory.instance.objectNode()
                    .put("computedTarget", parameters.path("targetStatus").asText("APPROVED"));
        }

        @Override
        protected void executeBulkDomainCommandItem(Long id, CommandEntity existing, JsonNode evaluatedPlan) {
            existing.status = evaluatedPlan.path("computedTarget").asText();
        }
    }
}
