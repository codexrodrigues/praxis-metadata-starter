package org.praxisplatform.uischema.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.facts.RuleFactCatalogDocument;
import org.praxisplatform.rules.facts.RuleFactCatalogLoader;
import org.praxisplatform.rules.facts.RuleFactDescriptor;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.NumericFormat;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.annotation.LegalTreatmentPurpose;
import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.annotation.UiOption;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher.DtoFactPropertyMetadata;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.capability.ResourceDecisionPointCatalogService;
import org.praxisplatform.uischema.capability.ResourceDecisionPointDescriptor;
import org.praxisplatform.uischema.capability.ResourceDecisionPointDescriptor.DecisionPointKind;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.option.PraxisUiOptionEnum;
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
                attributeId = "workforce.employees.cpf",
                sourceResourceKey = "workforce.employees")
        private String cpf;

        @Schema(description = "Valor da gratificação mensal da função de confiança.")
        @UISchema(label = "Valor da gratificação")
        @DomainGovernance(
                preset = DomainGovernancePreset.CONFIDENTIAL_FINANCIAL,
                complianceTags = {"LEI_8112_ART_62"})
        @DomainLineage(
                attributeId = "workforce.roles.stipendAmount",
                sourceResourceKey = "workforce.roles")
        private BigDecimal valorGratificacao;

        @Schema(description = "Remuneração bruta pública submetida à transparência ativa (LAI / STF Tema 483).")
        @UISchema(label = "Remuneração bruta")
        @DomainGovernance(preset = DomainGovernancePreset.PUBLIC_TRANSPARENCY_REMUNERATION)
        @DomainLineage(
                attributeId = "workforce.pay-statements.grossEarningsTotal",
                sourceResourceKey = "workforce.pay-statements")
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
        @DomainLineage(attributeId = "workforce.sample.id", sourceResourceKey = "workforce.sample")
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

    @ApiResource(value = "/api/workforce/sample", resourceKey = "workforce.sample")
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
        assertThat(cpf.lineageAttributeId()).isEqualTo("workforce.employees.cpf");

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
        assertThat(writeProps.get("cpf").lineageAttributeId()).isEqualTo("workforce.employees.cpf");
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
                          "evidenceRefs": ["PKG_WORKFORCE.P_VALIDATE_TAX_ID"],
                          "sensitivity": "SENSITIVE",
                          "redaction": "OMIT"
                        },
                        {
                          "schemaPointer": "#/properties/neighborhood/properties/activeCount",
                          "labels": { "pt-BR": "Titulares ativos no setor" },
                          "descriptions": { "pt-BR": "Quantidade de titulares ativos na unidade organizacional." },
                          "providerRef": "SampleFactSource#v1",
                          "evidenceRefs": ["PKG_WORKFORCE.P_VALIDATE_HOLDER"],
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
                        "workforce.sample.ruleset",
                        "CREATE",
                        "WorkforceBootstrap",
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
        // Replaces generic "Projeção primitiva..." with clean DTO description and separates helpText into hints,
        // unions evidenceRefs (PL/SQL + lineage + LGPD tags), omits sourcePaths from lineage, and preserves strictest SENSITIVE/OMIT!
        JsonNode cpfFact = facts.get(1);
        assertThat(cpfFact.path("path").asText()).isEqualTo("draft.cpf");
        assertThat(cpfFact.path("labels").path("pt-BR").asText()).isEqualTo("CPF do servidor");
        assertThat(cpfFact.path("labels").has("en-US")).isFalse();
        assertThat(cpfFact.path("descriptions").path("pt-BR").asText())
                .isEqualTo("Cadastro de Pessoa Física do servidor titular.")
                .doesNotContain("Orientação operacional:");
        assertThat(cpfFact.path("hints").path("pt-BR").asText())
                .isEqualTo("Informe os 11 dígitos do CPF.");
        assertThat(cpfFact.path("sensitivity").asText()).isEqualTo("SENSITIVE");
        assertThat(cpfFact.path("redaction").asText()).isEqualTo("OMIT");
        assertThat(cpfFact.path("governance").path("aiVisibility").asText()).isEqualTo("deny");
        assertThat(cpfFact.path("lineage").path("attributeId").asText())
                .isEqualTo("workforce.employees.cpf");
        assertThat(cpfFact.path("lineage").path("sourceResourceKey").asText())
                .isEqualTo("workforce.employees");
        assertThat(cpfFact.path("lineage").has("sourcePaths")).isFalse();
        List<String> cpfEvidence = new ArrayList<>();
        cpfFact.path("evidenceRefs").forEach(n -> cpfEvidence.add(n.asText()));
        assertThat(cpfEvidence)
                .contains(
                        "PKG_WORKFORCE.P_VALIDATE_TAX_ID",
                        "workforce.employees.cpf",
                        "LGPD_ART_5_I");

        // 3rd fact: DTO BigDecimal -> "number", canonical "SENSITIVE", clean single-locale labels
        JsonNode gratFact = facts.get(2);
        assertThat(gratFact.path("path").asText()).isEqualTo("current.valorGratificacao");
        assertThat(gratFact.path("valueType").asText()).isEqualTo("number");
        assertThat(gratFact.path("sensitivity").asText()).isEqualTo("SENSITIVE");
        assertThat(gratFact.path("labels").has("en-US")).isFalse();
        assertThat(gratFact.path("descriptions").has("en-US")).isFalse();

        // 4th fact: array-item path sanitized to STABLE_FACT_PATH_PATTERN
        String fourthPath = facts.get(3).path("path").asText();
        assertThat(RuleFactDescriptor.STABLE_FACT_PATH_PATTERN.matcher(fourthPath).matches())
                .isTrue();
    }

    enum SampleOptionStatus implements PraxisUiOptionEnum {
        ACTIVE("Ativo", "check_circle", QuickFilterTone.SUCCESS),
        SUSPENDED("Suspenso", "pause_circle", QuickFilterTone.WARNING);

        private final String label;
        private final String icon;
        private final QuickFilterTone tone;

        SampleOptionStatus(String label, String icon, QuickFilterTone tone) {
            this.label = label;
            this.icon = icon;
            this.tone = tone;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String icon() {
            return icon;
        }

        @Override
        public QuickFilterTone tone() {
            return tone;
        }
    }

    static class NestedRoleDTO {
        @Schema(description = "Código oficial do cargo efetivo.")
        @UISchema(
                label = "Código do cargo",
                group = "Estrutura funcional",
                example = "ENG-SR-01",
                mask = "AAA-AA-99")
        @Size(min = 3, max = 20)
        @Pattern(regexp = "^[A-Z0-9-]+$")
        private String code;
    }

    static class RecursiveNodeDTO {
        private RecursiveNodeDTO self;

        @UISchema(label = "Rótulo do nó")
        private String label;
    }

    static class RichDraftDTO {
        @UISchema(label = "Código raiz")
        private String code;

        private NestedRoleDTO nested;
        private RecursiveNodeDTO node;

        @Schema(description = "Valor monetário solicitado.")
        @UISchema(
                label = "Valor solicitado",
                helpText = "Considere apenas parcelas fixas.",
                group = "Valores financeiros",
                numericFormat = NumericFormat.CURRENCY,
                numericStep = "0.01",
                example = "1500.00",
                extraProperties = @ExtensionProperty(name = "unit", value = "BRL"))
        @DecimalMin("10.00")
        @DecimalMax("50000.00")
        private BigDecimal amount;

        @Schema(description = "Percentual de reajuste autorizado.")
        @UISchema(
                label = "Percentual",
                preset = UISchemaPreset.PERCENTAGE_RATE)
        private BigDecimal rate;

        @Schema(description = "Situação cadastral do vínculo.")
        @UISchema(
                label = "Situação",
                group = "Dados do vínculo",
                optionEnum = SampleOptionStatus.class)
        private String status;

        @Schema(description = "Canal prioritário de atendimento.")
        @UISchema(
                label = "Canal",
                optionItems = {
                    @UiOption(value = "PORTAL", label = "Portal Web", tone = QuickFilterTone.INFO),
                    @UiOption(value = "API", label = "Integração API", tone = QuickFilterTone.NEUTRAL)
                })
        private String channel;

        @Schema(description = "Unidade organizacional de lotação.")
        @UISchema(
                label = "Órgão de lotação",
                controlType = FieldControlType.ENTITY_LOOKUP,
                endpoint = "/api/org-units/options/filter")
        @DomainGovernance(
                classification = org.praxisplatform.uischema.annotation.DomainClassification.INTERNAL,
                dataCategory = org.praxisplatform.uischema.annotation.DomainDataCategory.OPERATIONAL)
        @DomainLineage(
                attributeId = "workforce.orgUnits.id",
                sourceResourceKey = "workforce.org-units")
        private Long orgUnitId;

        @Schema(description = "Chave secreta de autorização.")
        @UISchema(
                label = "Token secreto",
                example = "secret-token-value",
                optionItems = {
                    @UiOption(value = "ROOT", label = "Acesso Root")
                })
        @DomainGovernance(
                classification = org.praxisplatform.uischema.annotation.DomainClassification.RESTRICTED,
                dataCategory = org.praxisplatform.uischema.annotation.DomainDataCategory.CREDENTIAL)
        private String secretCredential;
    }

    @Test
    void enrichesNestedTraversalPresentationAllowedValuesConstraintsAndEnforcesSecretRedaction() {
        DtoMetadataFactCatalogEnricher enricher = new DtoMetadataFactCatalogEnricher();

        // Multi-level nested lookup resolves NestedRoleDTO.code without false-matching root RichDraftDTO.code
        Optional<DtoFactPropertyMetadata> nestedCode =
                enricher.resolveFactPath("draft.nested.code", Map.of("draft", RichDraftDTO.class));
        assertThat(nestedCode).isPresent();
        assertThat(nestedCode.get().labelPtBr()).isEqualTo("Código do cargo");
        assertThat(nestedCode.get().groupKey()).isEqualTo("estrutura-funcional");
        assertThat(nestedCode.get().groupLabelPtBr()).isEqualTo("Estrutura funcional");

        // Cycle safety: 1st hop works, repeated type self-cycle terminates cleanly as empty
        assertThat(enricher.resolveFactPath("draft.node.label", Map.of("draft", RichDraftDTO.class)))
                .isPresent();
        assertThat(
                        enricher.resolveFactPath(
                                "draft.node.self.label", Map.of("draft", RichDraftDTO.class)))
                .isEmpty();
        assertThat(
                        enricher.resolveFactPath(
                                "draft.node.self.self.label", Map.of("draft", RichDraftDTO.class)))
                .isEmpty();

        ObjectNode catalog =
                enricher.buildEnrichedFactCatalog(
                        MAPPER,
                        "praxis.domain-rule-fact-catalog.v1",
                        "workforce.rich.ruleset",
                        "CREATE",
                        "WorkforceRichSource",
                        List.of(
                                "draft.nested.code",
                                "draft.amount",
                                "draft.rate",
                                "draft.status",
                                "draft.channel",
                                "draft.orgUnitId",
                                "draft.secretCredential"),
                        Map.of("draft", RichDraftDTO.class),
                        null);

        JsonNode facts = catalog.path("facts");
        assertThat(facts).hasSize(7);

        JsonNode codeNode = facts.get(0);
        assertThat(codeNode.path("mask").asText()).isEqualTo("AAA-AA-99");
        assertThat(codeNode.path("example").asText()).isEqualTo("ENG-SR-01");
        assertThat(codeNode.path("constraints").path("minLength").asInt()).isEqualTo(3);
        assertThat(codeNode.path("constraints").path("maxLength").asInt()).isEqualTo(20);
        assertThat(codeNode.path("constraints").path("pattern").asText()).isEqualTo("^[A-Z0-9-]+$");

        JsonNode amountNode = facts.get(1);
        assertThat(amountNode.path("valuePresentation").asText()).isEqualTo("currency");
        assertThat(amountNode.path("unit").asText()).isEqualTo("BRL");
        assertThat(amountNode.path("hints").path("pt-BR").asText())
                .isEqualTo("Considere apenas parcelas fixas.");
        assertThat(amountNode.path("groupKey").asText()).isEqualTo("valores-financeiros");
        assertThat(amountNode.path("constraints").path("min").asText()).isEqualTo("10.00");
        assertThat(amountNode.path("constraints").path("max").asText()).isEqualTo("50000.00");
        assertThat(amountNode.path("constraints").path("step").asText()).isEqualTo("0.01");

        JsonNode rateNode = facts.get(2);
        assertThat(rateNode.path("valuePresentation").asText()).isEqualTo("percentage");
        assertThat(rateNode.path("unit").asText()).isEqualTo("%");

        JsonNode statusNode = facts.get(3);
        assertThat(statusNode.path("allowedValues")).hasSize(2);
        assertThat(statusNode.path("allowedValues").get(0).path("value").asText()).isEqualTo("ACTIVE");
        assertThat(statusNode.path("allowedValues").get(0).path("labels").path("pt-BR").asText())
                .isEqualTo("Ativo");
        assertThat(statusNode.path("allowedValues").get(0).path("tone").asText()).isEqualTo("success");

        JsonNode channelNode = facts.get(4);
        assertThat(channelNode.path("allowedValues")).hasSize(2);
        assertThat(channelNode.path("allowedValues").get(1).path("value").asText()).isEqualTo("API");

        JsonNode lookupNode = facts.get(5);
        assertThat(lookupNode.path("optionSourceRef").path("sourceKey").asText()).isEqualTo("org-units");
        assertThat(lookupNode.path("optionSourceRef").path("resourceKey").asText())
                .isEqualTo("workforce.org-units");

        JsonNode secretNode = facts.get(6);
        assertThat(secretNode.path("sensitivity").asText()).isEqualTo("SECRET");
        assertThat(secretNode.path("redaction").asText()).isEqualTo("OMIT");
        assertThat(secretNode.path("governance").path("aiVisibility").asText()).isEqualTo("deny");
        assertThat(secretNode.has("example")).isFalse();
        assertThat(secretNode.has("allowedValues")).isFalse();
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
                        "workforce.sample:operation:query",
                        "workforce.sample:operation:create",
                        "workforce.sample:operation:update",
                        "workforce.sample:operation:delete",
                        "workforce.sample:operation:export",
                        "workforce.sample:operation:ai-tool",
                        "workforce.sample:operation:stats-inference",
                        "workforce.sample:operation:stats-anomalies",
                        "workforce.sample:operation:ai-tool",
                        "workforce.sample:action:homologar-registro",
                        "workforce.sample:action:acao-sem-base-legal",
                        "workforce.sample:surface:audit-trail",
                        "workforce.sample:surface:detail",
                        "workforce.sample:bulk:atualizar-lote",
                        "workforce.sample:field.projection:cpf",
                        "workforce.sample:field.projection:campoSemGovernanca",
                        "workforce.sample:field.write:cpf",
                        "workforce.sample:field.write:codigosLotacao");

        ResourceDecisionPointDescriptor homologar =
                points.stream()
                        .filter(p -> p.decisionPointKey().equals("workforce.sample:action:homologar-registro"))
                        .findFirst()
                        .orElseThrow();
        assertThat(homologar.legalPurpose()).isEqualTo("human_resources_administration");
        assertThat(homologar.legalBasisRef()).isEqualTo("Lei 8.112/1990, art. 15");
        assertThat(homologar.explicitGovernanceDeclared()).isTrue();
        assertThat(homologar.failClosedRestricted()).isFalse();

        // LegalTreatmentPurpose.UNSPECIFIED normalized to null and flagged failClosedRestricted
        ResourceDecisionPointDescriptor semBase =
                points.stream()
                        .filter(p -> p.decisionPointKey().equals("workforce.sample:action:acao-sem-base-legal"))
                        .findFirst()
                        .orElseThrow();
        assertThat(semBase.legalPurpose()).isNull();
        assertThat(semBase.legalBasisRef()).isNull();
        assertThat(semBase.explicitGovernanceDeclared()).isFalse();
        assertThat(semBase.failClosedRestricted()).isTrue();

        // Write field on SampleRecordCreateDTO inherits lineageAttributeId from SampleResponseDTO
        ResourceDecisionPointDescriptor writeCpf =
                points.stream()
                        .filter(p -> p.decisionPointKey().equals("workforce.sample:field.write:cpf"))
                        .findFirst()
                        .orElseThrow();
        assertThat(writeCpf.lineageAttributeId()).isEqualTo("workforce.employees.cpf");
        assertThat(writeCpf.failClosedRestricted()).isFalse();

        // Write field without lineage in either DTO is flagged failClosedRestricted
        ResourceDecisionPointDescriptor writeSemLineage =
                points.stream()
                        .filter(p -> p.decisionPointKey().equals("workforce.sample:field.write:codigosLotacao"))
                        .findFirst()
                        .orElseThrow();
        assertThat(writeSemLineage.lineageAttributeId()).isNull();
        assertThat(writeSemLineage.failClosedRestricted()).isTrue();

        ResourceDecisionPointDescriptor statsInferencePoint = points.stream()
                .filter(p -> p.decisionPointKey().equals("workforce.sample:operation:stats-inference"))
                .findFirst()
                .orElseThrow();
        assertThat(statsInferencePoint.kind()).isEqualTo(DecisionPointKind.STATISTICAL_INFERENCE);

        ResourceDecisionPointDescriptor statsAnomaliesPoint = points.stream()
                .filter(p -> p.decisionPointKey().equals("workforce.sample:operation:stats-anomalies"))
                .findFirst()
                .orElseThrow();
        assertThat(statsAnomaliesPoint.kind()).isEqualTo(DecisionPointKind.ANOMALY_SCAN);
    }

    @Test
    void resolveFactPathFallsBackFromDraftToCurrentForInheritedWriteAndResponseOnlyFacts() {
        DtoMetadataFactCatalogEnricher enricher = new DtoMetadataFactCatalogEnricher();
        Map<String, Class<?>> bindings =
                Map.of(
                        "draft", SampleRecordCreateDTO.class,
                        "current", SampleResponseDTO.class);

        // 1-hop write property inherits governance, description, and lineage from current ResponseDTO
        DtoFactPropertyMetadata draftCpf = enricher.resolveFactPath("draft.cpf", bindings).orElseThrow();
        assertThat(draftCpf.labelPtBr()).isEqualTo("CPF informado");
        assertThat(draftCpf.descriptionPtBr())
                .isEqualTo("Cadastro de Pessoa Física do servidor titular.");
        assertThat(draftCpf.lineageAttributeId()).isEqualTo("workforce.employees.cpf");
        assertThat(draftCpf.sensitivity()).isEqualTo("PERSONAL");
        assertThat(draftCpf.redaction()).isEqualTo("MASK");

        // When current DTO does not override @UISchema on getter, helpText is inherited too
        DtoFactPropertyMetadata draftCpfFromBase =
                enricher.resolveFactPath(
                                "draft.cpf",
                                Map.of(
                                        "draft", SampleRecordCreateDTO.class,
                                        "current", SampleBaseDados.class))
                        .orElseThrow();
        assertThat(draftCpfFromBase.helpTextPtBr()).isEqualTo("Informe os 11 dígitos do CPF.");

        // Unannotated write property inherits label, governance, and lineage from current ResponseDTO
        DtoFactPropertyMetadata draftGrat =
                enricher.resolveFactPath("draft.valorGratificacao", bindings).orElseThrow();
        assertThat(draftGrat.labelPtBr()).isEqualTo("Valor da gratificação");
        assertThat(draftGrat.lineageAttributeId()).isEqualTo("workforce.roles.stipendAmount");

        // Response-only field referenced under draft falls back to current ResponseDTO
        DtoFactPropertyMetadata draftId = enricher.resolveFactPath("draft.id", bindings).orElseThrow();
        assertThat(draftId.labelPtBr()).isEqualTo("Código");
        assertThat(draftId.lineageAttributeId()).isEqualTo("workforce.sample.id");

        // Missing draft binding falls back onto current; multi-hop traversal falls back onto current
        Map<String, Class<?>> currentOnlyWithNested =
                Map.of("draft", SampleRecordCreateDTO.class, "current", RichDraftDTO.class);
        DtoFactPropertyMetadata nestedCode =
                enricher.resolveFactPath("draft.nested.code", currentOnlyWithNested).orElseThrow();
        assertThat(nestedCode.labelPtBr()).isEqualTo("Código do cargo");

        DtoFactPropertyMetadata currentOnlyAmount =
                enricher.resolveFactPath("draft.amount", Map.of("current", RichDraftDTO.class))
                        .orElseThrow();
        assertThat(currentOnlyAmount.labelPtBr()).isEqualTo("Valor solicitado");
        assertThat(currentOnlyAmount.helpTextPtBr()).isEqualTo("Considere apenas parcelas fixas.");
    }

    static class LabelAndHelpOnlyDTO {
        @UISchema(label = "Início de exercício", helpText = "Informe a data no formato dd/MM/yyyy.")
        private LocalDate dtexerc;

        @SuppressWarnings("unused")
        private List<? extends NestedRoleDTO> cargos;
    }

    static class WeakWriteDTO {
        @DomainGovernance(preset = DomainGovernancePreset.INTERNAL_OPERATIONAL)
        @DecimalMin("100.00")
        @UISchema(label = "Valor pago", helpText = "Somente valor líquido.")
        private BigDecimal salario;

        @UISchema(
                label = "Faixa",
                optionItems = {
                    @UiOption(value = "1", label = "Faixa 1"),
                    @UiOption(value = "1.0", label = "Faixa 1 duplicada"),
                    @UiOption(value = "2", label = "Faixa 2")
                })
        private Integer faixa;
    }

    static class StrongReadDTO {
        @Schema(description = "Remuneração bruta protegida por sigilo bancário.")
        @DomainGovernance(
                preset = DomainGovernancePreset.CONFIDENTIAL_FINANCIAL,
                complianceTags = {"BACEN_SIGILO"})
        @DecimalMax("50.00")
        private BigDecimal salario;
    }

    @Test
    void resolveStudioValueTypeEditorialPrecedenceFailClosedWriteGovernanceAndArrayUnwrapHold()
            throws Exception {
        DtoMetadataFactCatalogEnricher enricher = new DtoMetadataFactCatalogEnricher();

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
                            "required": ["dtexerc"],
                            "properties": {
                              "dtexerc": { "type": "string", "format": "date", "maxLength": 32 }
                            }
                          },
                          "neighborhood": {
                            "type": "object",
                            "additionalProperties": false,
                            "maxProperties": 1,
                            "required": ["activeCount"],
                            "properties": {
                              "activeCount": { "type": "integer", "minimum": 0, "maximum": 1000 }
                            }
                          }
                        }
                      },
                      "facts": [
                        {
                          "schemaPointer": "#/properties/draft/properties/dtexerc",
                          "labels": { "pt-BR": "Início do exercício" },
                          "descriptions": { "pt-BR": "Data oficial de entrada em exercício no vínculo funcional." },
                          "providerRef": "WorkforceSource#v1",
                          "evidenceRefs": ["PKG_WORKFORCE.P_EXERC"],
                          "sensitivity": "NON_SENSITIVE",
                          "redaction": "NONE"
                        },
                        {
                          "schemaPointer": "#/properties/neighborhood/properties/activeCount",
                          "labels": { "pt-BR": "Titulares ativos" },
                          "descriptions": { "pt-BR": "Contagem de titulares ativos." },
                          "providerRef": "WorkforceSource#v1",
                          "evidenceRefs": ["PKG_WORKFORCE.P_COUNT"],
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

        ObjectNode studioCatalog =
                enricher.buildEnrichedFactCatalog(
                        MAPPER,
                        "praxis.domain-rule-fact-catalog.v1",
                        "workforce.vinculos.ruleset",
                        "CREATE",
                        "WorkforceSeed",
                        List.of("draft.dtexerc", "neighborhood.activeCount", "flags.customFlag"),
                        Map.of("draft", LabelAndHelpOnlyDTO.class),
                        curatedCatalog,
                        path -> "flags.customFlag".equals(path) ? "boolean" : "string");

        JsonNode facts = studioCatalog.path("facts");
        assertThat(facts).hasSize(3);

        // 1. Authoritative valueType wins over fallback valueTypeResolver; DTO label wins over divergent curated label;
        //    curated description is preserved instead of overwritten by helpText!
        JsonNode dtexercFact = facts.get(0);
        assertThat(dtexercFact.path("valueType").asText()).isEqualTo("date");
        assertThat(dtexercFact.path("labels").path("pt-BR").asText())
                .isEqualTo("Início de exercício");
        assertThat(dtexercFact.path("descriptions").path("pt-BR").asText())
                .isEqualTo("Data oficial de entrada em exercício no vínculo funcional.");
        assertThat(dtexercFact.path("hints").path("pt-BR").asText())
                .isEqualTo("Informe a data no formato dd/MM/yyyy.");

        JsonNode countFact = facts.get(1);
        assertThat(countFact.path("valueType").asText()).isEqualTo("number");

        // Unbound synthetic path falls back cleanly to valueTypeResolver
        JsonNode flagFact = facts.get(2);
        assertThat(flagFact.path("valueType").asText()).isEqualTo("boolean");
        assertThat(flagFact.path("labels").has("en-US")).isFalse();
        assertThat(flagFact.path("descriptions").has("en-US")).isFalse();

        // 2. Fail-closed write governance + constraint conflict + numeric allowedValues dedup
        Map<String, DtoFactPropertyMetadata> writeProps =
                enricher.inspectWriteDto(WeakWriteDTO.class, StrongReadDTO.class);
        DtoFactPropertyMetadata salario = writeProps.get("salario");
        assertThat(salario.classification()).isEqualTo("confidential");
        assertThat(salario.sensitivity()).isEqualTo("SENSITIVE");
        assertThat(salario.redaction()).isEqualTo("MASK");
        assertThat(salario.exportable()).isFalse();
        assertThat(salario.aiVisibility()).isEqualTo("mask");
        assertThat(salario.complianceTags()).contains("BACEN_SIGILO");
        assertThat(salario.descriptionPtBr())
                .isEqualTo("Remuneração bruta protegida por sigilo bancário.");
        assertThat(salario.helpTextPtBr()).isEqualTo("Somente valor líquido.");
        assertThat(salario.constraints().min()).isEqualByComparingTo("100.00");
        assertThat(salario.constraints().max()).isNull();

        DtoFactPropertyMetadata faixa = writeProps.get("faixa");
        assertThat(faixa.allowedValues()).hasSize(2);

        // 3. Path element unwrapping (:items / :any) and wildcard generic traversal
        Map<String, Class<?>> recordBindings =
                Map.of(
                        "draft", SampleRecordCreateDTO.class,
                        "current", LabelAndHelpOnlyDTO.class);
        assertThat(
                        enricher.resolveFactPath("draft.codigosLotacao:items", recordBindings)
                                .orElseThrow()
                                .valueType())
                .isEqualTo("number");
        assertThat(
                        enricher.resolveFactPath("current.cargos:any.code", recordBindings)
                                .orElseThrow()
                                .labelPtBr())
                .isEqualTo("Código do cargo");
    }
}
