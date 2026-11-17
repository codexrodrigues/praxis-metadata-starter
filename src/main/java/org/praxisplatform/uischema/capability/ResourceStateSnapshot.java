package org.praxisplatform.uischema.capability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot canonico do estado de um recurso para avaliacao de availability.
 *
 * <p>
 * O snapshot e carregado uma vez por request contextual e compartilhado entre todas as avaliacoes
 * daquele catalogo, evitando custo N+1 por surface ou action.
 * </p>
 */
public record ResourceStateSnapshot(
        String state,
        Map<String, Object> metadata
) {

    public ResourceStateSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ResourceStateSnapshot of(String state) {
        return new ResourceStateSnapshot(state, Map.of());
    }

    public static ResourceStateSnapshot of(String state, Map<String, Object> metadata) {
        return new ResourceStateSnapshot(state, metadata);
    }

    public static Builder builder(String state) {
        return new Builder(state);
    }

    /** Builder fluente para compor snapshots de estado e overrides contextuais de metadata. */
    public static final class Builder {
        private final String state;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String state) {
            this.state = state;
        }

        public Builder metadata(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.metadata.put(key.trim(), value);
            }
            return this;
        }

        public Builder metadata(Map<String, Object> entries) {
            if (entries != null) {
                entries.forEach(this::metadata);
            }
            return this;
        }

        public Builder actionAllowed(String actionId, boolean allowed) {
            return actionMetadata(actionId, DefaultResourceAvailabilityService.KEY_ALLOWED, allowed);
        }

        public Builder actionReason(String actionId, String reason) {
            return actionMetadata(actionId, DefaultResourceAvailabilityService.KEY_REASON, reason);
        }

        public Builder actionLegalBasis(String actionId, String legalBasis) {
            return actionMetadata(actionId, DefaultResourceAvailabilityService.KEY_LEGAL_BASIS, legalBasis);
        }

        public Builder action(String actionId, boolean allowed, String reason) {
            actionAllowed(actionId, allowed);
            if (!allowed && reason != null && !reason.isBlank()) {
                actionReason(actionId, reason);
            }
            return this;
        }

        public Builder surfaceAllowed(String surfaceId, boolean allowed) {
            if (surfaceId != null && !surfaceId.isBlank()) {
                this.metadata.put(
                        DefaultResourceAvailabilityService.SURFACE_PREFIX + "." + surfaceId.trim() + "."
                                + DefaultResourceAvailabilityService.KEY_ALLOWED,
                        allowed
                );
            }
            return this;
        }

        public Builder surfaceReason(String surfaceId, String reason) {
            if (surfaceId != null && !surfaceId.isBlank() && reason != null && !reason.isBlank()) {
                this.metadata.put(
                        DefaultResourceAvailabilityService.SURFACE_PREFIX + "." + surfaceId.trim() + "."
                                + DefaultResourceAvailabilityService.KEY_REASON,
                        reason.trim()
                );
            }
            return this;
        }

        public Builder operationAllowed(String operationId, boolean allowed) {
            if (operationId != null && !operationId.isBlank()) {
                this.metadata.put(
                        DefaultResourceAvailabilityService.OPERATION_PREFIX + "." + operationId.trim() + "."
                                + DefaultResourceAvailabilityService.KEY_ALLOWED,
                        allowed
                );
            }
            return this;
        }

        private Builder actionMetadata(String actionId, String attribute, Object value) {
            if (actionId != null && !actionId.isBlank() && value != null) {
                Object effective = value instanceof String str ? str.trim() : value;
                if (!(effective instanceof String str) || !str.isEmpty()) {
                    this.metadata.put(
                            DefaultResourceAvailabilityService.ACTION_PREFIX + "." + actionId.trim() + "." + attribute,
                            effective
                    );
                }
            }
            return this;
        }

        public ResourceStateSnapshot build() {
            return new ResourceStateSnapshot(state, metadata);
        }
    }
}
