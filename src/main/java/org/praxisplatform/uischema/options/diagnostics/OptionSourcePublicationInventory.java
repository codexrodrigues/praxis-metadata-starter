package org.praxisplatform.uischema.options.diagnostics;

import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Optional host/provider SPI for publication diagnostics of provider-backed option sources.
 *
 * <p>
 * Implement this interface when a host has provider-backed sources configured outside the public
 * {@code OptionSourceRegistry} and wants tests or startup diagnostics to verify that each source is also published by
 * the canonical resource owner.
 * </p>
 */
public interface OptionSourcePublicationInventory {

    Collection<OptionSourcePublicationCandidate> optionSourcePublicationCandidates();

    static OptionSourcePublicationInventory of(OptionSourcePublicationCandidate... candidates) {
        return () -> candidates == null
                ? List.of()
                : Arrays.stream(candidates)
                .filter(Objects::nonNull)
                .toList();
    }
}
