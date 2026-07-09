package org.praxisplatform.uischema.options.diagnostics;

import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceRegistry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares provider-backed option sources known by host inventories with the canonical public registry.
 */
public class OptionSourcePublicationDiagnostics {

    private final OptionSourceRegistry optionSourceRegistry;
    private final List<OptionSourcePublicationInventory> inventories;

    public OptionSourcePublicationDiagnostics(
            OptionSourceRegistry optionSourceRegistry,
            Collection<OptionSourcePublicationInventory> inventories
    ) {
        this.optionSourceRegistry = optionSourceRegistry == null ? OptionSourceRegistry.empty() : optionSourceRegistry;
        this.inventories = inventories == null ? List.of() : inventories.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    public List<OptionSourcePublicationDiagnostic> diagnose() {
        return candidates().stream()
                .map(this::diagnose)
                .toList();
    }

    public List<OptionSourcePublicationDiagnostic> unpublishedOrMismatched() {
        return diagnose().stream()
                .filter(diagnostic -> diagnostic.status() != OptionSourcePublicationStatus.PUBLISHED)
                .toList();
    }

    private OptionSourcePublicationDiagnostic diagnose(OptionSourcePublicationCandidate candidate) {
        return optionSourceRegistry.resolveByResourcePathAndKey(candidate.expectedResourcePath(), candidate.sourceKey())
                .map(descriptor -> published(candidate, descriptor))
                .orElseGet(() -> mismatchOrMissing(candidate));
    }

    private OptionSourcePublicationDiagnostic published(
            OptionSourcePublicationCandidate candidate,
            OptionSourceDescriptor descriptor
    ) {
        return new OptionSourcePublicationDiagnostic(
                candidate.sourceKey(),
                candidate.catalogKey(),
                OptionSourcePublicationStatus.PUBLISHED,
                candidate.expectedResourcePath(),
                descriptor.resourcePath(),
                "Option source '%s' is published by resource '%s'.".formatted(
                        candidate.sourceKey(),
                        descriptor.resourcePath()
                )
        );
    }

    private OptionSourcePublicationDiagnostic mismatchOrMissing(OptionSourcePublicationCandidate candidate) {
        OptionSourceDescriptor sameKey = optionSourceRegistry.descriptors().stream()
                .filter(descriptor -> candidate.sourceKey().equals(descriptor.key()))
                .findFirst()
                .orElse(null);
        if (sameKey != null) {
            return new OptionSourcePublicationDiagnostic(
                    candidate.sourceKey(),
                    candidate.catalogKey(),
                    OptionSourcePublicationStatus.RESOURCE_MISMATCH,
                    candidate.expectedResourcePath(),
                    sameKey.resourcePath(),
                    "Option source '%s' is known by catalog '%s', expected resource '%s', but is published by resource '%s'.".formatted(
                            candidate.sourceKey(),
                            candidate.catalogKey(),
                            candidate.expectedResourcePath(),
                            sameKey.resourcePath()
                    )
            );
        }
        return new OptionSourcePublicationDiagnostic(
                candidate.sourceKey(),
                candidate.catalogKey(),
                OptionSourcePublicationStatus.UNPUBLISHED,
                candidate.expectedResourcePath(),
                null,
                "Option source '%s' is known by catalog '%s' but is not published by any OptionSourceRegistry descriptor.".formatted(
                        candidate.sourceKey(),
                        candidate.catalogKey()
                )
        );
    }

    private List<OptionSourcePublicationCandidate> candidates() {
        Map<String, OptionSourcePublicationCandidate> byPublicIdentity = new LinkedHashMap<>();
        for (OptionSourcePublicationInventory inventory : inventories) {
            Collection<OptionSourcePublicationCandidate> inventoryCandidates = inventory.optionSourcePublicationCandidates();
            if (inventoryCandidates == null || inventoryCandidates.isEmpty()) {
                continue;
            }
            for (OptionSourcePublicationCandidate candidate : inventoryCandidates) {
                if (candidate == null) {
                    continue;
                }
                byPublicIdentity.putIfAbsent(
                        candidate.expectedResourcePath() + "::" + candidate.sourceKey(),
                        candidate
                );
            }
        }
        return List.copyOf(byPublicIdentity.values());
    }
}
