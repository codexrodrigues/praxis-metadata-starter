package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.surface.SurfaceDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Servico canonico de avaliacao contextual e propagacao de metadados legais/operacionais
 * a partir de {@link ResourceStateSnapshot}.
 *
 * <p>
 * Reconhece nativamente em {@link ResourceStateSnapshot#metadata()} as chaves convencionais
 * {@code action.<id>.allowed}, {@code action.<id>.reason}, {@code action.<id>.legalBasis},
 * {@code action.<id>.legalPurpose}, {@code action.<id>.customTitle}, {@code action.<id>.blockedByRule}
 * (e equivalentes {@code surface.<id>.*} e {@code operation.<id>.*}), propagando-as para
 * {@link AvailabilityDecision#metadata()} sem exigir configuracoes manuais por recurso no host.
 * </p>
 */
public class DefaultResourceAvailabilityService {

    public static final DefaultResourceAvailabilityService INSTANCE = new DefaultResourceAvailabilityService();

    public static final String ACTION_PREFIX = "action";
    public static final String SURFACE_PREFIX = "surface";
    public static final String OPERATION_PREFIX = "operation";
    public static final String RESOURCE_PREFIX = "resource";

    public static final String KEY_ALLOWED = "allowed";
    public static final String KEY_REASON = "reason";
    public static final String KEY_LEGAL_BASIS = "legalBasis";
    public static final String KEY_LEGAL_PURPOSE = "legalPurpose";
    public static final String KEY_CUSTOM_TITLE = "customTitle";
    public static final String KEY_BLOCKED_BY_RULE = "blockedByRule";

    private static final String DEFAULT_DENIAL_REASON = "resource-state-blocked";

    public AvailabilityDecision evaluateActionSnapshot(
            ActionDefinition definition,
            ResourceStateSnapshot snapshot
    ) {
        return evaluateActionSnapshot(definition, snapshot, Map.of());
    }

    public AvailabilityDecision evaluateActionSnapshot(
            ActionDefinition definition,
            ResourceStateSnapshot snapshot,
            Map<String, Object> baseMetadata
    ) {
        Map<String, Object> metadata = baseMetadata != null
                ? new LinkedHashMap<>(baseMetadata)
                : new LinkedHashMap<>();
        if (definition == null) {
            return AvailabilityDecision.allow(metadata);
        }
        applyStaticLegalMetadata(definition.legalPurpose(), definition.legalBasisRef(), metadata);
        applyResourceLevelLegalFallback(snapshot, metadata);
        return evaluateSnapshotByPrefix(ACTION_PREFIX, definition.id(), snapshot, metadata);
    }

    public AvailabilityDecision evaluateSurfaceSnapshot(
            SurfaceDefinition definition,
            ResourceStateSnapshot snapshot
    ) {
        return evaluateSurfaceSnapshot(definition, snapshot, Map.of());
    }

    public AvailabilityDecision evaluateSurfaceSnapshot(
            SurfaceDefinition definition,
            ResourceStateSnapshot snapshot,
            Map<String, Object> baseMetadata
    ) {
        Map<String, Object> metadata = baseMetadata != null
                ? new LinkedHashMap<>(baseMetadata)
                : new LinkedHashMap<>();
        if (definition == null) {
            return AvailabilityDecision.allow(metadata);
        }
        applyStaticLegalMetadata(definition.legalPurpose(), definition.legalBasisRef(), metadata);
        applyResourceLevelLegalFallback(snapshot, metadata);
        return evaluateSnapshotByPrefix(SURFACE_PREFIX, definition.id(), snapshot, metadata);
    }

    public AvailabilityDecision evaluateOperationSnapshot(
            String operationId,
            ResourceStateSnapshot snapshot
    ) {
        return evaluateOperationSnapshot(operationId, snapshot, Map.of());
    }

    public AvailabilityDecision evaluateOperationSnapshot(
            String operationId,
            ResourceStateSnapshot snapshot,
            Map<String, Object> baseMetadata
    ) {
        Map<String, Object> metadata = baseMetadata != null
                ? new LinkedHashMap<>(baseMetadata)
                : new LinkedHashMap<>();
        if (operationId == null || operationId.isBlank() || snapshot == null || snapshot.metadata().isEmpty()) {
            return AvailabilityDecision.allow(metadata);
        }
        AvailabilityDecision opDecision = evaluateSnapshotByPrefix(OPERATION_PREFIX, operationId, snapshot, metadata);
        if (!opDecision.allowed()) {
            return opDecision;
        }
        return evaluateSnapshotByPrefix(ACTION_PREFIX, operationId, snapshot, opDecision.metadata());
    }

    public AvailabilityDecision evaluateSnapshotByPrefix(
            String domainPrefix,
            String targetId,
            ResourceStateSnapshot snapshot,
            Map<String, Object> baseMetadata
    ) {
        Map<String, Object> metadata = baseMetadata != null
                ? new LinkedHashMap<>(baseMetadata)
                : new LinkedHashMap<>();
        if (domainPrefix == null || domainPrefix.isBlank() || targetId == null || targetId.isBlank()) {
            return AvailabilityDecision.allow(metadata);
        }
        if (snapshot == null || snapshot.metadata() == null || snapshot.metadata().isEmpty()) {
            return AvailabilityDecision.allow(metadata);
        }

        String fullPrefix = domainPrefix.trim() + "." + targetId.trim() + ".";
        Boolean explicitAllowed = null;
        String explicitReason = null;

        for (Map.Entry<String, Object> entry : snapshot.metadata().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || !key.startsWith(fullPrefix) || value == null) {
                continue;
            }
            String attribute = key.substring(fullPrefix.length()).trim();
            if (attribute.isEmpty()) {
                continue;
            }
            if (KEY_ALLOWED.equals(attribute)) {
                explicitAllowed = coerceBoolean(value);
                if (explicitAllowed != null) {
                    metadata.put(KEY_ALLOWED, explicitAllowed);
                }
                continue;
            }
            if (KEY_REASON.equals(attribute)) {
                String text = coerceNonBlankString(value);
                if (text != null) {
                    explicitReason = text;
                    metadata.put(KEY_REASON, text);
                }
                continue;
            }
            if (KEY_LEGAL_BASIS.equals(attribute)
                    || KEY_LEGAL_PURPOSE.equals(attribute)
                    || KEY_CUSTOM_TITLE.equals(attribute)
                    || KEY_BLOCKED_BY_RULE.equals(attribute)) {
                String text = coerceNonBlankString(value);
                if (text != null) {
                    metadata.put(attribute, text);
                }
                continue;
            }
            metadata.put(attribute, value);
        }

        if (Boolean.FALSE.equals(explicitAllowed)) {
            if ( snapshot.state() != null && !snapshot.state().isBlank() ) {
                metadata.putIfAbsent("resourceState", snapshot.state());
            }
            String denialReason = explicitReason != null && !explicitReason.isBlank()
                    ? explicitReason
                    : DEFAULT_DENIAL_REASON;
            return AvailabilityDecision.deny(denialReason, metadata);
        }

        return AvailabilityDecision.allow(metadata);
    }

    private void applyStaticLegalMetadata(
            String staticLegalPurpose,
            String staticLegalBasisRef,
            Map<String, Object> metadata
    ) {
        if (staticLegalPurpose != null && !staticLegalPurpose.isBlank()) {
            metadata.putIfAbsent(KEY_LEGAL_PURPOSE, staticLegalPurpose.trim());
        }
        if (staticLegalBasisRef != null && !staticLegalBasisRef.isBlank()) {
            metadata.putIfAbsent(KEY_LEGAL_BASIS, staticLegalBasisRef.trim());
        }
    }

    private void applyResourceLevelLegalFallback(
            ResourceStateSnapshot snapshot,
            Map<String, Object> metadata
    ) {
        if (snapshot == null || snapshot.metadata() == null || snapshot.metadata().isEmpty()) {
            return;
        }
        String resourceLegalPurpose = coerceNonBlankString(
                snapshot.metadata().get(RESOURCE_PREFIX + "." + KEY_LEGAL_PURPOSE)
        );
        if (resourceLegalPurpose != null) {
            metadata.putIfAbsent(KEY_LEGAL_PURPOSE, resourceLegalPurpose);
        }
        String resourceLegalBasis = coerceNonBlankString(
                snapshot.metadata().get(RESOURCE_PREFIX + "." + KEY_LEGAL_BASIS)
        );
        if (resourceLegalBasis != null) {
            metadata.putIfAbsent(KEY_LEGAL_BASIS, resourceLegalBasis);
        }
    }

    private static Boolean coerceBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof CharSequence seq) {
            String text = seq.toString().trim();
            if ("true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static String coerceNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
