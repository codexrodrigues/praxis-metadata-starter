package org.praxisplatform.uischema.extension;

import io.swagger.v3.oas.models.media.Schema;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Herda metadados omitidos de {@code @UISchema} ({@code icon}, {@code endpoint}, {@code valueField},
 * {@code displayField} e {@code options}) do {@code ResponseDTO} (ou de propriedade irmã do próprio
 * {@code FilterDTO} com mesma {@code relation}) para propriedades correspondentes de um {@code FilterDTO},
 * respeitando a compatibilidade de predicado e preservando {@code controlType} explícitos.
 */
public final class FilterFieldMetadataInheritanceSupport {

    private static final String X_UI = "x-ui";
    private static final List<String> INHERITABLE_KEYS = List.of(
            FieldConfigProperties.ICON.getValue(),
            FieldConfigProperties.ENDPOINT.getValue(),
            FieldConfigProperties.VALUE_FIELD.getValue(),
            FieldConfigProperties.DISPLAY_FIELD.getValue(),
            "options"
    );
    private static final Set<Filterable.FilterOperation> NON_OPTION_OPERATIONS = EnumSet.of(
            Filterable.FilterOperation.IS_NULL,
            Filterable.FilterOperation.IS_NOT_NULL,
            Filterable.FilterOperation.IS_TRUE,
            Filterable.FilterOperation.IS_FALSE,
            Filterable.FilterOperation.IN_LAST_DAYS,
            Filterable.FilterOperation.IN_NEXT_DAYS,
            Filterable.FilterOperation.SIZE_GT,
            Filterable.FilterOperation.SIZE_EQ,
            Filterable.FilterOperation.SIZE_LT,
            Filterable.FilterOperation.BETWEEN,
            Filterable.FilterOperation.BETWEEN_EXCLUSIVE,
            Filterable.FilterOperation.NOT_BETWEEN,
            Filterable.FilterOperation.OUTSIDE_RANGE,
            Filterable.FilterOperation.GREATER_THAN,
            Filterable.FilterOperation.GREATER_OR_EQUAL,
            Filterable.FilterOperation.LESS_THAN,
            Filterable.FilterOperation.LESS_OR_EQUAL,
            Filterable.FilterOperation.ON_DATE,
            Filterable.FilterOperation.EFFECTIVE_ON_DATE
    );

    private FilterFieldMetadataInheritanceSupport() {
    }

    /**
     * Aplica heranca de metadados sobre um {@link Schema} OpenAPI de {@code FilterDTO} a partir do
     * {@link Schema} OpenAPI resolvido do {@code ResponseDTO} associado.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void inheritToOpenApiFilterSchema(
            Schema<?> filterSchema,
            Class<?> filterClass,
            Schema<?> responseSchema,
            Map<String, Schema> componentSchemas
    ) {
        if (filterSchema == null || filterClass == null) {
            return;
        }
        Map<String, Schema> filterProps = filterSchema.getProperties();
        if (filterProps == null || filterProps.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> responseXUiByPath = responseSchema != null
                ? indexOpenApiSchemaXUi(responseSchema, componentSchemas)
                : Map.of();

        Map<String, FilterTarget> filterTargets = inspectFilterTargets(filterClass);
        Map<String, SiblingOptionSource> siblingOptionsByRelation =
                buildSiblingOptionSourcesOpenApi(filterTargets, filterProps);
        if (responseXUiByPath.isEmpty() && siblingOptionsByRelation.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Schema> entry : filterProps.entrySet()) {
            String propName = entry.getKey();
            Schema<?> propSchema = entry.getValue();
            if (propSchema == null) {
                continue;
            }
            FilterTarget target = filterTargets.get(propName);
            Map<String, Object> responseSourceXUi = resolveSourceXUi(propName, target, responseXUiByPath);
            Map<String, Object> siblingSourceXUi = resolveSiblingSourceXUi(propName, target, siblingOptionsByRelation);
            if ((responseSourceXUi == null || responseSourceXUi.isEmpty())
                    && (siblingSourceXUi == null || siblingSourceXUi.isEmpty())) {
                continue;
            }
            Map<String, Object> extensions = propSchema.getExtensions();
            if (extensions == null) {
                extensions = new LinkedHashMap<>();
                propSchema.setExtensions(extensions);
            }
            Map<String, Object> targetXUi = extensions.get(X_UI) instanceof Map<?, ?> existing
                    ? (Map<String, Object>) existing
                    : null;
            if (targetXUi == null) {
                targetXUi = new LinkedHashMap<>();
                extensions.put(X_UI, targetXUi);
            }
            if (responseSourceXUi != null && !responseSourceXUi.isEmpty()) {
                copyOmittedMetadata(target, responseSourceXUi, targetXUi);
            }
            if (siblingSourceXUi != null && !siblingSourceXUi.isEmpty()) {
                copyOmittedMetadata(target, siblingSourceXUi, targetXUi);
            }
        }
    }

    /**
     * Aplica heranca de metadados no payload JSON de {@code /schemas/filtered} de um {@code FilterDTO}
     * a partir da arvore {@code ResponseDTO} ja resolvida no documento OpenAPI.
     */
    @SuppressWarnings("unchecked")
    public static void inheritToSchemaMap(
            Map<String, Object> filterSchemaMap,
            Class<?> filterClass,
            Map<String, Object> responseSchemaMap,
            Map<String, Object> allComponentSchemas
    ) {
        if (filterSchemaMap == null || filterClass == null) {
            return;
        }
        Object rawProps = filterSchemaMap.get("properties");
        if (!(rawProps instanceof Map<?, ?> filterProps) || filterProps.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> responseXUiByPath = new LinkedHashMap<>();
        if (responseSchemaMap != null) {
            collectMapSchemaXUi("", responseSchemaMap, allComponentSchemas, responseXUiByPath, 0);
        }

        Map<String, FilterTarget> filterTargets = inspectFilterTargets(filterClass);
        Map<String, SiblingOptionSource> siblingOptionsByRelation =
                buildSiblingOptionSourcesMap(filterTargets, filterProps);
        if (responseXUiByPath.isEmpty() && siblingOptionsByRelation.isEmpty()) {
            return;
        }

        for (Map.Entry<?, ?> entry : filterProps.entrySet()) {
            if (!(entry.getKey() instanceof String propName) || !(entry.getValue() instanceof Map<?, ?> propMapRaw)) {
                continue;
            }
            Map<String, Object> propMap = (Map<String, Object>) propMapRaw;
            FilterTarget target = filterTargets.get(propName);
            Map<String, Object> responseSourceXUi = resolveSourceXUi(propName, target, responseXUiByPath);
            Map<String, Object> siblingSourceXUi = resolveSiblingSourceXUi(propName, target, siblingOptionsByRelation);
            if ((responseSourceXUi == null || responseSourceXUi.isEmpty())
                    && (siblingSourceXUi == null || siblingSourceXUi.isEmpty())) {
                continue;
            }
            Map<String, Object> targetXUi = propMap.get(X_UI) instanceof Map<?, ?> existing
                    ? (Map<String, Object>) existing
                    : null;
            if (targetXUi == null) {
                targetXUi = new LinkedHashMap<>();
                propMap.put(X_UI, targetXUi);
            }
            if (responseSourceXUi != null && !responseSourceXUi.isEmpty()) {
                copyOmittedMetadata(target, responseSourceXUi, targetXUi);
            }
            if (siblingSourceXUi != null && !siblingSourceXUi.isEmpty()) {
                copyOmittedMetadata(target, siblingSourceXUi, targetXUi);
            }
        }
    }

    private static Map<String, Object> resolveSourceXUi(
            String filterPropertyName,
            FilterTarget target,
            Map<String, Map<String, Object>> responseXUiByPath
    ) {
        if (target != null && StringUtils.hasText(target.relation())) {
            Map<String, Object> byRelation = responseXUiByPath.get(target.relation());
            if (byRelation != null) {
                return byRelation;
            }
        }
        return responseXUiByPath.get(filterPropertyName);
    }

    private static Map<String, Object> resolveSiblingSourceXUi(
            String propName,
            FilterTarget target,
            Map<String, SiblingOptionSource> siblingOptionsByRelation
    ) {
        if (target == null || siblingOptionsByRelation.isEmpty()) {
            return null;
        }
        String key = StringUtils.hasText(target.relation()) ? target.relation() : propName;
        SiblingOptionSource sibling = siblingOptionsByRelation.get(key);
        if (sibling == null || propName.equals(sibling.propName())) {
            return null;
        }
        return sibling.xUi();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, SiblingOptionSource> buildSiblingOptionSourcesOpenApi(
            Map<String, FilterTarget> filterTargets,
            Map<String, Schema> filterProps
    ) {
        Map<String, SiblingOptionSource> result = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> entry : filterProps.entrySet()) {
            String propName = entry.getKey();
            Schema<?> propSchema = entry.getValue();
            if (propSchema == null || propSchema.getExtensions() == null) {
                continue;
            }
            if (propSchema.getExtensions().get(X_UI) instanceof Map<?, ?> xUiRaw) {
                registerSiblingOptionSource(result, propName, filterTargets.get(propName), (Map<String, Object>) xUiRaw);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, SiblingOptionSource> buildSiblingOptionSourcesMap(
            Map<String, FilterTarget> filterTargets,
            Map<?, ?> filterProps
    ) {
        Map<String, SiblingOptionSource> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : filterProps.entrySet()) {
            if (!(entry.getKey() instanceof String propName) || !(entry.getValue() instanceof Map<?, ?> propMapRaw)) {
                continue;
            }
            if (propMapRaw.get(X_UI) instanceof Map<?, ?> xUiRaw) {
                registerSiblingOptionSource(result, propName, filterTargets.get(propName), (Map<String, Object>) xUiRaw);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void registerSiblingOptionSource(
            Map<String, SiblingOptionSource> result,
            String propName,
            FilterTarget target,
            Map<String, Object> propXUi
    ) {
        if (propXUi == null) {
            return;
        }
        boolean hasOptionsOrEndpoint = hasValue(propXUi.get("options"))
                || hasValue(propXUi.get(FieldConfigProperties.ENDPOINT.getValue()));
        if (!hasOptionsOrEndpoint) {
            return;
        }
        if (target != null && (!isOptionCompatibleOperation(target.operation()) || target.booleanTarget())) {
            return;
        }
        String key = (target != null && StringUtils.hasText(target.relation())) ? target.relation() : propName;
        result.putIfAbsent(key, new SiblingOptionSource(propName, (Map<String, Object>) copyValue(propXUi)));
    }

    private static void copyOmittedMetadata(
            FilterTarget target,
            Map<String, Object> sourceXUi,
            Map<String, Object> targetXUi
    ) {
        if (!isOptionCompatibleTarget(target, targetXUi, sourceXUi)) {
            String iconKey = FieldConfigProperties.ICON.getValue();
            if (!hasValue(targetXUi.get(iconKey)) && hasValue(sourceXUi.get(iconKey))) {
                targetXUi.put(iconKey, copyValue(sourceXUi.get(iconKey)));
            }
            return;
        }
        for (String key : INHERITABLE_KEYS) {
            if (!hasValue(targetXUi.get(key)) && hasValue(sourceXUi.get(key))) {
                targetXUi.put(key, copyValue(sourceXUi.get(key)));
            }
        }
        reconcileInheritedControlType(target, sourceXUi, targetXUi);
    }

    private static boolean isOptionCompatibleOperation(Filterable.FilterOperation operation) {
        return operation == null || !NON_OPTION_OPERATIONS.contains(operation);
    }

    private static boolean isOptionCompatibleTarget(
            FilterTarget target,
            Map<String, Object> targetXUi,
            Map<String, Object> sourceXUi
    ) {
        if (target != null) {
            if (!isOptionCompatibleOperation(target.operation())) {
                return false;
            }
            boolean isTargetBoolean = target.booleanTarget()
                    || "boolean".equals(targetXUi.get(FieldConfigProperties.TYPE.getValue()));
            if (isTargetBoolean && !"boolean".equals(sourceXUi.get(FieldConfigProperties.TYPE.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private static void reconcileInheritedControlType(
            FilterTarget target,
            Map<String, Object> sourceXUi,
            Map<String, Object> targetXUi
    ) {
        if (target != null && target.explicitControlType()) {
            return;
        }
        boolean hasEndpoint = hasValue(targetXUi.get(FieldConfigProperties.ENDPOINT.getValue()));
        boolean hasOptions = hasValue(targetXUi.get("options"));
        if (!hasEndpoint && !hasOptions) {
            return;
        }
        Object currentControlType = targetXUi.get(FieldConfigProperties.CONTROL_TYPE.getValue());
        boolean isMultiValued = Boolean.TRUE.equals(targetXUi.get(FieldConfigProperties.MULTIPLE.getValue()))
                || "array".equals(targetXUi.get(FieldConfigProperties.TYPE.getValue()));
        if (isMultiValued) {
            if (currentControlType == null
                    || FieldControlType.CHIP_INPUT.getValue().equals(currentControlType)
                    || FieldControlType.INPUT.getValue().equals(currentControlType)
                    || FieldControlType.ARRAY.getValue().equals(currentControlType)) {
                targetXUi.put(FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.MULTI_SELECT.getValue());
            }
            return;
        }
        if (currentControlType == null || FieldControlType.INPUT.getValue().equals(currentControlType)) {
            if (hasEndpoint) {
                Object sourceControlType = sourceXUi.get(FieldConfigProperties.CONTROL_TYPE.getValue());
                if (FieldControlType.ENTITY_LOOKUP.getValue().equals(sourceControlType)
                        || FieldControlType.SELECT.getValue().equals(sourceControlType)) {
                    targetXUi.put(FieldConfigProperties.CONTROL_TYPE.getValue(), sourceControlType);
                    return;
                }
            }
            if (hasOptions) {
                targetXUi.put(FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.SELECT.getValue());
            }
        }
    }

    private static Object copyValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(FilterFieldMetadataInheritanceSupport::copyValue).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    copy.put(String.valueOf(k), copyValue(v));
                }
            });
            return copy;
        }
        return value;
    }

    private static boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String str) {
            return StringUtils.hasText(str);
        }
        if (value instanceof Collection<?> col) {
            return !col.isEmpty();
        }
        return true;
    }

    private static Map<String, FilterTarget> inspectFilterTargets(Class<?> rawFilterClass) {
        Class<?> filterClass = ClassUtils.getUserClass(rawFilterClass);
        Map<String, FilterTarget> result = new LinkedHashMap<>();
        for (Class<?> curr = filterClass; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
            for (Field field : curr.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                registerTarget(result, field.getName(), field);
            }
            for (Method method : curr.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge()
                        || method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                    continue;
                }
                String propName = resolvePropertyName(curr, method);
                if (propName != null) {
                    registerTarget(result, propName, method);
                }
            }
            if (curr.isRecord()) {
                for (RecordComponent rc : curr.getRecordComponents()) {
                    registerTarget(result, rc.getName(), rc);
                }
            }
        }
        return result;
    }

    private static void registerTarget(Map<String, FilterTarget> result, String propName, AnnotatedElement element) {
        Filterable filterable = element.getAnnotation(Filterable.class);
        UISchema uiSchema = element.getAnnotation(UISchema.class);
        String relation = filterable != null ? filterable.relation().trim() : "";
        Filterable.FilterOperation operation = filterable != null ? filterable.operation() : null;
        boolean explicitControlType = uiSchema != null && uiSchema.controlType() != FieldControlType.AUTO;
        boolean booleanTarget = isBooleanElement(element);
        FilterTarget incoming = new FilterTarget(relation, operation, explicitControlType, booleanTarget);
        result.merge(propName, incoming, (existing, next) -> new FilterTarget(
                StringUtils.hasText(existing.relation()) ? existing.relation() : next.relation(),
                existing.operation() != null ? existing.operation() : next.operation(),
                existing.explicitControlType() || next.explicitControlType(),
                existing.booleanTarget() || next.booleanTarget()
        ));
    }

    private static boolean isBooleanElement(AnnotatedElement element) {
        Class<?> type = null;
        if (element instanceof Field field) {
            type = field.getType();
        } else if (element instanceof Method method) {
            type = method.getReturnType();
        } else if (element instanceof RecordComponent rc) {
            type = rc.getType();
        }
        return type == Boolean.class || type == boolean.class;
    }

    private static String resolvePropertyName(Class<?> owner, Method method) {
        String name = method.getName();
        if (owner.isRecord()) {
            return name;
        }
        if (name.startsWith("get") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private static Map<String, Map<String, Object>> indexOpenApiSchemaXUi(
            Schema<?> root,
            Map<String, Schema> componentSchemas
    ) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        collectOpenApiSchemaXUi("", root, componentSchemas, index, 0);
        return index;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void collectOpenApiSchemaXUi(
            String prefix,
            Schema<?> schema,
            Map<String, Schema> componentSchemas,
            Map<String, Map<String, Object>> out,
            int depth
    ) {
        if (schema == null || depth > 3) {
            return;
        }
        Schema<?> resolved = resolveSchemaRef(schema, componentSchemas, 0);
        if (resolved == null) {
            return;
        }
        if (resolved.getAllOf() != null) {
            for (Schema<?> sub : resolved.getAllOf()) {
                collectOpenApiSchemaXUi(prefix, sub, componentSchemas, out, depth);
            }
        }
        Map<String, Schema> props = resolved.getProperties();
        if (props == null || props.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Schema> entry : props.entrySet()) {
            String key = entry.getKey();
            Schema<?> propSchema = entry.getValue();
            if (propSchema == null) {
                continue;
            }
            String fullPath = prefix.isEmpty() ? key : prefix + "." + key;
            if (propSchema.getExtensions() != null && propSchema.getExtensions().get(X_UI) instanceof Map<?, ?> xui) {
                out.putIfAbsent(fullPath, (Map<String, Object>) xui);
            }
            collectOpenApiSchemaXUi(fullPath, propSchema, componentSchemas, out, depth + 1);
        }
    }

    @SuppressWarnings("rawtypes")
    private static Schema<?> resolveSchemaRef(Schema<?> schema, Map<String, Schema> componentSchemas, int depth) {
        if (schema == null || depth > 8) {
            return schema;
        }
        String ref = schema.get$ref();
        if (ref != null && componentSchemas != null) {
            String schemaName = extractSchemaName(ref);
            if (schemaName != null && componentSchemas.containsKey(schemaName)) {
                return resolveSchemaRef(componentSchemas.get(schemaName), componentSchemas, depth + 1);
            }
        }
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static void collectMapSchemaXUi(
            String prefix,
            Map<String, Object> schemaMap,
            Map<String, Object> allComponentSchemas,
            Map<String, Map<String, Object>> out,
            int depth
    ) {
        if (schemaMap == null || depth > 3) {
            return;
        }
        Map<String, Object> resolved = resolveMapRef(schemaMap, allComponentSchemas, 0);
        if (resolved == null) {
            return;
        }
        if (resolved.get("allOf") instanceof List<?> allOf) {
            for (Object sub : allOf) {
                if (sub instanceof Map<?, ?> subMap) {
                    collectMapSchemaXUi(prefix, (Map<String, Object>) subMap, allComponentSchemas, out, depth);
                }
            }
        }
        Object rawProps = resolved.get("properties");
        if (!(rawProps instanceof Map<?, ?> props) || props.isEmpty()) {
            return;
        }
        for (Map.Entry<?, ?> entry : props.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Map<?, ?> propRaw)) {
                continue;
            }
            Map<String, Object> propMap = (Map<String, Object>) propRaw;
            String fullPath = prefix.isEmpty() ? key : prefix + "." + key;
            if (propMap.get(X_UI) instanceof Map<?, ?> xui) {
                out.putIfAbsent(fullPath, (Map<String, Object>) xui);
            }
            collectMapSchemaXUi(fullPath, propMap, allComponentSchemas, out, depth + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveMapRef(
            Map<String, Object> schemaMap,
            Map<String, Object> allComponentSchemas,
            int depth
    ) {
        if (schemaMap == null || depth > 8) {
            return schemaMap;
        }
        Object refObj = schemaMap.get("$ref");
        if (refObj instanceof String ref && allComponentSchemas != null) {
            String schemaName = extractSchemaName(ref);
            if (schemaName != null && allComponentSchemas.get(schemaName) instanceof Map<?, ?> target) {
                return resolveMapRef((Map<String, Object>) target, allComponentSchemas, depth + 1);
            }
        }
        return schemaMap;
    }

    private static String extractSchemaName(String ref) {
        if (ref == null) {
            return null;
        }
        String prefix = "#/components/schemas/";
        return ref.startsWith(prefix) ? ref.substring(prefix.length()) : null;
    }

    private record FilterTarget(
            String relation,
            Filterable.FilterOperation operation,
            boolean explicitControlType,
            boolean booleanTarget
    ) {
    }

    private record SiblingOptionSource(String propName, Map<String, Object> xUi) {
    }
}
