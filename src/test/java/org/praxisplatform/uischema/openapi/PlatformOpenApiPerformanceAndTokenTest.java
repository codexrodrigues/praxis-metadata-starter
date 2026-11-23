package org.praxisplatform.uischema.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.bulk.BulkExecution;
import org.praxisplatform.uischema.bulk.BulkExecutionMode;
import org.praxisplatform.uischema.bulk.BulkExecutionStatus;
import org.praxisplatform.uischema.bulk.BulkExecutionTotals;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.bulk.BulkProposal;
import org.praxisplatform.uischema.bulk.BulkProposalStatus;
import org.praxisplatform.uischema.bulk.BulkProposalTotals;
import org.praxisplatform.uischema.bulk.BulkResourceOperation;
import org.praxisplatform.uischema.bulk.BulkResourceOperationBindings;
import org.praxisplatform.uischema.bulk.BulkResourceOperations;
import org.praxisplatform.uischema.bulk.JpaResourceBulkEngine;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.configuration.OpenApiResponseGenerationAutoConfiguration;
import org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration;
import org.praxisplatform.uischema.configuration.OpenApiWriterAutoConfiguration;
import org.praxisplatform.uischema.configuration.PraxisMetadataAutoConfiguration;
import org.praxisplatform.uischema.controller.base.AbstractBulkGovernedResourceController;
import org.praxisplatform.uischema.controller.docs.DomainCatalogController;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.determination.DefaultReactiveDeterminationDefinitionRegistry;
import org.praxisplatform.uischema.determination.ReactiveDeterminationDefinition;
import org.praxisplatform.uischema.determination.ReactiveDeterminationFormMode;
import org.praxisplatform.uischema.determination.ReactiveDeterminationInputBinding;
import org.praxisplatform.uischema.determination.ReactiveDeterminationMetadataCompiler;
import org.praxisplatform.uischema.determination.ReactiveDeterminationOutputBinding;
import org.praxisplatform.uischema.determination.ReactiveDeterminationProvenance;
import org.praxisplatform.uischema.determination.ReactiveDeterminationProvenanceKind;
import org.praxisplatform.uischema.determination.ReactiveDeterminationScope;
import org.praxisplatform.uischema.determination.ReactiveDeterminationTriggerMode;
import org.praxisplatform.uischema.domain.ReactiveDeterminationCatalogSource;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.schema.ApiResourceIdentityResolver;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.praxisplatform.uischema.service.base.BaseResourceService;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.slf4j.Logger;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class PlatformOpenApiPerformanceAndTokenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_PATH = "/v3/api-docs";
    private static final String INVENTORY_PATH = "/api/items/bulk/proposals/{proposalId}";

    @ApiResource(value = "/api/items", resourceKey = "inventory.items")
    @BulkResourceOperations(
            proposalOperationId = "items.bulk.proposal",
            proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution",
            executionResultsOperationId = "items.bulk.execution-results",
            cancelOperationId = "items.bulk.cancel")
    static class BulkItemsController {
        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL)
        @GetMapping("/api/items/bulk/proposals/{proposalId}")
        public void proposal() { }

        @BulkResourceOperation(BulkResourceOperation.Role.PROPOSAL_RESULTS)
        @GetMapping("/api/items/bulk/proposals/{proposalId}/results")
        public void proposalResults() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION)
        @GetMapping("/api/items/bulk/executions/{executionId}")
        public void execution() { }

        @BulkResourceOperation(BulkResourceOperation.Role.EXECUTION_RESULTS)
        @GetMapping("/api/items/bulk/executions/{executionId}/results")
        public void executionResults() { }

        @BulkResourceOperation(BulkResourceOperation.Role.CANCEL)
        @PostMapping("/api/items/bulk/executions/{executionId}/cancel")
        public void cancel() { }
    }

    @ApiResource(value = "/api/employees", resourceKey = "hr.employees")
    static class EmployeesIdentityController {
        @GetMapping("/api/employees")
        @Operation(operationId = "listEmployees")
        public void list() { }

        @GetMapping("/api/employees/{id}/child-path-surface")
        @UiSurface(
                id = "child-path",
                title = "Child Path",
                kind = SurfaceKind.PARTIAL_FORM,
                scope = SurfaceScope.ITEM,
                relatedChildResourcePath = "/api/profiles")
        public void childByPath() { }

        @GetMapping("/api/employees/{id}/child-key-surface")
        @UiSurface(
                id = "child-key",
                title = "Child Key",
                kind = SurfaceKind.PARTIAL_FORM,
                scope = SurfaceScope.ITEM,
                relatedChildResourceKey = "explicit.child")
        public void childByKey() { }
    }

    @ApiResource(value = "/api/profiles", resourceKey = "hr.profiles")
    static class ProfilesIdentityController {
        @GetMapping("/api/profiles")
        public void list() { }
    }

    @ApiResource(value = "/api/alt-profiles", resourceKey = "hr.alt-profiles")
    static class AltProfilesIdentityController {
        @GetMapping("/api/alt-profiles")
        public void list() { }
    }

    @Test
    void groupDocumentCacheTokenAvoidsHeavyObjectRetentionAndRotatesOnPromotionAndClear() {
        ObjectNode inventoryDoc = docWithProposal("items.bulk.proposal", INVENTORY_PATH);
        ObjectNode otherDoc = minimalDoc();
        String configJson = "{\"urls\":[{\"name\":\"inventory\",\"url\":\"/v3/api-docs/inventory\"},"
                + "{\"name\":\"other\",\"url\":\"/v3/api-docs/other\"}]}";
        String inventoryJson = inventoryDoc.toString();
        String otherJson = otherDoc.toString();

        var source = new LocalMutableSource(Map.of(
                BASE_PATH, inventoryJson,
                BASE_PATH + "/inventory", inventoryJson,
                BASE_PATH + "/other", otherJson,
                BASE_PATH + "/swagger-config", configJson));

        try (var client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            var service = new CachedOpenApiDocumentService(client, MAPPER, source, true, Duration.ofSeconds(3));
            ReflectionTestUtils.setField(service, "openApiBasePath", BASE_PATH);
            source.owner = service;

            service.getDocumentForGroup("inventory");
            Object publicToken = service.groupDocumentCacheToken("inventory");
            assertThat(publicToken).isNotNull();
            assertTokenContainsNoHeavyObjects(publicToken);

            // Strict promotion creates a new entryId, rotating token in O(1) without touching JSON nodes.
            service.getDocumentForGroupStrict("inventory");
            Object strictToken = service.groupDocumentCacheToken("inventory");
            assertThat(strictToken).isNotNull().isNotEqualTo(publicToken);
            assertTokenContainsNoHeavyObjects(strictToken);

            service.clearCaches();
            assertThat(service.groupDocumentCacheToken("inventory")).isNull();
            service.getDocumentForGroup("inventory");
            Object reloadedToken = service.groupDocumentCacheToken("inventory");
            assertThat(reloadedToken).isNotNull().isNotEqualTo(strictToken);

            // Two distinct prepared candidates with byte-identical content must have distinct tokens.
            OpenApiPublicationCandidate first = service.prepareBulkOpenApiPublication(Set.of("inventory", "other"));
            OpenApiPublicationCandidate second = service.prepareBulkOpenApiPublication(Set.of("inventory", "other"));
            assertThat(first.digest()).isEqualTo(second.digest());

            AtomicReference<Object> firstPubToken = new AtomicReference<>();
            AtomicReference<Object> firstGroupToken = new AtomicReference<>();
            service.withPreparedBulkOpenApiPublication(first, () -> {
                firstPubToken.set(service.localPublishedBulkOpenApiPublicationToken());
                firstGroupToken.set(service.groupDocumentCacheToken("inventory"));
                assertThat(firstPubToken.get()).isNotNull().isNotInstanceOf(OpenApiPublicationCandidate.class);
                assertTokenContainsNoHeavyObjects(firstGroupToken.get());
                return null;
            });

            AtomicReference<Object> secondPubToken = new AtomicReference<>();
            AtomicReference<Object> secondGroupToken = new AtomicReference<>();
            service.withPreparedBulkOpenApiPublication(second, () -> {
                secondPubToken.set(service.localPublishedBulkOpenApiPublicationToken());
                secondGroupToken.set(service.groupDocumentCacheToken("inventory"));
                return null;
            });

            assertThat(secondPubToken.get()).isNotSameAs(firstPubToken.get());
            assertThat(secondGroupToken.get()).isNotEqualTo(firstGroupToken.get());
        }
    }

    @Test
    void zeroCopyStrictSummaryVerifiesMultiGroupCollisionsWithoutReFetchingAndDetectsNewCollisionsAfterClear()
            throws Exception {
        Map<RequestMappingInfo, HandlerMethod> mappings = bulkMappings();
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);
        BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(handlerMapping);
        assertThat(bindings.diagnostics()).isEmpty();

        ObjectNode inventoryDoc = docWithProposal("items.bulk.proposal", INVENTORY_PATH);
        ObjectNode otherAliasDoc = docWithProposal("items.bulk.proposal", "/api/items/bulk/proposals/{id}");
        AtomicInteger strictFetches = new AtomicInteger();

        OpenApiDocsSupport source = new OpenApiDocsSupport() {
            private final AtomicReference<Map<String, JsonNode>> docs = new AtomicReference<>(
                    Map.of("inventory", inventoryDoc, "other", otherAliasDoc));

            @Override
            public String resolveGroupFromPath(String path) {
                return "inventory";
            }

            @Override
            public JsonNode fetchOpenApiGroupDocument(RestTemplate restTemplate, String openApiBasePath,
                    String group, Logger logger) {
                strictFetches.incrementAndGet();
                return docs.get().get(group).deepCopy();
            }

            void setOtherDoc(JsonNode node) {
                docs.set(Map.of("inventory", inventoryDoc, "other", node));
            }
        };

        var service = new CachedOpenApiDocumentService(new RestTemplate(), MAPPER, source);
        ReflectionTestUtils.setField(service, "openApiBasePath", BASE_PATH);
        var resolver = new OpenApiCanonicalOperationResolver(service, handlerMapping, bindings, List.of("inventory", "other"));

        // Renamed template parameter in "other" ({id} vs {proposalId}) must validate cleanly and cache the entry/summary.
        assertThat(resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET").operationId())
                .isEqualTo("items.bulk.proposal");
        assertThat(strictFetches.get()).isEqualTo(2);

        // Second verification reads cached GroupOperationSummary without fetching or deep-copying full OpenAPI docs.
        assertThat(resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET").operationId())
                .isEqualTo("items.bulk.proposal");
        assertThat(strictFetches.get()).isEqualTo(2);

        // After clearCaches(), cross-group duplicate path, callback collision, webhook collision, and rewrite are all rejected.
        ObjectNode duplicatePathDoc = docWithProposal("items.bulk.proposal", "/api/items/bulk/proposals/{id}");
        ((ObjectNode) duplicatePathDoc.path("paths")).putObject("/api/other/collision")
                .putObject("get").put("operationId", "items.bulk.proposal");

        ObjectNode callbackCollisionDoc = docWithProposal("items.bulk.proposal", "/api/items/bulk/proposals/{id}");
        ((ObjectNode) callbackCollisionDoc.path("paths").path("/api/items/bulk/proposals/{id}").path("get"))
                .putObject("callbacks").putObject("hook").putObject("{$request.body#/url}")
                .putObject("post").put("operationId", "items.bulk.proposal");

        ObjectNode webhookCollisionDoc = docWithProposal("items.bulk.proposal", "/api/items/bulk/proposals/{id}");
        webhookCollisionDoc.putObject("webhooks").putObject("hook").putObject("post")
                .put("operationId", "items.bulk.proposal");

        ObjectNode rewrittenDoc = docWithProposal("rewritten.id", "/api/items/bulk/proposals/{id}");

        for (JsonNode badOtherDoc : List.of(duplicatePathDoc, callbackCollisionDoc, webhookCollisionDoc, rewrittenDoc)) {
            ReflectionTestUtils.invokeMethod(source, "setOtherDoc", badOtherDoc);
            service.clearCaches();
            assertThatThrownBy(() -> resolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void apiResourceIdentityResolverDirectHandlerAndIndexReflectLiveMappingChanges() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> mutableMappings = new LinkedHashMap<>();
        EmployeesIdentityController employees = new EmployeesIdentityController();
        ProfilesIdentityController profiles = new ProfilesIdentityController();

        HandlerMethod listHandler = new HandlerMethod(employees, EmployeesIdentityController.class.getMethod("list"));
        HandlerMethod childPathHandler = new HandlerMethod(
                employees, EmployeesIdentityController.class.getMethod("childByPath"));
        HandlerMethod childKeyHandler = new HandlerMethod(
                employees, EmployeesIdentityController.class.getMethod("childByKey"));

        mutableMappings.put(
                RequestMappingInfo.paths("/api/employees").methods(RequestMethod.GET).build(), listHandler);
        mutableMappings.put(
                RequestMappingInfo.paths("/api/employees/{employeeId}/child-path-surface").methods(RequestMethod.GET).build(),
                childPathHandler);
        mutableMappings.put(
                RequestMappingInfo.paths("/api/employees/{employeeId}/child-key-surface").methods(RequestMethod.GET).build(),
                childKeyHandler);
        mutableMappings.put(
                RequestMappingInfo.paths("/api/profiles").methods(RequestMethod.GET).build(),
                new HandlerMethod(profiles, ProfilesIdentityController.class.getMethod("list")));

        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenAnswer(ignored -> Map.copyOf(mutableMappings));

        var resolver = new ApiResourceIdentityResolver(handlerMapping);

        assertThat(resolver.resolveResourceKey(listHandler, "/api/employees"))
                .isEqualTo(resolver.resolveResourceKey("/api/employees", "/api/employees", "GET"))
                .contains("hr.employees");
        assertThat(resolver.resolveResourceKey(childPathHandler, "/api/employees/{id}/child-path-surface"))
                .isEqualTo(resolver.resolveResourceKey(
                        "/api/employees", "/api/employees/{id}/child-path-surface", "GET"))
                .contains("hr.profiles");
        assertThat(resolver.resolveResourceKey(childKeyHandler, "/api/employees/{id}/child-key-surface"))
                .isEqualTo(resolver.resolveResourceKey(
                        "/api/employees", "/api/employees/{id}/child-key-surface", "GET"))
                .contains("explicit.child");
        assertThat(resolver.resolveResourceKey((HandlerMethod) null, "/api/employees"))
                .isEqualTo(resolver.resolveResourceKey("/api/employees", "/api/employees/missing", "POST"))
                .contains("hr.employees");

        // Dynamic registry change invalidates the internal HandlerIndex snapshot automatically.
        AltProfilesIdentityController alt = new AltProfilesIdentityController();
        HandlerMethod altHandler = new HandlerMethod(alt, AltProfilesIdentityController.class.getMethod("list"));
        mutableMappings.put(
                RequestMappingInfo.paths("/api/alt-profiles").methods(RequestMethod.GET).build(), altHandler);
        assertThat(resolver.resolveResourceKey("/api/alt-profiles", "/api/alt-profiles", "GET"))
                .contains("hr.alt-profiles");
    }

    @Test
    void applicationOpenApiGroupIsRegisteredByDefaultAndCanBeDisabled() {
        var runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        JacksonAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        SpringDocConfiguration.class,
                        SpringDocWebMvcConfiguration.class,
                        OpenApiUiSchemaAutoConfiguration.class,
                        OpenApiResponseGenerationAutoConfiguration.class,
                        OpenApiWriterAutoConfiguration.class,
                        PraxisMetadataAutoConfiguration.class))
                .withBean(SpringDocConfigProperties.class, SpringDocConfigProperties::new);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(GroupedOpenApi.class)).containsKey("praxisMetadataApplicationOpenApi");
            assertThat(context.getBean("praxisMetadataApplicationOpenApi", GroupedOpenApi.class).getGroup())
                    .isEqualTo("application");
        });

        runner.withPropertyValues("praxis.openapi.application-group.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(GroupedOpenApi.class))
                            .doesNotContainKey("praxisMetadataApplicationOpenApi")
                            .containsKey("praxisMetadataInfraOpenApi");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void abstractBulkGovernedResourceControllerBatchesReadLinksPerResponse() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> mappings = governedBulkMappings();
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);
        BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(handlerMapping);
        assertThat(bindings.diagnostics()).isEmpty();

        ObjectNode inventoryDoc = docWithProposal("items.bulk.proposal", INVENTORY_PATH);
        ObjectNode paths = (ObjectNode) inventoryDoc.path("paths");
        paths.putObject("/api/items/bulk/proposals/{proposalId}/results")
                .putObject("get").put("operationId", "items.bulk.proposal-results");
        paths.putObject("/api/items/bulk/executions/{executionId}")
                .putObject("get").put("operationId", "items.bulk.execution");
        paths.putObject("/api/items/bulk/executions/{executionId}/results")
                .putObject("get").put("operationId", "items.bulk.execution-results");
        paths.putObject("/api/items/bulk/executions/{executionId}/cancel")
                .putObject("post").put("operationId", "items.bulk.cancel");

        OpenApiDocumentService documents = mock(OpenApiDocumentService.class);
        when(documents.withSchemaCacheReadLock(any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(documents.resolveGroupFromPath(anyString())).thenReturn("inventory");
        when(documents.getDocumentForGroupStrict("inventory")).thenReturn(inventoryDoc);

        var resolver = new OpenApiCanonicalOperationResolver(
                documents, handlerMapping, bindings, List.of("inventory"));

        UUID proposalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        Instant now = Instant.now();
        BulkProposal proposal = new BulkProposal(
                proposalId.toString(),
                new CanonicalOperationRef("inventory", "items.bulk.update", "/api/items/bulk/update", "POST"),
                BulkMode.UNIFORM_UPDATE,
                BulkExecutionMode.SYNC,
                ActionCollectionAtomicity.PER_ITEM,
                BulkProposalStatus.READY,
                now,
                now.plusSeconds(60),
                new BulkProposalTotals(1, 1, 1, 0),
                MAPPER.createObjectNode(),
                List.of(),
                List.of());
        BulkExecution execution = new BulkExecution(
                executionId.toString(),
                proposalId.toString(),
                new CanonicalOperationRef("inventory", "items.bulk.update", "/api/items/bulk/update", "POST"),
                BulkMode.UNIFORM_UPDATE,
                BulkExecutionMode.SYNC,
                ActionCollectionAtomicity.PER_ITEM,
                BulkExecutionStatus.COMPLETED,
                now,
                now,
                now,
                new BulkExecutionTotals(1, 0, 1, 0, 0, 0, 0, 0, 0),
                List.of());

        GovernedBulkController controller = new GovernedBulkController();
        ReflectionTestUtils.setField(controller, "bulkOperations", resolver);

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            ResponseEntity<RestApiResponse<BulkProposal>> proposalResponse = ReflectionTestUtils.invokeMethod(
                    controller, "proposalResponse", proposal);
            assertThat(proposalResponse.getBody().getLinks().values()).containsKeys("self", "results");
            verify(documents, times(1)).getDocumentForGroupStrict("inventory");

            ResponseEntity<RestApiResponse<BulkExecution>> executionResponse = ReflectionTestUtils.invokeMethod(
                    controller, "executionResponse", execution);
            assertThat(executionResponse.getBody().getLinks().values()).containsKeys("self", "results", "proposal");
            verify(documents, times(2)).getDocumentForGroupStrict("inventory");

            ResponseEntity<RestApiResponse<CursorPage<Object>>> resultsResponse = ReflectionTestUtils.invokeMethod(
                    controller,
                    "resultsResponse",
                    new CursorPage<>(List.of(), "next-cursor", "prev-cursor", 10),
                    "items.bulk.proposal-results",
                    Map.of("proposalId", proposalId),
                    "cur");
            assertThat(resultsResponse.getBody().getLinks().values()).containsKeys("self", "next", "prev");
            verify(documents, times(3)).getDocumentForGroupStrict("inventory");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void springAopProxiesPreserveZeroCopySummaryBatchingAndDirectHandlerIdentity() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> mappings = governedBulkMappings();
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);
        BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(handlerMapping);

        ObjectNode inventoryDoc = docWithProposal("items.bulk.proposal", INVENTORY_PATH);
        ObjectNode paths = (ObjectNode) inventoryDoc.path("paths");
        paths.putObject("/api/items/bulk/proposals/{proposalId}/results")
                .putObject("get").put("operationId", "items.bulk.proposal-results");
        paths.putObject("/api/items/bulk/executions/{executionId}")
                .putObject("get").put("operationId", "items.bulk.execution");
        paths.putObject("/api/items/bulk/executions/{executionId}/results")
                .putObject("get").put("operationId", "items.bulk.execution-results");
        paths.putObject("/api/items/bulk/executions/{executionId}/cancel")
                .putObject("post").put("operationId", "items.bulk.cancel");
        ObjectNode otherAliasDoc = docWithProposal("items.bulk.proposal", "/api/items/bulk/proposals/{id}");

        AtomicInteger strictFetches = new AtomicInteger();
        OpenApiDocsSupport source = new OpenApiDocsSupport() {
            @Override
            public String resolveGroupFromPath(String path) {
                return "inventory";
            }

            @Override
            public JsonNode fetchOpenApiGroupDocument(RestTemplate restTemplate, String openApiBasePath,
                    String group, Logger logger) {
                strictFetches.incrementAndGet();
                return ("other".equals(group) ? otherAliasDoc : inventoryDoc).deepCopy();
            }

            @Override
            public JsonNode fetchOpenApiDocument(RestTemplate restTemplate, String openApiBasePath,
                    String group, Logger logger) {
                return ("other".equals(group) ? otherAliasDoc : inventoryDoc).deepCopy();
            }
        };

        var realDocService = new CachedOpenApiDocumentService(new RestTemplate(), MAPPER, source);
        ReflectionTestUtils.setField(realDocService, "openApiBasePath", BASE_PATH);

        // Both CGLIB and JDK dynamic proxies around CachedOpenApiDocumentService keep zero-copy summary caching
        // and acquire withSchemaCacheReadLock at most once across all groups without mutating cached skeletons.
        for (boolean proxyTargetClass : new boolean[] {true, false}) {
            realDocService.clearCaches();
            strictFetches.set(0);
            AtomicInteger docAdviceCalls = new AtomicInteger();
            AtomicInteger readLockAdviceCalls = new AtomicInteger();
            ProxyFactory docProxyFactory = new ProxyFactory(realDocService);
            docProxyFactory.setProxyTargetClass(proxyTargetClass);
            if (!proxyTargetClass) {
                docProxyFactory.setInterfaces(OpenApiDocumentService.class);
            }
            docProxyFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                docAdviceCalls.incrementAndGet();
                if ("withSchemaCacheReadLock".equals(invocation.getMethod().getName())) {
                    readLockAdviceCalls.incrementAndGet();
                }
                Object result = invocation.proceed();
                if ("resolveDocumentPath".equals(invocation.getMethod().getName())
                        && invocation.getArguments().length > 0
                        && invocation.getArguments()[0] instanceof ObjectNode pathsArg) {
                    pathsArg.removeAll();
                }
                return result;
            });
            OpenApiDocumentService proxiedDocService = (OpenApiDocumentService) docProxyFactory.getProxy();

            var realResolver = new OpenApiCanonicalOperationResolver(
                    proxiedDocService, handlerMapping, bindings, List.of("inventory", "other"));
            assertThat(realResolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET").operationId())
                    .isEqualTo("items.bulk.proposal");
            assertThat(strictFetches.get()).isEqualTo(2);
            assertThat(readLockAdviceCalls.get()).isEqualTo(1);
            assertThat(realResolver.requireResourceOperation("inventory.items", "items.bulk.proposal", "GET").operationId())
                    .isEqualTo("items.bulk.proposal");
            assertThat(strictFetches.get()).isEqualTo(2);
            assertThat(docAdviceCalls.get()).isPositive();
        }

        // Proxied BatchCanonicalOperationResolver (CGLIB & JDK) keeps batching in AbstractBulkGovernedResourceController.
        OpenApiDocumentService spyDocs = mock(OpenApiDocumentService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(spyDocs.resolveGroupFromPath(anyString())).thenReturn("inventory");
        org.mockito.Mockito.doReturn(inventoryDoc).when(spyDocs).getDocumentForGroupStrict("inventory");

        for (boolean proxyTargetClass : new boolean[] {true, false}) {
            AtomicInteger resolverAdviceCalls = new AtomicInteger();
            var targetResolver = new OpenApiCanonicalOperationResolver(
                    spyDocs, handlerMapping, bindings, List.of("inventory"));
            ProxyFactory resolverProxyFactory = new ProxyFactory(targetResolver);
            resolverProxyFactory.setProxyTargetClass(proxyTargetClass);
            if (!proxyTargetClass) {
                resolverProxyFactory.setInterfaces(BatchCanonicalOperationResolver.class, CanonicalOperationResolver.class);
            }
            resolverProxyFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                resolverAdviceCalls.incrementAndGet();
                return invocation.proceed();
            });
            CanonicalOperationResolver proxiedResolver = (CanonicalOperationResolver) resolverProxyFactory.getProxy();

            GovernedBulkController controller = new GovernedBulkController();
            ReflectionTestUtils.setField(controller, "bulkOperations", proxiedResolver);
            UUID proposalId = UUID.randomUUID();
            Instant now = Instant.now();
            BulkProposal proposal = new BulkProposal(
                    proposalId.toString(),
                    new CanonicalOperationRef("inventory", "items.bulk.update", "/api/items/bulk/update", "POST"),
                    BulkMode.UNIFORM_UPDATE, BulkExecutionMode.SYNC, ActionCollectionAtomicity.PER_ITEM,
                    BulkProposalStatus.READY, now, now.plusSeconds(60),
                    new BulkProposalTotals(1, 1, 1, 0), MAPPER.createObjectNode(), List.of(), List.of());

            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            try {
                org.mockito.Mockito.clearInvocations(spyDocs);
                var response = ReflectionTestUtils.<ResponseEntity<RestApiResponse<BulkProposal>>>invokeMethod(
                        controller, "proposalResponse", proposal);
                assertThat(response.getBody().getLinks().values()).containsKeys("self", "results");
                verify(spyDocs, times(1)).getDocumentForGroupStrict("inventory");
                assertThat(resolverAdviceCalls.get()).isPositive();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }

        // Proxied ApiResourceIdentityResolver + JDK-proxied BatchCanonicalOperationResolver in ReactiveDeterminationCatalogSource.
        RequestMappingHandlerMapping detMvc = mock(RequestMappingHandlerMapping.class);
        EmployeesIdentityController empCtrl = new EmployeesIdentityController();
        HandlerMethod createEmp = new HandlerMethod(empCtrl, EmployeesIdentityController.class.getMethod("list"));
        HandlerMethod detEmp = new HandlerMethod(empCtrl, EmployeesIdentityController.class.getMethod("childByKey"));
        when(detMvc.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/employees").methods(RequestMethod.POST).build(), createEmp,
                RequestMappingInfo.paths("/api/employees/determine").methods(RequestMethod.POST).build(), detEmp));

        ObjectNode empDoc = (ObjectNode) MAPPER.readTree("""
                {
                  "openapi": "3.1.0",
                  "paths": {
                    "/api/employees": {
                      "post": {
                        "operationId": "listEmployees",
                        "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Emp"}}}}
                      }
                    },
                    "/api/employees/determine": {
                      "post": {
                        "operationId": "childByKey",
                        "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Emp"}}}},
                        "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Emp"}}}}}
                      }
                    }
                  },
                  "components": {"schemas": {"Emp": {"type": "object", "properties": {"code": {"type": "string"}, "label": {"type": "string"}}}}}
                }
                """);
        OpenApiDocumentService empDocs = mock(OpenApiDocumentService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(empDocs.resolveGroupFromPath(anyString())).thenReturn("employees");
        when(empDocs.getDocumentForGroup("employees")).thenReturn(empDoc);

        var targetDetOps = new OpenApiCanonicalOperationResolver(
                empDocs, detMvc, BulkResourceOperationBindings.empty(), List::of);
        ProxyFactory detOpsProxy = new ProxyFactory(targetDetOps);
        detOpsProxy.setProxyTargetClass(false);
        detOpsProxy.setInterfaces(BatchCanonicalOperationResolver.class, CanonicalOperationResolver.class);
        CanonicalOperationResolver jdkDetOps = (CanonicalOperationResolver) detOpsProxy.getProxy();

        AtomicInteger identityProxyCalls = new AtomicInteger();
        ProxyFactory identityProxyFactory = new ProxyFactory(new ApiResourceIdentityResolver(detMvc));
        identityProxyFactory.setProxyTargetClass(true);
        identityProxyFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            identityProxyCalls.incrementAndGet();
            return invocation.proceed();
        });
        ApiResourceIdentityResolver proxiedIdentities = (ApiResourceIdentityResolver) identityProxyFactory.getProxy();

        var registry = new DefaultReactiveDeterminationDefinitionRegistry(List.of(() -> List.of(
                new ReactiveDeterminationDefinition(
                        "empDet",
                        List.of(new ReactiveDeterminationScope("listEmployees", ReactiveDeterminationFormMode.CREATE)),
                        "childByKey",
                        ReactiveDeterminationTriggerMode.ON_CHANGE,
                        List.of("/code"),
                        List.of(new ReactiveDeterminationInputBinding("/code", "/code")),
                        List.of(new ReactiveDeterminationOutputBinding("/label", "/label")),
                        new ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST, "test", "1")))));
        var compiler = new ReactiveDeterminationMetadataCompiler(
                registry, jdkDetOps, new FilteredSchemaReferenceResolver(), empDocs);
        var sourceCatalog = new ReactiveDeterminationCatalogSource(
                registry, compiler, jdkDetOps, empDocs, proxiedIdentities,
                new FilteredSchemaReferenceResolver(), new OpenApiCanonicalCapabilityResolver(empDocs), MAPPER);

        org.mockito.Mockito.clearInvocations(detMvc);
        assertThat(proxiedIdentities.resolveResourceKey(createEmp, "/api/employees")).contains("hr.employees");
        verify(detMvc, org.mockito.Mockito.never()).getHandlerMethods();

        assertThat(sourceCatalog.find("hr.employees", null)).hasSize(1);
        // 1 MVC pass from resolveMvcOperationsByIds (0 in resolveResourceKey(HandlerMethod, ...)) + 1 in compiler.
        verify(detMvc, times(2)).getHandlerMethods();
        assertThat(identityProxyCalls.get()).isPositive();
    }

    @Test
    void customOpenApiDocumentServiceMutatingPathsSkeletonDoesNotCorruptIndex() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> mappings = governedBulkMappings();
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);
        BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(handlerMapping);

        ObjectNode inventoryDoc = docWithProposal("items.bulk.proposal", INVENTORY_PATH);
        ObjectNode otherAliasDoc = docWithProposal("items.bulk.proposal", "/api/items/bulk/proposals/{id}");

        OpenApiDocumentService mutatingService = new OpenApiDocumentService() {
            @Override
            public String resolveGroupFromPath(String path) {
                return "inventory";
            }

            @Override
            public JsonNode getDocumentForGroup(String groupName) {
                return "other".equals(groupName) ? otherAliasDoc.deepCopy() : inventoryDoc.deepCopy();
            }

            @Override
            public JsonNode getDocumentForGroupStrict(String groupName) {
                return getDocumentForGroup(groupName);
            }

            @Override
            public String resolveDocumentPath(JsonNode pathsNode, String requestedPath, String operation) {
                String resolved = OpenApiDocumentService.super.resolveDocumentPath(pathsNode, requestedPath, operation);
                if (pathsNode instanceof ObjectNode objectNode) {
                    objectNode.removeAll();
                }
                return resolved;
            }

            @Override
            public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
                return "h";
            }

            @Override
            public void clearCaches() { }
        };

        var resolver = new OpenApiCanonicalOperationResolver(
                mutatingService, handlerMapping, bindings, List.of("inventory", "other"));
        assertThat(resolver.resolveByOperationIdsForScope(List.of("items.bulk.proposal"), ref -> true))
                .containsOnlyKeys("items.bulk.proposal");
        assertThat(resolver.resolveByOperationIdsForScope(List.of("items.bulk.proposal"), ref -> true))
                .containsOnlyKeys("items.bulk.proposal");
    }

    @Test
    void twoSlotCachesPreventPreparedPublicationFromEvictingLivePublicationState() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> mappings = governedBulkMappings();
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);
        BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(handlerMapping);

        ObjectNode inventoryDoc = docWithProposal("items.bulk.proposal", INVENTORY_PATH);
        ObjectNode otherDoc = minimalDoc();
        Object livePublicationToken = new Object();
        Object preparedPublicationToken = new Object();
        Object liveGroupToken = new Object();
        Object preparedGroupToken = new Object();
        AtomicReference<Object> activePublication = new AtomicReference<>(livePublicationToken);
        AtomicReference<Object> activeGroupToken = new AtomicReference<>(liveGroupToken);
        AtomicInteger strictFetches = new AtomicInteger();

        OpenApiDocumentService alternatingService = new OpenApiDocumentService() {
            @Override
            public String resolveGroupFromPath(String path) {
                return "inventory";
            }

            @Override
            public JsonNode getDocumentForGroup(String groupName) {
                return "other".equals(groupName) ? otherDoc.deepCopy() : inventoryDoc.deepCopy();
            }

            @Override
            public JsonNode getDocumentForGroupStrict(String groupName) {
                strictFetches.incrementAndGet();
                return getDocumentForGroup(groupName);
            }

            @Override
            public Object localPublishedBulkOpenApiPublicationToken() {
                return activePublication.get();
            }

            @Override
            public Object groupDocumentCacheToken(String groupName) {
                return activeGroupToken.get();
            }

            @Override
            public String getOrComputeSchemaHash(String schemaId, Supplier<JsonNode> payloadSupplier) {
                return "h";
            }

            @Override
            public void clearCaches() { }
        };

        var resolver = new OpenApiCanonicalOperationResolver(
                alternatingService, handlerMapping, bindings, List.of("inventory", "other"));

        // Warm live OperationIndex slot (2 strict fetches: inventory + other).
        assertThat(resolver.resolveByOperationIdsForScope(List.of("items.bulk.proposal"), ref -> true))
                .containsOnlyKeys("items.bulk.proposal");
        assertThat(strictFetches.get()).isEqualTo(2);

        // Interleave prepared publication token (2 strict fetches for prepared slot).
        activePublication.set(preparedPublicationToken);
        activeGroupToken.set(preparedGroupToken);
        assertThat(resolver.resolveByOperationIdsForScope(List.of("items.bulk.proposal"), ref -> true))
                .containsOnlyKeys("items.bulk.proposal");
        assertThat(strictFetches.get()).isEqualTo(4);

        // Switching back to live publication hits secondary slot AND promotes live to primary.
        activePublication.set(livePublicationToken);
        activeGroupToken.set(liveGroupToken);
        assertThat(resolver.resolveByOperationIdsForScope(List.of("items.bulk.proposal"), ref -> true))
                .containsOnlyKeys("items.bulk.proposal");
        assertThat(strictFetches.get()).isEqualTo(4);

        // A second prepared token (P2) must evict P1 rather than the recently used live slot.
        Object secondPreparedPublicationToken = new Object();
        Object secondPreparedGroupToken = new Object();
        activePublication.set(secondPreparedPublicationToken);
        activeGroupToken.set(secondPreparedGroupToken);
        assertThat(resolver.resolveByOperationIdsForScope(List.of("items.bulk.proposal"), ref -> true))
                .containsOnlyKeys("items.bulk.proposal");
        assertThat(strictFetches.get()).isEqualTo(6);

        activePublication.set(livePublicationToken);
        activeGroupToken.set(liveGroupToken);
        assertThat(resolver.resolveByOperationIdsForScope(List.of("items.bulk.proposal"), ref -> true))
                .containsOnlyKeys("items.bulk.proposal");
        assertThat(strictFetches.get()).isEqualTo(6);

        // Verify 2-slot retention in ReactiveDeterminationCatalogSource.documentHashCache as well.
        RequestMappingHandlerMapping detMvc = mock(RequestMappingHandlerMapping.class);
        EmployeesIdentityController empCtrl = new EmployeesIdentityController();
        when(detMvc.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/employees").methods(RequestMethod.POST).build(),
                new HandlerMethod(empCtrl, EmployeesIdentityController.class.getMethod("list")),
                RequestMappingInfo.paths("/api/employees/determine").methods(RequestMethod.POST).build(),
                new HandlerMethod(empCtrl, EmployeesIdentityController.class.getMethod("childByKey"))));

        ObjectNode liveEmpDoc = (ObjectNode) MAPPER.readTree("""
                {
                  "openapi": "3.1.0",
                  "info": {"title": "live-v1", "version": "1"},
                  "paths": {
                    "/api/employees": {"post": {"operationId": "listEmployees", "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Emp"}}}}}},
                    "/api/employees/determine": {"post": {"operationId": "childByKey", "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Emp"}}}}, "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Emp"}}}}}}}
                  },
                  "components": {"schemas": {"Emp": {"type": "object", "properties": {"code": {"type": "string"}, "label": {"type": "string"}}}}}
                }
                """);
        ObjectNode preparedEmpDoc = liveEmpDoc.deepCopy();
        ((ObjectNode) preparedEmpDoc.path("info")).put("title", "prepared-v2");

        AtomicReference<JsonNode> activeDoc = new AtomicReference<>(liveEmpDoc);
        AtomicReference<Object> activeFormToken = new AtomicReference<>(liveGroupToken);
        OpenApiDocumentService empDocs = mock(OpenApiDocumentService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(empDocs.resolveGroupFromPath(anyString())).thenReturn("employees");
        when(empDocs.getDocumentForGroup("employees")).thenAnswer(call -> activeDoc.get().deepCopy());
        when(empDocs.groupDocumentCacheToken("employees")).thenAnswer(call -> activeFormToken.get());

        var ops = new OpenApiCanonicalOperationResolver(empDocs, detMvc, BulkResourceOperationBindings.empty(), List::of);
        var registry = new DefaultReactiveDeterminationDefinitionRegistry(List.of(() -> List.of(
                new ReactiveDeterminationDefinition(
                        "empDet",
                        List.of(new ReactiveDeterminationScope("listEmployees", ReactiveDeterminationFormMode.CREATE)),
                        "childByKey", ReactiveDeterminationTriggerMode.ON_CHANGE,
                        List.of("/code"),
                        List.of(new ReactiveDeterminationInputBinding("/code", "/code")),
                        List.of(new ReactiveDeterminationOutputBinding("/label", "/label")),
                        new ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST, "test", "1")))));
        var compiler = new ReactiveDeterminationMetadataCompiler(
                registry, ops, new FilteredSchemaReferenceResolver(), empDocs);
        var sourceCatalog = new ReactiveDeterminationCatalogSource(
                registry, compiler, ops, empDocs, new ApiResourceIdentityResolver(detMvc),
                new FilteredSchemaReferenceResolver(), new OpenApiCanonicalCapabilityResolver(empDocs), MAPPER);

        String liveHash = sourceCatalog.find("hr.employees", null).getFirst().sourceDocumentHash();
        activeFormToken.set(preparedGroupToken);
        activeDoc.set(preparedEmpDoc);
        String preparedHash = sourceCatalog.find("hr.employees", null).getFirst().sourceDocumentHash();
        assertThat(preparedHash).isNotEqualTo(liveHash);

        // Switching back to live token promotes it to primary in the 2-slot LRU cache.
        activeFormToken.set(liveGroupToken);
        String returnedLiveHash = sourceCatalog.find("hr.employees", null).getFirst().sourceDocumentHash();
        assertThat(returnedLiveHash).isEqualTo(liveHash);

        // Introducing a second prepared token (P2) evicts P1, preserving liveHash even when the underlying doc changes.
        ObjectNode secondPreparedEmpDoc = liveEmpDoc.deepCopy();
        ((ObjectNode) secondPreparedEmpDoc.path("info")).put("title", "prepared-v3");
        Object secondPreparedFormToken = new Object();
        activeFormToken.set(secondPreparedFormToken);
        activeDoc.set(secondPreparedEmpDoc);
        String preparedHash2 = sourceCatalog.find("hr.employees", null).getFirst().sourceDocumentHash();
        assertThat(preparedHash2).isNotEqualTo(liveHash).isNotEqualTo(preparedHash);

        activeFormToken.set(liveGroupToken);
        assertThat(sourceCatalog.find("hr.employees", null).getFirst().sourceDocumentHash()).isEqualTo(liveHash);
    }

    @Test
    void applicationGroupDisabledContractFailsFastUnlessCustomApplicationGroupExists() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        OpenApiGroupResolver groupResolver = mock(OpenApiGroupResolver.class);
        when(groupResolver.hasRegisteredGroup("application")).thenReturn(false);
        OpenApiDocsSupport docsSupport = new OpenApiDocsSupport();
        ReflectionTestUtils.setField(docsSupport, "openApiGroupResolver", groupResolver);
        ReflectionTestUtils.setField(docsSupport, "openApiInternalBaseUrl", "http://localhost");
        ReflectionTestUtils.setField(docsSupport, "applicationGroupEnabled", false);

        assertThat(docsSupport.isApplicationGroupAvailable()).isFalse();
        assertThatThrownBy(() -> docsSupport.fetchOpenApiDocument(
                restTemplate, BASE_PATH, "application", org.slf4j.LoggerFactory.getLogger(getClass())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("praxis.openapi.application-group.enabled=false");
        org.mockito.Mockito.verifyNoInteractions(restTemplate);

        DomainCatalogController controller = new DomainCatalogController();
        ReflectionTestUtils.setField(controller, "objectMapper", MAPPER);
        ReflectionTestUtils.setField(controller, "openApiDocsSupport", docsSupport);
        ReflectionTestUtils.setField(controller, "applicationGroupEnabled", false);
        ReflectionTestUtils.setField(controller, "excludedPathsRaw", "");
        ReflectionTestUtils.invokeMethod(controller, "initExcludedPaths");
        CachedOpenApiDocumentService docService = new CachedOpenApiDocumentService(restTemplate, MAPPER, docsSupport);
        ReflectionTestUtils.setField(docService, "openApiBasePath", BASE_PATH);
        ReflectionTestUtils.setField(controller, "openApiDocumentService", docService);
        ReflectionTestUtils.setField(controller, "schemaReferenceResolver", new FilteredSchemaReferenceResolver());

        assertThatThrownBy(() -> controller.getCatalog(null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> controller.getCatalog("application", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> controller.getCatalog(null, "/", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        org.mockito.Mockito.verifyNoInteractions(restTemplate);

        // Explicit group still fetches normally when application group is disabled.
        when(restTemplate.getForObject("http://localhost/v3/api-docs/inventory", JsonNode.class))
                .thenReturn(minimalDoc());
        assertThat(controller.getCatalog("inventory", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Overriding praxisMetadataApplicationOpenApi with a non-"application" group name disables "application" even if property is true.
        OpenApiGroupResolver renamedCatchAllResolver = new OpenApiGroupResolver(List.of(
                GroupedOpenApi.builder().group("custom-all").pathsToMatch("/**").build()));
        OpenApiDocsSupport docsSupportWithRenamedGroup = new OpenApiDocsSupport();
        ReflectionTestUtils.setField(docsSupportWithRenamedGroup, "openApiGroupResolver", renamedCatchAllResolver);
        ReflectionTestUtils.setField(docsSupportWithRenamedGroup, "applicationGroupEnabled", true);
        assertThat(docsSupportWithRenamedGroup.isApplicationGroupAvailable()).isFalse();

        OpenApiGroupResolver explicitAppResolver = new OpenApiGroupResolver(List.of(
                GroupedOpenApi.builder().group("application").pathsToMatch("/**").build()));
        ReflectionTestUtils.setField(docsSupportWithRenamedGroup, "openApiGroupResolver", explicitAppResolver);
        assertThat(docsSupportWithRenamedGroup.isApplicationGroupAvailable()).isTrue();

        // If host registers a custom "application" GroupedOpenApi bean, fallback becomes available again.
        when(groupResolver.hasRegisteredGroup("application")).thenReturn(true);
        when(restTemplate.getForObject("http://localhost/v3/api-docs/application", JsonNode.class))
                .thenReturn(minimalDoc());
        assertThat(docsSupport.isApplicationGroupAvailable()).isTrue();
        assertThat(docsSupport.fetchOpenApiDocument(
                restTemplate, BASE_PATH, "application", org.slf4j.LoggerFactory.getLogger(getClass()))).isNotNull();
    }

    @Test
    void reactiveDeterminationMetadataCompilerBatchesResolutionAndReusesGroupDocument() throws Exception {
        RequestMappingHandlerMapping detMvc = mock(RequestMappingHandlerMapping.class);
        EmployeesIdentityController empCtrl = new EmployeesIdentityController();
        when(detMvc.getHandlerMethods()).thenReturn(Map.of(
                RequestMappingInfo.paths("/api/employees/det-a").methods(RequestMethod.POST).build(),
                new HandlerMethod(empCtrl, EmployeesIdentityController.class.getMethod("childByPath")),
                RequestMappingInfo.paths("/api/employees/det-b").methods(RequestMethod.POST).build(),
                new HandlerMethod(empCtrl, EmployeesIdentityController.class.getMethod("childByKey"))));

        ObjectNode detDoc = (ObjectNode) MAPPER.readTree("""
                {
                  "openapi": "3.1.0",
                  "paths": {
                    "/api/employees": {
                      "post": {"operationId": "createEmployee", "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/EmpForm"}}}}}
                    },
                    "/api/employees/det-a": {
                      "post": {
                        "operationId": "childByPath",
                        "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/DetInput"}}}},
                        "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/DetOutput"}}}}}
                      }
                    },
                    "/api/employees/det-b": {
                      "post": {
                        "operationId": "childByKey",
                        "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/DetInput"}}}},
                        "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/DetOutput"}}}}}
                      }
                    }
                  },
                  "components": {"schemas": {
                    "EmpForm": {"type": "object", "properties": {
                      "a": {"type": "string"}, "b": {"type": "string"}, "c": {"type": "string"}, "d": {"type": "string"}
                    }},
                    "DetInput": {"type": "object", "properties": {"in": {"type": "string"}}},
                    "DetOutput": {"type": "object", "properties": {"out": {"type": "string"}}}
                  }}
                }
                """);

        OpenApiDocumentService docs = mock(OpenApiDocumentService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(docs.resolveGroupFromPath(anyString())).thenReturn("determinations");
        when(docs.getDocumentForGroup("determinations")).thenReturn(detDoc);
        org.mockito.Mockito.doReturn(detDoc).when(docs).getDocumentForGroupStrict("determinations");

        var opResolver = new OpenApiCanonicalOperationResolver(
                docs, detMvc, BulkResourceOperationBindings.empty(), List::of);
        var scope = List.of(new ReactiveDeterminationScope("createEmployee", ReactiveDeterminationFormMode.CREATE));
        var provenance = new ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST, "test", "1");
        var defs = List.of(
                new ReactiveDeterminationDefinition("det1", scope, "childByPath", ReactiveDeterminationTriggerMode.ON_CHANGE,
                        List.of("/a"), List.of(new ReactiveDeterminationInputBinding("/a", "/in")),
                        List.of(new ReactiveDeterminationOutputBinding("/out", "/b")), provenance),
                new ReactiveDeterminationDefinition("det2", scope, "childByKey", ReactiveDeterminationTriggerMode.ON_CHANGE,
                        List.of("/a"), List.of(new ReactiveDeterminationInputBinding("/a", "/in")),
                        List.of(new ReactiveDeterminationOutputBinding("/out", "/c")), provenance),
                new ReactiveDeterminationDefinition("det3", scope, "childByPath", ReactiveDeterminationTriggerMode.ON_CHANGE,
                        List.of("/a"), List.of(new ReactiveDeterminationInputBinding("/a", "/in")),
                        List.of(new ReactiveDeterminationOutputBinding("/out", "/d")), provenance));
        var registry = new DefaultReactiveDeterminationDefinitionRegistry(List.of(() -> defs));
        var compiler = new ReactiveDeterminationMetadataCompiler(
                registry, opResolver, new FilteredSchemaReferenceResolver(), docs);
        var formRef = new CanonicalOperationRef("determinations", "createEmployee", "/api/employees", "POST");
        JsonNode formSchema = detDoc.path("components").path("schemas").path("EmpForm");

        org.mockito.Mockito.clearInvocations(detMvc, docs);
        assertThat(compiler.compile(formRef, "request", detDoc, formSchema, false)).hasSize(3);
        verify(detMvc, times(1)).getHandlerMethods();
        verify(docs, times(1)).getDocumentForGroup("determinations");
        verify(docs, times(0)).getDocumentForGroupStrict(anyString());

        assertThat(compiler.compile(formRef, "request", detDoc, formSchema, true)).hasSize(3);
        verify(detMvc, times(2)).getHandlerMethods();
        verify(docs, times(1)).getDocumentForGroup("determinations");
        verify(docs, times(1)).getDocumentForGroupStrict("determinations");
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchResolverIsNullSafeAndDeduplicatesOperationIds() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> mappings = governedBulkMappings();
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(mappings);
        BulkResourceOperationBindings bindings = BulkResourceOperationBindings.from(handlerMapping);

        ObjectNode inventoryDoc = docWithProposal("items.bulk.proposal", INVENTORY_PATH);
        OpenApiDocumentService docs = mock(OpenApiDocumentService.class);
        when(docs.resolveGroupFromPath(anyString())).thenReturn("inventory");
        when(docs.withSchemaCacheReadLock(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(docs.getDocumentForGroupStrict("inventory")).thenReturn(inventoryDoc);

        var resolver = new OpenApiCanonicalOperationResolver(
                docs, handlerMapping, bindings, List.of("inventory"));

        assertThat(resolver.resolveByOperationId(null)).isEmpty();
        assertThat(resolver.resolveByOperationId("  ")).isEmpty();
        assertThat(resolver.resolveMvcOperationsByIds(java.util.Arrays.asList(
                "items.bulk.proposal", null, " ", "items.bulk.proposal")))
                .containsOnlyKeys("items.bulk.proposal");

        org.mockito.Mockito.clearInvocations(docs);
        assertThat(resolver.resolveByOperationIdsForScope(java.util.Arrays.asList(
                "items.bulk.proposal", null, " ", "items.bulk.proposal"), ref -> true))
                .containsOnlyKeys("items.bulk.proposal");
        verify(docs, times(1)).withSchemaCacheReadLock(any());
        verify(docs, times(1)).getDocumentForGroupStrict("inventory");
    }

    @Test
    void apiResourceIdentityResolverAvoidsInstantiatingLazyControllersAndUnwrapsJdkProxies() throws Exception {
        try (var ctx = new org.springframework.context.support.GenericApplicationContext()) {
            var lazyDef = new org.springframework.beans.factory.support.RootBeanDefinition(LazyIdentityController.class);
            lazyDef.setLazyInit(true);
            ctx.registerBeanDefinition("lazyIdentityController", lazyDef);

            ProxyFactory jdkFactory = new ProxyFactory(new JdkHierarchicalControllerImpl());
            jdkFactory.setProxyTargetClass(false);
            jdkFactory.setInterfaces(JdkHierarchicalControllerApi.class);
            ctx.registerBean("jdkHierarchicalController", JdkHierarchicalControllerApi.class,
                    () -> (JdkHierarchicalControllerApi) jdkFactory.getProxy());
            ctx.refresh();

            HandlerMethod lazyHandler = new HandlerMethod(
                    "lazyIdentityController", ctx.getBeanFactory(),
                    LazyIdentityController.class.getMethod("list"));
            HandlerMethod jdkHandler = new HandlerMethod(
                    "jdkHierarchicalController", ctx.getBeanFactory(),
                    JdkHierarchicalControllerApi.class.getMethod("list"));

            Map<RequestMappingInfo, HandlerMethod> contextMappings = Map.of(
                    RequestMappingInfo.paths("/api/lazy").methods(RequestMethod.GET).build(), lazyHandler,
                    RequestMappingInfo.paths("/api/jdk-hier").methods(RequestMethod.GET).build(), jdkHandler);
            RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping() {
                @Override
                public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
                    return contextMappings;
                }
            };
            ReflectionTestUtils.setField(handlerMapping, "applicationContext", ctx);

            var resolver = new ApiResourceIdentityResolver(handlerMapping);

            // Indexing must never instantiate @Lazy controllers.
            assertThat(resolver.resolveResourceKey("/api/lazy", "/api/lazy", "GET")).contains("hr.lazy");
            assertThat(ctx.getBeanFactory().containsSingleton("lazyIdentityController")).isFalse();
            assertThat(resolver.resolveResourceKey(lazyHandler, "/api/lazy")).contains("hr.lazy");
            assertThat(ctx.getBeanFactory().containsSingleton("lazyIdentityController")).isFalse();

            // JDK dynamic proxy unwrapping also applies to resolveHierarchy and resolveProgression.
            assertThat(resolver.resolveHierarchy("/api/jdk-hier", "GET"))
                    .isPresent()
                    .get()
                    .satisfies(meta -> assertThat(meta).containsEntry("parentField", "parentId"));
            assertThat(resolver.resolveProgression("/api/jdk-hier", "GET"))
                    .isPresent()
                    .get()
                    .satisfies(meta -> assertThat(meta).containsEntry("currentStepField", "stepOrder"));
        }
    }

    @ApiResource(value = "/api/lazy", resourceKey = "hr.lazy")
    static class LazyIdentityController {
        @GetMapping("/api/lazy")
        public void list() { }
    }

    interface JdkHierarchicalControllerApi {
        void list();
    }

    @ApiResource(value = "/api/jdk-hier", resourceKey = "hr.jdk-hier")
    @org.praxisplatform.uischema.annotation.ResourceHierarchy(parentField = "parentId")
    @org.praxisplatform.uischema.annotation.ResourceProgression(currentStepField = "stepOrder")
    static class JdkHierarchicalControllerImpl implements JdkHierarchicalControllerApi {
        @Override
        @GetMapping("/api/jdk-hier")
        public void list() { }
    }

    record BulkFilter() implements GenericFilterDTO { }

    @ApiResource(value = "/api/items", resourceKey = "inventory.items")
    @BulkResourceOperations(
            proposalOperationId = "items.bulk.proposal",
            proposalResultsOperationId = "items.bulk.proposal-results",
            executionOperationId = "items.bulk.execution",
            executionResultsOperationId = "items.bulk.execution-results",
            cancelOperationId = "items.bulk.cancel")
    static class GovernedBulkController
            extends AbstractBulkGovernedResourceController<Object, Long, BulkFilter, Object, Object> {
        @SuppressWarnings("unchecked")
        private final BaseResourceService<Object, Long, BulkFilter, Object, Object> service =
                mock(BaseResourceService.class);

        @Override
        protected BaseResourceService<Object, Long, BulkFilter, Object, Object> getService() {
            return service;
        }

        @Override
        protected Long getResponseId(Object dto) {
            return 1L;
        }

        @Override
        protected JpaResourceBulkEngine<Long, BulkFilter> getBulkEngine() {
            return null;
        }
    }

    private static Map<RequestMappingInfo, HandlerMethod> governedBulkMappings() throws Exception {
        GovernedBulkController controller = new GovernedBulkController();
        Map<RequestMappingInfo, HandlerMethod> mappings = new LinkedHashMap<>();
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/proposals/{proposalId}").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, AbstractBulkGovernedResourceController.class
                        .getMethod("bulkProposal", UUID.class)));
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/proposals/{proposalId}/results").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, AbstractBulkGovernedResourceController.class
                        .getMethod("bulkProposalResults", UUID.class, int.class, String.class)));
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/executions/{executionId}").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, AbstractBulkGovernedResourceController.class
                        .getMethod("bulkExecution", UUID.class)));
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/executions/{executionId}/results").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, AbstractBulkGovernedResourceController.class
                        .getMethod("bulkExecutionResults", UUID.class, int.class, String.class)));
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/executions/{executionId}/cancel").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, AbstractBulkGovernedResourceController.class
                        .getMethod("cancelBulkExecution", UUID.class)));
        return mappings;
    }

    private static void assertTokenContainsNoHeavyObjects(Object token) {
        assertThat(token).isNotNull();
        for (Field field : token.getClass().getDeclaredFields()) {
            assertThat(JsonNode.class.isAssignableFrom(field.getType()))
                    .as("Token field %s must not hold JsonNode", field.getName())
                    .isFalse();
            assertThat(OpenApiPublicationCandidate.class.isAssignableFrom(field.getType()))
                    .as("Token field %s must not hold OpenApiPublicationCandidate", field.getName())
                    .isFalse();
            assertThat(field.getType().getSimpleName())
                    .as("Token field %s must not hold CachedDocument", field.getName())
                    .doesNotContain("CachedDocument");
        }
    }

    private static ObjectNode docWithProposal(String proposalOpId, String proposalPath) {
        ObjectNode root = minimalDoc();
        ((ObjectNode) root.path("paths")).putObject(proposalPath).putObject("get").put("operationId", proposalOpId);
        return root;
    }

    private static ObjectNode minimalDoc() {
        ObjectNode root = MAPPER.createObjectNode().put("openapi", "3.1.0");
        root.putObject("info").put("title", "Test").put("version", "1");
        root.putObject("paths");
        return root;
    }

    private static Map<RequestMappingInfo, HandlerMethod> bulkMappings() throws Exception {
        BulkItemsController controller = new BulkItemsController();
        Map<RequestMappingInfo, HandlerMethod> mappings = new LinkedHashMap<>();
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/proposals/{proposalId}").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, BulkItemsController.class.getMethod("proposal")));
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/proposals/{proposalId}/results").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, BulkItemsController.class.getMethod("proposalResults")));
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/executions/{executionId}").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, BulkItemsController.class.getMethod("execution")));
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/executions/{executionId}/results").methods(RequestMethod.GET).build(),
                new HandlerMethod(controller, BulkItemsController.class.getMethod("executionResults")));
        mappings.put(
                RequestMappingInfo.paths("/api/items/bulk/executions/{executionId}/cancel").methods(RequestMethod.POST).build(),
                new HandlerMethod(controller, BulkItemsController.class.getMethod("cancel")));
        return mappings;
    }

    private static final class LocalMutableSource extends OpenApiDocsSupport {
        private final Map<String, String> responses;
        private CachedOpenApiDocumentService owner;

        private LocalMutableSource(Map<String, String> responses) {
            this.responses = responses;
        }

        @Override
        public String resolveGroupFromPath(String path) {
            return "inventory";
        }

        @Override
        public String localPublicationContextPath() {
            return "";
        }

        @Override
        public JsonNode fetchOpenApiDocument(RestTemplate restTemplate, String openApiBasePath,
                String group, Logger logger) {
            try {
                return MAPPER.readTree(responses.get(openApiBasePath + "/" + group));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public JsonNode fetchOpenApiGroupDocument(RestTemplate restTemplate, String openApiBasePath,
                String group, Logger logger) {
            return fetchOpenApiDocument(restTemplate, openApiBasePath, group, logger);
        }

        @Override
        public OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(RestTemplate client,
                String exactPath, Logger logger, ObjectMapper publicationMapper) {
            MockServletContext servletContext = new MockServletContext();
            servletContext.setContextPath("");
            MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", exactPath);
            request.setContextPath("");
            request.addHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER,
                    ((OpenApiInternalRestTemplate) client).producerCaptureToken(exactPath));
            assertThat(owner.consumeBulkOpenApiProducer(request)).isTrue();
            return OpenApiDocumentCapture.parse(responses.get(exactPath).getBytes(StandardCharsets.UTF_8), publicationMapper);
        }
    }
}
