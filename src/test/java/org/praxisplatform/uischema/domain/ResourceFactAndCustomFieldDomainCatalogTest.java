package org.praxisplatform.uischema.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.customfields.*;
import org.praxisplatform.uischema.fact.*;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.praxisplatform.uischema.controller.docs.SemanticDomainCatalogController;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.stream.Stream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResourceFactAndCustomFieldDomainCatalogTest {
    private static final String RESOURCE = "hr.employees";

    @Test void publishesFieldsFactsBindingsAndExplicitGovernance() {
        var service = service((r, t, s) -> List.of(field("shoe_size")),
                (r, t) -> List.of(fact("tenure", "Years of service")), () -> Optional.of("org-a"));
        var result = service.findByResourceKey(RESOURCE);
        assertThat(result.nodes()).filteredOn(n -> "custom_field".equals(n.source())).singleElement().satisfies(n -> {
            assertThat(n.nodeType()).isEqualTo("field");
            assertThat(n.metadata()).containsEntry("fieldName", "customFields.shoe_size");
        });
        assertThat(result.nodes()).filteredOn(n -> "resource_fact".equals(n.source())).singleElement().satisfies(n -> {
            assertThat(n.nodeKey()).isEqualTo(RESOURCE + ".fact.tenure");
            assertThat(n.nodeType()).isEqualTo("fact");
            assertThat(n.metadata()).containsEntry("deterministic", true).containsEntry("unit", "years");
        });
        assertThat(result.edges()).extracting(DomainCatalogResponse.DomainEdgeItem::edgeType).contains("has_field", "provides_fact");
        assertThat(result.bindings()).filteredOn(b -> "resource_fact".equals(b.bindingType())).singleElement()
                .satisfies(b -> assertThat(b.target()).containsEntry("factPath", "facts.tenure"));
        assertThat(result.bindings()).filteredOn(b -> "dto_field".equals(b.bindingType())).singleElement()
                .satisfies(b -> assertThat(b.target()).containsEntry("fieldName", "customFields.shoe_size"));
        assertThat(result.governance()).hasSize(2).allSatisfy(g -> assertThat(g.classification()).isEqualTo("confidential"));
        assertThat(service.findByResourceKey(RESOURCE)).isSameAs(result);
    }

    @ParameterizedTest
    @MethodSource("temporalDefaults")
    void fingerprintsTemporalDefaultsUsingDeterministicIsoValues(CustomFieldDataType dataType, Object value, String iso) {
        AtomicReference<Object> defaultValue = new AtomicReference<>(value);
        var service = service((r, t, role) -> List.of(new CustomFieldDescriptor(
                "effective", "Effective", "Temporal field", dataType, null, null, 0, false, false,
                false, false, false, defaultValue.get(), null, null, null, null, null, null, null,
                null, null, null, null, Map.of())), null, null);
        var first = service.findByResourceKey(RESOURCE);
        assertThat(first.nodes()).anyMatch(n -> n.nodeKey().equals(RESOURCE + ".field.customFields.effective"));
        defaultValue.set(iso);
        assertThat(service.findByResourceKey(RESOURCE)).isSameAs(first);
        defaultValue.set(dataType == CustomFieldDataType.DATE ? "2027-01-02" : "2027-01-02T00:00:00Z");
        assertThat(service.findByResourceKey(RESOURCE)).isNotSameAs(first);
        defaultValue.set(value);
        assertThat(service.findByResourceKey(RESOURCE)).isSameAs(first);
    }

    static Stream<Arguments> temporalDefaults() {
        return Stream.of(
                Arguments.of(CustomFieldDataType.DATE, LocalDate.of(2026, 1, 1), "2026-01-01"),
                Arguments.of(CustomFieldDataType.DATE_TIME, Instant.parse("2026-01-01T12:30:45Z"), "2026-01-01T12:30:45Z"),
                Arguments.of(CustomFieldDataType.DATE_TIME, LocalDateTime.parse("2026-01-01T12:30:45"), "2026-01-01T12:30:45"),
                Arguments.of(CustomFieldDataType.DATE_TIME, OffsetDateTime.parse("2026-01-01T12:30:45-03:00"), "2026-01-01T12:30:45-03:00"));
    }

    @Test void preservesDeclaredGovernanceForCustomFieldsAndFacts() {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("annotationType", "privacy");
        declaration.put("classification", "restricted");
        declaration.put("dataCategory", "personal");
        declaration.put("confidence", 0.4d);
        declaration.put("reason", "Historical evidence requires review");
        declaration.put("complianceTags", List.of("LGPD", "INTERNAL_POLICY"));
        declaration.put("owner", "People Operations");
        declaration.put("steward", "Data Protection");
        declaration.put("retentionPolicy", "five-years");
        declaration.put("source", "host.governance");
        declaration.put("aiUsage", Map.of("visibility", "mask", "trainingUse", "deny",
                "ruleAuthoring", "review_required", "reasoningUse", "review_required", "hostPolicy", "case-review",
                "reason", "Nested reason must yield to the root declaration"));
        var service = service((r, t, role) -> List.of(new CustomFieldDescriptor(
                "historical", "Historical", "", CustomFieldDataType.STRING, null, null, 0, false, false,
                false, false, false, null, null, null, null, null, null, null, null,
                null, null, null, null, declaration)),
                (r, t) -> List.of(new ResourceFactDescriptor("historical", RESOURCE, "Historical", "", "string", "",
                        Map.of(), true, "historical", declaration)), null);
        assertThat(service.findByResourceKey(RESOURCE).governance()).hasSize(2).allSatisfy(item -> {
            assertThat(item.governanceKey()).isEqualTo("governance:" + item.nodeKey());
            assertThat(item.annotationType()).isEqualTo("privacy");
            assertThat(item.classification()).isEqualTo("restricted");
            assertThat(item.dataCategory()).isEqualTo("personal");
            assertThat(item.confidence()).isEqualTo(0.4d);
            assertThat(item.complianceTags()).containsExactly("LGPD", "INTERNAL_POLICY");
            assertThat(item.owner()).isEqualTo("People Operations");
            assertThat(item.steward()).isEqualTo("Data Protection");
            assertThat(item.retentionPolicy()).isEqualTo("five-years");
            assertThat(item.source()).isEqualTo("host.governance");
            assertThat(item.aiUsage()).containsEntry("reason", "Historical evidence requires review")
                    .containsEntry("visibility", "mask").containsEntry("trainingUse", "deny")
                    .containsEntry("ruleAuthoring", "review_required").containsEntry("reasoningUse", "review_required")
                    .containsEntry("hostPolicy", "case-review");
        });
    }

    @ParameterizedTest
    @MethodSource("partialGovernanceDeclarations")
    void preservesGovernanceItemsAndOnlyDeclaredPoliciesForEmptyOrPartialExtensions(Map<String, Object> declaration) {
        var service = service((r, t, role) -> List.of(new CustomFieldDescriptor(
                "partial", "Partial", "", CustomFieldDataType.STRING, null, null, 0, false, false,
                false, false, false, null, null, null, null, null, null, null, null,
                null, null, null, null, declaration)),
                (r, t) -> List.of(new ResourceFactDescriptor("partial", RESOURCE, "Partial", "", "string", "",
                        Map.of(), true, "computed", declaration)), null);
        assertThat(service.findByResourceKey(RESOURCE).governance()).hasSize(2).allSatisfy(item -> {
            assertThat(item.governanceKey()).isEqualTo("governance:" + item.nodeKey());
            assertThat(item.annotationType()).isNull();
            assertThat(item.classification()).isNull();
            assertThat(item.dataCategory()).isNull();
            assertThat(item.confidence()).isEqualTo(declaration.getOrDefault("confidence", 1.0d));
            if (declaration.containsKey("reason")) {
                assertThat(item.aiUsage()).containsEntry("reason", declaration.get("reason"));
            } else if (declaration.containsKey("aiUsage")) {
                assertThat(item.aiUsage()).containsEntry("reason", "Nested evidence");
            } else {
                assertThat(item.aiUsage()).isEmpty();
            }
            assertThat(item.aiUsage()).doesNotContainKeys("visibility", "trainingUse", "ruleAuthoring", "reasoningUse");
        });
    }

    static Stream<Arguments> partialGovernanceDeclarations() {
        return Stream.of(
                Arguments.of(Map.of()),
                Arguments.of(Map.of("confidence", 0.4d, "reason", "Partial declaration")),
                Arguments.of(Map.of("confidence", 0.4d, "aiUsage", Map.of("reason", "Nested evidence", "hostPolicy", "manual-review"))),
                Arguments.of(Map.of("reason", "Root evidence", "aiUsage", Map.of("reason", "Nested evidence"))));
    }

    @Test void isolatesTenantCacheAndRefreshesChangedDefinitions() {
        AtomicReference<String> tenant = new AtomicReference<>("a");
        AtomicReference<String> title = new AtomicReference<>("First");
        var service = service((r, t, s) -> List.of(field("field_" + t)),
                (r, t) -> List.of(fact("tenure", title.get())), () -> Optional.of(tenant.get()));
        var first = service.findByResourceKey(RESOURCE);
        tenant.set("b");
        var second = service.findByResourceKey(RESOURCE);
        assertThat(second).isNotSameAs(first);
        assertThat(second.nodes()).noneMatch(n -> "customFields.field_a".equals(n.metadata().get("fieldName")));
        assertThat(second.release().sourceHash()).isNotEqualTo(first.release().sourceHash());
        title.set("Changed");
        assertThat(service.findByResourceKey(RESOURCE).release().sourceHash()).isNotEqualTo(second.release().sourceHash());
    }

    @Test void includesExtensionsForResourcesDiscoveredInGroup() {
        var actions = mock(ActionDefinitionRegistry.class);
        var surfaces = mock(SurfaceDefinitionRegistry.class);
        var surface = new org.praxisplatform.uischema.surface.SurfaceDefinition("browse", RESOURCE,
                "/api/hr/employees", "hr", org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                org.praxisplatform.uischema.surface.SurfaceScope.COLLECTION, "Employees", "Browse", "browse", null,
                "response", org.praxisplatform.uischema.surface.SurfaceResponseCardinality.COLLECTION,
                null, null, 0, List.of(), List.of(), List.of(), null);
        when(surfaces.findByGroup("hr")).thenReturn(List.of(surface));
        var service = new SemanticDomainCatalogService(actions, surfaces, OptionSourceRegistry.empty(), null, null,
                Clock.systemUTC(), "test", "Test", "1", (r, t, role) -> List.of(field("shoe_size")),
                (r, t) -> List.of(fact("tenure", "Tenure")), () -> Optional.of("a"));
        assertThat(service.findByGroup("hr").nodes()).extracting(DomainCatalogResponse.DomainNodeItem::source)
                .contains("custom_field", "resource_fact");
    }

    @Test void rejectsProviderResourceMismatchAndDuplicateFacts() {
        var wrong = service(null, (r, t) -> List.of(new ResourceFactDescriptor("tenure", "other", "", "", "number", "", Map.of(), true, "computed", Map.of())), null);
        assertThatThrownBy(() -> wrong.findByResourceKey(RESOURCE)).isInstanceOf(IllegalStateException.class);
        var duplicates = service(null, (r, t) -> List.of(fact("tenure", "A"), fact("tenure", "A")), null);
        assertThatThrownBy(() -> duplicates.findByResourceKey(RESOURCE)).isInstanceOf(IllegalStateException.class);
    }

    @Test void discoversAnnotationsWithoutConstructingOrInvokingDomainBeans() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("facts", new RootBeanDefinition(DeclaredFacts.class));
        var provider = new AnnotationResourceFactCatalogProvider(factory);
        assertThat(provider.findFacts(RESOURCE, "a")).singleElement().satisfies(f -> {
            assertThat(f.key()).isEqualTo("tenure");
            assertThat(f.parameters()).containsKey("asOf");
        });
        assertThat(factory.containsSingleton("facts")).isFalse();
        assertThat(provider.findFacts("other", "a")).isEmpty();
    }

    @Test void publishesTenantCatalogThroughExistingHttpContract() throws Exception {
        var service = service((r, t, s) -> List.of(field("field_" + t)), (r, t) -> List.of(fact("tenure", "Tenure")),
                new RequestHeaderResourceTenantContextResolver());
        var mvc = MockMvcBuilders.standaloneSetup(new SemanticDomainCatalogController(service)).build();
        mvc.perform(get("/schemas/domain").param("resource", RESOURCE).header("X-Tenant", "a"))
                .andExpect(status().isOk()).andExpect(header().string("Vary", "X-Tenant, X-Tenant-ID"))
                .andExpect(jsonPath("$.nodes[?(@.source == 'custom_field')].metadata.fieldName").value("customFields.field_a"));
        mvc.perform(get("/schemas/domain").param("resource", RESOURCE).header("X-Tenant-ID", "b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.source == 'custom_field')].metadata.fieldName").value("customFields.field_b"));
    }

    private SemanticDomainCatalogService service(CustomFieldDefinitionProvider fields, ResourceFactCatalogProvider facts,
            ResourceTenantContextResolver tenants) {
        return new SemanticDomainCatalogService(mock(ActionDefinitionRegistry.class), mock(SurfaceDefinitionRegistry.class),
                OptionSourceRegistry.empty(), null, null, Clock.systemUTC(), "test", "Test", "1", fields, facts, tenants);
    }
    private CustomFieldDescriptor field(String key) {
        return new CustomFieldDescriptor(key, key, "Tenant field", CustomFieldDataType.INTEGER,
                null, null, 0, false, false, true, false, true, null, null, null, null, null,
                null, null, null, null, null, null, null, governance());
    }
    private ResourceFactDescriptor fact(String key, String label) {
        return new ResourceFactDescriptor(key, RESOURCE, label, "Historical tenure", "number", "years",
                Map.of("asOf", Map.of("type", "string", "format", "date")), true, "historical", governance());
    }
    private Map<String, Object> governance() {
        return Map.of("annotationType", "privacy", "classification", "confidential", "dataCategory", "personal",
                "aiUsage", Map.of("trainingUse", "deny"));
    }
    static class DeclaredFacts {
        DeclaredFacts() { throw new AssertionError("Catalog must not instantiate fact beans"); }
        @ResourceFact(key = "tenure", resourceKey = RESOURCE, dataType = "number", parameters = "asOf")
        public int tenure() { throw new AssertionError("Catalog must not evaluate facts"); }
    }
}
