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
                description = "Descricao valida do segundo fato deterministico."
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
}
