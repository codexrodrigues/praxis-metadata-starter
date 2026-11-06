package org.praxisplatform.uischema.domain;

/**
 * Explicit semantic identity of a fact root for one RuleSet operation.
 * This describes the referenced concept, not a SQL lookup, CRUD capability or row identity.
 *
 * @param role business role of the root, distinct even when two roles reference the same resource
 * @param serviceKey service that owns the referenced definition
 * @param contextKey bounded context that owns the referenced definition
 * @param resourceKey canonical referenced resource/concept key; a local CRUD endpoint is not required
 */
public record ConceptRootBinding(
        @io.swagger.v3.oas.annotations.media.Schema(description = "Business role played by this concept in one RuleSet operation; distinct roles may reference the same concept.") String role,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Canonical service that owns the concept definition, including references to another service.") String serviceKey,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Bounded context that owns the referenced concept.") String contextKey,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Canonical logical resource key; a referenced concept does not require a local CRUD endpoint.") String resourceKey) {
    public ConceptRootBinding {
        role = required(role, "role");
        serviceKey = required(serviceKey, "serviceKey");
        contextKey = required(contextKey, "contextKey");
        resourceKey = required(resourceKey, "resourceKey");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
