package org.praxisplatform.uischema.authoring;

import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.annotation.AiUsageMode;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernanceKind;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMetadataReviewerTest {

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
                aiUsage = @AiUsagePolicy(visibility = AiUsageMode.MASK, trainingUse = AiUsageMode.DENY)
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
}
