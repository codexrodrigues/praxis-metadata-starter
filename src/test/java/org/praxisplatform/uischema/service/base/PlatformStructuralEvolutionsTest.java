package org.praxisplatform.uischema.service.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.ManyToOne;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.annotation.ResourceReference;
import org.praxisplatform.uischema.exporting.CollectionExportField;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.options.LookupDisplayDescriptor;
import org.praxisplatform.uischema.options.LookupDisplayFieldDescriptor;

class PlatformStructuralEvolutionsTest {

    static class ParentEntity {
        Long id;
    }

    static class ChildEntity {
        Long id;
        String codigo;
        @ManyToOne
        ParentEntity parent;
        @jakarta.persistence.ElementCollection
        java.util.Set<Integer> orgaosHabilitados = new java.util.LinkedHashSet<>();
    }

    static class SampleResponseDto {
        @UISchema(label = "Código", icon = "badge", order = 1)
        private String codigo;

        @UISchema(label = "Descrição detalhada", icon = "description", order = 2, tableHidden = true)
        private String descricaoDetalhada;

        @UISchema(label = "Segredo", icon = "lock", order = 3, tableHidden = true)
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_SENSITIVE_DATA, exportable = false)
        private String segredo;

        private Long cargoId;

        @ResourceReference(resourceKey = "ergon.cargos", valueField = "cargoId", surfaceId = "detail")
        @UISchema(label = "Cargo", icon = "work", order = 4)
        private String cargoNome;

        @UISchema(
                label = "Valor base",
                icon = "payments",
                order = 5,
                tableHidden = true,
                controlType = org.praxisplatform.uischema.FieldControlType.CURRENCY_INPUT)
        private java.math.BigDecimal valorBase;

        @UISchema(
                label = "Início vigência",
                icon = "calendar_today",
                order = 6,
                tableHidden = true,
                type = org.praxisplatform.uischema.FieldDataType.DATE)
        private java.time.LocalDate dtInicio;

        public String getCodigo() { return codigo; }
        public String getDescricaoDetalhada() { return descricaoDetalhada; }
        public String getSegredo() { return segredo; }
        public Long getCargoId() { return cargoId; }
        public String getCargoNome() { return cargoNome; }
        public java.math.BigDecimal getValorBase() { return valorBase; }
        public java.time.LocalDate getDtInicio() { return dtInicio; }
    }

    @Test
    void lookupDisplayDescriptorIntrospectsDtoUiSchemaAndFailsClosedOnUnknownField() {
        LookupDisplayDescriptor descriptor = LookupDisplayDescriptor.directoryFromDto(
                SampleResponseDto.class,
                "codigo",
                List.of("codigo", "descricaoDetalhada", "cargoNome", "valorBase", "dtInicio"),
                true,
                Map.of("ATIVO", "Ativo"));

        assertThat(descriptor.fields())
                .extracting(LookupDisplayFieldDescriptor::propertyPath)
                .containsExactly("codigo", "descricaoDetalhada", "cargoNome", "valorBase", "dtInicio");
        assertThat(descriptor.fields().get(0).label()).isEqualTo("Código");
        assertThat(descriptor.fields().get(0).icon()).isEqualTo("badge");
        assertThat(descriptor.fields().get(2).label()).isEqualTo("Cargo");
        assertThat(descriptor.fields().get(2).icon()).isEqualTo("work");
        assertThat(descriptor.fields().get(3).presentation()).isEqualTo("currency");
        assertThat(descriptor.fields().get(3).format()).isEqualTo("BRL");
        assertThat(descriptor.fields().get(4).presentation()).isEqualTo("date");
        assertThat(descriptor.fields().get(4).format()).isEqualTo("dd/MM/yyyy");

        assertThatThrownBy(() -> LookupDisplayFieldDescriptor.fromDtoField(SampleResponseDto.class, "inexistente"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inexistente");
    }

    @Test
    void resourceStateCopySupportSnapshotsScalarFieldsAndDetachesElementCollections() {
        ParentEntity parent = new ParentEntity();
        parent.id = 99L;

        ChildEntity source = new ChildEntity();
        source.id = 10L;
        source.codigo = "CAR-10";
        source.parent = parent;
        source.orgaosHabilitados.add(1);
        source.orgaosHabilitados.add(2);

        ChildEntity target = new ChildEntity();
        IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();
        copies.put(source, target);

        ResourceStateCopySupport.copyFields(source, target, copies);

        // Mutating the managed entity's @ElementCollection during update must NOT corrupt the snapshot!
        source.orgaosHabilitados.clear();
        source.orgaosHabilitados.add(3);

        assertThat(target.id).isEqualTo(10L);
        assertThat(target.codigo).isEqualTo("CAR-10");
        assertThat(target.parent).isSameAs(parent);
        assertThat(target.orgaosHabilitados).containsExactly(1, 2);
    }

    @Test
    void allExportableFieldsIncludesTableHiddenFieldsAsUnselectedByDefaultAndExcludesNonExportable() {
        List<CollectionExportField> defaultsOnly = ResourceExportSupport.defaults(SampleResponseDto.class);
        assertThat(defaultsOnly)
                .extracting(CollectionExportField::key)
                .containsExactly("codigo", "cargoNome");

        List<CollectionExportField> allExportable = ResourceExportSupport.allExportableFields(SampleResponseDto.class);
        assertThat(allExportable)
                .extracting(CollectionExportField::key)
                .containsExactly("codigo", "descricaoDetalhada", "cargoNome", "valorBase", "dtInicio");
        assertThat(allExportable.get(0).visible()).isTrue();
        assertThat(allExportable.get(1).visible()).isFalse();
        assertThat(allExportable.get(1).exportable()).isTrue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void scopedBatchReferenceHydratorResolvesTitlesInSingleBatchAndRespectsTenantScope() {
        AbstractBaseQueryResourceService<?, ?, ?, ?> cargoService =
                (AbstractBaseQueryResourceService<?, ?, ?, ?>) mock(AbstractBaseQueryResourceService.class);
        // Tenant 1 only has access to cargoId 100L; cargoId 200L belongs to another tenant and is omitted by resolveScopedTitlesByIds
        when(((AbstractBaseQueryResourceService) cargoService).resolveScopedTitlesByIds(anyCollection()))
                .thenReturn(Map.of(100L, "Auditor Fiscal"));

        ScopedBatchReferenceHydrator hydrator = new ScopedBatchReferenceHydrator(
                Map.of("ergon.cargos", cargoService));

        SampleResponseDto r1 = new SampleResponseDto();
        r1.cargoId = 100L;
        SampleResponseDto r2 = new SampleResponseDto();
        r2.cargoId = 100L;
        SampleResponseDto rCrossTenant = new SampleResponseDto();
        rCrossTenant.cargoId = 200L;

        hydrator.hydrate(List.of(r1, r2, rCrossTenant));

        assertThat(r1.getCargoNome()).isEqualTo("Auditor Fiscal");
        assertThat(r2.getCargoNome()).isEqualTo("Auditor Fiscal");
        assertThat(rCrossTenant.getCargoNome()).isNull();
        verify((AbstractBaseQueryResourceService) cargoService, times(1)).resolveScopedTitlesByIds(anyCollection());
    }

    @org.praxisplatform.uischema.annotation.ResourceIdentity(
            keyField = "codigo",
            titleField = "descricaoDetalhada",
            subtitleField = "cargoNome",
            metadataFields = {"valorBase", "dtInicio"}
    )
    static class AnnotatedIdentityDto extends SampleResponseDto {
        @UISchema(label = "Situação operacional", icon = "verified", order = 7)
        public String getSituacaoLookup() {
            return "VIGENTE";
        }
    }

    @org.praxisplatform.uischema.annotation.ResourceIdentity(
            keyField = "id",
            titleField = "descricaoDetalhada",
            subtitleField = "codigoNegocio",
            metadataFields = {"cargoNome", "valorBase"}
    )
    static class SurrogatePkEnterpriseCodeDto extends SampleResponseDto {
        @UISchema(
                label = "Identidade interna",
                preset = org.praxisplatform.uischema.extension.annotation.UISchemaPreset.ENTERPRISE_ID,
                tableHidden = true,
                formHidden = true)
        private Long id;

        @UISchema(
                label = "Sigla da tabela",
                icon = "table_chart",
                preset = org.praxisplatform.uischema.extension.annotation.UISchemaPreset.ENTERPRISE_CODE)
        private String codigoNegocio;

        public Long getId() { return id; }
        public String getCodigoNegocio() { return codigoNegocio; }
    }

    @Test
    void entityLookupDescriptorDirectoryBuilderFromDtoAutoDerivesIdentityAndUiSchemaDisplay() {
        org.praxisplatform.uischema.options.EntityLookupDescriptor lookup =
                org.praxisplatform.uischema.options.EntityLookupDescriptor
                        .directoryBuilderFromDto("ergon.tabelas-vencimento", AnnotatedIdentityDto.class)
                        .status("situacaoLookup")
                        .statusLabels(Map.of("VIGENTE", "Vigente", "ENCERRADO", "Encerrado"))
                        .detailSurface("detail", "detail")
                        .build();

        assertThat(lookup.entityKey()).isEqualTo("ergon.tabelas-vencimento");
        assertThat(lookup.codePropertyPath()).isEqualTo("codigo");
        assertThat(lookup.descriptionPropertyPaths())
                .containsExactly("descricaoDetalhada", "cargoNome", "valorBase", "dtInicio");
        assertThat(lookup.statusPropertyPath()).isEqualTo("situacaoLookup");
        assertThat(lookup.display().primaryPropertyPath()).isEqualTo("descricaoDetalhada");
        assertThat(lookup.display().statusLabelMap()).containsEntry("VIGENTE", "Vigente");
        assertThat(lookup.display().fields())
                .extracting(LookupDisplayFieldDescriptor::propertyPath)
                .containsExactly("codigo", "descricaoDetalhada", "cargoNome", "valorBase", "dtInicio", "situacaoLookup");
        assertThat(lookup.display().fields().get(3).presentation()).isEqualTo("currency");
        assertThat(lookup.display().fields().get(5).label()).isEqualTo("Situação operacional");
        assertThat(lookup.display().fields().get(5).icon()).isEqualTo("verified");

        // Surrogate-PK DTO with visible @UISchema(preset = ENTERPRISE_CODE) resolves business code without leaking hidden id
        org.praxisplatform.uischema.options.EntityLookupDescriptor surrogateLookup =
                org.praxisplatform.uischema.options.EntityLookupDescriptor
                        .directoryBuilderFromDto("ergon.tabelas-surrogate", SurrogatePkEnterpriseCodeDto.class)
                        .build();
        assertThat(surrogateLookup.codePropertyPath()).isEqualTo("codigoNegocio");
        assertThat(surrogateLookup.descriptionPropertyPaths())
                .containsExactly("descricaoDetalhada", "cargoNome", "valorBase");
        assertThat(surrogateLookup.searchPropertyPaths())
                .containsExactly("codigoNegocio", "descricaoDetalhada", "cargoNome", "valorBase")
                .doesNotContain("id");
        assertThat(surrogateLookup.display().fields())
                .extracting(LookupDisplayFieldDescriptor::propertyPath)
                .containsExactly("codigoNegocio", "descricaoDetalhada", "cargoNome", "valorBase");

        assertThatThrownBy(() -> org.praxisplatform.uischema.options.EntityLookupDescriptor
                .directoryBuilderFromDto("ergon.sem-identidade", SampleResponseDto.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@ResourceIdentity");
    }
}
