package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.HikariDataSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.*;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.*;
import org.praxisplatform.uischema.annotation.*;
import org.praxisplatform.uischema.action.*;
import org.praxisplatform.uischema.bulk.p3fixture.DeclarativeBulkItem;
import org.praxisplatform.uischema.concurrency.*;
import org.praxisplatform.uischema.controller.base.AbstractBulkGovernedResourceController;
import org.praxisplatform.uischema.e2e.fixture.E2eFixtureApplication;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.openapi.*;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.*;

import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP + Springdoc + PostgreSQL/JPA + protected proposal/receipt flow, isolated from any host. */
@SpringBootTest(classes = E2eFixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "springdoc.cache.disabled=true")
@ActiveProfiles("e2e-h2") @DirtiesContext
@Import({BulkDeclarativeJpaHttpTest.ItemController.class, BulkDeclarativeJpaHttpTest.Config.class})
class BulkDeclarativeJpaHttpTest {
    static final String RESOURCE = "p3.items", UNIFORM = RESOURCE + ".bulk.update", ITEMS = RESOURCE + ".bulk.update-items", COMMAND = "p3.command";
    static final String PATH = "/p3-items", NAMESPACE = "p3-test";
    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired Store store;
    @Autowired BulkOperationLifecycle lifecycle;
    @Autowired BulkOperationIdentityDiscovery discovery;
    @Autowired CanonicalOperationResolver operations;
    @Autowired ObjectMapper mapper;
    @Autowired jakarta.servlet.ServletContext servletContext;

    @BeforeEach void prepare() throws Exception {
        if (!store.installed) {
            var identities = discovery.discover(NAMESPACE);
            assertEquals(Set.of(UNIFORM, ITEMS, COMMAND), identities.stream().map(BulkOperationControlIdentity::confirmationOperationId).collect(java.util.stream.Collectors.toSet()));
            BulkExecutionMigrator.migrateWithOperations(store.admin, Map.of(NAMESPACE, "deployment-test"), identities);
            BulkPostgresTestSupport.grantRuntimeRole(store.admin, "bulk_runtime_test");
            BulkPostgresTestSupport.grantControlRole(store.admin, "bulk_control_test");
            store.installed = true;
            withRequest(() -> {
                for (var identity : identities) lifecycle.publish(identity, 0);
            });
        }
        store.sql.update("delete from bulk_p3_item");
        store.sql.update("insert into bulk_p3_item(id,revision,tenant,name,note) values (1,0,1,'First','old'),(2,0,1,'Second','old'),(3,0,2,'Other','private')");
        store.afterUpdates = 0; store.isolations.clear(); store.deny = false; store.failAfterUpdate = false;
        store.conflictId = null; store.technicalId = null; store.readDenied = false;
    }

    @Test void fullUniformFlowPreservesDetachedEvaluationAndCommitsExactlyOneDurableReceipt() {
        var proposal = evaluate("/bulk/update/evaluation", uniform("1", "Renamed"));
        assertEquals("READY", proposal.path("status").asText());
        assertEquals("First", name(1)); assertEquals(0, store.afterUpdates);
        assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ, store.isolations.getFirst());
        var id = proposal.path("proposalId").asText();
        assertEquals(200, rest.getForEntity(url("/bulk/proposals/" + id), JsonNode.class).getStatusCode().value());
        assertEquals(200, rest.getForEntity(url("/bulk/proposals/" + id + "/results?size=1"), JsonNode.class).getStatusCode().value());
        var execution = confirm("/bulk/update", id, "uniform-one");
        assertEquals("COMPLETED", execution.path("status").asText(), execution.toString());
        assertEquals("Renamed", name(1)); assertEquals(1, store.afterUpdates);
        var executionId = execution.path("executionId").asText();
        assertEquals(200, rest.getForEntity(url("/bulk/executions/" + executionId), JsonNode.class).getStatusCode().value());
        var results = rest.getForEntity(url("/bulk/executions/" + executionId + "/results"), JsonNode.class);
        assertEquals(200, results.getStatusCode().value());
        var replay = confirm("/bulk/update", id, "uniform-one");
        assertEquals(executionId, replay.path("executionId").asText()); assertEquals(1, store.afterUpdates);
        assertEquals(1, store.sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Integer.class, UUID.fromString(executionId)));
        var cancelled = post("/bulk/executions/" + executionId + "/cancel", null, null);
        assertEquals(200, cancelled.getStatusCode().value());
    }

    @Test void perItemClearAndDomainCommandUseTheirOwnTypedProposals() {
        var changes = mapper.createObjectNode().put("executionMode", "SYNC");
        var item = changes.putArray("items").addObject().put("id", "1").put("expectedVersion", etag(1, 0));
        item.putArray("changes").addObject().put("field", "note").put("operator", "CLEAR");
        var proposal = evaluate("/bulk/update-items/evaluation", changes);
        confirm("/bulk/update-items", proposal.path("proposalId").asText(), "clear-one");
        assertNull(store.sql.queryForObject("select note from bulk_p3_item where id=1", String.class));
        var command = selection("2"); command.putObject("parameters").put("name", "Command changed");
        var commandProposal = evaluate("/bulk/rename/evaluation", command);
        assertEquals("DOMAIN_COMMAND", commandProposal.path("mode").asText());
        confirm("/bulk/rename", commandProposal.path("proposalId").asText(), "command-one");
        assertEquals("Command changed", name(2));
    }

    @Test void invalidProtectedAndOutOfScopeCandidatesStayBlockedAndDoNotDirtyJpa() {
        for (var body : List.of(uniform("1", ""), uniform("1", "forbidden"), uniform("3", "Cross tenant"))) {
            var proposal = evaluate("/bulk/update/evaluation", body);
            assertEquals("BLOCKED", proposal.path("status").asText(), proposal.toString());
        }
        var protectedChange = uniform("1", "Allowed");
        ((com.fasterxml.jackson.databind.node.ObjectNode) protectedChange.path("changes").get(0)).put("field", "tenant");
        var blocked = evaluate("/bulk/update/evaluation", protectedChange);
        assertEquals("BLOCKED", blocked.path("status").asText());
        assertEquals("First", name(1)); assertEquals("Other", name(3)); assertEquals(0, store.afterUpdates);
    }

    @Test void staleVersionAndRevokedAdmissionNeverMutateTheDomain() {
        var proposal = evaluate("/bulk/update/evaluation", uniform("1", "Changed"));
        store.sql.update("update bulk_p3_item set revision=revision+1 where id=1");
        var result = confirm("/bulk/update", proposal.path("proposalId").asText(), "stale-one");
        assertEquals("COMPLETED_WITH_ERRORS", result.path("status").asText()); assertEquals("First", name(1));
        var second = evaluate("/bulk/update/evaluation", uniform("2", "Changed")); store.deny = true;
        var denied = confirm("/bulk/update", second.path("proposalId").asText(), "denied-one");
        assertEquals("COMPLETED_WITH_ERRORS", denied.path("status").asText()); assertEquals("Second", name(2));
    }

    @Test void mapperAndAfterUpdateFailureRollBackDomainAndReceiptTogether() {
        var proposal = evaluate("/bulk/update/evaluation", uniform("1", "Will roll back"));
        store.failAfterUpdate = true;
        var result = confirm("/bulk/update", proposal.path("proposalId").asText(), "rollback-one");
        assertNotEquals("COMPLETED", result.path("status").asText());
        assertEquals("First", name(1));
        assertEquals(0, store.sql.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?", Integer.class,
                UUID.fromString(result.path("executionId").asText())));
    }

    @Test void deletedTargetDoesNotStopTheNextIndependentItem() {
        var body = twoTargets("Renamed");
        var proposal = evaluate("/bulk/update/evaluation", body);
        store.sql.update("delete from bulk_p3_item where id=1");
        var execution = confirm("/bulk/update",proposal.path("proposalId").asText(),"deleted-target");
        assertEquals("COMPLETED_WITH_ERRORS",execution.path("status").asText(),execution.toString());
        assertEquals("Renamed",name(2));
        var results = executionResults(execution);
        assertEquals("INVALID",results.get(0).path("status").asText());
        assertEquals("CONFIRMED",results.get(1).path("status").asText());
    }

    @Test void stateConflictIsPreservedDuringFreshAdmissionAndTheNextItemCommits() {
        var proposal = evaluate("/bulk/update/evaluation",twoTargets("Renamed"));
        store.conflictId = 1L;
        var execution = confirm("/bulk/update",proposal.path("proposalId").asText(),"state-conflict");
        assertEquals("COMPLETED_WITH_ERRORS",execution.path("status").asText());
        assertEquals("First",name(1)); assertEquals("Renamed",name(2));
        var results = executionResults(execution);
        assertEquals("CONFLICT",results.get(0).path("status").asText());
        assertEquals("CONFIRMED",results.get(1).path("status").asText());
    }

    @Test void technicalFailureDuringAdmissionStillStopsInsteadOfAccusingTheTarget() {
        var proposal = evaluate("/bulk/update/evaluation",twoTargets("Renamed"));
        store.technicalId = 1L;
        var execution = confirm("/bulk/update",proposal.path("proposalId").asText(),"technical-admission");
        assertEquals("STOPPED",execution.path("status").asText(),execution.toString());
        assertEquals("First",name(1)); assertEquals("Second",name(2));
        var results = executionResults(execution);
        assertEquals(2,results.size());
        for (JsonNode item : results) assertNotEquals("INVALID",item.path("status").asText());
    }

    @Test void globallyDeniedReadsDoNotRevealKnownProposalOrExecutionIds() {
        var proposal = evaluate("/bulk/update/evaluation",uniform("1","Renamed"));
        var execution = confirm("/bulk/update",proposal.path("proposalId").asText(),"read-authorization");
        store.readDenied = true;
        for (String suffix : List.of("", "/results")) {
            assertEquals(403,rest.getForEntity(url("/bulk/proposals/"+proposal.path("proposalId").asText()+suffix),JsonNode.class).getStatusCode().value());
            assertEquals(403,rest.getForEntity(url("/bulk/proposals/"+UUID.randomUUID()+suffix),JsonNode.class).getStatusCode().value());
            assertEquals(403,rest.getForEntity(url("/bulk/executions/"+execution.path("executionId").asText()+suffix),JsonNode.class).getStatusCode().value());
            assertEquals(403,rest.getForEntity(url("/bulk/executions/"+UUID.randomUUID()+suffix),JsonNode.class).getStatusCode().value());
        }
        assertEquals(403,rest.postForEntity(url("/bulk/executions/"+execution.path("executionId").asText()+"/cancel"),null,JsonNode.class).getStatusCode().value());
        assertEquals(403,rest.postForEntity(url("/bulk/executions/"+UUID.randomUUID()+"/cancel"),null,JsonNode.class).getStatusCode().value());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode twoTargets(String name) {
        var body = uniform("1",name);
        ((com.fasterxml.jackson.databind.node.ArrayNode)body.at("/selection/targets")).addObject()
                .put("id","2").put("expectedVersion",etag(2L,0));
        return body;
    }

    private JsonNode executionResults(JsonNode execution) {
        var response=rest.getForEntity(url("/bulk/executions/"+execution.path("executionId").asText()+"/results"),JsonNode.class);
        assertEquals(200,response.getStatusCode().value());
        return response.getBody().path("data").path("content");
    }

    @Test void ruleCatalogDiagnosticsBlockTheTargetAndOnlyTheAllowedMessageIsPublic() {
        var proposal = evaluate("/bulk/update/evaluation", uniform("1", "rule-denied"));
        assertEquals("BLOCKED", proposal.path("status").asText());
        var results = rest.getForEntity(url("/bulk/proposals/" + proposal.path("proposalId").asText() + "/results"), JsonNode.class);
        assertEquals(200, results.getStatusCode().value());
        assertTrue(results.getBody().toString().contains("P3_NAME_DENIED"), results.getBody().toString());
        assertTrue(results.getBody().toString().contains("Name is blocked by policy"));
        assertFalse(results.getBody().toString().contains("private-rule-facts"));
        assertEquals("First", name(1)); assertEquals(0, store.afterUpdates);
    }

    @Test void querySelectionUsesCanonicalFilterAndMandatoryTenantScope() {
        var body = mapper.createObjectNode().put("executionMode", "SYNC");
        body.putObject("selection").put("mode", "QUERY").putObject("filter").put("name", "First");
        body.putArray("changes").addObject().put("field", "note").put("operator", "SET").put("value", "query");
        var proposal = evaluate("/bulk/update/evaluation", body);
        assertEquals(1, proposal.path("totals").path("targetCount").asInt());
        confirm("/bulk/update", proposal.path("proposalId").asText(), "query-one");
        assertEquals("query", store.sql.queryForObject("select note from bulk_p3_item where id=1", String.class));
        assertEquals("private", store.sql.queryForObject("select note from bulk_p3_item where id=3", String.class));
    }

    @Test void generatedOpenApiAndStrictIngressShareLongCodecAndConcreteDtos() {
        var document = rest.getForObject("http://localhost:" + port + "/v3/api-docs", JsonNode.class);
        var schemas = document.path("components").path("schemas");
        assertEquals("int64-decimal", schemas.path("ItemBulkUniformEvaluationRequest").at("/properties/selection/properties/targets/items/properties/id/format").asText());
        assertTrue(schemas.has("ItemBulkItemEvaluationRequest")); assertTrue(schemas.has("ItemBulkCommandEvaluationRequest"));
        String schemaUrl = "http://localhost:" + port + "/schemas/filtered?path=%2Fp3-items%2Fbulk%2Fupdate%2Fevaluation&operation=post&schemaType=request";
        var filtered = rest.getForEntity(schemaUrl, JsonNode.class);
        assertEquals(200, filtered.getStatusCode().value());
        assertNotNull(filtered.getHeaders().getETag()); assertNotNull(filtered.getHeaders().getFirst("X-Schema-Hash"));
        var conditional = new HttpHeaders(); conditional.setIfNoneMatch(filtered.getHeaders().getETag());
        assertEquals(304, rest.exchange(schemaUrl, HttpMethod.GET, new HttpEntity<>(conditional), String.class).getStatusCode().value());
        var wrongId = uniform("1", "Bad wire"); ((com.fasterxml.jackson.databind.node.ObjectNode) wrongId.at("/selection/targets/0")).put("id", 1);
        assertEquals(400, post("/bulk/update/evaluation", wrongId, null).getStatusCode().value());
        var unknown = uniform("1", "Bad field").put("unknown", "private");
        assertEquals(400, post("/bulk/update/evaluation", unknown, null).getStatusCode().value());
        var noKey = post("/bulk/update", mapper.createObjectNode().put("proposalId", UUID.randomUUID().toString()), null);
        assertEquals(400, noKey.getStatusCode().value());
        assertEquals("First", name(1));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode selection(String id) {
        var body = mapper.createObjectNode().put("executionMode", "SYNC");
        body.putObject("selection").put("mode", "EXPLICIT").putArray("targets").addObject().put("id", id).put("expectedVersion", etag(Long.parseLong(id), 0));
        return body;
    }
    private com.fasterxml.jackson.databind.node.ObjectNode uniform(String id, String name) {
        var body = selection(id); body.putArray("changes").addObject().put("field", "name").put("operator", "SET").put("value", name); return body;
    }
    private JsonNode evaluate(String path, Object body) { return successfulData(post(path, body, null)); }
    private JsonNode confirm(String path, String proposal, String key) { return evaluateWithKey(path, mapper.createObjectNode().put("proposalId", proposal), key); }
    private JsonNode evaluateWithKey(String path, Object body, String key) { return successfulData(post(path, body, key)); }
    private JsonNode successfulData(ResponseEntity<JsonNode> response) {
        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        var body = response.getBody(); assertEquals("success", body.path("status").asText());
        assertTrue(body.path("_links").path("self").has("href"), body.toString()); assertFalse(body.has("links"));
        assertEquals(200, rest.getForEntity(body.path("_links").path("self").path("href").asText(), JsonNode.class).getStatusCode().value());
        return body.path("data");
    }
    private ResponseEntity<JsonNode> post(String path, Object body, String key) { var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if (key != null) headers.set("Idempotency-Key", key); return rest.postForEntity(url(path), new HttpEntity<>(body, headers), JsonNode.class); }
    private String url(String suffix) { return "http://localhost:" + port + PATH + suffix; }
    private String name(long id) { return store.sql.queryForObject("select name from bulk_p3_item where id=?", String.class, id); }
    static String etag(long id, long version) { return new ResourceVersionEtagService("p3-test-key").create(ResourceVersionScope.GLOBAL, RESOURCE, id, version); }
    private void withRequest(Runnable work) {
        var previous = RequestContextHolder.getRequestAttributes();
        var request = new MockHttpServletRequest(servletContext, "GET", PATH);
        request.setContextPath(servletContext.getContextPath()); request.setServletPath(PATH);
        request.setLocalAddr("127.0.0.1"); request.setLocalPort(port); request.setServerPort(port);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { work.run(); } finally { if (previous == null) RequestContextHolder.resetRequestAttributes(); else RequestContextHolder.setRequestAttributes(previous); }
    }

    public record View(Long id, String name, String note) { }
    public record Update(@NotBlank @BulkEditable String name, @BulkEditable(allowClear = true) @Schema(nullable = true) String note) { }
    public static class Filter implements GenericFilterDTO { @Filterable public String name; }
    public record Rename(@NotBlank String name) { }
    interface Repository extends BaseCrudRepository<DeclarativeBulkItem, Long> { }

    static class Service extends AbstractBaseResourceService<DeclarativeBulkItem, View, Long, Filter, Update, Update> {
        final Store store;
        Service(Repository repository, Store store, EntityManager em) { super(repository, DeclarativeBulkItem.class); this.store = store; ReflectionTestUtils.setField(this, "entityManager", em); }
        @Override protected String resolveResourceKey() { return RESOURCE; }
        protected ResourceMapper<DeclarativeBulkItem, View, Update, Update, Long> getResourceMapper() { return new ResourceMapper<>() {
            public View toResponse(DeclarativeBulkItem e) { return new View(e.id, e.name, e.note); }
            public DeclarativeBulkItem newEntity(Update dto) { return new DeclarativeBulkItem(); }
            public void applyUpdate(DeclarativeBulkItem e, Update dto) { e.name = dto.name(); e.note = dto.note(); }
            public Long extractId(DeclarativeBulkItem e) { return e.id; }
        }; }
        protected ResourceFilterAccessScope<DeclarativeBulkItem> resolveResourceFilterAccessScope() { return ResourceFilterAccessScope.restricted((root, query, cb) -> cb.equal(root.get("tenant"), 1)); }
        protected void assertEntityAccessAllowed(DeclarativeBulkItem e) { if (e.tenant != 1) throw new SecurityException("private tenant"); }
        protected void beforeUpdate(Long id, DeclarativeBulkItem candidate, Update dto) {
            if (Objects.equals(store.technicalId,id)) throw new IllegalStateException("private authority failure");
            if (Objects.equals(store.conflictId,id)) throw new org.praxisplatform.rules.violation.PraxisRuleViolationException(
                    new org.praxisplatform.rules.violation.SlotViolationDetail("P3_STATE_CONFLICT",null,null,null,null,
                            org.praxisplatform.rules.contract.RuleDecision.DENY,"name","State no longer permits this change",null,"CONFLICT"));
            if (!getEntityManager().contains(candidate)) {
                try { store.isolations.add(org.springframework.jdbc.datasource.DataSourceUtils.getConnection(store.runtime.dataSource()).getTransactionIsolation()); }
                catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
                candidate.note = "detached-only"; // Must never dirty the managed source.
            } else assertEquals(LockModeType.PESSIMISTIC_WRITE, getEntityManager().getLockMode(candidate));
            if ("forbidden".equals(dto.name())) throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,"private domain details");
            if ("rule-denied".equals(dto.name())) rejectByRules();
        }
        private void rejectByRules() {
            var json = new ObjectMapper();
            var template = new org.praxisplatform.rules.runtime.GovernedRuleEngineTemplate(
                    org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry.empty(),
                    org.praxisplatform.rules.violation.RuleErrorCatalog.of(Map.of("P3_NAME_DENIED",
                            new org.praxisplatform.rules.violation.RuleErrorDescriptor("P3_NAME_DENIED", "name", "Name is blocked by policy", "fixture:name-policy", "POLICY"))));
            var definition = new org.praxisplatform.rules.contract.RuleSetDefinition(
                    new org.praxisplatform.rules.contract.RuleSetRef("fixture", "items", "items.rename", "update", 1), List.of("draft"),
                    List.of(new org.praxisplatform.rules.contract.DecisionSlot("name", org.praxisplatform.rules.contract.DecisionStage.DOMAIN_DECISION,
                            org.praxisplatform.rules.contract.SlotCardinality.SINGLE, org.praxisplatform.rules.contract.OverridePolicy.FORBIDDEN,
                            org.praxisplatform.rules.contract.DecisionAggregationPolicy.SINGLE_RESULT, null)),
                    List.of(new org.praxisplatform.rules.contract.DecisionBinding("name.product", "name", org.praxisplatform.rules.contract.DecisionSource.PRODUCT, null,
                            new org.praxisplatform.rules.contract.RuleExecutorRef(org.praxisplatform.rules.contract.RuleExecutorType.JSON_LOGIC, null, "1.0",
                                    json.valueToTree(Map.of("==", List.of(Map.of("var", "draft.valid"), true)))), List.of(), 0, true,
                            org.praxisplatform.rules.contract.RuleDecision.DENY, "P3_NAME_DENIED", List.of("draft.valid"), null)),
                    org.praxisplatform.rules.contract.RuleRuntimeCompatibility.current(), org.praxisplatform.rules.contract.RuleFailPolicy.FAIL_CLOSED);
            template.enforce(template.compile(definition, "fixture-tenant"),
                    json.createObjectNode().put("private", "private-rule-facts").set("draft", json.createObjectNode().put("valid", false)),
                    "2026-11-01T12:00:00Z", "UTC");
        }
        protected void afterUpdate(Long id, DeclarativeBulkItem e, Update dto) { store.afterUpdates++; if (store.failAfterUpdate) throw new IllegalStateException("private post-write failure"); }
    }

    @RestController @ApiResource(value = PATH, resourceKey = RESOURCE) @ApiGroup("p3")
    @BulkResourceOperations(proposalOperationId = "p3.proposal", proposalResultsOperationId = "p3.proposal-results",
            executionOperationId = "p3.execution", executionResultsOperationId = "p3.results", cancelOperationId = "p3.cancel", updateSourceOperationId = "p3.update")
    static class ItemController extends AbstractBulkGovernedResourceController<View, Long, Filter, Update, Update> {
        @Autowired(required = false) Store store;
        @Autowired(required = false) JpaResourceBulkEngine<Long, Filter> engine;
        protected BaseResourceService<View, Long, Filter, Update, Update> getService() { return store.service; }
        protected Long getResponseId(View dto) { return dto.id(); }
        protected JpaResourceBulkEngine<Long, Filter> getBulkEngine() { return engine; }
        @PostMapping("/bulk/rename/evaluation") @Operation(operationId = "p3.command.evaluate")
        public ResponseEntity<org.praxisplatform.uischema.rest.response.RestApiResponse<BulkProposal>> renameEvaluation(@RequestBody BulkCommandEvaluationRequest<Rename, ?, Filter> request) { return evaluateBulkDomainCommand("rename", request); }
        @PostMapping("/bulk/rename") @Operation(operationId = COMMAND)
        @WorkflowAction(id = "rename", title = "Rename selected items", scope = ActionScope.COLLECTION, atomicity = ActionCollectionAtomicity.PER_ITEM)
        @BulkOperation(mode = BulkMode.DOMAIN_COMMAND, evaluationOperationId = "p3.command.evaluate", atomicity = ActionCollectionAtomicity.PER_ITEM)
        public ResponseEntity<org.praxisplatform.uischema.rest.response.RestApiResponse<BulkExecution>> renameConfirm(@RequestBody BulkConfirmationRequest request, @RequestHeader("Idempotency-Key") String key) { return confirmBulkDomainCommand("rename", request, key); }
    }

    @TestConfiguration static class Config {
        @Bean(destroyMethod = "close") Store p3Store() throws Exception { return new Store(); }
        @Bean BulkExecutionInfrastructure p3Runtime(Store store) { return store.runtime; }
        @Bean BulkControlPlaneInfrastructure p3Control(Store store) { return store.control; }
        @Bean BulkOperationDescriptorProvider p3Uniform(Store store) { return provider(store, UNIFORM, BulkMode.UNIFORM_UPDATE); }
        @Bean BulkOperationDescriptorProvider p3Items(Store store) { return provider(store, ITEMS, BulkMode.PER_ITEM_UPDATE); }
        @Bean BulkOperationDescriptorProvider p3Command(Store store) { return provider(store, COMMAND, BulkMode.DOMAIN_COMMAND); }
        @Bean JpaResourceBulkEngine<Long, Filter> p3Engine(Store store, BulkOperationLifecycle lifecycle, CanonicalOperationResolver operations, ObjectMapper mapper) {
            var validator = Validation.buildDefaultValidatorFactory().getValidator();
            var adapter = store.service.bulkDomainAdapter(mapper, validator, new ResourceVersionEtagService("p3-test-key"), () -> ResourceVersionScope.GLOBAL);
            var resolved = io.swagger.v3.core.converter.ModelConverters.getInstance().resolveAsResolvedSchema(new io.swagger.v3.core.converter.AnnotatedType(Update.class));
            var schema = resolved.schema.get$ref() == null ? resolved.schema : resolved.referencedSchemas.get(resolved.schema.get$ref().replace("#/components/schemas/", ""));
            var fields = BulkEditableFields.compile(mapper, mapper.constructType(Update.class), io.swagger.v3.core.util.Json.mapper().valueToTree(schema), io.swagger.v3.oas.models.SpecVersion.V30, Set.of());
            var policy = new BulkResourcePolicy() {
                public BulkFingerprintContext context(String id) {
                    var ref = operations.resolveByOperationId(id).orElseThrow();
                    return new BulkFingerprintContext(NAMESPACE, "fixture-subject", RESOURCE, ref, "p3-schema-v1", ActionCollectionAtomicity.PER_ITEM);
                }
                public BulkEvaluationGovernance evaluateGovernance(BulkFingerprintContext context) { return new BulkEvaluationGovernance("p3-evaluator-v1", "fixture-authority-v1", List.of()); }
                public void authorize(BulkFingerprintContext context) { }
                public BulkUnitAdmission admit(BulkExecutionUnit unit) { return store.deny ? BulkUnitAdmission.denied(BulkUnitReasonCode.TARGET_DENIED) : BulkUnitAdmission.admit(); }
                public BulkReadAuthorizationProvider readAuthorization(String id) { return new BulkReadAuthorizationProvider() {
                    public String confirmationOperationId() { return id; }
                    public BulkExecutionInfrastructure executionInfrastructure() { return store.runtime; }
                    public GlobalDecision preAuthorize(Context context, Duration remaining) { return store.readDenied ? GlobalDecision.DENIED : GlobalDecision.ALLOWED; }
                    public ScopeDecision authorize(Context context, String creator, List<Target> targets, Duration remaining) { return ScopeDecision.authorized(new byte[32]); }
                }; }
                public BulkProposalProjectionProvider proposalProjection(String id) { return new BulkProposalProjectionProvider() {
                    public String resourceKey() { return RESOURCE; } public String confirmationOperationId() { return id; }
                    public String projectionRevision() { return "p3-public-v1"; }
                    public JsonNode projectRedactedIntent(BulkEvaluationSnapshot evaluation) { return mapper.createObjectNode().put("operation", evaluation.proposal().snapshot().mode().name()); }
                }; }
                public List<BulkPreviewProjection.PublicDiagnostic> publicDiagnostics(String id) { return List.of(
                        new BulkPreviewProjection.PublicDiagnostic(org.praxisplatform.uischema.command.ResourceCommandErrorCategory.VALIDATION,
                                "P3_NAME_DENIED", "Name is blocked by policy")); }
            };
            var command = new BulkDomainCommandAdapter<Long>() {
                public BulkDomainResourceAdapter.Candidate evaluate(Long id, String version, JsonNode parameters) {
                    return adapter.evaluate(id, version, List.of(new BulkFieldChange("name", BulkChangeOperator.SET, parameters.path("name"))), BulkMode.UNIFORM_UPDATE, fields);
                }
                public void execute(Long id, String version, JsonNode plan) { adapter.update(id, version, plan); }
            };
            return new JpaResourceBulkEngine<>(store.runtime, lifecycle, adapter, BulkIdentityCodecs.longs(), fields, mapper, policy,
                    new BulkReadCursorConfiguration("fixture", Map.of("fixture", new javax.crypto.spec.SecretKeySpec(new byte[32], "AES")), Duration.ofMinutes(5)), Map.of(COMMAND, command));
        }
        private static BulkOperationDescriptorProvider provider(Store store, String id, BulkMode mode) { return new BulkOperationDescriptorProvider() {
            public String confirmationOperationId() { return id; } public String providerId() { return "p3-" + mode; }
            public String providerRevision() { return "p3-v1"; } public BulkIdentityCodec<?, ?> identityCodec() { return BulkIdentityCodecs.longs(); }
            public String identitySchemaPointer() { return mode == BulkMode.PER_ITEM_UPDATE ? "/properties/items/items/properties/id" : "/properties/selection/properties/targets/items/properties/id"; }
            public BulkOperationalProfile profile() { return new BulkOperationalProfile(Set.of(mode), Set.of(BulkExecutionMode.SYNC), mode == BulkMode.UNIFORM_UPDATE ? Set.of(BulkSelectionMode.EXPLICIT, BulkSelectionMode.QUERY) : Set.of(BulkSelectionMode.EXPLICIT), 50, 100000, Duration.ofMinutes(5), Duration.ofSeconds(5)); }
            public BulkExecutionInfrastructure executionInfrastructure() { return store.runtime; }
        }; }
    }

    static class Store implements AutoCloseable {
        final EmbeddedPostgres pg; final javax.sql.DataSource admin; final JdbcTemplate sql;
        final BulkExecutionInfrastructure runtime; final BulkControlPlaneInfrastructure control; final Service service;
        private final HikariDataSource runtimeSource, controlSource;
        private final LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        boolean installed, deny, failAfterUpdate; int afterUpdates;
        volatile boolean readDenied;
        volatile Long conflictId, technicalId;
        final List<Integer> isolations = new java.util.concurrent.CopyOnWriteArrayList<>();
        Store() throws Exception {
            pg = EmbeddedPostgres.builder().setRegisterShutdownHook(false).start(); admin = pg.getPostgresDatabase();
            // Share the restricted runtime pool across JPA and JDBC, and keep control credentials separate.
            runtimeSource = pool(BulkPostgresTestSupport.runtimeDataSource(pg));
            controlSource = pool(new DriverManagerDataSource(pg.getJdbcUrl("bulk_control_test", "postgres"), "bulk_control_test", ""));
            sql = new JdbcTemplate(runtimeSource);
            try {
                var owner = new JdbcTemplate(admin); owner.execute("create role bulk_runtime_test login"); owner.execute("create role bulk_control_test login");
                owner.execute("create table bulk_p3_item(id bigint primary key, revision bigint not null, tenant integer not null, name text, note text)");
                owner.execute("grant select,insert,update,delete on bulk_p3_item to bulk_runtime_test");
                factory.setDataSource(runtimeSource);
                factory.setPackagesToScan(DeclarativeBulkItem.class.getPackageName());
                // Spring registers this fixture explicitly; do not scan unrelated test archives again.
                factory.setJpaPropertyMap(Map.of("hibernate.archive.autodetection", "none"));
                factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                factory.afterPropertiesSet();
                var emf = factory.getObject();
                var manager = new JpaTransactionManager(emf);
                var roles = new BulkExecutionRoleConfiguration("postgres", Set.of("bulk_runtime_test"), Set.of(), Set.of("bulk_control_test"));
                runtime = new BulkExecutionInfrastructure(runtimeSource, manager, NAMESPACE, "deployment-test", roles);
                control = new BulkControlPlaneInfrastructure(controlSource, new DataSourceTransactionManager(controlSource),
                        NAMESPACE, "deployment-test", "bulk_control_test", runtime);
                var em = SharedEntityManagerCreator.createSharedEntityManager(emf);
                service = new Service(new JpaRepositoryFactory(em).getRepository(Repository.class), this, em);
            } catch (Exception | Error failure) {
                try { close(); }
                catch (Exception | Error closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }

        private static HikariDataSource pool(javax.sql.DataSource source) {
            var pool = new HikariDataSource();
            pool.setDataSource(source);
            pool.setMaximumPoolSize(4);
            pool.setMinimumIdle(1);
            return pool;
        }

        public void close() throws Exception {
            try (pg; runtimeSource; controlSource) {
                factory.destroy();
            }
        }
    }
}
