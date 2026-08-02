package org.praxisplatform.uischema.action;

import java.util.List;

/**
 * Runtime projections that become stale after successful command execution.
 */
public record ActionRefreshPolicy(
        boolean item,
        boolean collection,
        boolean actions,
        boolean capabilities,
        List<String> resourceKeys
) {
    public ActionRefreshPolicy {
        resourceKeys = resourceKeys == null
                ? List.of()
                : resourceKeys.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }
}
