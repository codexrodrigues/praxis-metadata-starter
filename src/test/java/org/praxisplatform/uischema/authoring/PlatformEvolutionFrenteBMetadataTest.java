package org.praxisplatform.uischema.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.facts.RuleFactCatalogDocument;
import org.praxisplatform.rules.facts.RuleFactCatalogLoader;
import org.praxisplatform.rules.facts.RuleFactDescriptor;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.annotation.LegalTreatmentPurpose;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher.DtoFactPropertyMetadata;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.capability.ResourceDecisionPointCatalogService;
import org.praxisplatform.uischema.capability.ResourceDecisionPointDescriptor;
import org.praxisplatform.uischema.capability.ResourceDecisionPointDescriptor.DecisionPointKind;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.surface.SurfaceKind;
import org.praxisplatform.uischema.surface.SurfaceScope;

class PlatformEvolutionFrenteBMetadataTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static class SampleBaseDados {
        @NotBlank
        @Schema(description = "Cadastro de Pessoa Física do servidor titular.")
        @UISchema(label = "CPF do servidor", helpText = "Informe os 11 dígitos do CPF.")
        @DomainGovernance(
                preset = DomainGovernancePreset.LGPD_PERSONAL_ID,
                complianceTags = {"LGPD_ART_5_I", "RECEITA_FEDERAL"})
        @DomainLineage(
                attributeId = "ergon.funcionarios.cpf",
                sourceResourceKey = "ergon.funcionarios")
        private String cpf;

        @Schema(description = "Valor da gratificação mensal da função de confiança.")
        @UISchema(label = "Valor da gratificação")
        @DomainGovernance(
                preset = DomainGovernancePreset.CONFIDENTIAL_FINANCIAL,
                complianceTags = {"LEI_8112_ART_62"})
        @DomainLineage(
                attributeId = "ergon.funcoes.valorGratificacao",
                sourceResourceKey = "ergon.funcoes")
        private BigDecimal valorGratificacao;

        @Schema(description = "Remuneração bruta pública submetida à transparência ativa (LAI / STF Tema 483).")
        @UISchema(label = "Remuneração bruta")
        @DomainGovernance(preset = DomainGovernancePreset.PUBLIC_TRANSPARENCY_REMUNERATION)
        @DomainLineage(
                attributeId = "ergon.fichas-financeiras.totalVantagensBruto",
                sourceResourceKey = "ergon.fichas-financeiras")
        private BigDecimal totalVantagensBruto;

        @Schema(description = "Data de início de exercício.")
        @UISchema(label = "Data de início")
        @DomainGovernance(preset = DomainGovernancePreset.INTERNAL_OPERATIONAL)
        private LocalDate dataInicio;

        private boolean ativo;

        private String campoSemGovernanca;
    }

    static class SampleResponseDTO extends SampleBaseDados {
        @Schema(description = "Identificador único do registro.")
        @UISchema(label = "Código")
        @DomainGovernance(preset = DomainGovernancePreset.INTERNAL_OPERATIONAL)
        @DomainLineage(attributeId = "ergon.sample.id", sourceResourceKey = "ergon.sample")
        private Long id;

        // Subclass getter overriding governance/label of superclass property
        @UISchema(label = "CPF validado na RFB")
        public String getCpf() {
            return null;
        }
    }

    // Java record CreateDTO omitting @DomainLineage to prove P10.8-R01 inheritance from ResponseDTO
    record SampleRecordCreateDTO(
            @NotBlank @UISchema(label = "CPF informado") String cpf,
            BigDecimal valorGratificacao,
            List<Integer> codigosLotacao) {}

    abstract static class BaseAuditController {
        @UiSurface(
                id = "audit-trail",
                kind = SurfaceKind.READ_PROJECTION,
                scope = SurfaceScope.ITEM,
                title = "Trilha de Auditoria",
                description = "Histórico herdado da superclasse.")
        public void auditTrail() {}
    }

    @ApiResource(value = "/api/ergon/sample", resourceKey = "ergon.sample")
    static class SampleResourceController extends BaseAuditController {
        @UiSurface(
                id = "detail",
                kind = SurfaceKind.READ_PROJECTION,
                scope = SurfaceScope.ITEM,
                title = "Visão 360°",
                description = "Projeção consolidada de auditoria.",
                legalPurpose = LegalTreatmentPurpose.EXTERNAL_AUDIT_CONTROL,
                legalBasisRef = "CF/88, art. 37; Lei 8.112/1990")
        public void detail() {}

        @WorkflowAction(
                id = "homologar-registro",
                title = "Homologar registro",
                description = "Homologa o ato administrativo.",
                legalPurpose = LegalTreatmentPurpose.HUMAN_RESOURCES_ADMINISTRATION,
                legalBasisRef = "Lei 8.112/1990, art. 15",
                requiredAuthorities = {"ROLE_RH_HOMOLOGADOR"},
                allowedStates = {"PENDENTE"})
        public void homologar() {}

        @WorkflowAction(
                id = "acao-sem-base-legal",
                title = "Ação sem base legal",
                description = "Deve normalizar UNSPECIFIED para null e sinalizar fail-closed.")
        public void semBaseLegal() {}

        @BulkOperation(
                mode = BulkMode.UNIFORM_UPDATE,
                evaluationOperationId = "atualizar-lote",
                atomicity = ActionCollectionAtomicity.PER_ITEM)
        public void confirmBulk() {}
    }

    @Test
    void dtoEnricherSupportsRecordsSubclassOverridesTypeInferenceAndDomainRuleServiceParity()
            throws Exception {
        DtoMetadataFactCatalogEnricher enricher = new DtoMetadataFactCatalogEnricher();
        Map<String, DtoFactPropertyMetadata> props = enricher.inspectDto(SampleResponseDTO.class);

        assertThat(props)
                .containsKeys(
                        "id",
                        "cpf",
                        "valorGratificacao",
                        "dataInicio",
                        "ativo",
                        "campoSemGovernanca");

        // Subclass getter @UISchema overrides superclass field @UISchema while preserving field @DomainLineage/@NotBlank
        DtoFactPropertyMetadata cpf = props.get("cpf");
        assertThat(cpf.labelPtBr()).isEqualTo("CPF validado na RFB");
        assertThat(cpf.descriptionPtBr())
                .isEqualTo("Cadastro de Pessoa Física do servidor titular.");
        assertThat(cpf.valueType()).isEqualTo("string");
        assertThat(cpf.nullable()).isFalse();
        assertThat(cpf.sensitivity()).isEqualTo("PERSONAL");
        assertThat(cpf.redaction()).isEqualTo("MASK");
        assertThat(cpf.lineageAttributeId()).isEqualTo("ergon.funcionarios.cpf");

        // Canonical sensitivity taxonomy (CONFIDENTIAL_FINANCIAL -> SENSITIVE + MASK + exportable=false)
        DtoFactPropertyMetadata grat = props.get("valorGratificacao");
        assertThat(grat.valueType()).isEqualTo("number");
        assertThat(grat.nullable()).isTrue();
        assertThat(grat.sensitivity()).isEqualTo("SENSITIVE");
        assertThat(grat.redaction()).isEqualTo("MASK");
        assertThat(grat.exportable()).isFalse();

        // PLAT-EVO-13: PUBLIC_TRANSPARENCY_REMUNERATION -> classification="public", dataCategory="financial",
        // exportable=true, aiVisibility="allow", sensitivity="NON_SENSITIVE", redaction="NONE"
        DtoFactPropertyMetadata bruto = props.get("totalVantagensBruto");
        assertThat(bruto.classification()).isEqualTo("public");
        assertThat(bruto.dataCategory()).isEqualTo("financial");
        assertThat(bruto.exportable()).isTrue();
        assertThat(bruto.aiVisibility()).isEqualTo("allow");
        assertThat(bruto.sensitivity()).isEqualTo("NON_SENSITIVE");
        assertThat(bruto.redaction()).isEqualTo("NONE");
        assertThat(bruto.complianceTags())
                .contains("LAI", "LGPD_ART_23", "PUBLIC_TRANSPARENCY_REMUNERATION");

        assertThat(props.get("dataInicio").valueType()).isEqualTo("date");
        assertThat(props.get("ativo").valueType()).isEqualTo("boolean");
        assertThat(props.get("ativo").nullable()).isFalse();

        // Record DTO + write-DTO lineage fallback (P10.8-R01)
        Map<String, DtoFactPropertyMetadata> writeProps =
                enricher.inspectWriteDto(SampleRecordCreateDTO.class, SampleResponseDTO.class);
        assertThat(writeProps.get("cpf").labelPtBr()).isEqualTo("CPF informado");
        assertThat(writeProps.get("cpf").lineageAttributeId()).isEqualTo("ergon.funcionarios.cpf");
        assertThat(writeProps.get("cpf").sensitivity()).isEqualTo("PERSONAL");
        assertThat(writeProps.get("codigosLotacao").valueType()).isEqualTo("number-array");

        String curatedJson =
                """
                {
                  "schemaVersion": "praxis.domain-rule-fact-catalog.v2",
                  "operations": [
                    {
                      "operationKey": "CREATE",
                      "snapshotSchema": {
                        "type": "object",
                        "additionalProperties": false,
                        "maxProperties": 2,
                        "required": ["draft", "neighborhood"],
                        "properties": {
                          "draft": {
                            "type": "object",
                            "additionalProperties": false,
                            "maxProperties": 1,
                            "required": ["cpf"],
                            "properties": {
                              "cpf": { "type": "string", "maxLength": 14 }
                            }
                          },
                          "neighborhood": {
                            "type": "object",
                            "additionalProperties": false,
                            "maxProperties": 1,
                            "required": ["activeCount"],
                            "properties": {
                              "activeCount": { "type": "integer", "minimum": 0, "maximum": 100 }
                            }
                          }
                        }
                      },
                      "facts": [
                        {
                          "schemaPointer": "#/properties/draft/properties/cpf",
                          "labels": { "pt-BR": "cpf" },
                          "descriptions": {
                            "pt-BR": "Projeção primitiva de Servidor; somente os predicados do RuleSet decidem validade."
                          },
                          "providerRef": "SampleFactSource#v1",
                          "evidenceRefs": ["PCK_FUNCIONARIOS.P_VALIDA_CPF"],
                          "sensitivity": "SENSITIVE",
                          "redaction": "OMIT"
                        },
                        {
                          "schemaPointer": "#/properties/neighborhood/properties/activeCount",
                          "labels": { "pt-BR": "Titulares ativos no setor" },
                          "descriptions": { "pt-BR": "Quantidade de titulares ativos na unidade organizacional." },
                          "providerRef": "SampleFactSource#v1",
                          "evidenceRefs": ["PCK_FUNCOES.P_VALIDA_TITULAR"],
                          "sensitivity": "NON_SENSITIVE",
                          "redaction": "NONE"
                        }
                      ]
                    }
                  ]
                }
                """;
        RuleFactCatalogDocument curatedCatalog =
                RuleFactCatalogLoader.parse(MAPPER.readTree(curatedJson));

        List<String> requestedPaths = new ArrayList<>();
        requestedPaths.add("neighborhood.activeCount");
        requestedPaths.add("draft.cpf");
        requestedPaths.add("draft.cpf"); // duplicate must be deduplicated
        requestedPaths.add("current.valorGratificacao");
        requestedPaths.add("neighborhood.ocupantes[]"); // sanitized to STABLE_FACT_PATH
        for (int i = 0; i < 300; i++) {
            requestedPaths.add("flags.syntheticFlag" + i);
        }

        ObjectNode studioCatalog =
                enricher.buildEnrichedFactCatalog(
                        MAPPER,
                        "praxis.domain-rule-fact-catalog.v1",
                        "ergon.sample.ruleset",
                        "CREATE",
                        "ErgonBootstrap",
                        requestedPaths,
                        Map.of(
                                "draft", SampleBaseDados.class,
                                "current", SampleResponseDTO.class),
                        curatedCatalog);

        JsonNode facts = studioCatalog.path("facts");
        // Strictly capped at 256 (DomainRuleService.validateFactCatalog limit)
        assertThat(facts).hasSize(DtoMetadataFactCatalogEnricher.MAX_STUDIO_FACTS);

        // 1st fact: curated catalog with automatic integer -> "number" and nullable=false
        assertThat(facts.get(0).path("valueType").asText()).isEqualTo("number");
        assertThat(facts.get(0).path("nullable").asBoolean()).isFalse();
        assertThat(facts.get(0).path("labels").path("pt-BR").asText())
                .isEqualTo("Titulares ativos no setor");

        // 2nd fact: hybrid merge of curated catalog + DTO metadata!
        // Replaces generic "Projeção primitiva..." with rich DTO description + helpText,
        // unions evidenceRefs (PL/SQL + lineage + LGPD tags), and preserves strictest SENSITIVE/OMIT!
        JsonNode cpfFact = facts.get(1);
        assertThat(cpfFact.path("path").asText()).isEqualTo("draft.cpf");
        assertThat(cpfFact.path("labels").path("pt-BR").asText()).isEqualTo("CPF do servidor");
        assertThat(cpfFact.path("descriptions").path("pt-BR").asText())
                .contains("Cadastro de Pessoa Física do servidor titular.")
                .contains("Informe os 11 dígitos do CPF.");
        assertThat(cpfFact.path("sensitivity").asText()).isEqualTo("SENSITIVE");
        assertThat(cpfFact.path("redaction").asText()).isEqualTo("OMIT");
        List<String> cpfEvidence = new ArrayList<>();
        cpfFact.path("evidenceRefs").forEach(n -> cpfEvidence.add(n.asText()));
        assertThat(cpfEvidence)
                .contains(
                        "PCK_FUNCIONARIOS.P_VALIDA_CPF",
                        "ergon.funcionarios.cpf",
                        "LGPD_ART_5_I");

        // 3rd fact: DTO BigDecimal -> "number", canonical "SENSITIVE"
        JsonNode gratFact = facts.get(2);
        assertThat(gratFact.path("path").asText()).isEqualTo("current.valorGratificacao");
        assertThat(gratFact.path("valueType").asText()).isEqualTo("number");
        assertThat(gratFact.path("sensitivity").asText()).isEqualTo("SENSITIVE");

        // 4th fact: array-item path sanitized to STABLE_FACT_PATH_PATTERN
        String fourthPath = facts.get(3).path("path").asText();
        assertThat(RuleFactDescriptor.STABLE_FACT_PATH_PATTERN.matcher(fourthPath).matches())
                .isTrue();
    }

    @Test
    void decisionPointCatalogServiceWalksHierarchyNormalizesUnspecifiedPurposeAndInheritsLineage() {
        ResourceDecisionPointCatalogService service = new ResourceDecisionPointCatalogService();
        List<ResourceDecisionPointDescriptor> points =
                service.deriveForResource(
                        SampleResourceController.class,
                        SampleResponseDTO.class,
                        SampleRecordCreateDTO.class,
                        SampleRecordCreateDTO.class);

        assertThat(points)
                .extracting(ResourceDecisionPointDescriptor::decisionPointKey)
                .contains(
                        "ergon.sample:operation:query",
                        "ergon.sample:operation:create",
                        "ergon.sample:operation:update",
                        "ergon.sample:operation:delete",
                        "ergon.sample:operation:export",
                        "ergon.sample:operation:ai-tool",
                        "ergon.sample:action:homologar-registro",
                        "ergon.sample:action:acao-sem-base-legal",
                        "ergon.sample:surface:audit-trail",
                        "ergon.sample:surface:detail",
                        "ergon.sample:bulk:atualizar-lote",
                        "ergon.sample:field.projection:cpf",
                        "ergon.sample:field.projection:campoSemGovernanca",
                        "ergon.sample:field.write:cpf",
                        "ergon.sample:field.write:codigosLotacao");

        ResourceDecisionPointDescriptor homologar =
                points.stream()
                        .filter(p -> p.decisionPointKey().equals("ergon.sample:action:homologar-registro"))
                        .findFirst()
                        .orElseThrow();
        assertThat(homologar.legalPurpose()).isEqualTo("human_resources_administration");
        assertThat(homologar.legalBasisRef()).isEqualTo("Lei 8.112/1990, art. 15");
        assertThat(homologar.explicitGovernanceDeclared()).isTrue();
        assertThat(homologar.failClosedRestricted()).isFalse();

        // LegalTreatmentPurpose.UNSPECIFIED normalized to null and flagged failClosedRestricted
        ResourceDecisionPointDescriptor semBase =
                points.stream()
                        .filter(p -> p.decisionPointKey().equals("ergon.sample:action:acao-sem-base-legal"))
                        .findFirst()
                        .orElseThrow();
        assertThat(semBase.legalPurpose()).isNull();
        assertThat(semBase.legalBasisRef()).isNull();
        assertThat(semBase.explicitGovernanceDeclared()).isFalse();
        assertThat(semBase.failClosedRestricted()).isTrue();

        // Write field on SampleRecordCreateDTO inherits lineageAttributeId from SampleResponseDTO
        ResourceDecisionPointDescriptor writeCpf =
                points.stream()
                        .filter(p -> p.decisionPointKey().equals("ergon.sample:field.write:cpf"))
                        .findFirst()
                        .orElseThrow();
        assertThat(writeCpf.lineageAttributeId()).isEqualTo("ergon.funcionarios.cpf");
        assertThat(writeCpf.failClosedRestricted()).isFalse();

        // Write field without lineage in either DTO is flagged failClosedRestricted
        ResourceDecisionPointDescriptor writeSemLineage =
                points.stream()
                        .filter(p -> p.decisionPointKey().equals("ergon.sample:field.write:codigosLotacao"))
                        .findFirst()
                        .orElseThrow();
        assertThat(writeSemLineage.lineageAttributeId()).isNull();
        assertThat(writeSemLineage.failClosedRestricted()).isTrue();
    }
}
