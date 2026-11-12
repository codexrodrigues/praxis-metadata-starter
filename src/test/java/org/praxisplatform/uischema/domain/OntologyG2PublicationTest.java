package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.controller.docs.*;
import org.praxisplatform.uischema.determination.*;
import org.praxisplatform.uischema.openapi.*;
import org.praxisplatform.uischema.schema.*;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP serialization, compiler and canonical schema resolution; no DB or remote endpoint. */
class OntologyG2PublicationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode document;
    private CanonicalOperationResolver operations;
    private ApiResourceIdentityResolver identities;
    private OpenApiDocumentService documents;
    private FilteredSchemaReferenceResolver schemas;
    private OpenApiCanonicalCapabilityResolver capabilities;
    private ReactiveDeterminationMetadataCompiler compiler;
    private ReactiveDeterminationCatalogSource source;
    private SemanticDomainCatalogService service;
    private MockMvc http;
    private boolean requireReadLock;
    private int readLockDepth;

    @BeforeEach void setup() throws Exception {
        document = mapper.readTree("""
            {"openapi":"3.0.3","paths":{
              "/api/customers":{"post":{"operationId":"createCustomer","requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Draft"}}}}}},
              "/api/customers/{id}":{"put":{"operationId":"updateCustomer","requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Draft"}}}}}},
              "/api/customers/address":{"post":{"operationId":"determineAddress",
                "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Input"}}}},
                "responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Output"}}}}}}}},
             "components":{"schemas":{
               "Draft":{"type":"object","properties":{"id":{"type":"integer"},"postalCode":{"type":"string"},"country":{"type":"string"},
                 "address":{"$ref":"#/components/schemas/Address"}}},
               "Address":{"type":"object","properties":{"city":{"type":"string","description":"City selected for the customer address."},"state":{"type":"string"}}},
               "Input":{"type":"object","properties":{"postalCode":{"type":"string"},"country":{"type":"string"}}},
               "Output":{"type":"object","properties":{"city":{"type":"string"},"state":{"type":"string"}}}}}}
            """);
        Map<String, CanonicalOperationRef> refs = Map.of(
                "createCustomer", new CanonicalOperationRef("crm", "createCustomer", "/api/customers", "POST"),
                "updateCustomer", new CanonicalOperationRef("crm", "updateCustomer", "/api/customers/{id}", "PUT"),
                "determineAddress", new CanonicalOperationRef("crm", "determineAddress", "/api/customers/address", "POST"));
        operations = mock(CanonicalOperationResolver.class);
        when(operations.resolveByOperationId(anyString())).thenAnswer(call -> Optional.ofNullable(refs.get(call.getArgument(0))));
        when(operations.resolve(anyString(), anyString())).thenAnswer(call -> refs.values().stream().filter(ref ->
                ref.path().equals(call.getArgument(0)) && ref.method().equalsIgnoreCase(call.getArgument(1))).findFirst().orElseThrow());
        when(operations.resolveGroup(anyString())).thenReturn("crm");
        identities = mock(ApiResourceIdentityResolver.class);
        when(identities.resolveResourceKey(anyString(), anyString(), anyString())).thenReturn(Optional.of("crm.customers"));
        documents = new OpenApiDocumentService() {
            public String resolveGroupFromPath(String path) { return "crm"; }
            public JsonNode getDocumentForGroup(String group) {
                if (requireReadLock) assertThat(readLockDepth).as("All publication reads share the canonical cache read lock").isPositive();
                return document;
            }
            public <T> T withSchemaCacheReadLock(Supplier<T> action) {
                readLockDepth++;
                try { return action.get(); } finally { readLockDepth--; }
            }
            public String getOrComputeSchemaHash(String id, Supplier<JsonNode> supplier) { return Integer.toHexString(supplier.get().hashCode()); }
            public void clearCaches() { }
        };
        schemas = new FilteredSchemaReferenceResolver();
        capabilities = new OpenApiCanonicalCapabilityResolver(documents);
        configure(List.of(definition("address", "/address/city", "/postalCode")));
    }

    private ReactiveDeterminationDefinition definition(String id, String output, String trigger) {
        return new ReactiveDeterminationDefinition(id,
                List.of(new ReactiveDeterminationScope("createCustomer", ReactiveDeterminationFormMode.CREATE),
                        new ReactiveDeterminationScope("updateCustomer", ReactiveDeterminationFormMode.EDIT)),
                "determineAddress", ReactiveDeterminationTriggerMode.ON_CHANGE, List.of(trigger),
                List.of(new ReactiveDeterminationInputBinding(trigger, "/postalCode"), new ReactiveDeterminationInputBinding("/country", "/country")),
                List.of(new ReactiveDeterminationOutputBinding("/city", output)),
                new ReactiveDeterminationProvenance(ReactiveDeterminationProvenanceKind.HOST, "address-fixture", "1"));
    }

    private void configure(List<ReactiveDeterminationDefinition> definitions) {
        var registry = new DefaultReactiveDeterminationDefinitionRegistry(List.of(() -> definitions));
        compiler = new ReactiveDeterminationMetadataCompiler(registry, operations, schemas, documents);
        source = new ReactiveDeterminationCatalogSource(registry, compiler, operations, documents, identities, schemas, capabilities, mapper);
        service = new SemanticDomainCatalogService(mock(ActionDefinitionRegistry.class), mock(SurfaceDefinitionRegistry.class),
                null, documents, capabilities, Clock.systemUTC(), "host", "Host", "test", null, null, null, null, source);
        var filtered = new ApiDocsController();
        ReflectionTestUtils.setField(filtered, "objectMapper", mapper);
        ReflectionTestUtils.setField(filtered, "openApiDocsSupport", new OpenApiDocsSupport());
        ReflectionTestUtils.setField(filtered, "openApiDocumentService", documents);
        ReflectionTestUtils.setField(filtered, "canonicalOperationResolver", operations);
        ReflectionTestUtils.setField(filtered, "schemaReferenceResolver", schemas);
        ReflectionTestUtils.setField(filtered, "canonicalCapabilityResolver", capabilities);
        ReflectionTestUtils.setField(filtered, "reactiveDeterminationMetadataCompiler", compiler);
        http = MockMvcBuilders.standaloneSetup(filtered, new SemanticDomainCatalogController(service)).build();
    }

    @Test void httpCatalogMatchesFilteredSchemaForEachScopeAndValidatesClosedV02() throws Exception {
        JsonNode catalog = mapper.readTree(http.perform(get("/schemas/domain").param("resource", "crm.customers"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var contract = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                getClass().getResourceAsStream("/contracts/domain-catalog-v0.2.schema.json"));
        assertThat(contract.validate(catalog)).isEmpty();
        var determinations = service.findByResourceKey("crm.customers").nodes().stream()
                .filter(node -> "reactive-determination".equals(node.source())).toList();
        assertThat(determinations).hasSize(2).extracting(DomainCatalogResponse.DomainNodeItem::nodeKey).doesNotHaveDuplicates();
        for (var node : determinations) {
            JsonNode compiled = mapper.valueToTree(node.metadata().get("reactiveDetermination"));
            var form = service.findByResourceKey("crm.customers").bindings().stream()
                    .filter(binding -> node.nodeKey().equals(binding.nodeKey()) && "form_config".equals(binding.bindingType())).findFirst().orElseThrow();
            JsonNode filtered = mapper.readTree(http.perform(get(java.net.URI.create((String) form.target().get("schemaUrl"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(filtered.at("/x-ui/reactiveDeterminations/0")).isEqualTo(compiled);
            assertThat(compiled.at("/trigger/sourcePaths")).isEqualTo(mapper.valueToTree(List.of("/postalCode")));
            assertThat(compiled.path("inputs")).hasSize(2); // contextual country is not a trigger
            assertThat(form.target().get("configuration")).isEqualTo(node.metadata().get("reactiveDetermination"));
            var dependencies = service.findByResourceKey("crm.customers").edges().stream()
                    .filter(edge -> node.nodeKey().equals(edge.sourceNodeKey()) && "depends_on".equals(edge.edgeType())).toList();
            assertThat(dependencies).hasSize(2);
            assertThat(service.findByResourceKey("crm.customers").edges()).anySatisfy(edge -> {
                assertThat(edge.sourceNodeKey()).isEqualTo("crm.customers");
                assertThat(edge.targetNodeKey()).isEqualTo(node.nodeKey());
                assertThat(edge.edgeType()).isEqualTo("governed_by");
            });
        }
        assertThat(service.findByResourceKey("crm.customers").nodes()).anySatisfy(node ->
                assertThat(node.metadata()).containsEntry("fieldName", "address.city"));
        assertThat(catalog.path("edges")).allSatisfy(edge -> assertThat(edge.has("metadata")).isFalse());
        ObjectNode invalid = catalog.deepCopy();
        ((ObjectNode) invalid.path("edges").get(0)).putObject("metadata").put("role", "forbidden");
        assertThat(contract.validate(invalid)).isNotEmpty();
    }

    @Test void filtersByExactResourceAndGroupAndChangesFingerprintWithCompiledContent() {
        assertThat(source.find("crm.other", null)).isEmpty();
        assertThat(source.find(null, "other")).isEmpty();
        String before = service.findByResourceKey("crm.customers").release().sourceHash();
        ((ObjectNode) document.at("/components/schemas/Address/properties/city")).put("description", "Revised address meaning.");
        assertThat(service.findByResourceKey("crm.customers").release().sourceHash()).isNotEqualTo(before);
        String afterOutput = service.findByResourceKey("crm.customers").release().sourceHash();
        var unbound = (ObjectNode) document.at("/components/schemas/Address/properties/state");
        unbound.put("description", "Confidential region outside determination bindings.");
        unbound.putObject("x-domain-governance").put("annotationType", "security").put("classification", "restricted")
                .put("dataCategory", "operational").putObject("aiUsage").put("visibility", "deny");
        var changed = service.findByResourceKey("crm.customers");
        assertThat(changed.release().sourceHash()).isNotEqualTo(afterOutput);
        var state = changed.nodes().stream().filter(node -> "address.state".equals(node.metadata().get("fieldName"))).findFirst().orElseThrow();
        assertThat(changed.governance().stream().filter(item -> state.nodeKey().equals(item.nodeKey())))
                .singleElement().satisfies(item -> assertThat(item.aiUsage()).containsEntry("visibility", "deny"));
    }

    @Test void rejectsUnknownOwnerAndMismatchedPublishedOperation() {
        when(identities.resolveResourceKey(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> source.find(null, null)).hasMessageContaining("no explicit resource identity");
        when(identities.resolveResourceKey(anyString(), anyString(), anyString())).thenReturn(Optional.of("crm.customers"));
        ((ObjectNode) document.at("/paths/~1api~1customers/post")).put("operationId", "wrong");
        assertThatThrownBy(() -> source.find(null, null)).hasMessageContaining("differs from published");
    }

    @Test void rejectsWritersCyclesAndUnknownNestedFieldsThroughNativeCompiler() {
        configure(List.of(definition("first", "/address/city", "/postalCode"), definition("second", "/address/city", "/postalCode")));
        assertThatThrownBy(() -> service.findByResourceKey("crm.customers")).hasMessageContaining("overlapping targets");
        configure(List.of(definition("first", "/address/city", "/postalCode"), definition("second", "/postalCode", "/address/city")));
        assertThatThrownBy(() -> service.findByResourceKey("crm.customers")).hasMessageContaining("cycle");
        configure(List.of(definition("invalid", "/address/unknown", "/postalCode")));
        assertThatThrownBy(() -> service.findByResourceKey("crm.customers")).hasMessageContaining("unknown");
    }

    @Test void contractFixtureMatchesCanonicalConfigSchemaWhenGatePathIsProvided() throws Exception {
        String canonical = System.getProperty("ontology.g2.catalog-schema");
        org.junit.jupiter.api.Assumptions.assumeTrue(canonical != null, "Cross-repository gate supplies the canonical schema path");
        assertThat(mapper.readTree(java.nio.file.Path.of(canonical).toFile())).isEqualTo(
                mapper.readTree(getClass().getResourceAsStream("/contracts/domain-catalog-v0.2.schema.json")));
    }

    @Test void publicationReadsAllSchemaSnapshotsUnderTheCanonicalCacheLock() {
        requireReadLock = true;
        assertThat(service.findByResourceKey("crm.customers").nodes()).isNotEmpty();
        assertThat(readLockDepth).isZero();
    }

    @Test void nestedFieldsInLaterAllOfBranchesRemainPublished() {
        ObjectNode draft = (ObjectNode) document.at("/components/schemas/Draft");
        JsonNode original = draft.deepCopy();
        draft.removeAll();
        ObjectNode first = mapper.createObjectNode().put("type", "object");
        first.putObject("properties").putObject("customerCode").put("type", "string");
        draft.putArray("allOf").add(first).add(original);
        var catalog = service.findByResourceKey("crm.customers");
        assertThat(catalog.nodes().stream().filter(node -> "field".equals(node.nodeType())).map(node -> node.metadata().get("fieldName")))
                .contains("customerCode", "address.city", "postalCode", "country");
    }

    @Test void nestedAndScopedFieldsCannotBroadenDeclaredAncestorGovernance() {
        for (boolean explicitAllow : new boolean[]{false, true}) {
            var parent = (ObjectNode) document.at("/components/schemas/Draft/properties/address");
            var restriction = parent.putObject("x-domain-governance").put("annotationType", "security")
                    .put("classification", "restricted").put("dataCategory", "operational").put("exportable", false);
            restriction.putObject("aiUsage").put("visibility", "deny").put("trainingUse", "deny");
            if (explicitAllow) {
                var child = (ObjectNode) document.at("/components/schemas/Address/properties/city");
                child.putObject("x-domain-governance").put("annotationType", "privacy").put("classification", "public")
                        .put("dataCategory", "none").put("exportable", true).putObject("aiUsage").put("visibility", "allow");
            }
            var catalog = service.findByResourceKey("crm.customers");
            var cityNodes = catalog.nodes().stream().filter(node -> "address.city".equals(node.metadata().get("fieldName"))
                    || "/address/city".equals(node.metadata().get("fieldPath"))).toList();
            assertThat(cityNodes).hasSize(3);
            for (var node : cityNodes) assertThat(catalog.governance().stream().filter(item -> node.nodeKey().equals(item.nodeKey())))
                    .isNotEmpty().allSatisfy(item -> {
                        assertThat(item.aiUsage()).containsEntry("visibility", "deny");
                        assertThat(item.exportable()).isFalse();
                        assertThat(item.classification()).isEqualTo("restricted");
                        assertThat(item.dataCategory()).isEqualTo("operational");
                    });
        }
    }

    @Test void scopedFieldsCombineAllOfRestrictionsAndAnnotatedReferenceWrappers() {
        ObjectNode draft = (ObjectNode) document.at("/components/schemas/Draft");
        JsonNode original = draft.deepCopy();
        ((ObjectNode) document.at("/components/schemas")).set("BaseDraft", original);
        draft.removeAll();
        var branches = draft.putArray("allOf");
        branches.addObject().put("$ref", "#/components/schemas/BaseDraft");
        ObjectNode restrictedBranch = branches.addObject();
        var declaration = restrictedBranch.putObject("properties").putObject("postalCode").put("type", "string")
                .putObject("x-domain-governance").put("annotationType", "security").put("classification", "restricted")
                .put("dataCategory", "operational").put("exportable", false);
        declaration.putObject("aiUsage").put("visibility", "deny");
        assertScopedFieldDenied("/postalCode");
        // The restriction on a $ref wrapper must survive dereferencing as well.
        branches.remove(1);
        ((ObjectNode) branches.get(0)).set("x-domain-governance", declaration.deepCopy());
        assertScopedFieldDenied("/postalCode");
    }

    private void assertScopedFieldDenied(String pointer) {
        var catalog = service.findByResourceKey("crm.customers");
        var nodes = catalog.nodes().stream().filter(node -> pointer.equals(node.metadata().get("fieldPath"))).toList();
        assertThat(nodes).hasSize(2);
        for (var node : nodes) assertThat(catalog.governance().stream().filter(item -> node.nodeKey().equals(item.nodeKey())))
                .isNotEmpty().allSatisfy(item -> {
                    assertThat(item.aiUsage()).containsEntry("visibility", "deny"); assertThat(item.exportable()).isFalse();
                });
    }

    @Test void nestedObjectSplitAcrossAllOfBranchesResolvesThroughTheNativeCompiler() {
        ObjectNode draft = (ObjectNode) document.at("/components/schemas/Draft");
        JsonNode original = draft.deepCopy();
        ((ObjectNode) original.at("/properties")).remove("address");
        draft.removeAll();
        var branches = draft.putArray("allOf");
        branches.add(original);
        branches.addObject().putObject("properties").putObject("address").put("type", "object")
                .putObject("properties").putObject("state").put("type", "string");
        branches.addObject().putObject("properties").putObject("address").put("type", "object")
                .putObject("properties").putObject("city").put("type", "string");
        assertThat(source.find("crm.customers", null)).hasSize(2);
        assertThat(compiler.resolveFieldSchema(draft, document, "/address/city").path("type").asText()).isEqualTo("string");
        assertThat(compiler.resolveFieldSchema(draft, document, "/address/state").path("type").asText()).isEqualTo("string");
    }

    @Test void semanticGateRejectsSchemaValidEdgesWithWrongEndpointTypes() {
        var catalog = service.findByResourceKey("crm.customers");
        String determination = catalog.nodes().stream().filter(node -> "reactive-determination".equals(node.source()))
                .findFirst().orElseThrow().nodeKey();
        String field = catalog.nodes().stream().filter(node -> "field".equals(node.nodeType())).findFirst().orElseThrow().nodeKey();
        for (var invalid : List.of(
                new DomainCatalogResponse.DomainEdgeItem("invalid", "crm.customers", field, "uses_concept", "Invalid", 1.0, List.of()),
                new DomainCatalogResponse.DomainEdgeItem("invalid", determination, "crm.customers", "depends_on", "Invalid", 1.0, List.of()),
                new DomainCatalogResponse.DomainEdgeItem("invalid", "crm.customers", determination, "has_state", "Invalid", 1.0, List.of()))) {
            var edges = new ArrayList<>(catalog.edges()); edges.add(invalid);
            var corrupted = new DomainCatalogResponse(catalog.schemaVersion(), catalog.service(), catalog.release(), catalog.resourceKey(),
                    catalog.contexts(), catalog.nodes(), edges, catalog.bindings(), catalog.aliases(), catalog.evidence(), catalog.governance());
            assertThatThrownBy(() -> DomainCatalogSemanticValidator.validate(corrupted)).isInstanceOf(IllegalStateException.class);
        }
    }
}
