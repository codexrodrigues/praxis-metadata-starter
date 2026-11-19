package org.praxisplatform.uischema.schema;

import org.praxisplatform.uischema.annotation.FormLayout;
import org.praxisplatform.uischema.annotation.FormLayoutMode;
import org.praxisplatform.uischema.annotation.FormLayoutSection;
import org.praxisplatform.uischema.annotation.FormLayoutStep;
import org.praxisplatform.uischema.annotation.UiBoolean;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolve anotações {@link FormLayout} em tipos DTO ou métodos de controller para o contrato JSON
 * canônico {@code x-ui.formLayout} consumido por {@code @praxisui/core} e
 * {@code @praxisui/dynamic-form}.
 *
 * @since 8.0.0
 */
public final class FormLayoutMetadataResolver {

    private record IndexedSection(int order, int index, Map<String, Object> payload) {}

    private record AutoHeuristicDecision(String wireMode, boolean autoReviewStep) {}

    private FormLayoutMetadataResolver() {
    }

    public static Map<String, Object> resolve(AnnotatedElement element) {
        if (element == null) {
            return null;
        }
        FormLayout layout = AnnotatedElementUtils.findMergedAnnotation(element, FormLayout.class);
        if (layout == null) {
            return null;
        }

        FormLayoutSection[] rawSections = layout.sections();
        FormLayoutStep[] rawSteps = layout.steps();
        AutoHeuristicDecision heuristic = layout.mode() == FormLayoutMode.AUTO
                ? resolveAutoHeuristic(element, rawSections, rawSteps)
                : new AutoHeuristicDecision(layout.mode().wireValue(), false);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mode", heuristic.wireMode());

        String defaultTabId = StringUtils.hasText(layout.defaultTabId()) ? layout.defaultTabId().trim() : null;
        if (defaultTabId != null) {
            root.put("defaultTabId", defaultTabId);
        }

        String tabsAppearance = layout.tabsAppearance().wireValue();
        if (StringUtils.hasText(tabsAppearance)) {
            root.put("tabsAppearance", tabsAppearance);
        }

        putIfNotNull(root, "accordionMulti", layout.accordionMulti().toBoolean());
        putIfNotNull(root, "hideSectionHeaderInTabs", layout.hideSectionHeaderInTabs().toBoolean());
        putIfNotNull(root, "hideSectionHeaderInStepper", layout.hideSectionHeaderInStepper().toBoolean());

        String stepperInReadMode = layout.stepperInReadMode().wireValue();
        if (StringUtils.hasText(stepperInReadMode)) {
            root.put("stepperInReadMode", stepperInReadMode);
        }
        if (layout.stepperReviewStep() != UiBoolean.AUTO) {
            putIfNotNull(root, "stepperReviewStep", layout.stepperReviewStep().toBoolean());
        } else if (heuristic.autoReviewStep()) {
            root.put("stepperReviewStep", Boolean.TRUE);
        }
        if (layout.draftPersistence() != UiBoolean.AUTO) {
            putIfNotNull(root, "draftPersistence", layout.draftPersistence().toBoolean());
        } else if (heuristic.autoReviewStep()) {
            root.put("draftPersistence", Boolean.TRUE);
        }

        Set<String> seenIds = new LinkedHashSet<>();
        if (rawSections.length > 0) {
            boolean hasExplicitOrder = Arrays.stream(rawSections).anyMatch(sec -> sec.order() != 0);
            List<IndexedSection> indexed = new ArrayList<>(rawSections.length);

            for (int idx = 0; idx < rawSections.length; idx++) {
                FormLayoutSection section = rawSections[idx];
                if (!StringUtils.hasText(section.id()) || !StringUtils.hasText(section.title())) {
                    throw new IllegalArgumentException("@FormLayoutSection id and title must not be blank.");
                }
                String id = section.id().trim();
                if (!seenIds.add(id)) {
                    throw new IllegalArgumentException("@FormLayoutSection duplicate id: " + id);
                }

                int effectiveOrder = hasExplicitOrder ? section.order() : idx;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("title", section.title().trim());
                putIfHasText(entry, "subtitle", section.subtitle());
                putIfHasText(entry, "icon", section.icon());
                entry.put("order", effectiveOrder);
                putIfNotNull(entry, "optional", section.optional().toBoolean());

                List<String> groups = Arrays.stream(section.groups())
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .distinct()
                        .toList();
                if (!groups.isEmpty()) {
                    entry.put("groups", groups);
                }

                indexed.add(new IndexedSection(effectiveOrder, idx, Map.copyOf(entry)));
            }

            if (defaultTabId != null && !seenIds.contains(defaultTabId)) {
                throw new IllegalArgumentException(
                        "@FormLayout.defaultTabId '" + defaultTabId + "' must match one of the declared @FormLayoutSection ids."
                );
            }

            indexed.sort(Comparator.comparingInt(IndexedSection::order).thenComparingInt(IndexedSection::index));
            List<Map<String, Object>> sortedSections = indexed.stream()
                    .map(IndexedSection::payload)
                    .toList();
            root.put("sections", List.copyOf(sortedSections));
        }

        if (rawSteps.length > 0) {
            Set<String> seenStepIds = new LinkedHashSet<>();
            List<Map<String, Object>> stepsList = new ArrayList<>(rawSteps.length);
            for (FormLayoutStep step : rawSteps) {
                if (!StringUtils.hasText(step.id())) {
                    throw new IllegalArgumentException("@FormLayoutStep id must not be blank.");
                }
                String stepId = step.id().trim();
                if (!seenStepIds.add(stepId)) {
                    throw new IllegalArgumentException("@FormLayoutStep duplicate id: " + stepId);
                }
                List<String> sectionIds = Arrays.stream(step.sectionIds())
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .distinct()
                        .toList();
                if (sectionIds.isEmpty()) {
                    throw new IllegalArgumentException("@FormLayoutStep '" + stepId + "' must declare at least one non-blank sectionId.");
                }
                if (!seenIds.isEmpty()) {
                    for (String secId : sectionIds) {
                        if (!seenIds.contains(secId)) {
                            throw new IllegalArgumentException(
                                    "@FormLayoutStep '" + stepId + "' references unknown sectionId '" + secId
                                            + "'. Declared @FormLayoutSection ids: " + seenIds
                            );
                        }
                    }
                }
                Map<String, Object> stepEntry = new LinkedHashMap<>();
                stepEntry.put("id", stepId);
                putIfHasText(stepEntry, "label", step.label());
                putIfHasText(stepEntry, "description", step.description());
                putIfHasText(stepEntry, "icon", step.icon());
                stepEntry.put("sectionIds", sectionIds);
                putIfNotNull(stepEntry, "optional", step.optional().toBoolean());
                stepsList.add(Map.copyOf(stepEntry));
            }
            root.put("steps", List.copyOf(stepsList));
        }

        return Map.copyOf(root);
    }

    private static AutoHeuristicDecision resolveAutoHeuristic(
            AnnotatedElement element,
            FormLayoutSection[] rawSections,
            FormLayoutStep[] rawSteps
    ) {
        int uiFieldCount = 0;
        Set<String> distinctGroups = new LinkedHashSet<>();
        Set<String> seenProperties = new LinkedHashSet<>();
        Class<?> dtoClass = extractTargetDtoClass(element);
        if (dtoClass != null) {
            Class<?> current = dtoClass;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    UISchema uiSchema = AnnotatedElementUtils.findMergedAnnotation(field, UISchema.class);
                    if (uiSchema != null && !uiSchema.hidden() && seenProperties.add(field.getName())) {
                        uiFieldCount++;
                        if (StringUtils.hasText(uiSchema.group())) {
                            distinctGroups.add(uiSchema.group().trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                for (Method method : current.getDeclaredMethods()) {
                    if (method.isSynthetic()
                            || Modifier.isStatic(method.getModifiers())
                            || method.getParameterCount() != 0
                            || method.getReturnType() == Void.TYPE) {
                        continue;
                    }
                    UISchema uiSchema = AnnotatedElementUtils.findMergedAnnotation(method, UISchema.class);
                    if (uiSchema != null && !uiSchema.hidden()) {
                        String propName = normalizeAccessorPropertyName(method.getName());
                        if (seenProperties.add(propName)) {
                            uiFieldCount++;
                            if (StringUtils.hasText(uiSchema.group())) {
                                distinctGroups.add(uiSchema.group().trim().toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
                current = current.getSuperclass();
            }
        }
        int effectiveGroupCount = Math.max(distinctGroups.size(), rawSections.length);
        if (rawSteps.length >= 2 || effectiveGroupCount >= 4 || (effectiveGroupCount >= 3 && uiFieldCount >= 12)) {
            boolean autoReview = effectiveGroupCount >= 4 || rawSteps.length >= 3;
            return new AutoHeuristicDecision(FormLayoutMode.STEPPER.wireValue(), autoReview);
        }
        if (effectiveGroupCount >= 2 && (uiFieldCount >= 6 || rawSections.length >= 2)) {
            return new AutoHeuristicDecision(FormLayoutMode.TABS.wireValue(), false);
        }
        return new AutoHeuristicDecision(FormLayoutMode.FLAT.wireValue(), false);
    }

    private static Class<?> extractTargetDtoClass(AnnotatedElement element) {
        if (element instanceof Class<?> clazz) {
            return clazz;
        }
        if (element instanceof Method method) {
            for (Parameter param : method.getParameters()) {
                if (AnnotatedElementUtils.hasAnnotation(param, org.springframework.web.bind.annotation.RequestBody.class)) {
                    Class<?> type = param.getType();
                    if (type != Object.class
                            && !Map.class.isAssignableFrom(type)
                            && !java.util.Collection.class.isAssignableFrom(type)
                            && !org.praxisplatform.uischema.filter.dto.GenericFilterDTO.class.isAssignableFrom(type)) {
                        return type;
                    }
                }
            }
        }
        return null;
    }

    private static String normalizeAccessorPropertyName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3))) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2 && Character.isUpperCase(methodName.charAt(2))) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        return methodName;
    }

    private static void putIfHasText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Boolean value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
