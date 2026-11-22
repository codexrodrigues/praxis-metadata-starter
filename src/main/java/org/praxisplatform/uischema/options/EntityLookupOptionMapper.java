package org.praxisplatform.uischema.options;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.schema.DomainLineageResolver;
import org.praxisplatform.uischema.schema.DomainSchemaGovernance;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shared mapper projecting JPA entities, DTOs, records, or pre-resolved lookup rows into
 * canonical {@link OptionDTO} instances with a closed {@code extra} contract and OPA-aware
 * declarative {@link DomainGovernance} masking.
 */
public final class EntityLookupOptionMapper {

    public enum GovernanceMaskingMode {
        /** Apply declarative {@code @DomainGovernance} masking unless {@code AuthzGovernedOptionSourceProvider} is active. */
        AUTO,
        /** Always apply declarative {@code @DomainGovernance} masking. */
        ALWAYS,
        /** Skip declarative masking (caller guarantees governance). */
        NEVER
    }

    public record ResolvedLookupValues(
            Object code,
            List<Object> descriptions,
            Object status,
            Boolean disabled,
            String disabledReason,
            Boolean selectable,
            List<Object> badgeValues,
            List<Object> richFieldValues
    ) {
    }

    private enum MaskTier {
        ALLOW,
        MASK_EXPORTABLE,
        MASK_NON_EXPORTABLE,
        DENY
    }

    private interface PropertyAccessor {
        Object read(Object target);
    }

    private record AccessorCacheKey(Class<?> type, String segment) {
    }

    private record GovernanceCacheKey(Class<?> rootClass, String dottedPath) {
    }

    private record GovernanceSegmentInfo(
            Class<?> propertyType,
            List<AnnotatedElement> elements
    ) {
        <A extends Annotation> A findAnnotation(Class<A> annotationType) {
            for (AnnotatedElement el : elements) {
                A ann = AnnotatedElementUtils.findMergedAnnotation(el, annotationType);
                if (ann != null) {
                    return ann;
                }
            }
            return null;
        }
    }

    private static final String MASKED_VALUE = "***";
    private static final String AUTHZ_PROVIDER_CLASS =
            "org.praxisplatform.authz.host.metadata.AuthzGovernedOptionSourceProvider";
    private static final Set<String> FORBIDDEN_PATH_SEGMENTS = Set.of("class", "classLoader");
    private static final PropertyAccessor MISSING_ACCESSOR = target -> null;

    private static final ConcurrentHashMap<AccessorCacheKey, PropertyAccessor> ACCESSOR_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<GovernanceCacheKey, MaskTier> GOVERNANCE_TIER_CACHE =
            new ConcurrentHashMap<>();

    private EntityLookupOptionMapper() {
    }

    public static OptionDTO<Object> toOption(OptionSourceDescriptor descriptor, Object source) {
        return toOption(descriptor, source, null, source == null ? null : source.getClass(), GovernanceMaskingMode.AUTO);
    }

    public static OptionDTO<Object> toOption(
            OptionSourceDescriptor descriptor,
            Object source,
            String labelOverride
    ) {
        return toOption(descriptor, source, labelOverride, source == null ? null : source.getClass(), GovernanceMaskingMode.AUTO);
    }

    public static OptionDTO<Object> toOption(
            OptionSourceDescriptor descriptor,
            Object source,
            String labelOverride,
            Class<?> governanceClass,
            GovernanceMaskingMode maskingMode
    ) {
        Objects.requireNonNull(descriptor, "descriptor is required");
        if (source == null) {
            throw new IllegalArgumentException("source object is required");
        }
        String valuePath = descriptor.valuePropertyPath() != null
                ? descriptor.valuePropertyPath()
                : descriptor.propertyPath();
        Object id = readPropertyPath(source, valuePath);
        String labelPath = descriptor.labelPropertyPath() != null
                ? descriptor.labelPropertyPath()
                : descriptor.propertyPath();
        Object rawLabel = labelOverride != null ? labelOverride : readPropertyPath(source, labelPath);
        if (rawLabel == null && descriptor.entityLookup() != null
                && descriptor.entityLookup().display() != null
                && descriptor.entityLookup().display().primaryPropertyPath() != null) {
            rawLabel = readPropertyPath(source, descriptor.entityLookup().display().primaryPropertyPath());
        }

        Class<?> effectiveGovClass = governanceClass != null ? governanceClass : source.getClass();
        return mapFromValues(
                descriptor,
                id,
                rawLabel,
                labelOverride != null,
                path -> readPropertyPath(source, path),
                source,
                effectiveGovClass,
                maskingMode
        );
    }

    public static OptionDTO<Object> mapFromValues(
            OptionSourceDescriptor descriptor,
            Object id,
            Object label,
            Function<String, Object> valueLookup,
            Class<?> governanceClass,
            GovernanceMaskingMode maskingMode
    ) {
        return mapFromValues(descriptor, id, label, false, valueLookup, null, governanceClass, maskingMode);
    }

    private static OptionDTO<Object> mapFromValues(
            OptionSourceDescriptor descriptor,
            Object id,
            Object label,
            boolean explicitLabelOverride,
            Function<String, Object> valueLookup,
            Object rawSourceForFallback,
            Class<?> governanceClass,
            GovernanceMaskingMode maskingMode
    ) {
        Objects.requireNonNull(descriptor, "descriptor is required");
        Objects.requireNonNull(valueLookup, "valueLookup is required");

        EntityLookupDescriptor lookup = descriptor.entityLookup();
        if (lookup == null) {
            String effectiveLabel = resolveEffectiveLabel(
                    descriptor, id, label, explicitLabelOverride, null, governanceClass, maskingMode);
            return new org.praxisplatform.uischema.dto.OptionDTO<>(id, effectiveLabel, null);
        }

        Object code = lookup.codePropertyPath() != null ? valueLookup.apply(lookup.codePropertyPath()) : null;
        List<Object> descriptions = new ArrayList<>(lookup.descriptionPropertyPaths().size());
        for (String descPath : lookup.descriptionPropertyPaths()) {
            descriptions.add(valueLookup.apply(descPath));
        }

        String statusPath = resolveStatusPath(lookup);
        Object status = statusPath != null ? valueLookup.apply(statusPath) : null;
        Boolean disabled = lookup.disabledPropertyPath() != null
                ? toBooleanOrNull(valueLookup.apply(lookup.disabledPropertyPath()))
                : null;
        String disabledReason = lookup.disabledReasonPropertyPath() != null
                ? stringifyNullable(valueLookup.apply(lookup.disabledReasonPropertyPath()))
                : null;
        Boolean selectable = lookup.selectionPolicy() != null && lookup.selectionPolicy().selectablePropertyPath() != null
                ? toBooleanOrNull(valueLookup.apply(lookup.selectionPolicy().selectablePropertyPath()))
                : null;

        LookupDisplayDescriptor display = lookup.display();
        List<Object> badgeValues = List.of();
        List<Object> richFieldValues = List.of();
        if (display != null) {
            if (!display.badgePropertyPaths().isEmpty()) {
                List<Object> badges = new ArrayList<>(display.badgePropertyPaths().size());
                for (String badgePath : display.badgePropertyPaths()) {
                    badges.add(valueLookup.apply(badgePath));
                }
                badgeValues = badges;
            }
            if (!display.fields().isEmpty()) {
                List<Object> richValues = new ArrayList<>(display.fields().size());
                for (LookupDisplayFieldDescriptor fieldDesc : display.fields()) {
                    Object val = fieldDesc.propertyPath() != null ? valueLookup.apply(fieldDesc.propertyPath()) : null;
                    if (val == null && rawSourceForFallback != null
                            && fieldDesc.key() != null
                            && !fieldDesc.key().equals(fieldDesc.propertyPath())) {
                        val = readPropertyPath(rawSourceForFallback, fieldDesc.key());
                    }
                    richValues.add(val);
                }
                richFieldValues = richValues;
            }
        }

        return mapFromResolved(
                descriptor,
                id,
                label,
                explicitLabelOverride,
                null,
                new ResolvedLookupValues(code, descriptions, status, disabled, disabledReason, selectable, badgeValues, richFieldValues),
                governanceClass,
                maskingMode
        );
    }

    public static org.praxisplatform.uischema.dto.OptionDTO<Object> mapFromResolved(
            OptionSourceDescriptor descriptor,
            Object id,
            Object label,
            ResolvedLookupValues values,
            Class<?> governanceClass,
            GovernanceMaskingMode maskingMode
    ) {
        return mapFromResolved(descriptor, id, label, false, null, values, governanceClass, maskingMode);
    }

    public static org.praxisplatform.uischema.dto.OptionDTO<Object> mapFromResolved(
            OptionSourceDescriptor descriptor,
            Object id,
            Object label,
            String labelGovernancePathOverride,
            ResolvedLookupValues values,
            Class<?> governanceClass,
            GovernanceMaskingMode maskingMode
    ) {
        return mapFromResolved(descriptor, id, label, false, labelGovernancePathOverride, values, governanceClass, maskingMode);
    }

    private static org.praxisplatform.uischema.dto.OptionDTO<Object> mapFromResolved(
            OptionSourceDescriptor descriptor,
            Object id,
            Object label,
            boolean explicitLabelOverride,
            String labelGovernancePathOverride,
            ResolvedLookupValues values,
            Class<?> governanceClass,
            GovernanceMaskingMode maskingMode
    ) {
        Objects.requireNonNull(descriptor, "descriptor is required");
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        boolean applyMasking = shouldApplyDeclarativeMasking(descriptor, labelGovernancePathOverride, governanceClass, maskingMode);

        String effectiveLabel = resolveEffectiveLabel(
                descriptor, id, label, explicitLabelOverride, labelGovernancePathOverride,
                applyMasking ? governanceClass : null,
                applyMasking ? GovernanceMaskingMode.ALWAYS : GovernanceMaskingMode.NEVER);

        if (lookup == null) {
            return new org.praxisplatform.uischema.dto.OptionDTO<>(id, effectiveLabel, null);
        }

        ResolvedLookupValues safeValues = values != null
                ? values
                : new ResolvedLookupValues(null, List.of(), null, null, null, null, List.of(), List.of());

        Map<String, Object> extra = new LinkedHashMap<>();

        Object rawCode = safeValues.code();
        if (rawCode != null) {
            Object governedCode = applyGovernanceToExtraValue(
                    rawCode, applyMasking ? resolvePropertyTier(governanceClass, lookup, lookup.codePropertyPath()) : MaskTier.ALLOW);
            if (governedCode != OMITTED) {
                extra.put("code", governedCode);
            }
        }

        String description = buildDescription(lookup, safeValues.descriptions(), governanceClass, applyMasking);
        putIfNotBlank(extra, "description", description);

        Object rawStatus = safeValues.status();
        boolean selectable = resolveSelectable(safeValues, rawStatus, lookup);
        if (rawStatus != null) {
            String statusPath = resolveStatusPath(lookup);
            Object governedStatus = applyGovernanceToExtraValue(
                    rawStatus, applyMasking ? resolvePropertyTier(governanceClass, lookup, statusPath) : MaskTier.ALLOW);
            if (governedStatus != OMITTED) {
                extra.put("status", governedStatus);
            }
        }

        extra.put("selectable", selectable);

        String disabledReason = stringifyNullable(safeValues.disabledReason());
        if ((disabledReason == null || disabledReason.isBlank()) && !selectable && lookup.selectionPolicy() != null) {
            disabledReason = renderDisabledReasonTemplate(lookup.selectionPolicy().disabledReasonTemplate(), rawStatus, id);
        }
        putIfNotBlank(extra, "disabledReason", disabledReason);

        putIfNotBlank(extra, "detailHref", resolveTemplate(lookup.detail(), id, true));
        putIfNotBlank(extra, "detailRoute", resolveTemplate(lookup.detail(), id, false));
        putIfNotBlank(extra, "resourcePath", descriptor.resourcePath());
        putIfNotBlank(extra, "entityKey", lookup.entityKey());

        List<String> badges = buildDisplayBadges(lookup, safeValues.badgeValues(), governanceClass, applyMasking);
        if (!badges.isEmpty()) {
            extra.put("badges", badges);
        }

        List<Map<String, Object>> richFields = buildDisplayFields(lookup, safeValues.richFieldValues(), governanceClass, applyMasking);
        if (!richFields.isEmpty()) {
            extra.put("richFields", richFields);
        }

        return new org.praxisplatform.uischema.dto.OptionDTO<>(id, effectiveLabel, extra);
    }

    /**
     * Safely navigates a dotted property path across beans, Hibernate proxies, Java records, or maps,
     * preferring getter methods before declared fields and returning {@code null} for missing/transient segments.
     */
    public static Object readPropertyPath(Object source, String propertyPath) {
        if (source == null || propertyPath == null || propertyPath.isBlank()) {
            return null;
        }
        String[] segments = propertyPath.trim().split("\\.", -1);
        Object current = source;
        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            if (current instanceof Optional<?> optional) {
                current = optional.orElse(null);
                if (current == null) {
                    return null;
                }
            }
            String trimmed = segment.trim();
            if (trimmed.isEmpty() || FORBIDDEN_PATH_SEGMENTS.contains(trimmed)) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(trimmed);
                continue;
            }
            PropertyAccessor accessor = resolveAccessor(current.getClass(), trimmed);
            current = accessor.read(current);
        }
        if (current instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return current;
    }

    private static String resolveEffectiveLabel(
            OptionSourceDescriptor descriptor,
            Object id,
            Object rawLabel,
            boolean explicitLabelOverride,
            String labelGovernancePathOverride,
            Class<?> governanceClass,
            GovernanceMaskingMode maskingMode
    ) {
        String baseLabel = stringify(rawLabel != null ? rawLabel : id);
        boolean hasGovernanceClass = governanceClass != null || resolveSourceDtoClass(descriptor) != null;
        if (explicitLabelOverride || !hasGovernanceClass || maskingMode == GovernanceMaskingMode.NEVER) {
            return baseLabel;
        }
        MaskTier tier = resolveLabelTier(descriptor, labelGovernancePathOverride, governanceClass);
        if (tier == MaskTier.ALLOW) {
            return baseLabel;
        }
        if (maskingMode == GovernanceMaskingMode.AUTO && isAuthzProviderGoverningCall()) {
            return baseLabel;
        }
        return applyTierToLabel(tier, baseLabel, id);
    }

    private static String applyTierToLabel(MaskTier tier, String baseLabel, Object id) {
        return switch (tier) {
            case DENY -> stringify(id);
            case MASK_NON_EXPORTABLE -> MASKED_VALUE;
            case MASK_EXPORTABLE, ALLOW -> baseLabel;
        };
    }

    private static boolean shouldApplyDeclarativeMasking(
            OptionSourceDescriptor descriptor,
            String labelGovernancePathOverride,
            Class<?> governanceClass,
            GovernanceMaskingMode maskingMode
    ) {
        boolean hasGovernanceClass = governanceClass != null || resolveSourceDtoClass(descriptor) != null;
        if (!hasGovernanceClass || maskingMode == GovernanceMaskingMode.NEVER) {
            return false;
        }
        if (maskingMode == GovernanceMaskingMode.ALWAYS) {
            return true;
        }
        if (!hasAnyRestrictedProjectedPath(descriptor, labelGovernancePathOverride, governanceClass)) {
            return false;
        }
        return !isAuthzProviderGoverningCall();
    }

    private static boolean hasAnyRestrictedProjectedPath(
            OptionSourceDescriptor descriptor,
            String labelGovernancePathOverride,
            Class<?> governanceClass
    ) {
        if (resolveLabelTier(descriptor, labelGovernancePathOverride, governanceClass) != MaskTier.ALLOW) {
            return true;
        }
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        if (lookup == null) {
            return false;
        }
        if (lookup.codePropertyPath() != null
                && resolvePropertyTier(governanceClass, lookup, lookup.codePropertyPath()) != MaskTier.ALLOW) {
            return true;
        }
        String statusPath = resolveStatusPath(lookup);
        if (statusPath != null
                && resolvePropertyTier(governanceClass, lookup, statusPath) != MaskTier.ALLOW) {
            return true;
        }
        for (String descPath : lookup.descriptionPropertyPaths()) {
            if (resolvePropertyTier(governanceClass, lookup, descPath) != MaskTier.ALLOW) {
                return true;
            }
        }
        LookupDisplayDescriptor display = lookup.display();
        if (display != null) {
            for (String badgePath : display.badgePropertyPaths()) {
                if (resolvePropertyTier(governanceClass, lookup, badgePath) != MaskTier.ALLOW) {
                    return true;
                }
            }
            for (LookupDisplayFieldDescriptor field : display.fields()) {
                if (resolveFieldTier(governanceClass, lookup, field) != MaskTier.ALLOW) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAuthzProviderGoverningCall() {
        return StackWalker.getInstance().walk(frames ->
                frames.anyMatch(frame -> AUTHZ_PROVIDER_CLASS.equals(frame.getClassName())));
    }

    private static final Object OMITTED = new Object();

    private static Object applyGovernanceToExtraValue(Object rawValue, MaskTier tier) {
        if (rawValue == null) {
            return null;
        }
        return switch (tier) {
            case DENY -> OMITTED;
            case MASK_NON_EXPORTABLE, MASK_EXPORTABLE -> {
                String text = stringifyNullable(rawValue);
                yield text == null || text.isBlank() ? null : MASKED_VALUE;
            }
            case ALLOW -> rawValue;
        };
    }

    private static String buildDescription(
            EntityLookupDescriptor lookup,
            List<Object> values,
            Class<?> governanceClass,
            boolean applyMasking
    ) {
        List<String> descriptionPaths = lookup.descriptionPropertyPaths();
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Object raw = values.get(i);
            if (raw == null) {
                continue;
            }
            String path = i < descriptionPaths.size() ? descriptionPaths.get(i) : null;
            Object governed = applyMasking ? applyGovernanceToExtraValue(raw, resolvePropertyTier(governanceClass, lookup, path)) : raw;
            if (governed == OMITTED) {
                continue;
            }
            String text = stringifyNullable(governed);
            if (text != null && !text.isBlank()) {
                parts.add(text);
            }
        }
        return parts.isEmpty() ? null : String.join(" - ", parts);
    }

    private static List<String> buildDisplayBadges(
            EntityLookupDescriptor lookup,
            List<Object> badgeValues,
            Class<?> governanceClass,
            boolean applyMasking
    ) {
        LookupDisplayDescriptor display = lookup.display();
        if (display == null || display.badgePropertyPaths().isEmpty() || badgeValues == null || badgeValues.isEmpty()) {
            return List.of();
        }
        List<String> badges = new ArrayList<>();
        for (int i = 0; i < Math.min(display.badgePropertyPaths().size(), badgeValues.size()); i++) {
            Object raw = badgeValues.get(i);
            if (raw == null) {
                continue;
            }
            String path = display.badgePropertyPaths().get(i);
            Object governed = applyMasking ? applyGovernanceToExtraValue(raw, resolvePropertyTier(governanceClass, lookup, path)) : raw;
            if (governed == OMITTED) {
                continue;
            }
            String text = stringifyNullable(governed);
            if (text != null && !text.isBlank() && !badges.contains(text)) {
                badges.add(text);
            }
        }
        return List.copyOf(badges);
    }

    private static List<Map<String, Object>> buildDisplayFields(
            EntityLookupDescriptor lookup,
            List<Object> richFieldValues,
            Class<?> governanceClass,
            boolean applyMasking
    ) {
        LookupDisplayDescriptor display = lookup.display();
        if (display == null || display.fields().isEmpty() || richFieldValues == null || richFieldValues.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int i = 0; i < Math.min(display.fields().size(), richFieldValues.size()); i++) {
            LookupDisplayFieldDescriptor fieldDesc = display.fields().get(i);
            Object raw = richFieldValues.get(i);
            if (raw == null) {
                continue;
            }
            MaskTier tier = applyMasking ? resolveFieldTier(governanceClass, lookup, fieldDesc) : MaskTier.ALLOW;
            Object governed = applyGovernanceToExtraValue(raw, tier);
            if (governed == OMITTED) {
                continue;
            }
            String text = stringifyNullable(governed);
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfNotBlank(entry, "key", fieldDesc.key());
            putIfNotBlank(entry, "label", fieldDesc.label());
            putIfNotBlank(entry, "icon", fieldDesc.icon());
            putIfNotBlank(entry, "presentation", fieldDesc.presentation());
            putIfNotBlank(entry, "tone", fieldDesc.tone());
            putIfNotBlank(entry, "format", fieldDesc.format());
            entry.put("value", text);
            fields.add(entry);
        }
        return fields;
    }

    private static Class<?> resolveSourceDtoClass(OptionSourceDescriptor descriptor) {
        return descriptor != null && descriptor.entityLookup() != null
                ? descriptor.entityLookup().sourceDtoClass()
                : null;
    }

    private static MaskTier resolveLabelTier(
            OptionSourceDescriptor descriptor,
            String labelGovernancePathOverride,
            Class<?> governanceClass
    ) {
        Class<?> sourceDtoClass = resolveSourceDtoClass(descriptor);
        if (governanceClass == null && sourceDtoClass == null) {
            return MaskTier.ALLOW;
        }
        String descriptorLabelPath = descriptor.labelPropertyPath() != null
                ? descriptor.labelPropertyPath()
                : descriptor.propertyPath();
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        if (descriptorLabelPath == null && lookup != null && lookup.display() != null) {
            descriptorLabelPath = lookup.display().primaryPropertyPath();
        }
        String entityLabelPath = labelGovernancePathOverride != null
                ? labelGovernancePathOverride
                : descriptorLabelPath;

        MaskTier tier = MaskTier.ALLOW;
        if (governanceClass != null) {
            tier = mostRestrictive(tier, resolveTier(governanceClass, entityLabelPath));
            if (descriptorLabelPath != null && !descriptorLabelPath.equals(entityLabelPath)) {
                tier = mostRestrictive(tier, resolveTier(governanceClass, descriptorLabelPath));
            }
        }
        if (sourceDtoClass != null) {
            tier = mostRestrictive(tier, resolveTier(sourceDtoClass, descriptorLabelPath));
            if (entityLabelPath != null && !entityLabelPath.equals(descriptorLabelPath)) {
                tier = mostRestrictive(tier, resolveTier(sourceDtoClass, entityLabelPath));
            }
            if (lookup != null && lookup.display() != null) {
                for (LookupDisplayFieldDescriptor field : lookup.display().fields()) {
                    if (matchesField(field, entityLabelPath) || matchesField(field, descriptorLabelPath)) {
                        if (field.key() != null) {
                            tier = mostRestrictive(tier, resolveTier(sourceDtoClass, field.key()));
                        }
                        if (field.propertyPath() != null && !field.propertyPath().equals(field.key())) {
                            tier = mostRestrictive(tier, resolveTier(sourceDtoClass, field.propertyPath()));
                        }
                    }
                }
            }
        }
        return tier;
    }

    private static MaskTier resolvePropertyTier(
            Class<?> governanceClass,
            EntityLookupDescriptor lookup,
            String propertyPath
    ) {
        if (propertyPath == null || propertyPath.isBlank()) {
            return MaskTier.ALLOW;
        }
        Class<?> sourceDtoClass = lookup != null ? lookup.sourceDtoClass() : null;
        if (governanceClass == null && sourceDtoClass == null) {
            return MaskTier.ALLOW;
        }
        MaskTier tier = MaskTier.ALLOW;
        if (governanceClass != null) {
            tier = mostRestrictive(tier, resolveTier(governanceClass, propertyPath));
            if (lookup != null && lookup.display() != null) {
                for (LookupDisplayFieldDescriptor field : lookup.display().fields()) {
                    if (propertyPath.equals(field.key())
                            && field.propertyPath() != null
                            && !field.propertyPath().equals(propertyPath)) {
                        tier = mostRestrictive(tier, resolveTier(governanceClass, field.propertyPath()));
                    }
                }
            }
        }
        if (sourceDtoClass != null) {
            tier = mostRestrictive(tier, resolveTier(sourceDtoClass, propertyPath));
            if (lookup.display() != null) {
                for (LookupDisplayFieldDescriptor field : lookup.display().fields()) {
                    if (matchesField(field, propertyPath)) {
                        if (field.key() != null) {
                            tier = mostRestrictive(tier, resolveTier(sourceDtoClass, field.key()));
                        }
                        if (field.propertyPath() != null && !field.propertyPath().equals(field.key())) {
                            tier = mostRestrictive(tier, resolveTier(sourceDtoClass, field.propertyPath()));
                        }
                    }
                }
            }
        }
        return tier;
    }

    private static MaskTier resolveFieldTier(
            Class<?> governanceClass,
            EntityLookupDescriptor lookup,
            LookupDisplayFieldDescriptor fieldDesc
    ) {
        Class<?> sourceDtoClass = lookup != null ? lookup.sourceDtoClass() : null;
        if ((governanceClass == null && sourceDtoClass == null) || fieldDesc == null) {
            return MaskTier.ALLOW;
        }
        MaskTier tier = MaskTier.ALLOW;
        if (governanceClass != null) {
            if (fieldDesc.propertyPath() != null) {
                tier = mostRestrictive(tier, resolveTier(governanceClass, fieldDesc.propertyPath()));
            }
            if (fieldDesc.key() != null && !fieldDesc.key().equals(fieldDesc.propertyPath())) {
                tier = mostRestrictive(tier, resolveTier(governanceClass, fieldDesc.key()));
            }
        }
        if (sourceDtoClass != null) {
            if (fieldDesc.key() != null) {
                tier = mostRestrictive(tier, resolveTier(sourceDtoClass, fieldDesc.key()));
            }
            if (fieldDesc.propertyPath() != null && !fieldDesc.propertyPath().equals(fieldDesc.key())) {
                tier = mostRestrictive(tier, resolveTier(sourceDtoClass, fieldDesc.propertyPath()));
            }
        }
        return tier;
    }

    private static boolean matchesField(LookupDisplayFieldDescriptor field, String candidatePath) {
        if (field == null || candidatePath == null) {
            return false;
        }
        return candidatePath.equals(field.propertyPath()) || candidatePath.equals(field.key());
    }

    private static MaskTier mostRestrictive(MaskTier left, MaskTier right) {
        if (left == null) {
            return right == null ? MaskTier.ALLOW : right;
        }
        if (right == null) {
            return left;
        }
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static MaskTier resolveTier(Class<?> rootClass, String dottedPath) {
        if (rootClass == null || dottedPath == null || dottedPath.isBlank()) {
            return MaskTier.ALLOW;
        }
        Class<?> userClass = ClassUtils.getUserClass(rootClass);
        return GOVERNANCE_TIER_CACHE.computeIfAbsent(
                new GovernanceCacheKey(userClass, dottedPath.trim()),
                key -> computeGovernanceTier(key.rootClass(), key.dottedPath())
        );
    }

    private static MaskTier computeGovernanceTier(Class<?> rootClass, String dottedPath) {
        String[] segments = dottedPath.split("\\.", -1);
        Class<?> currentClass = rootClass;
        ObjectNode accumulated = null;

        for (String rawSegment : segments) {
            String segment = rawSegment.trim();
            if (segment.isEmpty() || currentClass == null || currentClass == Object.class || currentClass == Void.class) {
                break;
            }
            ObjectNode typeGov = DomainLineageResolver.resolveTypeGovernance(currentClass);
            if (typeGov != null) {
                accumulated = DomainSchemaGovernance.mergeOrCopy(accumulated, typeGov);
            }
            GovernanceSegmentInfo info = findGovernanceSegment(currentClass, segment);
            if (info == null) {
                break;
            }
            DomainGovernance memberGov = info.findAnnotation(DomainGovernance.class);
            DomainLineage lineage = info.findAnnotation(DomainLineage.class);
            ObjectNode resolvedNode = resolveMemberGovernanceQuietly(lineage, memberGov, typeGov);
            if (resolvedNode != null) {
                accumulated = DomainSchemaGovernance.mergeOrCopy(accumulated, resolvedNode);
            }
            currentClass = info.propertyType();
        }

        if (accumulated == null) {
            return MaskTier.ALLOW;
        }
        String visibility = accumulated.path("aiUsage").path("visibility").asText("allow").toLowerCase(Locale.ROOT);
        String classification = accumulated.path("classification").asText("internal").toLowerCase(Locale.ROOT);
        String dataCategory = accumulated.path("dataCategory").asText("operational").toLowerCase(Locale.ROOT);
        boolean exportable = DomainSchemaGovernance.isExportable(accumulated);

        if ("deny".equals(visibility)
                || "credential".equals(dataCategory)
                || ("restricted".equals(classification) && !"mask".equals(visibility))) {
            return MaskTier.DENY;
        }
        if ("mask".equals(visibility)
                || "summarize_only".equals(visibility)
                || "sensitive_personal".equals(dataCategory)
                || !exportable
                || "restricted".equals(classification)) {
            boolean allowReadablePersonalDisplayLabel = "personal".equals(dataCategory)
                    && exportable
                    && !"restricted".equals(classification)
                    && "mask".equals(visibility);
            return allowReadablePersonalDisplayLabel
                    ? MaskTier.MASK_EXPORTABLE
                    : MaskTier.MASK_NON_EXPORTABLE;
        }
        return MaskTier.ALLOW;
    }

    private static ObjectNode resolveMemberGovernanceQuietly(
            DomainLineage lineage,
            DomainGovernance memberGov,
            ObjectNode typeGov
    ) {
        if (lineage != null) {
            try {
                DomainLineageResolver.ResolvedLineage resolved =
                        DomainLineageResolver.resolve(lineage, memberGov, typeGov);
                return resolved != null ? resolved.effectiveGovernance() : null;
            } catch (IllegalArgumentException ignored) {
                // External or unannotated lineage target falls back to local member/type governance
            }
        }
        ObjectNode memberNode = memberGov != null ? DomainLineageResolver.toGovernanceNode(memberGov) : null;
        try {
            return DomainSchemaGovernance.mergeOrCopy(typeGov, memberNode);
        } catch (IllegalArgumentException ignored) {
            return memberNode != null ? memberNode : typeGov;
        }
    }

    private static GovernanceSegmentInfo findGovernanceSegment(Class<?> ownerType, String propertyName) {
        Class<?> propertyType = null;
        List<AnnotatedElement> elements = new ArrayList<>();
        for (Class<?> curr = ownerType; curr != null && curr != Object.class && curr != Record.class; curr = curr.getSuperclass()) {
            if (curr.isRecord() && curr.getRecordComponents() != null) {
                for (RecordComponent rc : curr.getRecordComponents()) {
                    if (propertyName.equals(rc.getName())) {
                        if (propertyType == null) {
                            propertyType = rc.getType();
                        }
                        elements.add(rc);
                        if (rc.getAccessor() != null) {
                            elements.add(rc.getAccessor());
                        }
                    }
                }
            }
            try {
                Field f = curr.getDeclaredField(propertyName);
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                    if (propertyType == null) {
                        propertyType = f.getType();
                    }
                    elements.add(f);
                }
            } catch (NoSuchFieldException ignored) {
                // continue
            }
            addAccessorElements(curr, propertyName, elements);
            if (propertyType == null) {
                for (AnnotatedElement el : elements) {
                    if (el instanceof Method m) {
                        propertyType = m.getReturnType();
                        break;
                    }
                }
            }
        }
        for (Class<?> iface : ClassUtils.getAllInterfacesForClass(ownerType)) {
            addAccessorElements(iface, propertyName, elements);
            if (propertyType == null) {
                for (AnnotatedElement el : elements) {
                    if (el instanceof Method m) {
                        propertyType = m.getReturnType();
                        break;
                    }
                }
            }
        }
        return elements.isEmpty() ? null : new GovernanceSegmentInfo(propertyType, List.copyOf(elements));
    }

    private static void addAccessorElements(Class<?> type, String propertyName, List<AnnotatedElement> elements) {
        if (propertyName.isEmpty()) {
            return;
        }
        String cap = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        List<String> candidates = type.isRecord()
                ? List.of(propertyName, "get" + cap, "is" + cap)
                : List.of("get" + cap, "is" + cap);
        for (String candidate : candidates) {
            try {
                Method m = type.getDeclaredMethod(candidate);
                if (m.getParameterCount() == 0
                        && !Modifier.isStatic(m.getModifiers())
                        && !m.isSynthetic()
                        && !m.isBridge()
                        && m.getReturnType() != void.class
                        && !"getClass".equals(m.getName())) {
                    if (candidate.startsWith("is")
                            && m.getReturnType() != boolean.class
                            && m.getReturnType() != Boolean.class) {
                        continue;
                    }
                    elements.add(m);
                }
            } catch (NoSuchMethodException ignored) {
                // continue
            }
        }
    }

    private static PropertyAccessor resolveAccessor(Class<?> targetClass, String segment) {
        return ACCESSOR_CACHE.computeIfAbsent(
                new AccessorCacheKey(targetClass, segment),
                key -> createAccessor(key.type(), key.segment())
        );
    }

    private static PropertyAccessor createAccessor(Class<?> targetClass, String segment) {
        if (targetClass.isRecord() && targetClass.getRecordComponents() != null) {
            for (RecordComponent rc : targetClass.getRecordComponents()) {
                if (segment.equals(rc.getName()) && rc.getAccessor() != null) {
                    Method accessor = rc.getAccessor();
                    accessor.trySetAccessible();
                    return target -> invokeMethod(accessor, target);
                }
            }
        }
        String cap = Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
        for (String methodName : List.of("get" + cap, "is" + cap, segment)) {
            Method method = findZeroArgMethod(targetClass, methodName);
            if (method != null) {
                method.trySetAccessible();
                return target -> invokeMethod(method, target);
            }
        }
        Field field = findFieldInHierarchy(targetClass, segment);
        if (field != null) {
            field.trySetAccessible();
            return target -> readField(field, target);
        }
        return MISSING_ACCESSOR;
    }

    private static Method findZeroArgMethod(Class<?> targetClass, String methodName) {
        for (Class<?> curr = targetClass; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
            try {
                Method m = curr.getDeclaredMethod(methodName);
                if (m.getParameterCount() == 0
                        && !Modifier.isStatic(m.getModifiers())
                        && !m.isSynthetic()
                        && !m.isBridge()
                        && m.getReturnType() != void.class
                        && !"getClass".equals(m.getName())) {
                    if (methodName.startsWith("is")
                            && m.getReturnType() != boolean.class
                            && m.getReturnType() != Boolean.class) {
                        continue;
                    }
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // continue
            }
        }
        for (Class<?> iface : ClassUtils.getAllInterfacesForClass(targetClass)) {
            try {
                Method m = iface.getMethod(methodName);
                if (m.getParameterCount() == 0
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() != void.class
                        && !"getClass".equals(m.getName())) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // continue
            }
        }
        return null;
    }

    private static Field findFieldInHierarchy(Class<?> targetClass, String fieldName) {
        for (Class<?> curr = targetClass; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
            try {
                Field f = curr.getDeclaredField(fieldName);
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
                // continue
            }
        }
        return null;
    }

    private static Object invokeMethod(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object readField(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static String resolveStatusPath(EntityLookupDescriptor lookup) {
        if (lookup.statusPropertyPath() != null) {
            return lookup.statusPropertyPath();
        }
        return lookup.selectionPolicy() != null ? lookup.selectionPolicy().statusPropertyPath() : null;
    }

    private static boolean resolveSelectable(
            ResolvedLookupValues values,
            Object rawStatus,
            EntityLookupDescriptor lookup
    ) {
        if (values.selectable() != null) {
            return values.selectable();
        }
        if (Boolean.TRUE.equals(values.disabled())) {
            return false;
        }
        LookupSelectionPolicy policy = lookup.selectionPolicy();
        if (policy == null) {
            return true;
        }
        String statusText = stringifyNullable(rawStatus);
        if (statusText != null && policy.blockedStatuses().contains(statusText)) {
            return false;
        }
        if (statusText != null && !policy.allowedStatuses().isEmpty()) {
            return policy.allowedStatuses().contains(statusText);
        }
        return true;
    }

    private static String renderDisabledReasonTemplate(String template, Object rawStatus, Object id) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String statusText = stringifyNullable(rawStatus);
        String idText = stringifyNullable(id);
        return template
                .replace("{status}", statusText == null ? "" : statusText)
                .replace("{id}", idText == null ? "" : idText);
    }

    private static String resolveTemplate(LookupDetailDescriptor detail, Object id, boolean href) {
        if (detail == null || id == null) {
            return null;
        }
        String template = href ? detail.hrefTemplate() : detail.routeTemplate();
        if (template == null || template.isBlank()) {
            return null;
        }
        return template.replace("{id}", stringify(id));
    }

    private static Boolean toBooleanOrNull(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return null;
    }

    private static String stringify(Object value) {
        return CompositeIdSupport.stringify(value);
    }

    private static String stringifyNullable(Object value) {
        return CompositeIdSupport.stringifyNullable(value);
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
