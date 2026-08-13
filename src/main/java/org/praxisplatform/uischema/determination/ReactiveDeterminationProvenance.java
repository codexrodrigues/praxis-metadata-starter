package org.praxisplatform.uischema.determination;

/**
 * Provenance estrutural estavel do binding.
 *
 * <p>{@code source} identifica o provider/capability de codigo e {@code version} identifica sua
 * versao estrutural. Valores contextuais de tenant, usuario ou publicacao aplicada sao proibidos
 * nesta superficie publica e cacheavel.</p>
 */
public record ReactiveDeterminationProvenance(
        ReactiveDeterminationProvenanceKind kind,
        String source,
        String version
) {
}
