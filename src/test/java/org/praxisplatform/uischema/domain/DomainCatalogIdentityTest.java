package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.*;
import org.praxisplatform.uischema.openapi.*;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DomainCatalogIdentityTest {
    private ActionDefinition action(String resource, CanonicalSchemaRef response) {
        return new ActionDefinition("inspect", resource, "/api/" + resource, "crm", ActionScope.ITEM,
                "Inspect", "Inspect the resource", new CanonicalOperationRef("crm", "inspect" + resource, "/api/" + resource, "GET"),
                null, response, 1, null, List.of(), List.of("ACTIVE"), List.of(), null);
    }

    private SemanticDomainCatalogService service(List<DomainRuleSetDescriptor> descriptors, List<ActionDefinition> actions, JsonNode doc) {
        var registry = mock(ActionDefinitionRegistry.class);
        when(registry.findByGroup("crm")).thenReturn(actions);
        var documents = new OpenApiDocumentService() {
            public String resolveGroupFromPath(String path) { return "crm"; }
            public JsonNode getDocumentForGroup(String group) { return doc; }
            public String getOrComputeSchemaHash(String key, Supplier<JsonNode> supplier) { return "unused"; }
            public void clearCaches() { }
        };
        return new SemanticDomainCatalogService(registry, mock(SurfaceDefinitionRegistry.class), null, documents, null,
                Clock.systemUTC(), "host", "Host", "test", null, null, null, DomainRuleSetCatalogProvider.ofDescriptors(descriptors));
    }

    private DomainRuleSetDescriptor descriptor(String resource, String operation, Map<String, ConceptRootBinding> bindings) {
        return DomainRuleSetDescriptor.of(resource, DomainRuleSetDescriptorTest.definition(operation, bindings.keySet().toArray(String[]::new)), bindings);
    }

    @Test void preservesSelfReferencesDistinctRolesAndExternalOwnerWithoutCrud() {
        var bindings = Map.of("employee", DomainRuleSetDescriptorTest.binding("employee", "crm.people"),
                "manager", DomainRuleSetDescriptorTest.binding("manager", "crm.people"),
                "external", new ConceptRootBinding("external", "other-service", "crm", "crm.people"));
        var catalog = service(List.of(descriptor("crm.people", "CREATE", bindings)), List.of(), new ObjectMapper().createObjectNode())
                .findByResourceKey("crm.people");
        var own = catalog.nodes().stream().filter(node -> node.nodeKey().equals("crm.people")).findFirst().orElseThrow();
        assertThat(own.metadata()).containsEntry("projectionRole", "DEFINITION").containsEntry("ownerServiceKey", "host");
        assertThat(catalog.edges().stream().filter(edge -> "references".equals(edge.edgeType()))).hasSize(3);
        assertThat(catalog.nodes().stream().filter(node -> "concept-reference".equals(node.source()))).singleElement().satisfies(node -> {
            assertThat(node.metadata()).containsEntry("ownerServiceKey", "other-service").containsEntry("resourceKey", "crm.people")
                    .doesNotContainKeys("capabilities", "readOnly", "resourcePath");
            assertThat(node.nodeKey()).isNotEqualTo(own.nodeKey());
        });
    }

    @Test void inverseReferencesPreserveAuthoritativeDefinitionsInBothInputOrders() {
        var a = descriptor("crm.a", "A", Map.of("b", DomainRuleSetDescriptorTest.binding("b", "crm.b")));
        var b = descriptor("crm.b", "B", Map.of("a", DomainRuleSetDescriptorTest.binding("a", "crm.a")));
        for (var actions : List.of(List.of(action("crm.a", null), action("crm.b", null)),
                List.of(action("crm.b", null), action("crm.a", null)))) {
            var catalog = service(List.of(a, b), actions, new ObjectMapper().createObjectNode()).findByGroup("crm");
            assertThat(catalog.nodes().stream().filter(node -> "concept".equals(node.nodeType()))).hasSize(2).allSatisfy(node ->
                    assertThat(node.metadata()).containsEntry("projectionRole", "DEFINITION").containsEntry("ownerServiceKey", "host"));
            assertThat(catalog.edges().stream().filter(edge -> "allowed_in_state".equals(edge.edgeType()))).hasSize(2).allSatisfy(edge ->
                    assertThat(catalog.nodes()).anySatisfy(node -> {
                        assertThat(node.nodeKey()).isEqualTo(edge.targetNodeKey()); assertThat(node.nodeType()).isEqualTo("state");
                    }));
        }
    }

    @Test void rejectsConflictingLocalIdentityAndKeepsOperationIdentitiesStable() {
        var wrong = descriptor("crm.people", "CREATE", Map.of("person", new ConceptRootBinding("person", "host", "other", "crm.people")));
        assertThatThrownBy(() -> service(List.of(wrong), List.of(), new ObjectMapper().createObjectNode()).findByResourceKey("crm.people"))
                .hasMessageContaining("Ambiguous concept reference");
        var first = descriptor("crm.people", "B C", Map.of("person", DomainRuleSetDescriptorTest.binding("person", "crm.people")));
        var second = descriptor("crm.people", "B-C", Map.of("person", DomainRuleSetDescriptorTest.binding("person", "crm.other")));
        var third = descriptor("crm.people", "A", Map.of("person", DomainRuleSetDescriptorTest.binding("person", "crm.people")));
        var baseline = service(List.of(first), List.of(), new ObjectMapper().createObjectNode()).findByResourceKey("crm.people");
        String identity = baseline.nodes().stream().filter(node -> "rule-set".equals(node.source())).findFirst().orElseThrow().nodeKey();
        var catalog = service(List.of(first, second, third), List.of(), new ObjectMapper().createObjectNode()).findByResourceKey("crm.people");
        assertThat(catalog.nodes().stream().filter(node -> "rule-set".equals(node.source()))).hasSize(3)
                .extracting(DomainCatalogResponse.DomainNodeItem::nodeKey).doesNotHaveDuplicates().contains(identity);
        var owner = catalog.nodes().stream().filter(node -> "crm.people".equals(node.nodeKey())).findFirst().orElseThrow();
        assertThat(((Map<?, ?>) owner.metadata().getOrDefault("conceptFactRootMap", Map.of())).containsKey("person")).isFalse();
    }

    @Test void arrayResponseRetainsInlineNestedFields() throws Exception {
        JsonNode doc = new ObjectMapper().readTree("""
                {"components":{"schemas":{"Rows":{"type":"array","items":{"type":"object","properties":{
                  "name":{"type":"string"},"address":{"type":"object","properties":{"city":{"type":"string"}}}
                }}}}}}
                """);
        var catalog = service(List.of(), List.of(action("crm.people", new CanonicalSchemaRef("Rows", "response", "/schemas/filtered?schemaType=response"))), doc)
                .findByGroup("crm");
        assertThat(catalog.nodes().stream().filter(node -> "field".equals(node.nodeType())).map(node -> node.metadata().get("fieldName")))
                .contains("name", "address.city");
    }
}
