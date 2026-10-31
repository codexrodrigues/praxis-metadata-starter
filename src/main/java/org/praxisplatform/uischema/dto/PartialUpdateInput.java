package org.praxisplatform.uischema.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashSet;
import java.util.Set;

/**
 * Presence tracking for typed partial-update inputs without changing their JSON shape.
 * Each explicit setter must call {@link #supplied(String)} with its public wire name,
 * including when the supplied value is null. Omitted fields remain absent.
 * This class does not authorize fields, apply changes or decide nullability; the
 * operation schema, validation and domain handler own those decisions.
 */
public abstract class PartialUpdateInput {
    @JsonIgnore
    @Schema(hidden = true)
    private final Set<String> suppliedProperties = new HashSet<>();

    /** Records a property accepted by a typed setter; never bind this set from JSON. */
    protected final void supplied(String wireName) {
        suppliedProperties.add(java.util.Objects.requireNonNull(wireName));
    }

    /** Whether the caller explicitly supplied this wire property, even with null. */
    public final boolean wasSupplied(String wireName) {
        return suppliedProperties.contains(wireName);
    }
}
