package org.praxisplatform.uischema.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.annotation.MicroVisualization;
import org.praxisplatform.uischema.annotation.MicroVisualizationKind;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceReference;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.web.server.ResponseStatusException;

class ContextualSurfaceSummaryProjectorTest {

    @Test
    void projectsIdentityQuickViewOrderJsonPropertyAndCompanionFields() {
        var plan = ContextualSurfaceSummaryProjector.planFor(null, RichPersonDto.class, "id");

        List<String> plannedNames = plan.fields().stream()
                .map(ContextualSurfaceSummaryProjector.FieldDescriptor::name)
                .toList();
        assertEquals(
                List.of(
                        "id",
                        "fullName",
                        "preferredName",
                        "taxIdentifier",
                        "eMail",
                        "status",
                        "progressScore",
                        "internalSecret",
                        "departmentId",
                        "completedSteps",
                        "totalSteps"
                ),
                plannedNames
        );

        var emailField = plan.fields().stream()
                .filter(f -> f.name().equals("eMail"))
                .findFirst()
                .orElseThrow();
        assertTrue(emailField.hasDataCategory(DomainDataCategory.PERSONAL));
        assertEquals("hr.people.email", emailField.effectiveAttributeId());
        assertTrue(emailField.hasResourceReference());

        var dto = new RichPersonDto();
        dto.setId(42L);
        dto.setFullName("Ada Lovelace");
        dto.setPreferredName("Ada");
        dto.setTaxIdentifier("12345678909");
        dto.setEMail("ada@example.invalid");
        dto.setStatus("ACTIVE");
        dto.setProgressScore(80);
        dto.setDepartmentId(99L);
        dto.setCompletedSteps(4);
        dto.setTotalSteps(5);
        dto.setInternalSecret("classified");
        dto.setIgnoredNote("never-serialized");

        Map<String, Object> restricted = plan.project(dto, Set.of("ROLE_VIEWER"), null);
        assertFalse(restricted.containsKey("internalSecret"));
        assertFalse(restricted.containsKey("ignoredNote"));
        assertEquals("ada@example.invalid", restricted.get("eMail"));
        assertEquals(99L, restricted.get("departmentId"));
        assertEquals(4, restricted.get("completedSteps"));
        assertEquals(5, restricted.get("totalSteps"));

        Map<String, Object> authorized = plan.project(dto, Set.of("PERM_AUDIT"), null);
        assertEquals("classified", authorized.get("internalSecret"));
    }

    @Test
    void supportsSanitizerMaskingAndOmissionWithoutMutatingSourceDto() {
        var plan = ContextualSurfaceSummaryProjector.planFor(null, RichPersonDto.class, "id");

        var dto = new RichPersonDto();
        dto.setId(7L);
        dto.setFullName("Grace Hopper");
        dto.setTaxIdentifier("99988877766");
        dto.setEMail("grace@example.invalid");
        dto.setStatus("ACTIVE");

        Map<String, Object> sanitized = plan.project(
                dto,
                Set.of("ROLE_VIEWER"),
                (field, rawValue, authorities) -> {
                    if (field.name().equals("taxIdentifier")) {
                        return ContextualSurfaceSummaryProjector.FieldDecision.keep("MASKED");
                    }
                    if (field.hasDataCategory(DomainDataCategory.PERSONAL)
                            && !field.isIdentityHeader()
                            && !field.isIdentityMetadata()
                            && !field.hasResourceReference()) {
                        return ContextualSurfaceSummaryProjector.FieldDecision.omit();
                    }
                    return ContextualSurfaceSummaryProjector.FieldDecision.keep(rawValue);
                }
        );

        assertEquals("MASKED", sanitized.get("taxIdentifier"));
        assertEquals("Grace Hopper", sanitized.get("fullName"));
        assertEquals("ACTIVE", sanitized.get("status"));
        assertEquals("99988877766", dto.getTaxIdentifier());
    }

    @Test
    void fallsBackToNonTableHiddenFieldsWhenNoQuickViewVisibleDeclared() {
        var plan = ContextualSurfaceSummaryProjector.planFor(null, FallbackSummaryDto.class, "id");
        List<String> plannedNames = plan.fields().stream()
                .map(ContextualSurfaceSummaryProjector.FieldDescriptor::name)
                .toList();

        assertEquals(
                List.of(
                        "id", "title", "col1", "col2", "col3", "col4", "col5", "col6", "col7", "col8"
                ),
                plannedNames
        );
        assertFalse(plannedNames.contains("hiddenField"));
        assertFalse(plannedNames.contains("col9"));
    }

    @Test
    void exposesEffectiveClassificationAndNormalizesCglibControllerProxyClass() {
        ProxyFactory pf = new ProxyFactory(new SampleAnnotatedController());
        pf.setProxyTargetClass(true);
        Object proxiedController = pf.getProxy();

        var directPlan = ContextualSurfaceSummaryProjector.planFor(
                SampleAnnotatedController.class, FinancialDto.class, "id");
        var proxyPlan = ContextualSurfaceSummaryProjector.planFor(
                proxiedController.getClass(), FinancialDto.class, "id");
        assertSame(directPlan, proxyPlan);

        List<String> plannedNames = proxyPlan.fields().stream()
                .map(ContextualSurfaceSummaryProjector.FieldDescriptor::name)
                .toList();
        assertTrue(plannedNames.containsAll(List.of("id", "displayTitle", "publicRemuneration", "netAmount")));

        var publicField = proxyPlan.fields().stream()
                .filter(f -> f.name().equals("publicRemuneration"))
                .findFirst()
                .orElseThrow();
        var confidentialField = proxyPlan.fields().stream()
                .filter(f -> f.name().equals("netAmount"))
                .findFirst()
                .orElseThrow();

        assertTrue(publicField.hasDataCategory(DomainDataCategory.FINANCIAL));
        assertTrue(publicField.hasClassification(DomainClassification.PUBLIC));
        assertFalse(publicField.hasClassification(DomainClassification.CONFIDENTIAL));

        assertTrue(confidentialField.hasDataCategory(DomainDataCategory.FINANCIAL));
        assertTrue(confidentialField.hasClassification(DomainClassification.CONFIDENTIAL));
        assertFalse(confidentialField.hasClassification(DomainClassification.PUBLIC));
    }

    @Test
    void contextualSurfaceReadPreChecksExistenceAndAuthoritiesBeforeAuthorizedRead() {
        SurfaceDefinition detailSurface = new SurfaceDefinition(
                "detail", "sample.items", "/sample-items", "sample",
                SurfaceKind.READ_PROJECTION, SurfaceScope.ITEM, "Detail", "", "view", null,
                "response", SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("sample", "sample.detail", "/sample-items/{id}", "GET"),
                new CanonicalSchemaRef("detail-schema", "response", "/schemas/filtered?path=/sample-items"),
                1, List.of(), List.of(), List.of(), null, null, null,
                "/sample-items/{id}/surfaces/detail/summary"
        );
        SurfaceDefinition restrictedSurface = new SurfaceDefinition(
                "audit", "sample.items", "/sample-items", "sample",
                SurfaceKind.READ_PROJECTION, SurfaceScope.ITEM, "Audit", "", "audit", null,
                "response", SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("sample", "sample.audit", "/sample-items/{id}", "GET"),
                new CanonicalSchemaRef("audit-schema", "response", "/schemas/filtered?path=/sample-items"),
                2, List.of("PERM_AUDIT"), List.of(), List.of(), null, null, null,
                "/sample-items/{id}/surfaces/audit/summary"
        );
        SurfaceDefinition editWithoutSummary = new SurfaceDefinition(
                "edit", "sample.items", "/sample-items", "sample",
                SurfaceKind.PARTIAL_FORM, SurfaceScope.ITEM, "Edit", "", "edit", null,
                "request", SurfaceResponseCardinality.OBJECT,
                new CanonicalOperationRef("sample", "sample.edit", "/sample-items/{id}", "PATCH"),
                new CanonicalSchemaRef("edit-schema", "request", "/schemas/filtered?path=/sample-items"),
                3, List.of(), List.of(), List.of(), null, null, null,
                null
        );

        SurfaceDefinitionRegistry registry = new SurfaceDefinitionRegistry() {
            @Override
            public List<SurfaceDefinition> findByResourceKey(String resourceKey) {
                return List.of(detailSurface, restrictedSurface, editWithoutSummary);
            }

            @Override
            public List<SurfaceDefinition> findByGroup(String group) {
                return List.of(detailSurface, restrictedSurface, editWithoutSummary);
            }
        };

        AtomicInteger contextCalls = new AtomicInteger();
        SurfaceCatalogService catalogService = new SurfaceCatalogService(
                registry,
                new DefaultSurfaceAvailabilityEvaluator(),
                (resourceKey, resourcePath, resourceId) -> {
                    contextCalls.incrementAndGet();
                    return new SurfaceAvailabilityContext(
                            resourceKey, resourcePath, resourceId, null, Locale.ROOT, null, Set.of("PERM_USER"), null);
                }
        );

        AtomicInteger dbReads = new AtomicInteger();

        ResponseStatusException unknownSurfaceEx = assertThrows(
                ResponseStatusException.class,
                () -> catalogService.readContextualSurface("sample.items", 42L, "nonexistent", () -> {
                    dbReads.incrementAndGet();
                    return Map.of("id", 42L);
                }));
        assertEquals(403, unknownSurfaceEx.getStatusCode().value());

        ResponseStatusException noReadPathEx = assertThrows(
                ResponseStatusException.class,
                () -> catalogService.readContextualSurface("sample.items", 42L, "edit", () -> {
                    dbReads.incrementAndGet();
                    return Map.of("id", 42L);
                }));
        assertEquals(403, noReadPathEx.getStatusCode().value());

        ResponseStatusException missingAuthorityEx = assertThrows(
                ResponseStatusException.class,
                () -> catalogService.readContextualSurface("sample.items", 42L, "audit", () -> {
                    dbReads.incrementAndGet();
                    return Map.of("id", 42L);
                }));
        assertEquals(403, missingAuthorityEx.getStatusCode().value());

        assertEquals(0, dbReads.get());

        ContextualSurfaceRead allowed = catalogService.readContextualSurface(
                "sample.items", 42L, "detail", () -> {
                    dbReads.incrementAndGet();
                    return Map.of("id", 42L, "title", "Valid");
                });
        assertEquals(1, dbReads.get());
        assertEquals("detail", allowed.surfaceId());
        assertEquals("Valid", allowed.data().get("title"));
    }

    @ApiResource(
            value = "/sample-items",
            resourceKey = "sample.items",
            identity = @ResourceIdentity(keyField = "id", titleField = "displayTitle")
    )
    static class SampleAnnotatedController {
        public String ping() {
            return "ok";
        }
    }

    record FinancialDto(
            Long id,
            @UISchema(label = "Titulo", order = 1) String displayTitle,
            @DomainGovernance(preset = DomainGovernancePreset.PUBLIC_TRANSPARENCY_REMUNERATION)
            @UISchema(label = "Remuneracao publica", order = 2, quickViewVisible = true, quickViewOrder = 1)
            String publicRemuneration,
            @DomainGovernance(preset = DomainGovernancePreset.CONFIDENTIAL_FINANCIAL)
            @UISchema(label = "Liquido privado", order = 3, quickViewVisible = true, quickViewOrder = 2)
            String netAmount
    ) {
    }

    static class BasePersonDto {
        private Long id;

        @UISchema(label = "Nome", order = 10)
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_DATA)
        private String fullName;

        @UISchema(label = "E-mail", order = 20, quickViewVisible = true, quickViewOrder = 1)
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_CONTACT)
        @DomainLineage(attributeId = "hr.people.email", sourcePaths = {"eMail"})
        private String eMail;

        @UISchema(label = "Documento", order = 15)
        private String taxIdentifier;

        @JsonIgnore
        @UISchema(label = "Ignored", quickViewVisible = true, quickViewOrder = 99)
        private String ignoredNote;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        @JsonProperty("eMail")
        public String getEMail() {
            return eMail;
        }

        public void setEMail(String eMail) {
            this.eMail = eMail;
        }

        public String getTaxIdentifier() {
            return taxIdentifier;
        }

        public void setTaxIdentifier(String taxIdentifier) {
            this.taxIdentifier = taxIdentifier;
        }

        public String getIgnoredNote() {
            return ignoredNote;
        }

        public void setIgnoredNote(String ignoredNote) {
            this.ignoredNote = ignoredNote;
        }
    }

    @ResourceIdentity(
            keyField = "id",
            titleField = "fullName",
            subtitleField = "preferredName",
            metadataFields = {"taxIdentifier"}
    )
    static class RichPersonDto extends BasePersonDto {
        @UISchema(label = "Nome social", order = 12)
        private String preferredName;

        @UISchema(label = "Situacao", order = 30, quickViewVisible = true, quickViewOrder = 2)
        private String status;

        @UISchema(label = "Conclusao", order = 40, quickViewVisible = true, quickViewOrder = 3)
        @MicroVisualization(
                kind = MicroVisualizationKind.BULLET,
                valueExpr = "completedSteps",
                targetExpr = "totalSteps"
        )
        private Integer progressScore;

        @UISchema(
                label = "Auditoria interna",
                order = 50,
                quickViewVisible = true,
                quickViewOrder = 4,
                visibleForAuthorities = {"PERM_AUDIT", "PERM_PII"}
        )
        private String internalSecret;

        private Long departmentId;
        private Integer completedSteps;
        private Integer totalSteps;

        @Override
        @ResourceReference(
                resourceKey = "org.departments",
                valueField = "departmentId",
                surfaceId = "detail",
                previewSurfaceId = "detail"
        )
        @JsonProperty("eMail")
        public String getEMail() {
            return super.getEMail();
        }

        public String getPreferredName() {
            return preferredName;
        }

        public void setPreferredName(String preferredName) {
            this.preferredName = preferredName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Integer getProgressScore() {
            return progressScore;
        }

        public void setProgressScore(Integer progressScore) {
            this.progressScore = progressScore;
        }

        public String getInternalSecret() {
            return internalSecret;
        }

        public void setInternalSecret(String internalSecret) {
            this.internalSecret = internalSecret;
        }

        public Long getDepartmentId() {
            return departmentId;
        }

        public void setDepartmentId(Long departmentId) {
            this.departmentId = departmentId;
        }

        public Integer getCompletedSteps() {
            return completedSteps;
        }

        public void setCompletedSteps(Integer completedSteps) {
            this.completedSteps = completedSteps;
        }

        public Integer getTotalSteps() {
            return totalSteps;
        }

        public void setTotalSteps(Integer totalSteps) {
            this.totalSteps = totalSteps;
        }
    }

    @ResourceIdentity(keyField = "id", titleField = "title")
    record FallbackSummaryDto(
            Long id,
            @UISchema(label = "Title", order = 1) String title,
            @UISchema(label = "Hidden", order = 2, tableHidden = true) String hiddenField,
            @UISchema(label = "C1", order = 10) String col1,
            @UISchema(label = "C2", order = 20) String col2,
            @UISchema(label = "C3", order = 30) String col3,
            @UISchema(label = "C4", order = 40) String col4,
            @UISchema(label = "C5", order = 50) String col5,
            @UISchema(label = "C6", order = 60) String col6,
            @UISchema(label = "C7", order = 70) String col7,
            @UISchema(label = "C8", order = 80) String col8,
            @UISchema(label = "C9", order = 90) String col9
    ) {
    }
}
