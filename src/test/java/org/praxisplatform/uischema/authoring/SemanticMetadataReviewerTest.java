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
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.fact.ResourceFact;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertTrue(codes.contains("sensitive-field-without-governance"));
        assertTrue(report.issues().stream().noneMatch(i -> i.fieldName().equals("taxaOcupacaoPct")));
    }

    @Test
    void reviewsStrictGovernanceAndLgpdArt11Tokens() {
        SemanticMetadataReviewReport strictReport =
                new SemanticMetadataReviewer().reviewStrictGovernance(LgpdSensitiveUnannotatedDTO.class);
        assertTrue(strictReport.hasErrors());
        Set<String> errorFields = strictReport.issues().stream()
                .filter(i -> i.severity() == SemanticMetadataIssueSeverity.ERROR
                        && "sensitive-field-without-governance".equals(i.code()))
                .map(SemanticMetadataReviewIssue::fieldName)
                .collect(Collectors.toSet());
        assertTrue(errorFields.containsAll(Set.of("raca", "grupoSanguineo", "obsDeficiencia", "remuneracao")));
    }

    private record LgpdSensitiveUnannotatedDTO(
            @Schema(description = "Autodeclaracao etnico-racial do servidor para fins censitarios e de cotas.")
            @UISchema(label = "Raça/Cor")
            Integer raca,

            @Schema(description = "Tipagem sanguinea e fator Rh registrados no prontuario medico funcional.")
            @UISchema(label = "Grupo Sanguíneo")
            String grupoSanguineo,

            @Schema(description = "Laudo descritivo de restricoes e adaptacoes de acessibilidade da pessoa com deficiencia.")
            @UISchema(label = "Observação de Deficiência")
            String obsDeficiencia,

            @Schema(description = "Valor bruto da remuneracao mensal permanente do vinculo.")
            @UISchema(label = "Remuneração")
            Double remuneracao
    ) {}

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

        @Schema(description = "Grupo sanguineo retornado por getter calculado sem anotacao de governanca.")
        @UISchema(label = "Grupo Sanguíneo", readOnly = true)
        public String getGrupoSanguineo() {
            return "O+";
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
        assertTrue(codes.contains("uischema-group-prefix-collision"));
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
        @UISchema(label = "Situação", group = "Identificação", quickViewVisible = true, quickViewOrder = 1)
        String situacao;

        @Schema(description = "Nome do departamento de lotacao do registro.")
        @UISchema(label = "Departamento", group = "Identificação civil", quickViewVisible = true, quickViewOrder = 2)
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

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.RECORD_COMPONENT})
    @DomainGovernance(
            preset = org.praxisplatform.uischema.annotation.DomainGovernancePreset.LGPD_PERSONAL_ID,
            reason = "Documento civil protegido pela LGPD."
    )
    @UISchema(label = "CPF", preset = UISchemaPreset.CPF_CNPJ)
    private @interface ComposedCpfMetadata {
    }

    private record ConcisePresetAndMetaAnnotatedDTO(
            @Schema(description = "Cadastro de Pessoa Fisica do servidor titular usado na qualificacao cadastral.")
            @ComposedCpfMetadata
            String cpf,

            @DomainGovernance(
                    preset = org.praxisplatform.uischema.annotation.DomainGovernancePreset.CONFIDENTIAL_FINANCIAL,
                    reason = "Remuneracao bruta protegida por sigilo salarial."
            )
            @Schema(description = "Valor bruto da remuneracao mensal permanente do vinculo funcional.")
            @UISchema(label = "Remuneração", preset = UISchemaPreset.MONETARY_AMOUNT)
            Double remuneracao
    ) {}

    @Test
    void acceptsDomainGovernancePresetsAndComposedMetaAnnotations() {
        SemanticMetadataReviewReport report =
                new SemanticMetadataReviewer().reviewStrictGovernance(ConcisePresetAndMetaAnnotatedDTO.class);
        assertFalse(report.hasErrors(), report.issues().toString());
        assertTrue(report.issues().isEmpty(), report.issues().toString());
    }

    private record MisconfiguredSensitiveGovernanceDTO(
            @DomainGovernance(
                    classification = org.praxisplatform.uischema.annotation.DomainClassification.PUBLIC,
                    dataCategory = org.praxisplatform.uischema.annotation.DomainDataCategory.PERSONAL
            )
            @Schema(description = "Cadastro de Pessoa Fisica do servidor titular para qualificacao cadastral.")
            @UISchema(label = "CPF")
            String cpf,

            @DomainGovernance(
                    classification = org.praxisplatform.uischema.annotation.DomainClassification.RESTRICTED,
                    dataCategory = org.praxisplatform.uischema.annotation.DomainDataCategory.SENSITIVE_PERSONAL,
                    aiUsage = @org.praxisplatform.uischema.annotation.AiUsagePolicy(
                            trainingUse = org.praxisplatform.uischema.annotation.AiTrainingUseMode.ALLOW
                    )
            )
            @Schema(description = "Codigo internacional da molestia clinica registrada na pericia medica.")
            @UISchema(label = "CID")
            String cid
    ) {}

    @Test
    void rejectsSensitiveFieldWithPublicClassificationOrAllowedAiTraining() {
        SemanticMetadataReviewReport report =
                new SemanticMetadataReviewer().reviewStrictGovernance(MisconfiguredSensitiveGovernanceDTO.class);
        assertTrue(report.hasErrors());
        assertTrue(report.issues().stream().anyMatch(i ->
                i.severity() == SemanticMetadataIssueSeverity.ERROR
                        && "sensitive-field-public-classification".equals(i.code())
                        && "cpf".equals(i.fieldName())));
        assertTrue(report.issues().stream().anyMatch(i ->
                i.severity() == SemanticMetadataIssueSeverity.ERROR
                        && "sensitive-field-allows-ai-training".equals(i.code())
                        && "cid".equals(i.fieldName())));
    }

    private record AllNewPresetsOpenApiDTO(
            @Schema(description = "Cadastro de Pessoa Fisica do colaborador titular.")
            @ComposedCpfMetadata
            String cpf,

            @Schema(description = "Endereco de e-mail corporativo para notificacoes oficiais.")
            @UISchema(label = "E-mail", preset = UISchemaPreset.EMAIL_CONTACT)
            String email,

            @Schema(description = "Telefone celular corporativo para contato de plantao.")
            @UISchema(label = "Telefone", preset = UISchemaPreset.PHONE_CONTACT)
            String telefone,

            @Schema(description = "Quantidade inteira de dependentes habilitados no vinculo.")
            @UISchema(label = "Dependentes", preset = UISchemaPreset.INTEGER_COUNTER)
            Integer totalDependentes,

            @Schema(description = "Percentual de gratificacao de desempenho aplicado sobre o vencimento.")
            @UISchema(label = "Percentual", preset = UISchemaPreset.PERCENTAGE_RATE)
            Double percentualGratificacao
    ) {}

    @Test
    @SuppressWarnings("unchecked")
    void customOpenApiResolverMaterializesNewUISchemaPresetsDomainGovernancePresetsAndMetaAnnotations() {
        var converter = new org.praxisplatform.uischema.extension.CustomOpenApiResolver(new com.fasterxml.jackson.databind.ObjectMapper());
        var context = new io.swagger.v3.core.converter.ModelConverterContextImpl(List.of(converter));
        io.swagger.v3.oas.models.media.Schema<?> schema =
                context.resolve(new io.swagger.v3.core.converter.AnnotatedType(AllNewPresetsOpenApiDTO.class));
        assertNotNull(schema);
        java.util.Map<String, io.swagger.v3.oas.models.media.Schema> props = schema.getProperties();
        assertNotNull(props);

        var cpfSchema = props.get("cpf");
        var cpfUi = (java.util.Map<String, Object>) cpfSchema.getExtensions().get("x-ui");
        assertEquals("cpfCnpjInput", cpfUi.get("controlType"));
        assertEquals("cpf-cnpj", cpfUi.get("presentationPreset"));
        var cpfGov = (java.util.Map<String, Object>) cpfSchema.getExtensions().get("x-domain-governance");
        assertNotNull(cpfGov);
        assertEquals("confidential", cpfGov.get("classification"));
        assertEquals("personal", cpfGov.get("dataCategory"));
        assertEquals(List.of("LGPD", "PII"), cpfGov.get("complianceTags"));

        var emailUi = (java.util.Map<String, Object>) props.get("email").getExtensions().get("x-ui");
        assertEquals("email", emailUi.get("type"));
        assertEquals("email", emailUi.get("controlType"));
        assertEquals("email-contact", emailUi.get("presentationPreset"));

        var phoneUi = (java.util.Map<String, Object>) props.get("telefone").getExtensions().get("x-ui");
        assertEquals("phone", phoneUi.get("controlType"));
        assertEquals("phone-contact", phoneUi.get("presentationPreset"));

        var counterUi = (java.util.Map<String, Object>) props.get("totalDependentes").getExtensions().get("x-ui");
        assertEquals("numericTextBox", counterUi.get("controlType"));
        assertEquals("integer", counterUi.get("numericFormat"));
        assertEquals("integer-counter", counterUi.get("presentationPreset"));

        var rateUi = (java.util.Map<String, Object>) props.get("percentualGratificacao").getExtensions().get("x-ui");
        assertEquals("numericTextBox", rateUi.get("controlType"));
        assertEquals("percent", rateUi.get("numericFormat"));
        assertEquals("percentage-rate", rateUi.get("presentationPreset"));
    }

    private static final class AuthoritativeHealthEntity {
        @DomainGovernance(
                preset = org.praxisplatform.uischema.annotation.DomainGovernancePreset.LGPD_SENSITIVE_DATA,
                complianceTags = {"LGPD", "LGPD_ART_11"},
                reason = "Codigo CID de afastamento clinico protegido pelo Art. 11 da LGPD."
        )
        private String cidAfastamento;
    }

    private record RestrictedAnalyticsExposureDTO(
            @Schema(description = "Codigo CID de afastamento clinico herdado por linhagem para pericia.")
            @UISchema(label = "CID")
            @org.praxisplatform.uischema.annotation.DomainLineage(
                    sourceClass = AuthoritativeHealthEntity.class,
                    sourcePaths = {"cidAfastamento"},
                    attributeId = "ergon.afastamento.cid",
                    transformation = org.praxisplatform.uischema.annotation.DomainLineageTransformation.DIRECT
            )
            String cidAfastamento,

            @Schema(description = "Cadastro de Pessoa Fisica restrito do servidor.")
            @UISchema(label = "CPF")
            @DomainGovernance(
                    preset = org.praxisplatform.uischema.annotation.DomainGovernancePreset.LGPD_PERSONAL_ID,
                    reason = "CPF restrito bloqueado para agregacao categorica e exportacao."
            )
            String cpf,

            @Schema(description = "Situacao funcional publica/interna elegivel para agregacao.")
            @UISchema(label = "Situacao")
            @DomainGovernance(preset = org.praxisplatform.uischema.annotation.DomainGovernancePreset.INTERNAL_OPERATIONAL)
            String situacao
    ) {}

    @Test
    void rejectsRestrictedOrSensitiveFieldsInCategoricalAnalyticsAndOptionSources() {
        var statsFields = org.praxisplatform.uischema.stats.StatsFieldRegistry.builder()
                .categoricalGroupByBucket("cidAfastamento", "cidAfastamento")
                .categoricalGroupByBucket("situacao", "situacao")
                .build();
        var optionSources = List.of(
                new org.praxisplatform.uischema.options.OptionSourceDescriptor(
                        "cpf",
                        org.praxisplatform.uischema.options.OptionSourceType.DISTINCT_DIMENSION,
                        "/api/ergon/afastamentos",
                        "cpf",
                        "cpf",
                        "cpf",
                        "cpf",
                        List.of(),
                        java.util.Map.of(),
                        null
                )
        );

        SemanticMetadataReviewReport report = new SemanticMetadataReviewer()
                .review(RestrictedAnalyticsExposureDTO.class, optionSources, statsFields);

        assertTrue(report.hasErrors());
        assertTrue(report.issues().stream().anyMatch(i ->
                i.severity() == SemanticMetadataIssueSeverity.ERROR
                        && "restricted-field-in-analytics-or-option-source".equals(i.code())
                        && "cidAfastamento".equals(i.fieldName())));
        assertTrue(report.issues().stream().anyMatch(i ->
                i.severity() == SemanticMetadataIssueSeverity.ERROR
                        && "restricted-field-in-analytics-or-option-source".equals(i.code())
                        && "cpf".equals(i.fieldName())));
        assertTrue(report.issues().stream().noneMatch(i ->
                "restricted-field-in-analytics-or-option-source".equals(i.code())
                        && "situacao".equals(i.fieldName())));
    }

    private record RenamedLineageAndFilterRestrictedDTO(
            @Schema(description = "Projeção renomeada do código CID clínico do afastamento protegido pelo Art. 11 da LGPD.")
            @UISchema(label = "Código CID")
            @org.praxisplatform.uischema.annotation.DomainLineage(
                    sourceClass = AuthoritativeHealthEntity.class,
                    sourcePaths = {"cidAfastamento"},
                    attributeId = "ergon.afastamento.cid",
                    transformation = org.praxisplatform.uischema.annotation.DomainLineageTransformation.ALIAS
            )
            String codigoCidClinico,

            @Schema(description = "Filtro por CPF do titular via relação JPA com bloqueio de agregação categórica.")
            @UISchema(label = "CPF do titular")
            @org.praxisplatform.uischema.filter.annotation.Filterable(relation = "pessoa.cpf")
            @DomainGovernance(
                    preset = org.praxisplatform.uischema.annotation.DomainGovernancePreset.LGPD_PERSONAL_ID,
                    reason = "CPF restrito bloqueado para agregacao categorica."
            )
            String cpfTitular
    ) {}

    private static final class MisconfiguredLegalPurposeController {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "ficha-sem-finalidade",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Ficha",
                description = "Surface com base legal sem finalidade.",
                legalBasisRef = "LGPD Art. 11, II, 'a'"
        )
        public void surfaceWithBasisWithoutPurpose() {
        }

        @org.praxisplatform.uischema.annotation.WorkflowAction(
                id = "homologar-sem-base",
                title = "Homologar",
                description = "Action com finalidade sem base legal normativa.",
                legalPurpose = org.praxisplatform.uischema.annotation.LegalTreatmentPurpose.OFFICIAL_HEALTH_EXPERTISE
        )
        public void actionWithPurposeWithoutBasis() {
        }
    }

    @Test
    void detectsRenamedLineageRestrictedAliasesAndMisconfiguredLegalTreatmentBindings() {
        var statsFields = org.praxisplatform.uischema.stats.StatsFieldRegistry.builder()
                .categoricalGroupByBucket("cid", "afastamento.cidAfastamento")
                .build();
        var optionSources = List.of(
                new org.praxisplatform.uischema.options.OptionSourceDescriptor(
                        "cpfBucket",
                        org.praxisplatform.uischema.options.OptionSourceType.CATEGORICAL_BUCKET,
                        "/api/ergon/afastamentos",
                        "cpfTitular",
                        "pessoa.cpf",
                        "pessoa.cpf",
                        "pessoa.cpf",
                        List.of(),
                        java.util.Map.of(),
                        null
                )
        );

        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();
        SemanticMetadataReviewReport aliasReport =
                reviewer.review(RenamedLineageAndFilterRestrictedDTO.class, optionSources, statsFields);
        assertTrue(aliasReport.hasErrors());
        assertTrue(aliasReport.issues().stream().anyMatch(i ->
                "restricted-field-in-analytics-or-option-source".equals(i.code())
                        && "codigoCidClinico".equals(i.fieldName())));
        assertTrue(aliasReport.issues().stream().anyMatch(i ->
                "restricted-field-in-analytics-or-option-source".equals(i.code())
                        && "cpfTitular".equals(i.fieldName())));

        SemanticMetadataReviewReport controllerReport =
                reviewer.reviewResourceFacts(MisconfiguredLegalPurposeController.class);
        assertTrue(controllerReport.hasErrors());
        assertTrue(controllerReport.issues().stream().anyMatch(i ->
                "legal-basis-without-legal-purpose".equals(i.code())
                        && "surfaceWithBasisWithoutPurpose@UiSurface(ficha-sem-finalidade)".equals(i.fieldName())));
        assertTrue(controllerReport.issues().stream().anyMatch(i ->
                "legal-purpose-without-legal-basis-ref".equals(i.code())
                        && "actionWithPurposeWithoutBasis@WorkflowAction(homologar-sem-base)".equals(i.fieldName())));
    }

    private static final class AuthoritativePersonEntity {
        @DomainGovernance(
                preset = org.praxisplatform.uischema.annotation.DomainGovernancePreset.LGPD_PERSONAL_ID,
                exportable = false,
                reason = "Nome civil do titular restrito para agregacao categorica."
        )
        private String nome;
    }

    @DomainGovernance(
            classification = org.praxisplatform.uischema.annotation.DomainClassification.RESTRICTED,
            exportable = false,
            reason = "Interface base restrita para todos os atributos herdados."
    )
    private interface RestrictedBaseContract {
    }

    private record HomonymAndInheritedTypeGovernanceDTO(
            @Schema(description = "Nome civil do servidor titular vinculado por linhagem com bloqueio de exportacao.")
            @UISchema(label = "Nome do Servidor")
            @org.praxisplatform.uischema.annotation.DomainLineage(
                    sourceClass = AuthoritativePersonEntity.class,
                    sourcePaths = {"nome"},
                    attributeId = "ergon.servidor_pessoa.nome",
                    transformation = org.praxisplatform.uischema.annotation.DomainLineageTransformation.ALIAS
            )
            String pessoaNome,

            @Schema(description = "Parecer reservado cujo tipo base ja exige classificacao RESTRICTED.")
            @UISchema(label = "Parecer")
            @DomainGovernance(
                    classification = org.praxisplatform.uischema.annotation.DomainClassification.INTERNAL,
                    aiUsage = @org.praxisplatform.uischema.annotation.AiUsagePolicy(
                            trainingUse = org.praxisplatform.uischema.annotation.AiTrainingUseMode.ALLOW
                    )
            )
            String parecerInterno
    ) implements RestrictedBaseContract {}

    @Test
    void avoidsGenericLeafHomonymFalsePositivesAndComposesTypeLevelGovernanceWithMemberGovernance() {
        var safeOrganizationalStats = org.praxisplatform.uischema.stats.StatsFieldRegistry.builder()
                .categoricalGroupByBucket("lotacaoNome", "lotacao.nome")
                .build();
        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

        SemanticMetadataReviewReport reportWithSafeOrgDimension =
                reviewer.review(HomonymAndInheritedTypeGovernanceDTO.class, List.of(), safeOrganizationalStats);
        assertTrue(reportWithSafeOrgDimension.issues().stream().noneMatch(i ->
                "restricted-field-in-analytics-or-option-source".equals(i.code())
                        && "pessoaNome".equals(i.fieldName())),
                "Generic leaf 'nome' on ServidorPessoa must not falsely block organizational 'lotacao.nome': "
                        + reportWithSafeOrgDimension.issues());
        assertTrue(reportWithSafeOrgDimension.issues().stream().anyMatch(i ->
                "sensitive-field-allows-ai-training".equals(i.code())
                        && "parecerInterno".equals(i.fieldName())),
                "Member governance with trainingUse=ALLOW on a RESTRICTED type must be flagged: "
                        + reportWithSafeOrgDimension.issues());

        var statsExposingInheritedRestrictedMember = org.praxisplatform.uischema.stats.StatsFieldRegistry.builder()
                .categoricalGroupByBucket("parecerInterno", "parecerInterno")
                .build();
        SemanticMetadataReviewReport reportExposingRestrictedMember =
                reviewer.review(HomonymAndInheritedTypeGovernanceDTO.class, List.of(), statsExposingInheritedRestrictedMember);
        assertTrue(reportExposingRestrictedMember.issues().stream().anyMatch(i ->
                "restricted-field-in-analytics-or-option-source".equals(i.code())
                        && "parecerInterno".equals(i.fieldName())),
                "Type-level RESTRICTED governance must be preserved when merged with member INTERNAL governance: "
                        + reportExposingRestrictedMember.issues());
    }

    private enum SampleFilterStatus {
        ATIVO,
        SUSPENSO
    }

    private static class SampleControllerFilterDTO implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO {
        @org.praxisplatform.uischema.filter.annotation.Filterable
        private SampleFilterStatus status;

        @org.praxisplatform.uischema.filter.annotation.Filterable
        private List<SampleFilterStatus> statuses;

        @org.praxisplatform.uischema.filter.annotation.Filterable
        private Boolean prioritario;

        @org.praxisplatform.uischema.filter.annotation.Filterable
        private Integer diasAtraso;

        @org.praxisplatform.uischema.filter.annotation.Filterable
        @DomainGovernance(preset = DomainGovernancePreset.SECURITY_CREDENTIAL, reason = "Token técnico secreto")
        private String apiSecretToken;

        private String semFilterable;
    }

    private static abstract class GenericBaseController<D, ID, F extends org.praxisplatform.uischema.filter.dto.GenericFilterDTO> {
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/items",
            resourceKey = "sample.items",
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "ativos",
                            label = "Ativos",
                            filter = "status=ATIVO"
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "prioritarios",
                            label = "Prioritários",
                            filter = "status=ATIVO&prioritario=true&diasAtraso=15"
                    )
            }
    )
    private static class ValidResourceController extends GenericBaseController<GoodRecordDTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/broken",
            resourceKey = "sample.broken",
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "enum-invalido",
                            label = "Enum inválido",
                            filter = "status=INEXISTENTE"
                    )
            }
    )
    private static class InvalidQuickFilterAndSurfaceController extends GenericBaseController<GoodRecordDTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "child-only",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                relatedChildResourceKey = "sample.children",
                title = "Filhos",
                description = "Aba 1:N filha que não substitui a ficha 360° do próprio item."
        )
        public void listChildren() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/malformed",
            resourceKey = "sample.malformed",
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "campo-inexistente",
                            label = "Campo inexistente",
                            filter = "naoExiste=123"
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "campo-inexistente",
                            label = "Este rótulo de quick filter é longo demais e termina com ponto.",
                            filter = "semFilterable=abc&prioritario=talvez&diasAtraso=abc&status="
                    )
            }
    )
    private static class MalformedQuickFilterController extends GenericBaseController<GoodRecordDTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.READ_PROJECTION,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Projeção 360° do item."
        )
        public void getById() {
        }
    }

    @Test
    void reviewsResourceControllerQuickFiltersAndItemDetailSurfaces() {
        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

        SemanticMetadataReviewReport validReport = reviewer.reviewResourceController(ValidResourceController.class);
        assertFalse(validReport.hasErrors(), validReport.issues().toString());
        assertTrue(validReport.issues().isEmpty(), validReport.issues().toString());

        SemanticMetadataReviewReport invalidReport = reviewer.reviewResourceController(InvalidQuickFilterAndSurfaceController.class);
        assertTrue(invalidReport.hasErrors());
        Set<String> invalidCodes = invalidReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(invalidCodes.contains("quick-filter-count-insufficient"));
        assertTrue(invalidCodes.contains("quick-filter-enum-value-invalid"));
        assertTrue(invalidCodes.contains("item-detail-surface-missing"));

        SemanticMetadataReviewReport malformedReport = reviewer.reviewResourceController(MalformedQuickFilterController.class);
        assertTrue(malformedReport.hasErrors());
        Set<String> malformedCodes = malformedReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(malformedCodes.contains("quick-filter-id-duplicate"));
        assertTrue(malformedCodes.contains("quick-filter-label-sentence-like"));
        assertTrue(malformedCodes.contains("quick-filter-target-field-missing"));
        assertTrue(malformedCodes.contains("quick-filter-target-field-not-filterable"));
        assertTrue(malformedCodes.contains("quick-filter-boolean-value-invalid"));
        assertTrue(malformedCodes.contains("quick-filter-numeric-value-invalid"));
        assertTrue(malformedCodes.contains("quick-filter-expression-malformed"));
        assertFalse(malformedCodes.contains("item-detail-surface-missing"));
    }

    @Test
    void reviewsOptionSourceRegistryAndEnforcesRichResourceEntityAndSurfaceDetailContract() {
        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

        var validLookup = org.praxisplatform.uischema.options.EntityLookupDescriptor.directoryBuilder("sample.departments", "code", "name")
                .status("status")
                .detailSurface("sample.departments", "detail", "detail")
                .build();
        var validEntitySource = new org.praxisplatform.uischema.options.OptionSourceDescriptor(
                "departmentLookup",
                org.praxisplatform.uischema.options.OptionSourceType.RESOURCE_ENTITY,
                "/api/sample/departments",
                "departmentId",
                null,
                "name",
                "id",
                List.of(),
                java.util.Map.of(),
                org.praxisplatform.uischema.options.OptionSourcePolicy.defaults(),
                validLookup
        );
        var validDimensionSource = new org.praxisplatform.uischema.options.OptionSourceDescriptor(
                "status",
                org.praxisplatform.uischema.options.OptionSourceType.DISTINCT_DIMENSION,
                "/api/sample/departments",
                "status",
                "status",
                "status",
                "status",
                List.of(),
                java.util.Map.of(),
                org.praxisplatform.uischema.options.OptionSourcePolicy.defaults()
        );
        var validRegistry = org.praxisplatform.uischema.options.OptionSourceRegistry.builder()
                .add(GoodRecordDTO.class, validEntitySource)
                .add(GoodRecordDTO.class, validDimensionSource)
                .build();

        SemanticMetadataReviewReport validReport = reviewer.reviewOptionSourceRegistry(validRegistry);
        assertFalse(validReport.hasErrors(), validReport.issues().toString());
        assertTrue(validReport.issues().isEmpty(), validReport.issues().toString());

        var missingEntityLookupSource = new org.praxisplatform.uischema.options.OptionSourceDescriptor(
                "missingLookup",
                org.praxisplatform.uischema.options.OptionSourceType.RESOURCE_ENTITY,
                "/api/sample/departments",
                "departmentId",
                null,
                "name",
                "id",
                List.of(),
                java.util.Map.of(),
                org.praxisplatform.uischema.options.OptionSourcePolicy.defaults(),
                null
        );
        SemanticMetadataReviewReport missingLookupReport = reviewer.reviewOptionSourceDescriptor(missingEntityLookupSource);
        assertTrue(missingLookupReport.hasErrors());
        assertTrue(missingLookupReport.issues().stream().anyMatch(i ->
                "option-source-entity-lookup-missing".equals(i.code())));

        var legacyRouteLookup = new org.praxisplatform.uischema.options.EntityLookupDescriptor(
                "departments",
                "",
                List.of(),
                "status",
                null,
                null,
                List.of(),
                java.util.Map.of(),
                null,
                null,
                new org.praxisplatform.uischema.options.LookupDetailDescriptor(
                        "/api/sample/departments/{id}",
                        "/sample/departments/{id}",
                        "route"
                ),
                null,
                null
        );
        var invalidEntitySource = new org.praxisplatform.uischema.options.OptionSourceDescriptor(
                "legacyLookup",
                org.praxisplatform.uischema.options.OptionSourceType.RESOURCE_ENTITY,
                "/api/sample/departments",
                "departmentId",
                null,
                "name",
                "id",
                List.of(),
                java.util.Map.of(),
                org.praxisplatform.uischema.options.OptionSourcePolicy.defaults(),
                legacyRouteLookup
        );
        var invalidRegistry = org.praxisplatform.uischema.options.OptionSourceRegistry.builder()
                .add(GoodRecordDTO.class, invalidEntitySource)
                .build();

        SemanticMetadataReviewReport invalidReport = reviewer.reviewOptionSourceRegistry(invalidRegistry);
        assertTrue(invalidReport.hasErrors());
        Set<String> invalidCodes = invalidReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(invalidCodes.contains("option-source-lookup-identity-paths-missing"));
        assertTrue(invalidCodes.contains("option-source-lookup-search-paths-empty"));
        assertTrue(invalidCodes.contains("option-source-lookup-subcontract-missing"));
        assertTrue(invalidCodes.contains("option-source-lookup-detail-legacy-route"));
        assertTrue(invalidCodes.contains("option-source-lookup-detail-surface-incomplete"));
    }

    @org.praxisplatform.uischema.annotation.ResourceIdentity(
            keyField = "id",
            titleField = "nomeCompleto",
            metadataFields = {"cargoNome", "cargoNome"}
    )
    private static class ShallowAndDuplicateIdentityDTO {
        @Schema(description = "Identificador unico imutavel do registro funcional.")
        @UISchema(label = "ID")
        Long id;

        @Schema(description = "Nome civil completo do colaborador registrado no cadastro.")
        @UISchema(label = "Nome")
        @org.praxisplatform.uischema.annotation.ResourceReference(
                resourceKey = "sample.employees",
                valueField = "id",
                surfaceId = "detail"
        )
        String nomeCompleto;

        @Schema(description = "Cargo contratual ocupado pelo colaborador na estrutura.")
        @UISchema(label = "Cargo")
        String cargoNome;

        @Schema(description = "Departamento de lotacao operacional do colaborador.")
        @UISchema(label = "Departamento")
        String departamentoNome;

        @Schema(description = "Situacao cadastral vigente do vinculo funcional.")
        @UISchema(label = "Situação", quickViewVisible = true, quickViewOrder = 1)
        String situacao;

        @Schema(description = "Modalidade de regime de trabalho do colaborador.")
        @UISchema(label = "Regime")
        String regime;

        @Schema(description = "Unidade fisica de alocacao principal do colaborador.")
        @UISchema(label = "Unidade")
        String unidade;

        @Schema(description = "Endereco de correio eletronico corporativo do colaborador.")
        @UISchema(label = "E-mail")
        String email;
    }

    @org.praxisplatform.uischema.annotation.ResourceIdentity(
            keyField = "id",
            titleField = "nomeCompleto",
            subtitleField = "email",
            metadataFields = {"cargoNome"}
    )
    private static class InsufficientMetadataCountDTO {
        @Schema(description = "Identificador unico imutavel do registro funcional.")
        @UISchema(label = "ID")
        Long id;

        @Schema(description = "Nome civil completo do colaborador registrado no cadastro.")
        @UISchema(label = "Nome")
        String nomeCompleto;

        @Schema(description = "Endereco de correio eletronico corporativo do colaborador.")
        @UISchema(label = "E-mail")
        String email;

        @Schema(description = "Cargo contratual ocupado pelo colaborador na estrutura.")
        @UISchema(label = "Cargo")
        String cargoNome;

        @Schema(description = "Departamento de lotacao operacional do colaborador.")
        @UISchema(label = "Departamento")
        String departamentoNome;

        @Schema(description = "Situacao cadastral vigente do vinculo funcional.")
        @UISchema(label = "Situação", quickViewVisible = true, quickViewOrder = 1)
        String situacao;

        @Schema(description = "Modalidade de regime de trabalho do colaborador.")
        @UISchema(label = "Regime", quickViewVisible = true, quickViewOrder = 2)
        String regime;

        @Schema(description = "Unidade fisica de alocacao principal do colaborador.")
        @UISchema(label = "Unidade", quickViewVisible = true, quickViewOrder = 3)
        String unidade;

        @Schema(description = "Turno operacional atribuido ao colaborador.")
        @UISchema(label = "Turno", quickViewVisible = true, quickViewOrder = 4)
        String turno;
    }

    @org.praxisplatform.uischema.annotation.ResourceIdentity(
            keyField = "id",
            titleField = "nomeCompleto",
            subtitleField = "email",
            metadataFields = {"cargoNome", "departamentoNome"}
    )
    private static class ValidQuickView360DTO {
        @Schema(description = "Identificador unico imutavel do registro funcional.")
        @UISchema(label = "ID")
        Long id;

        @Schema(description = "Nome civil completo do colaborador registrado no cadastro.")
        @UISchema(label = "Nome")
        @org.praxisplatform.uischema.annotation.ResourceReference(
                resourceKey = "sample.items",
                valueField = "id",
                surfaceId = "detail",
                previewSurfaceId = "detail"
        )
        String nomeCompleto;

        @Schema(description = "Endereco de correio eletronico corporativo do colaborador.")
        @UISchema(label = "E-mail")
        String email;

        @Schema(description = "Cargo contratual ocupado pelo colaborador na estrutura.")
        @UISchema(label = "Cargo")
        String cargoNome;

        @Schema(description = "Departamento de lotacao operacional do colaborador.")
        @UISchema(label = "Departamento")
        String departamentoNome;

        @Schema(description = "Situacao cadastral vigente do vinculo funcional.")
        @UISchema(label = "Situação", quickViewVisible = true, quickViewOrder = 1)
        String situacao;

        @Schema(description = "Modalidade de regime de trabalho do colaborador.")
        @UISchema(label = "Regime", quickViewVisible = true, quickViewOrder = 2)
        String regime;

        @Schema(description = "Unidade fisica de alocacao principal do colaborador.")
        @UISchema(label = "Unidade", quickViewVisible = true, quickViewOrder = 3)
        String unidade;

        @Schema(description = "Turno operacional atribuido ao colaborador.")
        @UISchema(label = "Turno", quickViewVisible = true, quickViewOrder = 4)
        String turno;
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/items",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "ativos",
                            label = "Ativos",
                            filter = "status=ATIVO"
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "prioritarios",
                            label = "Prioritários",
                            filter = "prioritario=true"
                    )
            }
    )
    private static class ValidQuickView360Controller
            extends GenericBaseController<ValidQuickView360DTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/mismatch",
            resourceKey = "sample.mismatch",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "cargoNome",
                    metadataFields = {"departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "ativos",
                            label = "Ativos",
                            filter = "status=ATIVO"
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "prioritarios",
                            label = "Prioritários",
                            filter = "prioritario=true"
                    )
            }
    )
    private static class MismatchedIdentityAndTitleRefController
            extends GenericBaseController<ValidQuickView360DTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/items",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "ativos",
                            label = "Ativos",
                            filter = "status=ATIVO"
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "prioritarios",
                            label = "Prioritários",
                            filter = "prioritario=true"
                    )
            }
    )
    private static class UnresolvedTitleSurfaceController
            extends GenericBaseController<ValidQuickView360DTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "view-only",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° com ID diferente do referenciado."
        )
        public void getById() {
        }
    }

    @Test
    void enforcesQuickView360AndResourceIdentityParityAcrossDtoAndController() {
        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

        SemanticMetadataReviewReport shallowReport = reviewer.review(ShallowAndDuplicateIdentityDTO.class);
        assertTrue(shallowReport.hasErrors());
        Set<String> shallowCodes = shallowReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(shallowCodes.contains("resource-reference-preview-surface-missing"));
        assertTrue(shallowCodes.contains("resource-identity-subtitle-missing"));
        assertTrue(shallowCodes.contains("resource-identity-metadata-duplicate"));
        assertTrue(shallowCodes.contains("quick-view-field-count-out-of-range"));

        SemanticMetadataReviewReport metaCountReport = reviewer.review(InsufficientMetadataCountDTO.class);
        assertTrue(metaCountReport.hasErrors());
        assertTrue(metaCountReport.issues().stream()
                .anyMatch(i -> "resource-identity-metadata-count-out-of-range".equals(i.code())));

        SemanticMetadataReviewReport validDtoReport = reviewer.review(ValidQuickView360DTO.class);
        assertFalse(validDtoReport.hasErrors(), validDtoReport.issues().toString());

        SemanticMetadataReviewReport validCtrlReport = reviewer.reviewResourceController(
                ValidQuickView360Controller.class,
                ValidQuickView360DTO.class,
                SampleControllerFilterDTO.class
        );
        assertFalse(validCtrlReport.hasErrors(), validCtrlReport.issues().toString());

        SemanticMetadataReviewReport mismatchCtrlReport = reviewer.reviewResourceController(
                MismatchedIdentityAndTitleRefController.class,
                ValidQuickView360DTO.class,
                SampleControllerFilterDTO.class
        );
        assertTrue(mismatchCtrlReport.hasErrors());
        Set<String> mismatchCodes = mismatchCtrlReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(mismatchCodes.contains("resource-identity-controller-dto-mismatch"));
        assertTrue(mismatchCodes.contains("title-field-self-reference-missing"));

        SemanticMetadataReviewReport unresolvedSurfReport = reviewer.reviewResourceController(
                UnresolvedTitleSurfaceController.class,
                ValidQuickView360DTO.class,
                SampleControllerFilterDTO.class
        );
        assertTrue(unresolvedSurfReport.hasErrors());
        assertTrue(unresolvedSurfReport.issues().stream()
                .anyMatch(i -> "resource-reference-surface-unresolved".equals(i.code())));

        SemanticMetadataReviewReport missingIdentityReport = reviewer.reviewResourceController(
                ValidResourceController.class,
                GoodRecordDTO.class,
                SampleControllerFilterDTO.class
        );
        assertTrue(missingIdentityReport.hasErrors());
        assertTrue(missingIdentityReport.issues().stream()
                .anyMatch(i -> "resource-identity-missing".equals(i.code())));

        SemanticMetadataReviewReport validJsonQfReport = reviewer.reviewResourceController(
                ValidJsonQuickFilterController.class
        );
        assertFalse(validJsonQfReport.hasErrors(), validJsonQfReport.issues().toString());

        SemanticMetadataReviewReport invalidJsonQfReport = reviewer.reviewResourceController(
                InvalidJsonQuickFilterController.class
        );
        assertTrue(invalidJsonQfReport.hasErrors());
        Set<String> invalidJsonCodes = invalidJsonQfReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(invalidJsonCodes.contains("quick-filter-target-field-missing"));
        assertTrue(invalidJsonCodes.contains("quick-filter-target-field-not-filterable"));
        assertTrue(invalidJsonCodes.contains("quick-filter-enum-value-invalid"));
        assertTrue(invalidJsonCodes.contains("quick-filter-numeric-value-invalid"));
        assertTrue(invalidJsonCodes.contains("quick-filter-expression-malformed"));

        SemanticMetadataReviewReport validCriteriaReport = reviewer.reviewResourceController(
                ValidCriteriaQuickFilterController.class
        );
        assertFalse(validCriteriaReport.hasErrors(), validCriteriaReport.issues().toString());

        SemanticMetadataReviewReport invalidCriteriaReport = reviewer.reviewResourceController(
                InvalidCriteriaQuickFilterController.class
        );
        assertTrue(invalidCriteriaReport.hasErrors());
        Set<String> invalidCriteriaCodes = invalidCriteriaReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(invalidCriteriaCodes.contains("quick-filter-criteria-and-filter-conflict"));
        assertTrue(invalidCriteriaCodes.contains("quick-filter-multiple-defaults"));
        assertTrue(invalidCriteriaCodes.contains("quick-filter-icon-not-snake-case"));
        assertTrue(invalidCriteriaCodes.contains("quick-filter-criterion-multiple-value-types"));
        assertTrue(invalidCriteriaCodes.contains("quick-filter-criterion-cardinality-mismatch"));
        assertTrue(invalidCriteriaCodes.contains("quick-filter-target-field-governance-denied"));
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/items",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "ativos",
                            label = "Ativos",
                            filter = "{\"statuses\":[\"ATIVO\"],\"prioritario\":true}"
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "atrasados",
                            label = "Em atraso",
                            filter = "{\"diasAtraso\":10}"
                    )
            }
    )
    private static class ValidJsonQuickFilterController
            extends GenericBaseController<ValidQuickView360DTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/items",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "broken-fields",
                            label = "Campos inválidos",
                            filter = "{\"naoExiste\":true,\"semFilterable\":\"abc\",\"statuses\":[\"INEXISTENTE\"],\"diasAtraso\":12.5}"
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "scalar-on-collection",
                            label = "Escalar em coleção",
                            filter = "{\"statuses\":\"ATIVO\"}"
                    )
            }
    )
    private static class InvalidJsonQuickFilterController
            extends GenericBaseController<ValidQuickView360DTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/items",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "ativos-prioritarios",
                            label = "Ativos prioritários",
                            icon = "check_circle",
                            tone = org.praxisplatform.uischema.annotation.QuickFilterTone.SUCCESS,
                            defaultActive = true,
                            description = "Itens ativos com prioridade operacional.",
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "statuses", values = {"ATIVO", "SUSPENSO"}),
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "prioritario", bool = true)
                            }
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "em-atraso",
                            label = "Em atraso",
                            icon = "schedule",
                            tone = org.praxisplatform.uischema.annotation.QuickFilterTone.DANGER,
                            criteria = @org.praxisplatform.uischema.annotation.FilterCriterion(field = "diasAtraso", number = 15)
                    )
            }
    )
    private static class ValidCriteriaQuickFilterController
            extends GenericBaseController<ValidQuickView360DTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/criteria-invalid",
            resourceKey = "sample.criteria.invalid",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "conflito",
                            label = "Conflito",
                            icon = "Check_Circle",
                            defaultActive = true,
                            filter = "status=ATIVO",
                            criteria = @org.praxisplatform.uischema.annotation.FilterCriterion(field = "status", values = "ATIVO")
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "erros-criterios",
                            label = "Erros de critérios",
                            defaultActive = true,
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "prioritario", values = "true", bool = true),
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "status", values = {"ATIVO", "SUSPENSO"}),
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "apiSecretToken", values = "abc")
                            }
                    )
            }
    )
    private static class InvalidCriteriaQuickFilterController
            extends GenericBaseController<ValidQuickView360DTO, Long, SampleControllerFilterDTO> {
        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @Test
    void validatesShorthandLocalDateTokensRelativePeriodPresetsFiniteDecimalsAndProgrammaticQuickFilters() {
        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

        SemanticMetadataReviewReport validReport = reviewer.reviewResourceController(
                ValidTemporalQuickFilterController.class,
                ValidQuickView360DTO.class,
                TemporalAndProgrammaticFilterDTO.class
        );
        assertFalse(validReport.hasErrors(), validReport.issues().toString());

        SemanticMetadataReviewReport invalidAnnotationReport = reviewer.reviewResourceController(
                InvalidTemporalAndNaNQuickFilterController.class,
                ValidQuickView360DTO.class,
                TemporalAndProgrammaticFilterDTO.class
        );
        assertTrue(invalidAnnotationReport.hasErrors());
        Set<String> annotationCodes = invalidAnnotationReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(annotationCodes.contains("quick-filter-temporal-value-invalid"), annotationCodes.toString());
        assertTrue(annotationCodes.contains("quick-filter-numeric-value-invalid"), annotationCodes.toString());

        SemanticMetadataReviewReport invalidProgrammaticReport = reviewer.reviewResourceController(
                InvalidProgrammaticQuickFilterController.class,
                ValidQuickView360DTO.class,
                TemporalAndProgrammaticFilterDTO.class
        );
        assertTrue(invalidProgrammaticReport.hasErrors());
        Set<String> programmaticCodes = invalidProgrammaticReport.issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(programmaticCodes.contains("quick-filter-temporal-value-invalid"), programmaticCodes.toString());
        assertTrue(programmaticCodes.contains("quick-filter-criterion-cardinality-mismatch"), programmaticCodes.toString());
        assertTrue(programmaticCodes.contains("quick-filter-numeric-value-invalid"), programmaticCodes.toString());

        SemanticMetadataReviewReport rangeArityReport = reviewer.reviewResourceController(
                InvalidRangeArityAndPreCoercionQuickFilterController.class,
                ValidQuickView360DTO.class,
                TemporalAndProgrammaticFilterDTO.class
        );
        assertTrue(rangeArityReport.hasErrors());
        List<SemanticMetadataReviewIssue> rangeCardinalityIssues = rangeArityReport.issues().stream()
                .filter(issue -> "quick-filter-criterion-cardinality-mismatch".equals(issue.code()))
                .toList();
        assertEquals(3, rangeCardinalityIssues.size(), rangeArityReport.issues().toString());

        SemanticMetadataReviewReport scalarBetweenReport = reviewer.reviewResourceController(
                ScalarBetweenQuickFilterController.class,
                ValidQuickView360DTO.class,
                ScalarBetweenFilterDTO.class
        );
        assertTrue(scalarBetweenReport.hasErrors(), scalarBetweenReport.issues().toString());
        assertTrue(
                scalarBetweenReport.issues().stream()
                        .anyMatch(issue -> "quick-filter-criterion-cardinality-mismatch".equals(issue.code())
                                && issue.message().contains("BETWEEN")),
                scalarBetweenReport.issues().toString()
        );
    }

    private static class TemporalAndProgrammaticFilterDTO
            implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO {
        @Schema(description = "Data de referência da folha para enquadramento temporal do servidor.")
        @UISchema(label = "Data de referência", controlType = FieldControlType.DATE_PICKER)
        @org.praxisplatform.uischema.filter.annotation.Filterable(
                operation = org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.EQUAL
        )
        private java.time.LocalDate dataReferencia;

        @Schema(description = "Faixa de competência analisada entre data inicial e final de referência.")
        @UISchema(label = "Período de competência", controlType = FieldControlType.DATE_RANGE)
        @org.praxisplatform.uischema.filter.annotation.Filterable(
                operation = org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.BETWEEN
        )
        private List<java.time.LocalDate> competenciaPeriodo;

        @Schema(description = "Faixa estrita de pontuação funcional avaliada entre limite inferior e superior.")
        @UISchema(label = "Faixa estrita", controlType = FieldControlType.NUMERIC_TEXT_BOX)
        @org.praxisplatform.uischema.filter.annotation.Filterable(
                operation = org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.BETWEEN_EXCLUSIVE
        )
        private List<Integer> pontuacaoExclusiva;

        @Schema(description = "Preset relativo de período selecionado pelo usuário na barra de pesquisa.")
        @UISchema(label = "Período relativo", controlType = FieldControlType.INLINE_RELATIVE_PERIOD)
        private String periodoRelativo;

        @Schema(description = "Percentual mínimo apurado no indicador funcional de elegibilidade.")
        @UISchema(label = "Percentual mínimo", controlType = FieldControlType.NUMERIC_TEXT_BOX)
        @org.praxisplatform.uischema.filter.annotation.Filterable(
                operation = org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.GREATER_OR_EQUAL
        )
        private Double percentualMinimo;

        public java.time.LocalDate getDataReferencia() { return dataReferencia; }
        public void setDataReferencia(java.time.LocalDate dataReferencia) { this.dataReferencia = dataReferencia; }
        public List<java.time.LocalDate> getCompetenciaPeriodo() { return competenciaPeriodo; }
        public void setCompetenciaPeriodo(List<java.time.LocalDate> competenciaPeriodo) { this.competenciaPeriodo = competenciaPeriodo; }
        public List<Integer> getPontuacaoExclusiva() { return pontuacaoExclusiva; }
        public void setPontuacaoExclusiva(List<Integer> pontuacaoExclusiva) { this.pontuacaoExclusiva = pontuacaoExclusiva; }
        public String getPeriodoRelativo() { return periodoRelativo; }
        public void setPeriodoRelativo(String periodoRelativo) { this.periodoRelativo = periodoRelativo; }
        public Double getPercentualMinimo() { return percentualMinimo; }
        public void setPercentualMinimo(Double percentualMinimo) { this.percentualMinimo = percentualMinimo; }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/temporal-valid",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "competencia-mes",
                            label = "Competência março",
                            filter = "dataReferencia=2026-03"
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "trimestre-e-preset",
                            label = "Trimestre e janela",
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "dataReferencia", values = "2026-Q1"),
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "periodoRelativo", values = "last30")
                            }
                    )
            }
    )
    private static class ValidTemporalQuickFilterController
            extends org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController<
                    ValidQuickView360DTO, Long, TemporalAndProgrammaticFilterDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceQueryService<
                ValidQuickView360DTO, Long, TemporalAndProgrammaticFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(ValidQuickView360DTO dto) {
            return null;
        }

        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/temporal-invalid",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "preset-invalido",
                            label = "Preset inválido",
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "periodoRelativo", values = "invalidPreset")
                            }
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "decimal-nan",
                            label = "Decimal não finito",
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "percentualMinimo", decimal = Double.NaN)
                            }
                    )
            }
    )
    private static class InvalidTemporalAndNaNQuickFilterController
            extends org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController<
                    ValidQuickView360DTO, Long, TemporalAndProgrammaticFilterDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceQueryService<
                ValidQuickView360DTO, Long, TemporalAndProgrammaticFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(ValidQuickView360DTO dto) {
            return null;
        }

        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/programmatic-invalid",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            )
    )
    private static class InvalidProgrammaticQuickFilterController
            extends org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController<
                    ValidQuickView360DTO, Long, TemporalAndProgrammaticFilterDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceQueryService<
                ValidQuickView360DTO, Long, TemporalAndProgrammaticFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(ValidQuickView360DTO dto) {
            return null;
        }

        @Override
        protected List<org.praxisplatform.uischema.schema.QuickFilterDescriptor<TemporalAndProgrammaticFilterDTO>> defineQuickFilters() {
            return List.of(
                    org.praxisplatform.uischema.schema.QuickFilterDescriptor.<TemporalAndProgrammaticFilterDTO>builder(
                                    "preset-invalido-prog", "Preset programático")
                            .where(f -> {
                                f.setPeriodoRelativo("invalidPreset");
                                f.setPercentualMinimo(Double.NaN);
                            })
                            .build(),
                    org.praxisplatform.uischema.schema.QuickFilterDescriptor.<TemporalAndProgrammaticFilterDTO>builder(
                                    "cardinalidade-incompativel-prog", "Cardinalidade incompatível")
                            .criterion("dataReferencia", java.util.List.of(
                                    java.time.LocalDate.of(2026, 3, 1),
                                    java.time.LocalDate.of(2026, 3, 31)
                            ))
                            .build()
            );
        }

        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/range-arity-invalid",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "between-tres-valores",
                            label = "Between com três valores",
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(
                                            field = "competenciaPeriodo",
                                            values = {"2026-01-01", "2026-02-01", "2026-03-01"}
                                    )
                            }
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "between-exclusive-um-valor",
                            label = "Between exclusivo incompleto",
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(
                                            field = "pontuacaoExclusiva",
                                            number = {50}
                                    )
                            }
                    )
            }
    )
    private static class InvalidRangeArityAndPreCoercionQuickFilterController
            extends org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController<
                    ValidQuickView360DTO, Long, TemporalAndProgrammaticFilterDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceQueryService<
                ValidQuickView360DTO, Long, TemporalAndProgrammaticFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(ValidQuickView360DTO dto) {
            return null;
        }

        @Override
        protected List<org.praxisplatform.uischema.schema.QuickFilterDescriptor<TemporalAndProgrammaticFilterDTO>> defineQuickFilters() {
            return List.of(
                    org.praxisplatform.uischema.schema.QuickFilterDescriptor.<TemporalAndProgrammaticFilterDTO>builder(
                                    "escalar-em-colecao-prog", "Escalar em coleção")
                            .criterion("competenciaPeriodo", java.time.LocalDate.of(2026, 3, 1))
                            .build()
            );
        }

        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    private static class ScalarBetweenFilterDTO
            implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO {
        @Schema(description = "Campo escalar anotado incorretamente com operação BETWEEN para validar cardinality check.")
        @UISchema(label = "Competência escalar", controlType = FieldControlType.DATE_PICKER)
        @org.praxisplatform.uischema.filter.annotation.Filterable(
                operation = org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.BETWEEN
        )
        private java.time.LocalDate competenciaEscalar;

        public java.time.LocalDate getCompetenciaEscalar() { return competenciaEscalar; }
        public void setCompetenciaEscalar(java.time.LocalDate competenciaEscalar) { this.competenciaEscalar = competenciaEscalar; }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/scalar-between-invalid",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "between-em-escalar",
                            label = "Between em escalar",
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(
                                            field = "competenciaEscalar",
                                            values = {"2026-01-01"}
                                    )
                            }
                    )
            }
    )
    private static class ScalarBetweenQuickFilterController
            extends org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController<
                    ValidQuickView360DTO, Long, ScalarBetweenFilterDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceQueryService<
                ValidQuickView360DTO, Long, ScalarBetweenFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(ValidQuickView360DTO dto) {
            return null;
        }

        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    private enum SampleStaticOptionEnum {
        VIGENTE,
        ENCERRADA
    }

    private static class ValidTypedOptionsDTO {
        @Schema(description = "Situação funcional consolidada da vantagem no vínculo.")
        @UISchema(
                label = "Situação",
                controlType = FieldControlType.SELECT,
                optionItems = {
                        @org.praxisplatform.uischema.annotation.UiOption(
                                value = "ATIVA",
                                label = "Ativa",
                                icon = "check_circle",
                                tone = org.praxisplatform.uischema.annotation.QuickFilterTone.SUCCESS
                        ),
                        @org.praxisplatform.uischema.annotation.UiOption(
                                value = "SUSPENSA",
                                label = "Suspensa",
                                icon = "pause_circle",
                                tone = org.praxisplatform.uischema.annotation.QuickFilterTone.WARNING
                        )
                }
        )
        private String situacao;

        @Schema(description = "Situação de vigência resolvida a partir do enum de domínio.")
        @UISchema(
                label = "Vigência",
                controlType = FieldControlType.SELECT,
                optionEnum = SampleStaticOptionEnum.class
        )
        private String vigencia;
    }

    private static class InvalidTypedOptionsDTO {
        @Schema(description = "Campo com múltiplas fontes de opções estáticas em conflito.")
        @UISchema(
                label = "Conflito",
                controlType = FieldControlType.SELECT,
                optionItems = { @org.praxisplatform.uischema.annotation.UiOption(value = "A", label = "Item A") },
                optionEnum = SampleStaticOptionEnum.class
        )
        private String conflito;

        @Schema(description = "Campo referenciando classe não enum em optionEnum.")
        @UISchema(label = "Enum inválido", controlType = FieldControlType.SELECT, optionEnum = String.class)
        private String enumInvalido;

        @Schema(description = "Campo com itens duplicados, ícone não snake_case e rótulos em branco.")
        @UISchema(
                label = "Itens inválidos",
                controlType = FieldControlType.SELECT,
                optionItems = {
                        @org.praxisplatform.uischema.annotation.UiOption(value = "", label = "Valor vazio"),
                        @org.praxisplatform.uischema.annotation.UiOption(value = "DUP", label = "Duplicado um", icon = "InvalidIcon"),
                        @org.praxisplatform.uischema.annotation.UiOption(value = "DUP", label = "")
                }
        )
        private String itensInvalidos;

        @Schema(description = "Campo com JSON legado usando id sem value e sem valueField.")
        @UISchema(
                label = "Legado sem value",
                controlType = FieldControlType.SELECT,
                options = "[{\"id\":\"FOLHA\",\"label\":\"Folha\"}]"
        )
        private String legadoSemValue;
    }

    private static class OptionBackedFilterDTO
            implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO {
        @Schema(description = "Situação filtrável com opções estáticas fechadas.")
        @UISchema(
                label = "Situação",
                controlType = FieldControlType.SELECT,
                optionItems = {
                        @org.praxisplatform.uischema.annotation.UiOption(value = "ATIVA", label = "Ativa"),
                        @org.praxisplatform.uischema.annotation.UiOption(value = "ENCERRADA", label = "Encerrada")
                }
        )
        @org.praxisplatform.uischema.filter.annotation.Filterable(
                operation = org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.EQUAL
        )
        private String situacao;

        public String getSituacao() { return situacao; }
        public void setSituacao(String situacao) { this.situacao = situacao; }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/option-backed-invalid",
            resourceKey = "sample.items",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto",
                    subtitleField = "email",
                    metadataFields = {"cargoNome", "departamentoNome"}
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "situacao-valida",
                            label = "Ativas",
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "situacao", values = {"ATIVA"})
                            }
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "situacao-inexistente",
                            label = "Canceladas",
                            criteria = {
                                    @org.praxisplatform.uischema.annotation.FilterCriterion(field = "situacao", values = {"CANCELADA"})
                            }
                    )
            }
    )
    private static class InvalidOptionQuickFilterController
            extends org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController<
                    ValidQuickView360DTO, Long, OptionBackedFilterDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceQueryService<
                ValidQuickView360DTO, Long, OptionBackedFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(ValidQuickView360DTO dto) {
            return null;
        }

        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Ficha 360° do item."
        )
        public void getById() {
        }
    }

    @Test
    void validatesTypedUiOptionsAndCrossChecksQuickFilterValues() {
        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

        assertTrue(reviewer.review(ValidTypedOptionsDTO.class).issues().stream()
                .noneMatch(i -> i.severity() == SemanticMetadataIssueSeverity.ERROR));

        List<String> invalidCodes = reviewer.review(InvalidTypedOptionsDTO.class).issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .toList();
        assertTrue(invalidCodes.contains("uischema-options-source-conflict"));
        assertTrue(invalidCodes.contains("uischema-option-enum-invalid"));
        assertTrue(invalidCodes.contains("uischema-option-value-blank"));
        assertTrue(invalidCodes.contains("uischema-option-duplicate-value"));
        assertTrue(invalidCodes.contains("uischema-option-label-blank"));
        assertTrue(invalidCodes.contains("uischema-option-icon-invalid"));
        assertTrue(invalidCodes.contains("uischema-options-missing-value-key"));

        List<SemanticMetadataReviewIssue> qfIssues = reviewer.reviewResourceController(InvalidOptionQuickFilterController.class).issues();
        assertEquals(1, qfIssues.stream().filter(i -> "quick-filter-option-value-invalid".equals(i.code())).count());
        assertTrue(qfIssues.stream()
                .filter(i -> "quick-filter-option-value-invalid".equals(i.code()))
                .allMatch(i -> i.message().contains("CANCELADA")));
    }

    @Test
    void validatesCanonicalResourceFactVocabulariesDuplicatesAndInheritance() {
        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

        SemanticMetadataReviewReport validControllerFacts = reviewer.reviewResourceFacts(ValidInheritedFactController.class);
        assertFalse(validControllerFacts.hasErrors(), validControllerFacts.issues().toString());
        SemanticMetadataReviewReport validControllerReport = reviewer.reviewResourceController(ValidInheritedFactController.class);
        assertFalse(validControllerReport.hasErrors(), validControllerReport.issues().toString());

        Set<String> invalidControllerFactCodes = reviewer.reviewResourceFacts(InvalidResourceFactController.class).issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(invalidControllerFactCodes.containsAll(Set.of(
                "resource-fact-key-invalid",
                "resource-fact-duplicate-key",
                "resource-fact-resource-key-mismatch",
                "resource-fact-data-type-invalid",
                "resource-fact-source-kind-invalid",
                "resource-fact-authority-invalid",
                "resource-fact-evaluation-phase-invalid",
                "resource-fact-missing-value-policy-invalid",
                "resource-fact-scope-invalid",
                "resource-fact-tone-invalid",
                "resource-fact-icon-not-snake-case",
                "resource-fact-source-field-unknown",
                "resource-fact-threshold-tone-invalid"
        )));

        Set<String> unscopedFactCodes = reviewer.reviewResourceFacts(UnscopedBlankResourceKeyFactHost.class).issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(unscopedFactCodes.contains("resource-fact-resource-key-missing"));
    }

    @Test
    void validatesReactiveDeterminationsAndBulkGovernance() {
        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();

        SemanticMetadataReviewReport validReport = reviewer.reviewResourceController(ValidDeterminationAndBulkController.class);
        assertFalse(validReport.hasErrors(), validReport.issues().toString());

        Set<String> invalidDtoCodes = reviewer.review(InvalidBulkEditableDTO.class).issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(invalidDtoCodes.containsAll(Set.of(
                "bulk-editable-invalid-mode",
                "bulk-editable-clear-requires-nullable-schema"
        )));

        Set<String> invalidControllerCodes = reviewer.reviewResourceController(InvalidDeterminationAndBulkController.class).issues().stream()
                .map(SemanticMetadataReviewIssue::code)
                .collect(Collectors.toSet());
        assertTrue(invalidControllerCodes.containsAll(Set.of(
                "reactive-determination-operation-id-missing",
                "reactive-determination-id-invalid",
                "reactive-determination-duplicate-id",
                "reactive-determination-scope-conflict",
                "reactive-determination-target-operation-unknown",
                "reactive-determination-input-mapping-invalid",
                "reactive-determination-output-mapping-invalid",
                "reactive-determination-input-field-unknown",
                "reactive-determination-input-request-field-unknown",
                "reactive-determination-output-field-unknown",
                "reactive-determination-output-response-field-unknown",
                "reactive-determination-json-pointer-invalid",
                "reactive-determination-source-field-unknown",
                "bulk-resource-operations-operation-id-missing",
                "bulk-protected-field-blank",
                "bulk-protected-field-duplicate",
                "bulk-protected-field-unknown",
                "bulk-protected-field-conflicts-with-bulk-editable"
        )));
    }

    @org.praxisplatform.uischema.annotation.ResourceIdentity(
            keyField = "id",
            titleField = "nomeCompleto"
    )
    private static final class FactBackedResponseDTO {
        @Schema(description = "Identificador unico do item de amostra no catalogo.")
        @UISchema(label = "ID", controlType = FieldControlType.INPUT, group = "Geral")
        private Long id;

        @Schema(description = "Titulo principal exibido nos cartoes executivos.")
        @UISchema(label = "Titulo", controlType = FieldControlType.INPUT, group = "Geral")
        @org.praxisplatform.uischema.annotation.ResourceReference(resourceKey = "sample.facts", valueField = "id", surfaceId = "detail")
        private String nomeCompleto;

        @Schema(description = "Saldo de dias disponiveis apurados no backend.")
        @UISchema(label = "Saldo Dias", controlType = FieldControlType.INPUT, group = "Geral")
        private Integer saldoDias;

        public Long getId() { return id; }
        public String getNomeCompleto() { return nomeCompleto; }
        public Integer getSaldoDias() { return saldoDias; }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/facts-inherited",
            resourceKey = "sample.facts",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto"
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "ativos",
                            label = "Ativos",
                            criteria = {@org.praxisplatform.uischema.annotation.FilterCriterion(field = "status", values = {"ATIVO"})}
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "com-atraso",
                            label = "Com Atraso",
                            criteria = {@org.praxisplatform.uischema.annotation.FilterCriterion(field = "diasAtraso", values = {"15"})}
                    )
            }
    )
    @org.praxisplatform.uischema.fact.ResourceFact(
            key = "saldoDias",
            label = "Saldo de Dias",
            description = "Saldo apurado direto do campo do DTO herdando resourceKey do controller.",
            dataType = "integer",
            sourceKind = "field",
            icon = "event_available",
            tone = "positive",
            thresholds = {@org.praxisplatform.uischema.annotation.Threshold(equalsValue = "0", tone = "neutral")}
    )
    private static class ValidInheritedFactController
            extends org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController<
                    FactBackedResponseDTO, Long, SampleControllerFilterDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceQueryService<
                FactBackedResponseDTO, Long, SampleControllerFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(FactBackedResponseDTO dto) {
            return dto == null ? null : dto.getId();
        }

        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Detalhe 360."
        )
        public void detail() {
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(value = "/api/sample/facts-invalid", resourceKey = "sample.facts")
    @org.praxisplatform.uischema.fact.ResourceFact(
            key = "1bad key!",
            resourceKey = "other.resource",
            label = "Invalid Key",
            description = "Invalid fact key and conflicting resourceKey.",
            dataType = "bogus_type",
            sourceKind = "bogus_kind",
            authority = "client_side",
            evaluationPhase = "after_dinner",
            missingValuePolicy = "ignore",
            scope = "planet",
            tone = "rainbow",
            icon = "InvalidIcon",
            thresholds = {@org.praxisplatform.uischema.annotation.Threshold(min = 0, max = 10, tone = "neon")}
    )
    @org.praxisplatform.uischema.fact.ResourceFact(
            key = "missingFieldKey",
            label = "Missing DTO Field",
            description = "References field absent from backing DTO.",
            sourceKind = "field"
    )
    @org.praxisplatform.uischema.fact.ResourceFact(
            key = "missingFieldKey",
            label = "Duplicate Key",
            description = "Duplicate fact key on controller.",
            sourceKind = "computed"
    )
    private static class InvalidResourceFactController
            extends org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController<
                    FactBackedResponseDTO, Long, SampleControllerFilterDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceQueryService<
                FactBackedResponseDTO, Long, SampleControllerFilterDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(FactBackedResponseDTO dto) {
            return null;
        }
    }

    @org.praxisplatform.uischema.fact.ResourceFact(
            key = "unscoped_fact",
            label = "Unscoped Fact",
            description = "Fails because host is not @ApiResource and resourceKey is blank."
    )
    private static class UnscopedBlankResourceKeyFactHost {
    }

    @org.praxisplatform.uischema.annotation.ResourceIdentity(
            keyField = "id",
            titleField = "nomeCompleto"
    )
    private static class ValidDeterminationDTO {
        @Schema(description = "Identificador unico do calculo.")
        @UISchema(label = "ID", controlType = FieldControlType.INPUT, group = "Geral")
        private Long id;

        @Schema(description = "Titulo principal exibido nos cartoes.")
        @UISchema(label = "Titulo", controlType = FieldControlType.INPUT, group = "Geral")
        @org.praxisplatform.uischema.annotation.ResourceReference(resourceKey = "sample.det", valueField = "id", surfaceId = "detail")
        private String nomeCompleto;

        @Schema(description = "Salario base para previa reativa.")
        @UISchema(label = "Salario Base", controlType = FieldControlType.CURRENCY_INPUT, group = "Geral")
        private java.math.BigDecimal salarioBase;

        @Schema(description = "Gratificacao ajustavel em lote.", nullable = true)
        @UISchema(label = "Gratificacao", controlType = FieldControlType.CURRENCY_INPUT, group = "Geral")
        @org.praxisplatform.uischema.annotation.BulkEditable(allowClear = true)
        private java.math.BigDecimal gratificacao;

        @Schema(description = "Valor liquido previsto calculado pela previa reativa.")
        @UISchema(label = "Liquido Previsto", controlType = FieldControlType.CURRENCY_INPUT, group = "Geral")
        private java.math.BigDecimal valorLiquido;

        public Long getId() { return id; }
        public String getNomeCompleto() { return nomeCompleto; }
        public java.math.BigDecimal getSalarioBase() { return salarioBase; }
        public java.math.BigDecimal getGratificacao() { return gratificacao; }
        public java.math.BigDecimal getValorLiquido() { return valorLiquido; }
    }

    record DeterminationRequestPayload(java.math.BigDecimal baseInformada) {}
    record DeterminationResponsePayload(java.math.BigDecimal valorLiquido) {}

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/det-valid",
            resourceKey = "sample.det",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto"
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "ativos",
                            label = "Ativos",
                            criteria = {@org.praxisplatform.uischema.annotation.FilterCriterion(field = "status", values = {"ATIVO"})}
                    ),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "com-atraso",
                            label = "Com Atraso",
                            criteria = {@org.praxisplatform.uischema.annotation.FilterCriterion(field = "diasAtraso", values = {"15"})}
                    )
            }
    )
    @org.praxisplatform.uischema.bulk.BulkResourceOperations(
            proposalOperationId = "sample.bulk.proposal",
            proposalResultsOperationId = "sample.bulk.proposal-results",
            executionOperationId = "sample.bulk.execution",
            executionResultsOperationId = "sample.bulk.results",
            cancelOperationId = "sample.bulk.cancel",
            protectedUpdateFields = {"salarioBase"}
    )
    private static class ValidDeterminationAndBulkController
            extends org.praxisplatform.uischema.controller.base.AbstractResourceController<
                    ValidDeterminationDTO, Long, SampleControllerFilterDTO, ValidDeterminationDTO, ValidDeterminationDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceService<
                ValidDeterminationDTO, Long, SampleControllerFilterDTO, ValidDeterminationDTO, ValidDeterminationDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(ValidDeterminationDTO dto) {
            return dto == null ? null : dto.getId();
        }

        @io.swagger.v3.oas.annotations.Operation(operationId = "createDetValid", summary = "Criar")
        @Override
        public org.springframework.http.ResponseEntity<org.praxisplatform.uischema.rest.response.RestApiResponse<ValidDeterminationDTO>> create(
                ValidDeterminationDTO dto
        ) {
            return null;
        }

        @io.swagger.v3.oas.annotations.Operation(operationId = "updateDetValid", summary = "Atualizar")
        @Override
        public org.springframework.http.ResponseEntity<org.praxisplatform.uischema.rest.response.RestApiResponse<ValidDeterminationDTO>> update(
                Long id,
                ValidDeterminationDTO dto
        ) {
            return null;
        }

        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Detalhe 360."
        )
        public void detail() {
        }

        @io.swagger.v3.oas.annotations.Operation(operationId = "previewDetValid", summary = "Preview")
        @org.praxisplatform.uischema.annotation.ReactiveDetermination(
                id = "sample.preview.valid",
                createOperationId = "createDetValid",
                updateOperationId = "updateDetValid",
                sourceFields = {"salarioBase"},
                inputs = {@org.praxisplatform.uischema.annotation.DeterminationInput(field = "salarioBase", request = "baseInformada")},
                outputs = {@org.praxisplatform.uischema.annotation.DeterminationOutput("valorLiquido")}
        )
        public org.springframework.http.ResponseEntity<org.praxisplatform.uischema.rest.response.RestApiResponse<DeterminationResponsePayload>> preview(
                @org.springframework.web.bind.annotation.RequestBody DeterminationRequestPayload body
        ) {
            return null;
        }
    }

    private static class InvalidBulkEditableDTO {
        @Schema(description = "Campo com modo invalido de edicao em lote e limpeza sem nullability.")
        @UISchema(label = "Campo Invalido", controlType = FieldControlType.INPUT, group = "Geral")
        @org.praxisplatform.uischema.annotation.BulkEditable(
                modes = {org.praxisplatform.uischema.bulk.BulkMode.DOMAIN_COMMAND},
                allowClear = true
        )
        private String campoInvalido;

        public String getCampoInvalido() { return campoInvalido; }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(
            value = "/api/sample/det-invalid",
            resourceKey = "sample.det.invalid",
            identity = @org.praxisplatform.uischema.annotation.ResourceIdentity(
                    keyField = "id",
                    titleField = "nomeCompleto"
            ),
            quickFilters = {
                    @org.praxisplatform.uischema.annotation.QuickFilter(id = "all", label = "Todos"),
                    @org.praxisplatform.uischema.annotation.QuickFilter(
                            id = "por-nome",
                            label = "Por Nome",
                            criteria = {@org.praxisplatform.uischema.annotation.FilterCriterion(field = "nome", values = {"a"})}
                    )
            }
    )
    @org.praxisplatform.uischema.bulk.BulkResourceOperations(
            proposalOperationId = "",
            proposalResultsOperationId = "r",
            executionOperationId = "e",
            executionResultsOperationId = "er",
            cancelOperationId = "c",
            protectedUpdateFields = {" ", "salarioBase", "salarioBase", "gratificacao", "campoFantasma"}
    )
    private static class InvalidDeterminationAndBulkController
            extends org.praxisplatform.uischema.controller.base.AbstractResourceController<
                    ValidDeterminationDTO, Long, SampleControllerFilterDTO, ValidDeterminationDTO, ValidDeterminationDTO> {
        @Override
        protected org.praxisplatform.uischema.service.base.BaseResourceService<
                ValidDeterminationDTO, Long, SampleControllerFilterDTO, ValidDeterminationDTO, ValidDeterminationDTO> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(ValidDeterminationDTO dto) {
            return null;
        }

        @org.praxisplatform.uischema.annotation.UiSurface(
                id = "detail",
                kind = org.praxisplatform.uischema.surface.SurfaceKind.VIEW,
                scope = org.praxisplatform.uischema.surface.SurfaceScope.ITEM,
                title = "Detalhe",
                description = "Detalhe 360."
        )
        public void detail() {
        }

        @org.praxisplatform.uischema.annotation.ReactiveDetermination(
                id = "bad id with spaces",
                createOperationId = "missingCreateOp",
                scopes = {@org.praxisplatform.uischema.annotation.DeterminationScope(
                        schemaOperationId = "missingScopeOp",
                        formMode = org.praxisplatform.uischema.determination.ReactiveDeterminationFormMode.CREATE
                )},
                sourceFields = {"fonteInexistente"},
                inputs = {
                        @org.praxisplatform.uischema.annotation.DeterminationInput(value = "x", field = "y"),
                        @org.praxisplatform.uischema.annotation.DeterminationInput(field = "campoFormInexistente", request = "reqFantasma")
                },
                outputs = {
                        @org.praxisplatform.uischema.annotation.DeterminationOutput(value = "a", response = "b"),
                        @org.praxisplatform.uischema.annotation.DeterminationOutput(response = "respFantasma", field = "outFormInexistente"),
                        @org.praxisplatform.uischema.annotation.DeterminationOutput("bad//pointer/")
                }
        )
        public DeterminationResponsePayload brokenOne(@org.springframework.web.bind.annotation.RequestBody DeterminationRequestPayload body) {
            return null;
        }

        @io.swagger.v3.oas.annotations.Operation(operationId = "detOpOne", summary = "Op 1")
        @org.praxisplatform.uischema.annotation.ReactiveDetermination(
                id = "dup.id",
                createOperationId = "unknownOp",
                inputs = {@org.praxisplatform.uischema.annotation.DeterminationInput("salarioBase")},
                outputs = {@org.praxisplatform.uischema.annotation.DeterminationOutput("valorLiquido")}
        )
        public DeterminationResponsePayload dupOne(@org.springframework.web.bind.annotation.RequestBody DeterminationRequestPayload body) {
            return null;
        }

        @io.swagger.v3.oas.annotations.Operation(operationId = "detOpTwo", summary = "Op 2")
        @org.praxisplatform.uischema.annotation.ReactiveDetermination(
                id = "dup.id",
                createOperationId = "unknownOpTwo",
                inputs = {@org.praxisplatform.uischema.annotation.DeterminationInput("salarioBase")},
                outputs = {@org.praxisplatform.uischema.annotation.DeterminationOutput("valorLiquido")}
        )
        public DeterminationResponsePayload dupTwo(@org.springframework.web.bind.annotation.RequestBody DeterminationRequestPayload body) {
            return null;
        }
    }
}
