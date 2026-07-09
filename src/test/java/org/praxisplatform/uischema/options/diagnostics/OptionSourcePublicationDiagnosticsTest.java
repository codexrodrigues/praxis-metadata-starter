package org.praxisplatform.uischema.options.diagnostics;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.options.GovernedOptionSourceCatalog;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class OptionSourcePublicationDiagnosticsTest {

    @Test
    void reportsProviderBackedSourceConfiguredButNotPublished() {
        OptionSourcePublicationDiagnostics diagnostics = new OptionSourcePublicationDiagnostics(
                OptionSourceRegistry.empty(),
                List.of(OptionSourcePublicationInventory.of(
                        OptionSourcePublicationCandidate.of(
                                "administracao-pessoal.folhas-emp.mes-ano-visivel",
                                "/api/empresas",
                                "folhas-emp-catalog"
                        )
                ))
        );

        OptionSourcePublicationDiagnostic diagnostic = diagnostics.unpublishedOrMismatched().getFirst();

        assertEquals(OptionSourcePublicationStatus.UNPUBLISHED, diagnostic.status());
        assertEquals("administracao-pessoal.folhas-emp.mes-ano-visivel", diagnostic.sourceKey());
        assertEquals("folhas-emp-catalog", diagnostic.catalogKey());
        assertEquals("/api/empresas", diagnostic.expectedResourcePath());
        assertNull(diagnostic.publishedResourcePath());
        assertFalse(diagnostic.message().contains("SELECT"));
        assertFalse(diagnostic.message().contains("HADES"));
        assertFalse(diagnostic.message().contains("tenant"));
    }

    @Test
    void reportsProviderBackedSourcePublishedByDifferentResource() {
        OptionSourceDescriptor descriptor = descriptor(
                "administracao-pessoal.folhas-emp.numero-por-mes",
                "/api/outro-catalogo"
        );
        OptionSourcePublicationDiagnostics diagnostics = new OptionSourcePublicationDiagnostics(
                GovernedOptionSourceCatalog.registry(PublishedResource.class, descriptor),
                List.of(OptionSourcePublicationInventory.of(
                        OptionSourcePublicationCandidate.of(
                                descriptor.key(),
                                "/api/empresas",
                                "folhas-emp-catalog"
                        )
                ))
        );

        OptionSourcePublicationDiagnostic diagnostic = diagnostics.unpublishedOrMismatched().getFirst();

        assertEquals(OptionSourcePublicationStatus.RESOURCE_MISMATCH, diagnostic.status());
        assertEquals("/api/empresas", diagnostic.expectedResourcePath());
        assertEquals("/api/outro-catalogo", diagnostic.publishedResourcePath());
    }

    @Test
    void omitsProviderBackedSourcePublishedByExpectedResourceFromFailures() {
        OptionSourceDescriptor descriptor = descriptor(
                "administracao-pessoal.folhas-emp.numero-por-mes",
                "/api/empresas"
        );
        OptionSourcePublicationDiagnostics diagnostics = new OptionSourcePublicationDiagnostics(
                GovernedOptionSourceCatalog.registry(PublishedResource.class, descriptor),
                List.of(OptionSourcePublicationInventory.of(
                        OptionSourcePublicationCandidate.of(
                                descriptor.key(),
                                descriptor.resourcePath(),
                                "folhas-emp-catalog"
                        )
                ))
        );

        List<OptionSourcePublicationDiagnostic> allDiagnostics = diagnostics.diagnose();

        assertEquals(1, allDiagnostics.size());
        assertEquals(OptionSourcePublicationStatus.PUBLISHED, allDiagnostics.getFirst().status());
        assertEquals(List.of(), diagnostics.unpublishedOrMismatched());
    }

    private static OptionSourceDescriptor descriptor(String key, String resourcePath) {
        return GovernedOptionSourceCatalog.providerBackedLookup(
                key,
                resourcePath,
                null,
                "label",
                "id",
                List.of("empresaId"),
                Map.of("empresaId", "empresaId"),
                OptionSourcePolicy.defaults()
        );
    }

    static final class PublishedResource {
    }
}
