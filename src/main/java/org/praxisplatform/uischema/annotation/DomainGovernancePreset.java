package org.praxisplatform.uischema.annotation;

import java.util.List;

/**
 * Presets canonicos de governanca de dominio para {@link DomainGovernance}.
 *
 * <p>
 * Reduzem a repeticao visual de atributos de privacidade (LGPD/PII), sigilo salarial/financeiro,
 * dados sensiveis (Art. 11 da LGPD) e credenciais de seguranca nos DTOs, preservando 100% da
 * semantica publicada em {@code x-domain-governance} e no catalogo {@code /schemas/domain}.
 * O campo {@link DomainGovernance#reason()} continua recomendado para documentar a justificativa
 * especifica do atributo no dominio.
 * </p>
 */
public enum DomainGovernancePreset {
    NONE(
            DomainGovernanceKind.PRIVACY,
            DomainClassification.INTERNAL,
            DomainDataCategory.OPERATIONAL,
            List.of(),
            true,
            AiVisibilityMode.ALLOW,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.REVIEW_REQUIRED,
            AiControlledUseMode.ALLOW
    ),
    /**
     * Identificadores civis e documentos pessoais (CPF, RG, PIS/PASEP, CNH, titulo eleitoral).
     */
    LGPD_PERSONAL_ID(
            DomainGovernanceKind.PRIVACY,
            DomainClassification.CONFIDENTIAL,
            DomainDataCategory.PERSONAL,
            List.of("LGPD", "PII"),
            false,
            AiVisibilityMode.MASK,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.REVIEW_REQUIRED,
            AiControlledUseMode.ALLOW
    ),
    /**
     * Dados pessoais de contato e localizacao (e-mail pessoal, telefone, endereco residencial).
     */
    LGPD_PERSONAL_CONTACT(
            DomainGovernanceKind.PRIVACY,
            DomainClassification.CONFIDENTIAL,
            DomainDataCategory.PERSONAL,
            List.of("LGPD", "PII"),
            true,
            AiVisibilityMode.MASK,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.REVIEW_REQUIRED,
            AiControlledUseMode.ALLOW
    ),
    /**
     * Dados pessoais cadastrais, biograficos, civis e familiares (nome, filiacao, data de nascimento,
     * estado civil, escolaridade, nacionalidade, observacoes pessoais) que permitem exportacao governada
     * mas exigem mascaramento de visibilidade em fluxos abertos de IA.
     */
    LGPD_PERSONAL_DATA(
            DomainGovernanceKind.PRIVACY,
            DomainClassification.CONFIDENTIAL,
            DomainDataCategory.PERSONAL,
            List.of("LGPD", "PII"),
            true,
            AiVisibilityMode.MASK,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.REVIEW_REQUIRED,
            AiControlledUseMode.ALLOW
    ),
    /**
     * Dados pessoais sensiveis protegidos pelo Art. 11 da LGPD (saude, CID, deficiencia, raca/cor,
     * tipo sanguineo, biometria, filiacao sindical).
     */
    LGPD_SENSITIVE_DATA(
            DomainGovernanceKind.PRIVACY,
            DomainClassification.RESTRICTED,
            DomainDataCategory.SENSITIVE_PERSONAL,
            List.of("LGPD", "LGPD_ART_11", "SENSITIVE_PII"),
            false,
            AiVisibilityMode.DENY,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.DENY,
            AiControlledUseMode.DENY
    ),
    /**
     * Dados financeiros, remuneratorios e bancarios confidenciais (salario privado, retencoes intimas,
     * pensao alimenticia, consignacoes facultativas, filiacao sindical, bloqueios judiciais, conta bancaria, PIX).
     */
    CONFIDENTIAL_FINANCIAL(
            DomainGovernanceKind.COMPLIANCE,
            DomainClassification.CONFIDENTIAL,
            DomainDataCategory.FINANCIAL,
            List.of("LGPD", "FINANCIAL_CONFIDENTIAL"),
            false,
            AiVisibilityMode.MASK,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.REVIEW_REQUIRED,
            AiControlledUseMode.ALLOW
    ),
    /**
     * Dados financeiros e remuneratorios sujeitos a transparencia ativa ou publicidade legal/contratual
     * ({@code PLAT-EVO-13}: remuneracao bruta, subsidio, vencimento basico, tabelas salariais de carreira,
     * abate-teto constitucional e tributos retidos sob a Lei de Acesso a Informacao — Lei 12.527/2011,
     * art. 31, § 3º, IV; STF Tema 483 / ARE 652.777; LGPD, art. 7º, II e III e art. 23 — ou tabelas
     * publicas de precos/tarifas B2B). Preserva {@code dataCategory = FINANCIAL} para rastreabilidade
     * semantica, mas habilita {@code classification = PUBLIC}, {@code exportable = true} e
     * {@code aiVisibility = ALLOW} para analytics, exportacao governada e assistentes de auditoria,
     * diferenciando-se das retencoes intimas protegidas por {@link #CONFIDENTIAL_FINANCIAL}.
     */
    PUBLIC_TRANSPARENCY_REMUNERATION(
            DomainGovernanceKind.COMPLIANCE,
            DomainClassification.PUBLIC,
            DomainDataCategory.FINANCIAL,
            List.of("LAI", "LGPD_ART_23", "PUBLIC_TRANSPARENCY_REMUNERATION"),
            true,
            AiVisibilityMode.ALLOW,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.REVIEW_REQUIRED,
            AiControlledUseMode.ALLOW
    ),
    /**
     * Credenciais, segredos, hashes de senha, chaves privadas e tokens de autenticacao.
     */
    SECURITY_CREDENTIAL(
            DomainGovernanceKind.SECURITY,
            DomainClassification.RESTRICTED,
            DomainDataCategory.CREDENTIAL,
            List.of("SECURITY", "CREDENTIAL"),
            false,
            AiVisibilityMode.DENY,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.DENY,
            AiControlledUseMode.DENY
    ),
    /**
     * Dados operacionais internos da organizacao sem restricao de dados pessoais.
     */
    INTERNAL_OPERATIONAL(
            DomainGovernanceKind.COMPLIANCE,
            DomainClassification.INTERNAL,
            DomainDataCategory.OPERATIONAL,
            List.of("INTERNAL_POLICY"),
            true,
            AiVisibilityMode.ALLOW,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.REVIEW_REQUIRED,
            AiControlledUseMode.ALLOW
    ),
    /**
     * Dados operacionais confidenciais ou taticos (objetivos, planos operacionais, avaliacoes de risco, localizacao sensivel).
     */
    CONFIDENTIAL_OPERATIONAL(
            DomainGovernanceKind.SECURITY,
            DomainClassification.CONFIDENTIAL,
            DomainDataCategory.OPERATIONAL,
            List.of("INTERNAL_POLICY", "OPERATIONAL_SECURITY"),
            true,
            AiVisibilityMode.SUMMARIZE_ONLY,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.REVIEW_REQUIRED,
            AiControlledUseMode.REVIEW_REQUIRED
    ),
    /**
     * Atos normativos, portarias, processos judiciais/administrativos e fundamentacao legal.
     */
    LEGAL_COMPLIANCE(
            DomainGovernanceKind.COMPLIANCE,
            DomainClassification.INTERNAL,
            DomainDataCategory.LEGAL,
            List.of("COMPLIANCE", "INTERNAL_POLICY", "REGULATORY"),
            true,
            AiVisibilityMode.ALLOW,
            AiTrainingUseMode.DENY,
            AiControlledUseMode.REVIEW_REQUIRED,
            AiControlledUseMode.ALLOW
    );

    private final DomainGovernanceKind kind;
    private final DomainClassification classification;
    private final DomainDataCategory dataCategory;
    private final List<String> complianceTags;
    private final boolean exportable;
    private final AiVisibilityMode aiVisibility;
    private final AiTrainingUseMode aiTrainingUse;
    private final AiControlledUseMode aiRuleAuthoring;
    private final AiControlledUseMode aiReasoningUse;

    DomainGovernancePreset(
            DomainGovernanceKind kind,
            DomainClassification classification,
            DomainDataCategory dataCategory,
            List<String> complianceTags,
            boolean exportable,
            AiVisibilityMode aiVisibility,
            AiTrainingUseMode aiTrainingUse,
            AiControlledUseMode aiRuleAuthoring,
            AiControlledUseMode aiReasoningUse
    ) {
        this.kind = kind;
        this.classification = classification;
        this.dataCategory = dataCategory;
        this.complianceTags = complianceTags;
        this.exportable = exportable;
        this.aiVisibility = aiVisibility;
        this.aiTrainingUse = aiTrainingUse;
        this.aiRuleAuthoring = aiRuleAuthoring;
        this.aiReasoningUse = aiReasoningUse;
    }

    public DomainGovernanceKind kind() {
        return kind;
    }

    public DomainClassification classification() {
        return classification;
    }

    public DomainDataCategory dataCategory() {
        return dataCategory;
    }

    public List<String> complianceTags() {
        return complianceTags;
    }

    public boolean exportable() {
        return exportable;
    }

    public AiVisibilityMode aiVisibility() {
        return aiVisibility;
    }

    public AiTrainingUseMode aiTrainingUse() {
        return aiTrainingUse;
    }

    public AiControlledUseMode aiRuleAuthoring() {
        return aiRuleAuthoring;
    }

    public AiControlledUseMode aiReasoningUse() {
        return aiReasoningUse;
    }
}
