package org.praxisplatform.uischema.authoring;

import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.annotation.AiTrainingUseMode;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernanceKind;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.fact.ResourceFact;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMetadataReviewerTest {

    @Test
    void excludesInternalStateOnlyWhenBothWireAndSchemaExplicitlyIgnoreIt() {
        var issues = new SemanticMetadataReviewer().review(InternalStateDTO.class).issues();
        assertTrue(issues.stream().noneMatch(i -> i.fieldName().equals("internal")));
        assertTrue(issues.stream().anyMatch(i -> i.fieldName().equals("hiddenOnly")));
        assertTrue(issues.stream().anyMatch(i -> i.fieldName().equals("publicWire")));
    }

    private static class InternalStateDTO {
        @Schema(hidden=true) @com.fasterxml.jackson.annotation.JsonIgnore private String internal;
        @Schema(hidden=true) private String hiddenOnly;
        @Schema(hidden=true) @com.fasterxml.jackson.annotation.JsonIgnore(false) private String publicWire;
    }

    @Test
    void acceptsDeliberateDescriptionsAndGovernedContextFields() {
        SemanticMetadataReviewReport report = new SemanticMetadataReviewer().review(GoodMetadataDTO.class);

        assertFalse(report.hasErrors());
        assertTrue(report.issues().isEmpty(), report.issues().toString());
    }

    @Test
    void flagsGeneratedLookingDescriptionsAndPrivateContextLeakage() {
        SemanticMetadataReviewReport report = new SemanticMetadataReviewer().review(BadMetadataDTO.class);
        Set<String> codes = report.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());

        assertTrue(report.hasErrors());
        assertTrue(codes.contains("schema-description-copies-ui-label"));
        assertTrue(codes.contains("schema-description-derived-from-field-name"));
        assertTrue(codes.contains("schema-description-missing"));
        assertTrue(codes.contains("preset-without-domain-description"));
        assertTrue(codes.contains("public-private-context-field-without-governance"));
    }

    @Test
    void reviewsInheritedDtoFields() {
        SemanticMetadataReviewReport report = new SemanticMetadataReviewer().review(ChildBadMetadataDTO.class);
        Set<String> inheritedIssueFields = report.issues().stream()
                .filter(issue -> issue.code().equals("public-private-context-field-without-governance"))
                .map(SemanticMetadataReviewIssue::fieldName)
                .collect(Collectors.toSet());

        assertTrue(inheritedIssueFields.contains("userId"));
    }

    @Test
    void reviewsJavaRecordDtosAndEditorialLabels() {
        SemanticMetadataReviewReport goodReport = new SemanticMetadataReviewer().review(GoodRecordDTO.class);
        assertFalse(goodReport.hasErrors(), goodReport.issues().toString());
        assertTrue(goodReport.issues().isEmpty(), goodReport.issues().toString());

        SemanticMetadataReviewReport badReport = new SemanticMetadataReviewer().review(BadEditorialRecordDTO.class);
        assertTrue(badReport.hasErrors());
        Set<String> codes = badReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("ui-label-looks-like-sentence"));
        assertTrue(codes.contains("sensitive-field-without-governance"));

        SemanticMetadataReviewIssue cpfWarning = badReport.issues().stream()
                .filter(i -> i.code().equals("sensitive-field-without-governance"))
                .findFirst()
                .orElseThrow();
        assertEquals(SemanticMetadataIssueSeverity.WARNING, cpfWarning.severity());
    }

    @Test
    void reviewsResourceFactEditorialQuality() {
        SemanticMetadataReviewReport goodFacts = new SemanticMetadataReviewer().reviewResourceFacts(GoodResourceFactHost.class);
        assertFalse(goodFacts.hasErrors(), goodFacts.issues().toString());

        SemanticMetadataReviewReport badFacts = new SemanticMetadataReviewer().reviewResourceFacts(BadResourceFactHost.class);
        assertTrue(badFacts.hasErrors());
        Set<String> codes = badFacts.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("resource-fact-description-missing"));
        assertTrue(codes.contains("resource-fact-label-missing"));
        assertTrue(codes.contains("resource-fact-label-looks-like-sentence"));
        assertTrue(codes.contains("resource-fact-threshold-range-inverted"));
        assertTrue(codes.contains("resource-fact-threshold-bounds-missing"));
        assertTrue(codes.contains("resource-fact-threshold-tone-missing"));
    }

    @Test
    void reviewsComputedGetterProperties() {
        SemanticMetadataReviewReport report = new SemanticMetadataReviewer().review(ComputedGetterDTO.class);
        assertTrue(report.hasErrors());
        Set<String> codes = report.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("schema-description-missing"));
        assertTrue(codes.contains("ui-label-looks-like-sentence"));
        assertTrue(report.issues().stream().noneMatch(i -> i.fieldName().equals("taxaOcupacaoPct")));
    }

    private static class ComputedGetterDTO {
        @Schema(description = "Percentual calculado de vagas ocupadas em relacao ao total de vagas autorizadas.")
        @UISchema(label = "Taxa de ocupação", readOnly = true)
        public Double getTaxaOcupacaoPct() {
            return 85.0;
        }

        @UISchema(label = "Este rotulo calculado tem mais de trinta e seis caracteres e ponto final.")
        public String getIndicadorSemDescricao() {
            return "ALERTA";
        }
    }

    private record GoodRecordDTO(
            @Schema(description = "Identificador funcional unico do cargo no quadro de pessoal.")
            @UISchema(label = "ID do cargo", controlType = FieldControlType.NUMERIC_TEXT_BOX)
            Long id,

            @DomainGovernance(
                    kind = DomainGovernanceKind.PRIVACY,
                    classification = DomainClassification.CONFIDENTIAL,
                    dataCategory = DomainDataCategory.PERSONAL,
                    aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK, trainingUse = AiTrainingUseMode.DENY)
            )
            @Schema(description = "Cadastro de Pessoa Fisica do servidor titular usado na qualificacao cadastral.")
            @UISchema(label = "CPF", controlType = FieldControlType.CPF_CNPJ_INPUT)
            String cpf
    ) {}

    private record BadEditorialRecordDTO(
            @Schema(description = "Descricao detalhada do motivo de afastamento funcional do servidor.")
            @UISchema(label = "Descricao detalhada do motivo de afastamento funcional.")
            String motivoAfastamento,

            @Schema(description = "Cadastro de Pessoa Fisica do servidor titular sem anotacao de governanca.")
            @UISchema(label = "CPF")
            String cpf
    ) {}

    @ResourceFact(
            key = "deficit-vagas",
            resourceKey = "administracao-pessoal.cargos",
            label = "Déficit de vagas",
            description = "Quantidade de vagas ocupadas acima do limite legal autorizado para o cargo."
    )
    private static class GoodResourceFactHost {
        @ResourceFact(
                key = "ocupacao-percentual",
                resourceKey = "administracao-pessoal.cargos",
                label = "Taxa de ocupação",
                description = "Percentual de vagas preenchidas em relacao ao quadro autorizado."
        )
        public void computeFacts() {}
    }

    @ResourceFact(
            key = "sem-descricao",
            resourceKey = "administracao-pessoal.cargos",
            label = "Fato curto",
            description = ""
    )
    private static class BadResourceFactHost {
        @ResourceFact(
                key = "rotulo-longo",
                resourceKey = "administracao-pessoal.cargos",
                label = "Este rotulo tem mais de trinta e seis caracteres e ponto.",
                description = "Descricao valida do fato deterministico."
        )
        @ResourceFact(
                key = "sem-rotulo",
                resourceKey = "administracao-pessoal.cargos",
                label = "",
                description = "Descricao valida do segundo fato deterministico.",
                thresholds = {
                        @org.praxisplatform.uischema.annotation.Threshold(min = 90.0, max = 50.0, tone = ""),
                        @org.praxisplatform.uischema.annotation.Threshold(tone = "danger")
                }
        )
        public void computeFacts() {}
    }

    private static class GoodMetadataDTO {
        @Schema(description = "Codigo operacional usado pela folha para vincular a rubrica ao catalogo corporativo de pagamento.")
        @UISchema(label = "Codigo", preset = UISchemaPreset.ENTERPRISE_CODE)
        String codigo;

        @Schema(description = "Valor mensal previsto para calculo financeiro, apresentado como moeda sem alterar a regra de negocio.")
        @UISchema(label = "Valor", preset = UISchemaPreset.MONETARY_AMOUNT)
        Double valorMensal;

        @DomainGovernance(
                kind = DomainGovernanceKind.SECURITY,
                classification = DomainClassification.INTERNAL,
                dataCategory = DomainDataCategory.OPERATIONAL,
                aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK, trainingUse = AiTrainingUseMode.DENY)
        )
        @Schema(description = "Identificador interno do tenant usado apenas para segregacao operacional e auditoria.")
        @UISchema(label = "Tenant", controlType = FieldControlType.INPUT)
        String tenantId;
    }

    private static class BadMetadataDTO {
        @Schema(description = "Codigo")
        @UISchema(label = "Codigo", preset = UISchemaPreset.ENTERPRISE_CODE)
        String codigo;

        @Schema(description = "Valor mensal")
        @UISchema(label = "Valor", preset = UISchemaPreset.MONETARY_AMOUNT)
        Double valorMensal;

        @UISchema(label = "Status", preset = UISchemaPreset.ENTERPRISE_STATUS)
        String status;

        @Schema(description = "User")
        String userId;
    }

    private static class BaseBadMetadataDTO {
        @Schema(description = "User")
        String userId;
    }

    private static class ChildBadMetadataDTO extends BaseBadMetadataDTO {
        @Schema(description = "Codigo operacional usado pela folha para vincular a rubrica ao catalogo corporativo de pagamento.")
        @UISchema(label = "Codigo", preset = UISchemaPreset.ENTERPRISE_CODE)
        String codigo;
    }

    @Test
    void flagsResourceIdentityOverlapMissingIdentityFieldsAndMissingReferenceValueFields() {
        SemanticMetadataReviewReport report = new SemanticMetadataReviewer().review(BadQuickViewIdentityDTO.class);
        assertTrue(report.hasErrors());
        Set<String> codes = report.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(codes.contains("quick-view-repeats-identity-field"));
        assertTrue(codes.contains("resource-identity-metadata-repeats-header-field"));
        assertTrue(codes.contains("resource-identity-field-missing"));
        assertTrue(codes.contains("resource-reference-value-field-missing"));
        assertTrue(codes.contains("uischema-group-case-divergence"));
    }

    @org.praxisplatform.uischema.annotation.ResourceIdentity(
            keyField = "id",
            titleField = "nome",
            subtitleField = "subtituloInexistente",
            metadataFields = {"id", "situacao"}
    )
    private static class BadQuickViewIdentityDTO {
        @Schema(description = "Identificador unico do registro cadastral.")
        @UISchema(label = "ID", group = "endereco")
        Long id;

        @Schema(description = "Nome completo do registro para exibicao principal.")
        @UISchema(label = "Nome", group = "Endereço")
        String nome;

        @Schema(description = "Situacao operacional do registro no ciclo de vida.")
        @UISchema(label = "Situação", quickViewVisible = true, quickViewOrder = 1)
        String situacao;

        @Schema(description = "Nome do departamento de lotacao do registro.")
        @UISchema(label = "Departamento", quickViewVisible = true, quickViewOrder = 2)
        @org.praxisplatform.uischema.annotation.ResourceReference(
                resourceKey = "human-resources.departamentos",
                valueField = "departamentoIdInexistente",
                surfaceId = "detail",
                previewSurfaceId = "detail"
        )
        String departamentoNome;
    }

    @Test
    void honorsSubclassGetterOverridesForQuickViewAndResourceReference() {
        SemanticMetadataReviewReport report = new SemanticMetadataReviewer().review(SubclassOverrideQuickViewDTO.class);
        assertFalse(report.hasErrors(), report.issues().toString());
        assertTrue(report.issues().isEmpty(), report.issues().toString());
    }

    private static class BaseQuickViewDatosDTO {
        @Schema(description = "Descricao funcional completa do assentamento no historico do servidor.")
        @UISchema(label = "Descrição")
        String descricao;

        @Schema(description = "Nome civil ou social da pessoa vinculada ao assentamento funcional.")
        @UISchema(label = "Pessoa")
        String pessoaNome;

        @Schema(description = "Situacao cadastral vigente do assentamento funcional.")
        @UISchema(label = "Situação", quickViewVisible = true, quickViewOrder = 1)
        String situacao;

        public String getDescricao() {
            return descricao;
        }

        public String getSituacao() {
            return situacao;
        }
    }

    @org.praxisplatform.uischema.annotation.ResourceIdentity(
            keyField = "id",
            titleField = "descricao",
            subtitleField = "pessoaNome",
            metadataFields = {"situacao"}
    )
    private static class SubclassOverrideQuickViewDTO extends BaseQuickViewDatosDTO {
        @Schema(description = "Identificador unico imutavel do assentamento funcional.")
        @UISchema(label = "ID")
        Long id;

        @Override
        @org.praxisplatform.uischema.annotation.ResourceReference(
                resourceKey = "administracao-pessoal.averbacoes",
                valueField = "id",
                surfaceId = "detail",
                previewSurfaceId = "detail"
        )
        public String getDescricao() {
            return super.getDescricao();
        }

        @Override
        @UISchema(label = "Situação", quickViewVisible = false)
        public String getSituacao() {
            return super.getSituacao();
        }
    }
}
