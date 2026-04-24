package org.praxisplatform.uischema.domain;

import java.util.List;
import java.util.Map;

/**
 * Contrato v0.2 do catalogo semantico publicado por {@code /schemas/domain}.
 *
 * <p>
 * O contrato descreve vocabulario de dominio extraido de metadados ja conhecidos pelo runtime,
 * mantendo ligacoes para APIs, schemas, actions e surfaces existentes.
 * </p>
 */
public record DomainCatalogResponse(
        String schemaVersion,
        DomainServiceInfo service,
        DomainReleaseInfo release,
        List<DomainContextItem> contexts,
        List<DomainNodeItem> nodes,
        List<DomainEdgeItem> edges,
        List<DomainBindingItem> bindings,
        List<DomainAliasItem> aliases,
        List<DomainEvidenceItem> evidence,
        List<DomainGovernanceItem> governance
) {

    public record DomainServiceInfo(
            String serviceKey,
            String name,
            String version
    ) {
    }

    public record DomainReleaseInfo(
            String releaseKey,
            String generatedAt,
            String sourceHash
    ) {
    }

    public record DomainContextItem(
            String contextKey,
            String label,
            String description,
            String owner,
            String source,
            String status,
            List<String> tags,
            Double confidence,
            String semanticOwner,
            String lifecycle,
            Map<String, Object> businessGlossary
    ) {
    }

    public record DomainNodeItem(
            String nodeKey,
            String contextKey,
            String nodeType,
            String label,
            String description,
            String status,
            String source,
            Double confidence,
            Map<String, Object> metadata,
            List<String> tags,
            String owner,
            String semanticOwner,
            String lifecycle,
            Map<String, Object> businessGlossary,
            Map<String, Object> resolution,
            List<String> sourceEvidenceKeys
    ) {
    }

    public record DomainEdgeItem(
            String edgeKey,
            String sourceNodeKey,
            String targetNodeKey,
            String edgeType,
            String label,
            Double confidence,
            List<String> evidenceKeys
    ) {
    }

    public record DomainBindingItem(
            String bindingKey,
            String nodeKey,
            String bindingType,
            Map<String, Object> target,
            List<DomainSchemaLink> schemaLinks,
            List<String> evidenceKeys
    ) {
    }

    public record DomainSchemaLink(
            String rel,
            String href,
            String schemaId,
            String schemaType
    ) {
    }

    public record DomainAliasItem(
            String aliasKey,
            String nodeKey,
            String alias,
            String locale,
            String source,
            Double confidence
    ) {
    }

    public record DomainEvidenceItem(
            String evidenceKey,
            String evidenceType,
            Map<String, Object> sourceRef,
            String summary,
            Double confidence
    ) {
    }

    public record DomainGovernanceItem(
            String governanceKey,
            String nodeKey,
            String annotationType,
            String classification,
            String dataCategory,
            List<String> complianceTags,
            String owner,
            String steward,
            String retentionPolicy,
            Map<String, Object> aiUsage,
            String source,
            Double confidence
    ) {
    }
}
