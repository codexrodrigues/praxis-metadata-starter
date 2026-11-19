package org.praxisplatform.uischema.service.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernanceKind;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.annotation.DomainLineageTransformation;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewReport;
import org.praxisplatform.uischema.authoring.SemanticMetadataReviewer;
import org.praxisplatform.uischema.exporting.CollectionExportField;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.extension.annotation.UISchema;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceExportSupportTest {

    @Test
    void enforcesTypeLevelAndInheritedPropertyGovernanceViaCanonicalDomainSchemaGovernance() {
        assertThat(ResourceExportSupport.exportable(EmployeeExportDto.class, "publicCode")).isTrue();
        assertThat(ResourceExportSupport.exportable(EmployeeExportDto.class, "payroll")).isFalse();
        assertThat(ResourceExportSupport.exportable(EmployeeExportDto.class, "payroll.grossAmount")).isFalse();
        assertThat(ResourceExportSupport.exportable(EmployeeExportDto.class, "secretProfile")).isFalse();
        assertThat(ResourceExportSupport.exportable(EmployeeExportDto.class, "secretProfile.alias")).isFalse();
        assertThat(ResourceExportSupport.defaults(EmployeeExportDto.class))
                .extracting(CollectionExportField::key)
                .containsExactly("publicCode");
    }

    @Test
    void failsClosedWhenConcreteSubtypeOrConflictingFacetViolatesExportGovernance() {
        PublicNode allowed = new PublicNode("OK");
        RestrictedSubtypeNode restricted = new RestrictedSubtypeNode("OK", "12345678900");

        assertThat(ResourceExportSupport.requireExportableValue(allowed)).isSameAs(allowed);
        assertThatThrownBy(() -> ResourceExportSupport.requireExportableValue(restricted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted property");

        assertThat(ResourceExportSupport.exportable(ConflictingGovernanceDto.class, "ambiguousField")).isFalse();
    }

    @Test
    void publishesClassLevelDomainGovernanceToOpenApiSchemaAndInheritsIntoProperties() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomOpenApiResolver(mapper));

        Schema<?> schema = converters.resolveAsResolvedSchema(
                new AnnotatedType(SecretProfileDto.class).resolveAsRef(false)).schema;
        assertThat(schema.getExtensions()).containsKey("x-domain-governance");
        JsonNode typeGov = mapper.valueToTree(schema.getExtensions().get("x-domain-governance"));
        assertThat(typeGov.path("exportable").asBoolean(true)).isFalse();
        assertThat(typeGov.path("classification").asText()).isEqualTo("restricted");

        Schema<?> aliasProp = (Schema<?>) schema.getProperties().get("alias");
        assertThat(aliasProp.getExtensions()).containsKey("x-domain-governance");
        JsonNode propGov = mapper.valueToTree(aliasProp.getExtensions().get("x-domain-governance"));
        assertThat(propGov.path("exportable").asBoolean(true)).isFalse();
        assertThat(propGov.path("classification").asText()).isEqualTo("restricted");

        Schema<?> mixedSchema = converters.resolveAsResolvedSchema(
                new AnnotatedType(ConfidentialClassWithPersonalFieldDto.class).resolveAsRef(false)).schema;
        Schema<?> cpfProp = (Schema<?>) mixedSchema.getProperties().get("cpf");
        JsonNode cpfGov = mapper.valueToTree(cpfProp.getExtensions().get("x-domain-governance"));
        assertThat(cpfGov.path("classification").asText()).isEqualTo("confidential");
        assertThat(cpfGov.path("dataCategory").asText()).isEqualTo("personal");
        assertThat(ResourceExportSupport.exportable(ConfidentialClassWithPersonalFieldDto.class, "cpf")).isTrue();
        assertThat(ResourceExportSupport.exportable(ConfidentialClassWithPersonalFieldDto.class, "cid")).isFalse();
    }

    @Test
    void enforcesDomainLineageAcrossOpenApiExportAndReviewerForDirectAliasDerivedAndFilterRepresentations() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomOpenApiResolver(mapper));

        Schema<?> responseSchema = converters.resolveAsResolvedSchema(
                new AnnotatedType(PessoaDetalheResponseDto.class).resolveAsRef(false)).schema;
        Schema<?> docFiscalProp = (Schema<?>) responseSchema.getProperties().get("documentoFiscal");
        JsonNode docFiscalGov = mapper.valueToTree(docFiscalProp.getExtensions().get("x-domain-governance"));
        assertThat(docFiscalGov.path("classification").asText()).isEqualTo("confidential");
        assertThat(docFiscalGov.path("dataCategory").asText()).isEqualTo("personal");
        assertThat(docFiscalGov.path("exportable").asBoolean(true)).isFalse();
        assertThat(docFiscalGov.path("source").asText()).isEqualTo("java.lineage");
        assertThat(docFiscalGov.path("lineage").path("attributeId").asText()).isEqualTo("workforce.person.taxId");
        assertThat(docFiscalGov.path("lineage").path("transformation").asText()).isEqualTo("alias");

        Schema<?> restricaoProp = (Schema<?>) responseSchema.getProperties().get("possuiRestricaoClinica");
        JsonNode restricaoGov = mapper.valueToTree(restricaoProp.getExtensions().get("x-domain-governance"));
        assertThat(restricaoGov.path("classification").asText()).isEqualTo("restricted");
        assertThat(restricaoGov.path("dataCategory").asText()).isEqualTo("sensitive_personal");
        assertThat(restricaoGov.path("exportable").asBoolean(true)).isFalse();
        assertThat(restricaoGov.path("lineage").path("transformation").asText()).isEqualTo("derived");

        assertThat(ResourceExportSupport.exportable(PessoaDetalheResponseDto.class, "nomeExibicao")).isTrue();
        assertThat(ResourceExportSupport.exportable(PessoaDetalheResponseDto.class, "documentoFiscal")).isFalse();
        assertThat(ResourceExportSupport.exportable(PessoaDetalheResponseDto.class, "possuiRestricaoClinica")).isFalse();
        assertThat(ResourceExportSupport.defaults(PessoaDetalheResponseDto.class))
                .extracting(CollectionExportField::key)
                .containsExactly("nomeExibicao");

        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();
        assertThat(reviewer.reviewStrictGovernance(PessoaInclusaoRequestDto.class).issues()).isEmpty();
        assertThat(reviewer.reviewStrictGovernance(PessoaDetalheResponseDto.class).issues()).isEmpty();
        assertThat(reviewer.reviewStrictGovernance(PessoaPesquisaFilterDto.class).issues()).isEmpty();

        SemanticMetadataReviewReport weakenedReport = reviewer.reviewStrictGovernance(WeakenedLineageDto.class);
        assertThat(weakenedReport.hasErrors()).isTrue();
        assertThat(weakenedReport.issues())
                .extracting(org.praxisplatform.uischema.authoring.SemanticMetadataReviewIssue::code)
                .contains("lineage-governance-weakened");
        assertThat(ResourceExportSupport.exportable(WeakenedLineageDto.class, "cpf")).isFalse();
        assertThat(ResourceExportSupport.exportable(WeakenedLineageDto.class, "nomeEnfraquecido")).isFalse();

        SemanticMetadataReviewReport brokenReport = reviewer.reviewStrictGovernance(BrokenLineageDto.class);
        assertThat(brokenReport.hasErrors()).isTrue();
        assertThat(brokenReport.issues())
                .extracting(org.praxisplatform.uischema.authoring.SemanticMetadataReviewIssue::code)
                .contains("invalid-domain-lineage");
        assertThat(ResourceExportSupport.exportable(BrokenLineageDto.class, "documentoInvalido")).isFalse();
    }

    public static class PessoaOriginEntity {
        @DomainGovernance(preset = DomainGovernancePreset.INTERNAL_OPERATIONAL)
        private String nome;

        @DomainGovernance(
                preset = DomainGovernancePreset.LGPD_PERSONAL_ID,
                exportable = false,
                reason = "CPF oficial da pessoa fisica protegido pela LGPD."
        )
        private String cpf;

        @DomainGovernance(
                preset = DomainGovernancePreset.LGPD_SENSITIVE_DATA,
                reason = "Codigo CID de afastamento clinico protegido pelo Art. 11 da LGPD."
        )
        private String cidAfastamento;
    }

    public record PessoaInclusaoRequestDto(
            @io.swagger.v3.oas.annotations.media.Schema(description = "Numero de inscricao da pessoa fisica no Cadastro de Pessoas Fisicas da Receita Federal informado na admissao.")
            @UISchema(label = "CPF", order = 1)
            @DomainLineage(
                    attributeId = "workforce.person.taxId",
                    sourceClass = PessoaOriginEntity.class,
                    sourcePaths = {"cpf"},
                    sourceResourceKey = "workforce.persons",
                    transformation = DomainLineageTransformation.DIRECT
            )
            String cpf
    ) {
    }

    public record PessoaDetalheResponseDto(
            @io.swagger.v3.oas.annotations.media.Schema(description = "Nome civil completo de exibicao cadastral do servidor ou dependente.")
            @UISchema(label = "Nome", order = 1)
            @DomainLineage(
                    attributeId = "workforce.person.name",
                    sourceClass = PessoaOriginEntity.class,
                    sourcePaths = {"nome"},
                    sourceResourceKey = "workforce.persons",
                    transformation = DomainLineageTransformation.ALIAS
            )
            String nomeExibicao,

            @io.swagger.v3.oas.annotations.media.Schema(description = "Documento fiscal oficial da pessoa fisica retornado sob alias de contrato de leitura.")
            @UISchema(label = "Documento Fiscal", order = 2)
            @DomainLineage(
                    attributeId = "workforce.person.taxId",
                    sourceClass = PessoaOriginEntity.class,
                    sourcePaths = {"cpf"},
                    sourceResourceKey = "workforce.persons",
                    transformation = DomainLineageTransformation.ALIAS
            )
            String documentoFiscal,

            @io.swagger.v3.oas.annotations.media.Schema(description = "Indicador derivado da existencia de restricao clinica ativa vinculada ao prontuario de afastamento.")
            @UISchema(label = "Restricao Clinica", order = 3)
            @DomainLineage(
                    attributeId = "workforce.person.has-medical-restriction",
                    sourceClass = PessoaOriginEntity.class,
                    sourcePaths = {"cidAfastamento"},
                    sourceResourceKey = "workforce.persons",
                    transformation = DomainLineageTransformation.DERIVED
            )
            Boolean possuiRestricaoClinica
    ) {
    }

    public record PessoaPesquisaFilterDto(
            @io.swagger.v3.oas.annotations.media.Schema(description = "Filtro exato pelo numero do documento fiscal (CPF) da pessoa fisica na pesquisa cadastral.")
            @UISchema(label = "CPF (Filtro)", order = 1)
            @DomainLineage(
                    attributeId = "workforce.person.taxId",
                    sourceClass = PessoaOriginEntity.class,
                    sourcePaths = {"cpf"},
                    sourceResourceKey = "workforce.persons",
                    transformation = DomainLineageTransformation.FILTER_PREDICATE
            )
            String documentoFiscalFiltro
    ) {
    }

    public record WeakenedLineageDto(
            @io.swagger.v3.oas.annotations.media.Schema(description = "Tentativa invalida de rebaixar classificacao confidencial de CPF para PUBLIC no DTO.")
            @UISchema(label = "CPF Publico", order = 1)
            @DomainLineage(
                    attributeId = "workforce.person.taxId",
                    sourceClass = PessoaOriginEntity.class,
                    sourcePaths = {"cpf"}
            )
            @DomainGovernance(classification = DomainClassification.PUBLIC)
            String cpf,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Tentativa invalida de rebaixar classificacao interna de nome exportavel para PUBLIC no DTO.")
            @UISchema(label = "Nome Publico", order = 2)
            @DomainLineage(
                    attributeId = "workforce.person.name",
                    sourceClass = PessoaOriginEntity.class,
                    sourcePaths = {"nome"}
            )
            @DomainGovernance(classification = DomainClassification.PUBLIC)
            String nomeEnfraquecido
    ) {
    }

    public record BrokenLineageDto(
            @io.swagger.v3.oas.annotations.media.Schema(description = "Campo com referencia de linhagem apontando para propriedade inexistente na entidade.")
            @UISchema(label = "Documento", order = 1)
            @DomainLineage(
                    attributeId = "workforce.person.nonexistent",
                    sourceClass = PessoaOriginEntity.class,
                    sourcePaths = {"propriedadeInexistente"}
            )
            String documentoInvalido
    ) {
    }

    @DomainGovernance(classification = DomainClassification.CONFIDENTIAL)
    public record ConfidentialClassWithPersonalFieldDto(
            @DomainGovernance(dataCategory = DomainDataCategory.PERSONAL)
            String cpf,
            @DomainGovernance(preset = org.praxisplatform.uischema.annotation.DomainGovernancePreset.LGPD_SENSITIVE_DATA)
            String cid
    ) {
    }

    public record PayrollSummaryDto(String grossAmount) {
    }

    @DomainGovernance(
            kind = DomainGovernanceKind.SECURITY,
            classification = DomainClassification.RESTRICTED,
            dataCategory = DomainDataCategory.CREDENTIAL,
            complianceTags = {"LGPD", "NON_EXPORTABLE"},
            exportable = false
    )
    public record SecretProfileDto(String alias) {
    }

    public record EmployeeExportDto(
            @UISchema(label = "Código", order = 1)
            String publicCode,
            @UISchema(label = "Folha", order = 2)
            @DomainGovernance(
                    kind = DomainGovernanceKind.PRIVACY,
                    classification = DomainClassification.CONFIDENTIAL,
                    dataCategory = DomainDataCategory.FINANCIAL,
                    exportable = false
            )
            PayrollSummaryDto payroll,
            @UISchema(label = "Perfil Secreto", order = 3)
            SecretProfileDto secretProfile
    ) {
    }

    public static class PublicNode {
        private final String label;

        public PublicNode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class RestrictedSubtypeNode extends PublicNode {
        @DomainGovernance(
                kind = DomainGovernanceKind.PRIVACY,
                classification = DomainClassification.CONFIDENTIAL,
                dataCategory = DomainDataCategory.PERSONAL,
                complianceTags = {"LGPD", "NON_EXPORTABLE"}
        )
        private final String cpf;

        public RestrictedSubtypeNode(String label, String cpf) {
            super(label);
            this.cpf = cpf;
        }

        public String getCpf() {
            return cpf;
        }
    }

    public static final class ConflictingGovernanceDto {
        @DomainGovernance(
                kind = DomainGovernanceKind.PRIVACY,
                classification = DomainClassification.CONFIDENTIAL,
                dataCategory = DomainDataCategory.FINANCIAL
        )
        private String ambiguousField;

        @DomainGovernance(
                kind = DomainGovernanceKind.PRIVACY,
                classification = DomainClassification.CONFIDENTIAL,
                dataCategory = DomainDataCategory.PERSONAL
        )
        public String getAmbiguousField() {
            return ambiguousField;
        }

        public void setAmbiguousField(String ambiguousField) {
            this.ambiguousField = ambiguousField;
        }
    }

    @SuppressWarnings("unused")
    private List<EmployeeExportDto> keepRecordReferences() {
        return List.of();
    }

    @Test
    void allowsCustomFieldsExportPathsSubjectToSafeKeysAndGovernanceAndIntegratesDynamicExportFields() {
        assertThat(ResourceExportSupport.exportable(CustomFieldResponseDto.class, "customFields.regime_teletrabalho")).isTrue();
        assertThat(ResourceExportSupport.exportable(CustomFieldResponseDto.class, "customFields.constructor")).isFalse();
        assertThat(ResourceExportSupport.exportable(RestrictedCustomFieldResponseDto.class, "customFields.seguro")).isFalse();

        @SuppressWarnings("unchecked")
        org.praxisplatform.uischema.repository.base.BaseCrudRepository<CustomFieldEntity, Long> repo =
                org.mockito.Mockito.mock(org.praxisplatform.uischema.repository.base.BaseCrudRepository.class);
        AbstractBaseQueryResourceService<CustomFieldEntity, CustomFieldResponseDto, Long, CustomFieldFilter> service =
                new AbstractBaseQueryResourceService<>(repo, CustomFieldEntity.class) {
                    @Override
                    protected org.praxisplatform.uischema.mapper.base.ResourceMapper<CustomFieldEntity, CustomFieldResponseDto, ?, ?, Long> getResourceMapper() {
                        return null;
                    }

                    @Override
                    protected List<CollectionExportField> exportFields() {
                        return List.of(
                                new CollectionExportField("nome", "Nome", true, true, "string", "nome"),
                                new CollectionExportField("customFields.bloqueado", "Bloqueado", true, true, "string", "customFields.bloqueado"));
                    }
                };
        var provider = (org.praxisplatform.uischema.customfields.CustomFieldDefinitionProvider) (resourceKey, tenant, schemaType) -> List.of(
                new org.praxisplatform.uischema.customfields.CustomFieldDescriptor(
                        "regime_teletrabalho", "Regime", null, org.praxisplatform.uischema.customfields.CustomFieldDataType.STRING,
                        null, "Contrato", 10, false, false, true, false, true, null,
                        null, null, null, null, null, null, null, List.of(), List.of(), null,
                        org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.EQUAL, java.util.Map.of()),
                new org.praxisplatform.uischema.customfields.CustomFieldDescriptor(
                        "bloqueado", "Bloqueado", null, org.praxisplatform.uischema.customfields.CustomFieldDataType.STRING,
                        null, "Contrato", 20, false, false, false, false, false, null,
                        null, null, null, null, null, null, null, List.of(), List.of(), null,
                        org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.EQUAL, java.util.Map.of()),
                new org.praxisplatform.uischema.customfields.CustomFieldDescriptor(
                        "restrito", "Restrito", null, org.praxisplatform.uischema.customfields.CustomFieldDataType.STRING,
                        null, "Contrato", 30, false, false, false, false, true, null,
                        null, null, null, null, null, null, null, List.of(), List.of(), null,
                        org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.EQUAL,
                        java.util.Map.of("classification", "restricted", "exportable", false))
        );
        service.configureCustomFieldFiltering(provider, () -> java.util.Optional.of("1"));

        List<CollectionExportField> exported = service.governedExportFields();
        assertThat(exported)
                .extracting(CollectionExportField::key)
                .containsExactly("nome", "customFields.regime_teletrabalho");
    }

    static class CustomFieldEntity {
        @org.praxisplatform.uischema.customfields.CustomFields(resourceKey = "hr.custom")
        private java.util.Map<String, Object> customFields;
    }

    static class CustomFieldFilter implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO {}

    public record CustomFieldResponseDto(
            String nome,
            @org.praxisplatform.uischema.customfields.CustomFields(resourceKey = "hr.custom")
            java.util.Map<String, Object> customFields) {}

    public record RestrictedCustomFieldResponseDto(
            @DomainGovernance(kind = DomainGovernanceKind.PRIVACY, classification = DomainClassification.RESTRICTED, exportable = false)
            @org.praxisplatform.uischema.customfields.CustomFields(resourceKey = "hr.restricted")
            java.util.Map<String, Object> customFields) {}
}
