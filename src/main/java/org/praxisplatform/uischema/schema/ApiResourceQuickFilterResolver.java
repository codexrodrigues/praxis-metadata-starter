package org.praxisplatform.uischema.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.FilterCriterion;
import org.praxisplatform.uischema.annotation.QuickFilter;
import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Resolve, valida e normaliza os filtros rapidos canonicos declarados via
 * {@link ApiResource#quickFilters()} e {@link AbstractResourceQueryController#defineQuickFilters()},
 * aplicando coercao deterministica guiada pelo {@link GenericFilterDTO} do recurso.
 */
@Component
public class ApiResourceQuickFilterResolver {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RequestMappingHandlerMapping handlerMapping;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    public ApiResourceQuickFilterResolver(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            ObjectMapper objectMapper
    ) {
        this(handlerMapping, objectMapper, null);
    }

    @Autowired
    public ApiResourceQuickFilterResolver(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) ApplicationContext applicationContext
    ) {
        this.handlerMapping = handlerMapping;
        this.objectMapper = objectMapper != null ? objectMapper : DEFAULT_MAPPER;
        this.applicationContext = applicationContext;
    }

    /**
     * Resolve a lista de filtros rapidos publicados para um dado path operacional de recurso.
     *
     * @param resourcePath path operacional do recurso (ex: "/api/heroes")
     * @return lista imutavel de mapas representando os filtros rapidos ou lista vazia
     */
    public List<Map<String, Object>> resolve(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank() || handlerMapping == null) {
            return Collections.emptyList();
        }
        String expectedPath = normalizePath(resourcePath);
        return findControllerCandidate(candidate -> matchesPath(candidate.apiResource(), expectedPath))
                .map(candidate -> resolveForCandidate(candidate, null))
                .orElse(Collections.emptyList());
    }

    /**
     * Resolve a lista de filtros rapidos publicados para uma chave semantica de recurso ({@code resourceKey}).
     *
     * @param resourceKey chave canonica do recurso (ex: "ergon.aposentadorias")
     * @return lista imutavel de mapas representando os filtros rapidos ou lista vazia
     */
    public List<Map<String, Object>> resolveByResourceKey(String resourceKey) {
        if (!StringUtils.hasText(resourceKey) || handlerMapping == null) {
            return Collections.emptyList();
        }
        String expectedKey = resourceKey.trim();
        return findControllerCandidate(candidate -> expectedKey.equals(resolveResourceKey(candidate.apiResource())))
                .map(candidate -> resolveForCandidate(candidate, null))
                .orElse(Collections.emptyList());
    }

    /**
     * Resolve o path primario declarado em {@link ApiResource} para um {@code resourceKey}, quando disponivel.
     */
    public Optional<String> resolveResourcePathByResourceKey(String resourceKey) {
        if (!StringUtils.hasText(resourceKey) || handlerMapping == null) {
            return Optional.empty();
        }
        String expectedKey = resourceKey.trim();
        return findControllerCandidate(candidate -> expectedKey.equals(resolveResourceKey(candidate.apiResource())))
                .map(candidate -> primaryResourcePath(candidate.apiResource()))
                .filter(StringUtils::hasText);
    }

    /**
     * Compila diretamente os filtros rapidos de uma classe de controller (uso em testes de contrato e revisao estatica).
     */
    public static List<Map<String, Object>> resolveControllerQuickFilters(
            Class<?> controllerClass,
            Object controllerInstance,
            Class<?> explicitFilterDtoClass
    ) {
        if (controllerClass == null) {
            return Collections.emptyList();
        }
        ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(controllerClass, ApiResource.class);
        if (apiResource == null) {
            return Collections.emptyList();
        }
        ControllerCandidate candidate = new ControllerCandidate(controllerClass, controllerInstance, apiResource);
        return compileQuickFilters(candidate, explicitFilterDtoClass, DEFAULT_MAPPER);
    }

    private Optional<ControllerCandidate> findControllerCandidate(
            java.util.function.Predicate<ControllerCandidate> predicate
    ) {
        Map<Class<?>, Object> seenTypes = new LinkedHashMap<>();
        for (HandlerMethod handlerMethod : handlerMapping.getHandlerMethods().values()) {
            Class<?> beanType = ClassUtils.getUserClass(handlerMethod.getBeanType());
            if (!seenTypes.containsKey(beanType)) {
                seenTypes.put(beanType, resolveBeanInstance(handlerMethod));
            }
        }
        for (Map.Entry<Class<?>, Object> entry : seenTypes.entrySet()) {
            ApiResource annotation = AnnotatedElementUtils.findMergedAnnotation(entry.getKey(), ApiResource.class);
            if (annotation == null) {
                continue;
            }
            ControllerCandidate candidate = new ControllerCandidate(entry.getKey(), entry.getValue(), annotation);
            if (predicate.test(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Object resolveBeanInstance(HandlerMethod handlerMethod) {
        Object bean = handlerMethod.getBean();
        if (bean instanceof String beanName) {
            if (applicationContext != null && applicationContext.containsBean(beanName)) {
                try {
                    return applicationContext.getBean(beanName);
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }
        return bean;
    }

    private List<Map<String, Object>> resolveForCandidate(
            ControllerCandidate candidate,
            Class<?> explicitFilterDtoClass
    ) {
        return compileQuickFilters(candidate, explicitFilterDtoClass, this.objectMapper);
    }

    private static List<Map<String, Object>> compileQuickFilters(
            ControllerCandidate candidate,
            Class<?> explicitFilterDtoClass,
            ObjectMapper mapper
    ) {
        Class<?> filterDtoClass = explicitFilterDtoClass != null
                ? explicitFilterDtoClass
                : resolveControllerFilterDtoType(candidate.controllerType());
        Map<String, Field> filterFields = filterDtoClass != null
                ? collectAllInstanceFields(filterDtoClass)
                : Collections.emptyMap();

        List<OrderedEntry> entries = new ArrayList<>();
        int seq = 0;

        QuickFilter[] declarative = candidate.apiResource().quickFilters();
        if (declarative != null) {
            for (QuickFilter qf : declarative) {
                if (qf == null || qf.id() == null || qf.id().isBlank()) {
                    continue;
                }
                Map<String, Object> serialized = convertDeclarativeQuickFilter(qf, filterFields, mapper);
                entries.add(new OrderedEntry(qf.order(), seq++, serialized));
            }
        }

        List<QuickFilterDescriptor<?>> programmatic = extractProgrammaticDescriptors(
                candidate.controllerType(),
                candidate.controllerInstance()
        );
        for (QuickFilterDescriptor<?> descriptor : programmatic) {
            if (descriptor == null || descriptor.id().isBlank()) {
                continue;
            }
            Map<String, Object> serialized = convertProgrammaticQuickFilter(
                    descriptor,
                    filterDtoClass,
                    filterFields,
                    mapper
            );
            entries.add(new OrderedEntry(descriptor.order(), seq++, serialized));
        }

        if (entries.isEmpty()) {
            return Collections.emptyList();
        }

        entries.sort(Comparator.comparingInt(OrderedEntry::order).thenComparingInt(OrderedEntry::declarationIndex));
        List<Map<String, Object>> result = new ArrayList<>(entries.size());
        for (OrderedEntry entry : entries) {
            result.add(Collections.unmodifiableMap(entry.payload()));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> convertDeclarativeQuickFilter(
            QuickFilter qf,
            Map<String, Field> filterFields,
            ObjectMapper mapper
    ) {
        String id = qf.id().trim();
        String label = qf.label() != null && !qf.label().isBlank() ? qf.label().trim() : id;
        Object compiledFilter = qf.criteria() != null && qf.criteria().length > 0
                ? compileCriteria(qf.criteria(), filterFields)
                : parseFilterExpressionInternal(qf.filter(), filterFields, mapper);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        map.put("filter", compiledFilter);
        if (qf.icon() != null && !qf.icon().isBlank()) {
            map.put("icon", qf.icon().trim());
        }
        if (qf.tone() != null && qf.tone() != QuickFilterTone.UNSPECIFIED) {
            map.put("tone", qf.tone().wireValue());
        }
        if (qf.defaultActive()) {
            map.put("isDefault", Boolean.TRUE);
        }
        if (qf.description() != null && !qf.description().isBlank()) {
            map.put("description", qf.description().trim());
        }
        if (qf.order() != 0) {
            map.put("order", qf.order());
        }
        return map;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Object> convertProgrammaticQuickFilter(
            QuickFilterDescriptor<?> descriptor,
            Class<?> filterDtoClass,
            Map<String, Field> filterFields,
            ObjectMapper mapper
    ) {
        Map<String, Object> filterMap = new LinkedHashMap<>();
        Consumer configurer = descriptor.filterConfigurer();
        if (configurer != null && filterDtoClass != null && GenericFilterDTO.class.isAssignableFrom(filterDtoClass)) {
            Object baseline = BeanUtils.instantiateClass(filterDtoClass);
            Object configured = BeanUtils.instantiateClass(filterDtoClass);
            configurer.accept(configured);
            Map<String, Object> baselineTree = mapper.convertValue(baseline, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> configuredTree = mapper.convertValue(configured, new TypeReference<Map<String, Object>>() {});
            for (Map.Entry<String, Object> entry : configuredTree.entrySet()) {
                Object val = entry.getValue();
                Object baseVal = baselineTree != null ? baselineTree.get(entry.getKey()) : null;
                if (val != null && !Objects.equals(val, baseVal)) {
                    filterMap.put(entry.getKey(), val);
                }
            }
        }
        for (Map.Entry<String, Object> explicit : descriptor.explicitCriteria().entrySet()) {
            Field targetField = filterFields.get(explicit.getKey());
            filterMap.put(explicit.getKey(), coerceAgainstField(explicit.getValue(), targetField));
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", descriptor.id());
        map.put("label", descriptor.label().isBlank() ? descriptor.id() : descriptor.label());
        map.put("filter", Collections.unmodifiableMap(filterMap));
        if (!descriptor.icon().isBlank()) {
            map.put("icon", descriptor.icon());
        }
        if (descriptor.tone() != QuickFilterTone.UNSPECIFIED) {
            map.put("tone", descriptor.tone().wireValue());
        }
        if (descriptor.defaultActive()) {
            map.put("isDefault", Boolean.TRUE);
        }
        if (!descriptor.description().isBlank()) {
            map.put("description", descriptor.description());
        }
        if (descriptor.order() != 0) {
            map.put("order", descriptor.order());
        }
        return map;
    }

    /**
     * Compila um array de {@link FilterCriterion} num mapa JSON-compativel guiado pelos campos do {@code FilterDTO}.
     */
    public static Map<String, Object> compileCriteria(
            FilterCriterion[] criteria,
            Map<String, Field> filterFields
    ) {
        if (criteria == null || criteria.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (FilterCriterion criterion : criteria) {
            if (criterion == null || criterion.field() == null || criterion.field().isBlank()) {
                continue;
            }
            String fieldName = criterion.field().trim();
            Field targetField = filterFields != null ? filterFields.get(fieldName) : null;
            List<Object> rawValues = extractRawCriterionValues(criterion);
            if (rawValues.isEmpty()) {
                continue;
            }
            result.put(fieldName, coerceCriterionValues(rawValues, targetField));
        }
        return Collections.unmodifiableMap(result);
    }

    public static List<Object> extractRawCriterionValues(FilterCriterion criterion) {
        List<Object> rawValues = new ArrayList<>();
        if (criterion.bool() != null && criterion.bool().length > 0) {
            for (boolean b : criterion.bool()) {
                rawValues.add(b);
            }
        } else if (criterion.number() != null && criterion.number().length > 0) {
            for (long n : criterion.number()) {
                rawValues.add(n);
            }
        } else if (criterion.decimal() != null && criterion.decimal().length > 0) {
            for (double d : criterion.decimal()) {
                rawValues.add(d);
            }
        } else if (criterion.values() != null && criterion.values().length > 0) {
            for (String s : criterion.values()) {
                if (s != null) {
                    rawValues.add(s.trim());
                }
            }
        }
        return rawValues;
    }

    private static Object coerceCriterionValues(List<Object> rawValues, Field targetField) {
        if (targetField == null) {
            if (rawValues.size() == 1) {
                Object single = rawValues.get(0);
                if (single instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return l.intValue();
                }
                return single;
            }
            List<Object> narrowed = new ArrayList<>(rawValues.size());
            for (Object item : rawValues) {
                if (item instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    narrowed.add(l.intValue());
                } else {
                    narrowed.add(item);
                }
            }
            return Collections.unmodifiableList(narrowed);
        }

        boolean isCollection = isCollectionOrArrayField(targetField);
        Class<?> scalarType = resolveFieldScalarType(targetField);
        List<Object> coerced = new ArrayList<>(rawValues.size());
        for (Object rawItem : rawValues) {
            coerced.add(coerceScalarToTargetType(rawItem, scalarType));
        }
        if (isCollection) {
            return Collections.unmodifiableList(coerced);
        }
        return coerced.size() == 1 ? coerced.get(0) : Collections.unmodifiableList(coerced);
    }

    private static Object coerceAgainstField(Object value, Field targetField) {
        if (value == null || targetField == null) {
            return value;
        }
        boolean isCollection = isCollectionOrArrayField(targetField);
        Class<?> scalarType = resolveFieldScalarType(targetField);
        if (value instanceof Collection<?> col) {
            List<Object> items = new ArrayList<>(col.size());
            for (Object elem : col) {
                items.add(coerceScalarToTargetType(elem, scalarType));
            }
            return isCollection
                    ? Collections.unmodifiableList(items)
                    : (items.size() == 1 ? items.get(0) : Collections.unmodifiableList(items));
        }
        Object single = coerceScalarToTargetType(value, scalarType);
        return isCollection ? List.of(single) : single;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerceScalarToTargetType(Object rawItem, Class<?> scalarType) {
        if (rawItem == null || scalarType == null || scalarType == Object.class) {
            return rawItem;
        }
        String token = String.valueOf(rawItem).trim();
        if (scalarType == String.class) {
            return token;
        }
        if (scalarType.isEnum()) {
            Class<? extends Enum> enumType = (Class<? extends Enum>) scalarType;
            try {
                return Enum.valueOf(enumType, token).name();
            } catch (IllegalArgumentException ignored) {
                return token;
            }
        }
        if (scalarType == Boolean.class || scalarType == boolean.class) {
            if (rawItem instanceof Boolean b) {
                return b;
            }
            if ("true".equalsIgnoreCase(token)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(token)) {
                return Boolean.FALSE;
            }
            return rawItem;
        }
        if (scalarType == Integer.class || scalarType == int.class) {
            if (rawItem instanceof Number n && isIntegralNumber(n)) {
                long v = n.longValue();
                return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? (Object) (int) v : rawItem;
            }
            try {
                return Integer.valueOf(token);
            } catch (NumberFormatException ignored) {
                return rawItem;
            }
        }
        if (scalarType == Long.class || scalarType == long.class) {
            if (rawItem instanceof Number n && isIntegralNumber(n)) {
                return n.longValue();
            }
            try {
                return Long.valueOf(token);
            } catch (NumberFormatException ignored) {
                return rawItem;
            }
        }
        if (scalarType == Short.class || scalarType == short.class) {
            if (rawItem instanceof Number n && isIntegralNumber(n)) {
                long v = n.longValue();
                return v >= Short.MIN_VALUE && v <= Short.MAX_VALUE ? (Object) (short) v : rawItem;
            }
            try {
                return Short.valueOf(token);
            } catch (NumberFormatException ignored) {
                return rawItem;
            }
        }
        if (scalarType == Byte.class || scalarType == byte.class) {
            if (rawItem instanceof Number n && isIntegralNumber(n)) {
                long v = n.longValue();
                return v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE ? (Object) (byte) v : rawItem;
            }
            try {
                return Byte.valueOf(token);
            } catch (NumberFormatException ignored) {
                return rawItem;
            }
        }
        if (scalarType == BigInteger.class) {
            try {
                return new BigInteger(token);
            } catch (NumberFormatException ignored) {
                return rawItem;
            }
        }
        if (scalarType == BigDecimal.class) {
            if (rawItem instanceof BigDecimal bd) {
                return bd;
            }
            if (rawItem instanceof Double d) {
                return BigDecimal.valueOf(d);
            }
            if (rawItem instanceof Long l) {
                return BigDecimal.valueOf(l);
            }
            try {
                return new BigDecimal(token);
            } catch (NumberFormatException ignored) {
                return rawItem;
            }
        }
        if (scalarType == Double.class || scalarType == double.class) {
            if (rawItem instanceof Number n) {
                return n.doubleValue();
            }
            try {
                return Double.valueOf(token);
            } catch (NumberFormatException ignored) {
                return rawItem;
            }
        }
        if (scalarType == Float.class || scalarType == float.class) {
            if (rawItem instanceof Number n) {
                return n.floatValue();
            }
            try {
                return Float.valueOf(token);
            } catch (NumberFormatException ignored) {
                return rawItem;
            }
        }
        if (scalarType == LocalDate.class) {
            try {
                return LocalDate.parse(token).toString();
            } catch (Exception ignored) {
                return token;
            }
        }
        if (scalarType == LocalDateTime.class) {
            try {
                return LocalDateTime.parse(token).toString();
            } catch (Exception ignored) {
                return token;
            }
        }
        if (scalarType == Instant.class) {
            try {
                return Instant.parse(token).toString();
            } catch (Exception ignored) {
                return token;
            }
        }
        if (scalarType == OffsetDateTime.class) {
            try {
                return OffsetDateTime.parse(token).toString();
            } catch (Exception ignored) {
                return token;
            }
        }
        return rawItem;
    }

    private static boolean isIntegralNumber(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte || n instanceof BigInteger) {
            return true;
        }
        double d = n.doubleValue();
        return Double.isFinite(d) && d == Math.rint(d);
    }

    /**
     * Normaliza a expressao legada de filtro para um objeto estruturado {@link Map} ou fallback em {@link String}.
     * Suporta formato JSON ({@code "{\"ativo\":true}"}) e query string ({@code "ativo=true&departamentoId=10"}).
     */
    public Object parseFilterExpression(String raw) {
        return parseFilterExpressionInternal(raw, Collections.emptyMap(), this.objectMapper);
    }

    private static Object parseFilterExpressionInternal(
            String raw,
            Map<String, Field> filterFields,
            ObjectMapper mapper
    ) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> parsed = mapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
                if (filterFields == null || filterFields.isEmpty()) {
                    return parsed;
                }
                Map<String, Object> coerced = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                    Field field = filterFields.get(entry.getKey());
                    coerced.put(entry.getKey(), field != null ? coerceAgainstField(entry.getValue(), field) : entry.getValue());
                }
                return Collections.unmodifiableMap(coerced);
            } catch (Exception ignored) {
                // fallback to key-value or raw
            }
        }
        if (trimmed.contains("=")) {
            Map<String, Object> map = new LinkedHashMap<>();
            String[] pairs = trimmed.split("&");
            for (String pair : pairs) {
                int eqIdx = pair.indexOf('=');
                if (eqIdx > 0) {
                    String key = pair.substring(0, eqIdx).trim();
                    String val = pair.substring(eqIdx + 1).trim();
                    Field targetField = filterFields != null ? filterFields.get(key) : null;
                    Object parsedVal = parseQueryValueGuided(val, targetField);
                    map.merge(key, parsedVal, (existing, incoming) -> mergeQueryValues(existing, incoming));
                }
            }
            if (!map.isEmpty()) {
                return Collections.unmodifiableMap(map);
            }
        }
        return trimmed;
    }

    private static Object parseQueryValueGuided(String val, Field targetField) {
        if (targetField == null) {
            return parseUntypedQueryValue(val);
        }
        boolean isCollection = isCollectionOrArrayField(targetField);
        Class<?> scalarType = resolveFieldScalarType(targetField);
        if (isCollection) {
            List<Object> items = new ArrayList<>();
            for (String token : val.split(",", -1)) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    items.add(coerceScalarToTargetType(trimmed, scalarType));
                }
            }
            return Collections.unmodifiableList(items);
        }
        return coerceScalarToTargetType(val, scalarType);
    }

    private static Object parseUntypedQueryValue(String val) {
        if (val.contains(",")) {
            List<Object> items = new ArrayList<>();
            for (String token : val.split(",", -1)) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    items.add(parseUntypedScalarValue(trimmed));
                }
            }
            return Collections.unmodifiableList(items);
        }
        return parseUntypedScalarValue(val);
    }

    private static Object mergeQueryValues(Object existing, Object incoming) {
        List<Object> combined = new ArrayList<>();
        if (existing instanceof Collection<?> col) {
            combined.addAll(col);
        } else {
            combined.add(existing);
        }
        if (incoming instanceof Collection<?> col) {
            combined.addAll(col);
        } else {
            combined.add(incoming);
        }
        return Collections.unmodifiableList(combined);
    }

    private static Object parseUntypedScalarValue(String val) {
        if ("true".equalsIgnoreCase(val)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(val)) {
            return Boolean.FALSE;
        }
        if (val.matches("^-?\\d+$")) {
            try {
                long num = Long.parseLong(val);
                if (num >= Integer.MIN_VALUE && num <= Integer.MAX_VALUE) {
                    return (int) num;
                }
                return num;
            } catch (NumberFormatException ignored) {
                // keep string
            }
        }
        if (val.matches("^-?\\d+\\.\\d+$")) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException ignored) {
                // keep string
            }
        }
        return val;
    }

    public static Class<?> resolveControllerFilterDtoType(Class<?> controllerClass) {
        if (controllerClass == null) {
            return null;
        }
        Class<?> resolved = ResolvableType.forClass(controllerClass)
                .as(AbstractResourceQueryController.class)
                .getGeneric(2)
                .resolve();
        if (resolved != null && resolved != Object.class && GenericFilterDTO.class.isAssignableFrom(resolved)) {
            return resolved;
        }
        java.lang.reflect.Type current = controllerClass.getGenericSuperclass();
        while (current != null && current != Object.class) {
            if (current instanceof java.lang.reflect.ParameterizedType pt) {
                for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                    if (arg instanceof Class<?> cls
                            && (GenericFilterDTO.class.isAssignableFrom(cls)
                                || cls.getSimpleName().endsWith("FilterDTO")
                                || cls.getSimpleName().endsWith("Filter"))) {
                        return cls;
                    }
                }
                if (pt.getRawType() instanceof Class<?> rawCls) {
                    current = rawCls.getGenericSuperclass();
                } else {
                    break;
                }
            } else if (current instanceof Class<?> cls) {
                current = cls.getGenericSuperclass();
            } else {
                break;
            }
        }
        return null;
    }

    public static boolean overridesDefineQuickFilters(Class<?> controllerClass) {
        Class<?> current = controllerClass;
        while (current != null && current != Object.class && current != AbstractResourceQueryController.class) {
            try {
                current.getDeclaredMethod("defineQuickFilters");
                return true;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return false;
    }

    public static List<QuickFilterDescriptor<?>> extractProgrammaticDescriptors(
            Class<?> controllerType,
            Object controllerInstance
    ) {
        Object target = unwrapAopTarget(controllerInstance);
        if (target == null && controllerType != null && overridesDefineQuickFilters(controllerType)) {
            target = instantiateForStaticReview(controllerType);
        }
        if (target instanceof AbstractResourceQueryController<?, ?, ?> queryController) {
            try {
                List<? extends QuickFilterDescriptor<?>> list = queryController.resolvedQuickFilterDescriptors();
                return list == null ? Collections.emptyList() : new ArrayList<>(list);
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                        "defineQuickFilters() falhou em " + controllerType.getName()
                                + "; filtros rapidos programaticos devem ser deterministicos e independentes de estado injetado.",
                        ex);
            }
        }
        return Collections.emptyList();
    }

    /**
     * Desembrulha proxies Spring AOP (CGLIB ou JDK) para invocar {@code defineQuickFilters()} sobre o bean real.
     */
    private static Object unwrapAopTarget(Object candidate) {
        Object current = candidate;
        for (int depth = 0; current != null && depth < 8; depth++) {
            Object next = org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(current);
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        return current;
    }

    /**
     * Instancia o controller apenas para revisao estatica. Controllers com injecao por construtor recebem
     * valores neutros (null/0/false), pois {@code defineQuickFilters()} deve depender apenas do FilterDTO.
     */
    private static Object instantiateForStaticReview(Class<?> controllerType) {
        java.lang.reflect.Constructor<?>[] ctors = controllerType.getDeclaredConstructors();
        java.util.Arrays.sort(ctors, java.util.Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount));
        for (java.lang.reflect.Constructor<?> ctor : ctors) {
            try {
                ctor.setAccessible(true);
                Class<?>[] types = ctor.getParameterTypes();
                Object[] args = new Object[types.length];
                for (int i = 0; i < types.length; i++) {
                    args[i] = neutralValue(types[i]);
                }
                return ctor.newInstance(args);
            } catch (Exception | LinkageError ignored) {
                // tenta o proximo construtor
            }
        }
        return null;
    }

    private static Object neutralValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }

    public static Map<String, Field> collectAllInstanceFields(Class<?> type) {
        Map<String, Field> fields = new LinkedHashMap<>();
        Class<?> current = type;
        while (current != null && current != Object.class && current != Record.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                fields.putIfAbsent(field.getName(), field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    public static boolean isCollectionOrArrayField(Field field) {
        return field != null && (Collection.class.isAssignableFrom(field.getType()) || field.getType().isArray());
    }

    public static Class<?> resolveFieldScalarType(Field field) {
        if (field == null) {
            return Object.class;
        }
        if (Collection.class.isAssignableFrom(field.getType())) {
            Class<?> generic = ResolvableType.forField(field).asCollection().resolveGeneric(0);
            return generic != null ? generic : Object.class;
        }
        if (field.getType().isArray()) {
            return field.getType().getComponentType();
        }
        return field.getType();
    }

    private static String resolveResourceKey(ApiResource resource) {
        if (resource == null) {
            return "";
        }
        if (StringUtils.hasText(resource.resourceKey())) {
            return resource.resourceKey().trim();
        }
        String primaryPath = primaryResourcePath(resource);
        if (!StringUtils.hasText(primaryPath)) {
            return "";
        }
        String trimmed = primaryPath.replaceAll("^/+|/+$", "");
        if (trimmed.startsWith("api/")) {
            trimmed = trimmed.substring(4);
        }
        return trimmed.replace('/', '.');
    }

    private static String primaryResourcePath(ApiResource resource) {
        if (resource.value() != null && resource.value().length > 0 && StringUtils.hasText(resource.value()[0])) {
            return normalizePath(resource.value()[0]);
        }
        if (resource.path() != null && resource.path().length > 0 && StringUtils.hasText(resource.path()[0])) {
            return normalizePath(resource.path()[0]);
        }
        return null;
    }

    private static boolean matchesPath(ApiResource resource, String expectedPath) {
        return Arrays.stream(resource.value()).anyMatch(path -> normalizePath(path).equals(expectedPath))
                || Arrays.stream(resource.path()).anyMatch(path -> normalizePath(path).equals(expectedPath));
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.trim().replaceAll("/+$", "");
        return normalized.isBlank() ? "/" : normalized;
    }

    private record ControllerCandidate(Class<?> controllerType, Object controllerInstance, ApiResource apiResource) {
    }

    private record OrderedEntry(int order, int declarationIndex, Map<String, Object> payload) {
    }
}
