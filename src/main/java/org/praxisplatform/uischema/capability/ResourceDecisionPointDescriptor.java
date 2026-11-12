package org.praxisplatform.uischema.capability;

import java.util.List;
import java.util.Objects;

/**
 * Descritor canônico (`PLAT-EVO-07`) de um ponto de decisão governado derivado automaticamente de
 * um recurso {@code @ApiResource}, suas operações estruturais, ações de workflow
 * ({@code @WorkflowAction}), superfícies ({@code @UiSurface}), operações em lote
 * ({@code @BulkOperation} / {@code @BulkResourceOperations}) e campos governados
 * ({@code @DomainGovernance} / {@code @DomainLineage}).
 *
 * <p><b>Fronteira arquitetural com {@code praxis-authz-contracts} /
 * {@code praxis-authz-starter}:</b> este record pertence à camada neutra de descoberta semântica
 * de metadados ({@code praxis-metadata-starter}, sem dependência de OPA ou de contratos PEP/PDP) e
 * fornece o inventário determinístico de prontidão (PAP / catálogo / auditoria LGPD / grounding
 * para autoria assistida de políticas por IA) que alimenta tanto o cockpit de metadados quanto o
 * bootstrap governado do {@code praxis-authz-starter}
 * ({@code org.praxisplatform.authz.contracts.decisionpoint.DecisionPointDescriptor}).
 *
 * @param decisionPointKey identificador único e estável do ponto de decisão (ex.: {@code "ergon.cargos:action:extinguir-cargo"})
 * @param resourceKey chave semântica do recurso proprietário (ex.: {@code "ergon.cargos"})
 * @param resourcePath caminho HTTP canônico do recurso (ex.: {@code "/api/ergon/cargos"})
 * @param kind natureza estrutural do ponto de decisão ({@link DecisionPointKind})
 * @param targetId identificador do alvo dentro do recurso (ID da action, surface, bulk operation, nome do campo ou operação CRUD)
 * @param title título curto legível para humanos e catálogos
 * @param description descrição semântica e normativa do ponto de decisão
 * @param legalPurpose finalidade legal do tratamento ({@code LegalTreatmentPurpose.wireValue()}, ou {@code null} quando não especificada)
 * @param legalBasisRef referência normativa/legal explícita ({@code legalBasisRef}, ou {@code null} quando ausente)
 * @param requiredAuthorities autoridades/perfis declarados estaticamente na operação
 * @param allowedStates estados operacionais permitidos declarados estaticamente
 * @param explicitGovernanceDeclared indica se o alvo possui declaração explícita completa de governança/linhagem
 * @param failClosedRestricted indica se o alvo sofre restrição fail-closed para exportação, projeção ou uso por IA
 * @param classification classificação de segurança do dado/operação ({@code public}, {@code internal}, {@code confidential}, {@code restricted})
 * @param dataCategory categoria semântica do dado ({@code operational}, {@code personal}, {@code sensitive_personal}, {@code financial}, {@code credential})
 * @param sensitivity sensibilidade canônica da plataforma ({@code NON_SENSITIVE}, {@code PERSONAL}, {@code SENSITIVE}, {@code SECRET})
 * @param redaction política de redação ({@code NONE}, {@code MASK}, {@code HASH}, {@code OMIT})
 * @param complianceTags tags regulatórias associadas
 * @param lineageAttributeId identificador canônico de linhagem de domínio ({@code @DomainLineage.attributeId})
 */
public record ResourceDecisionPointDescriptor(
        String decisionPointKey,
        String resourceKey,
        String resourcePath,
        DecisionPointKind kind,
        String targetId,
        String title,
        String description,
        String legalPurpose,
        String legalBasisRef,
        List<String> requiredAuthorities,
        List<String> allowedStates,
        boolean explicitGovernanceDeclared,
        boolean failClosedRestricted,
        String classification,
        String dataCategory,
        String sensitivity,
        String redaction,
        List<String> complianceTags,
        String lineageAttributeId) {

    /** Categorias estruturais de pontos de decisão governados na plataforma Praxis. */
    public enum DecisionPointKind {
        RESOURCE_QUERY,
        RESOURCE_CREATE,
        RESOURCE_UPDATE,
        RESOURCE_DELETE,
        WORKFLOW_ACTION,
        SURFACE_AVAILABILITY,
        BULK_OPERATION,
        FIELD_PROJECTION,
        FIELD_WRITE,
        COLLECTION_EXPORT,
        AI_TOOL_EXECUTION
    }

    public ResourceDecisionPointDescriptor {
        Objects.requireNonNull(decisionPointKey, "decisionPointKey");
        Objects.requireNonNull(resourceKey, "resourceKey");
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetId, "targetId");
        requiredAuthorities =
                requiredAuthorities == null ? List.of() : List.copyOf(requiredAuthorities);
        allowedStates = allowedStates == null ? List.of() : List.copyOf(allowedStates);
        complianceTags = complianceTags == null ? List.of() : List.copyOf(complianceTags);
    }
}
