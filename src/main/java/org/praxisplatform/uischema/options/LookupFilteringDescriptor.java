package org.praxisplatform.uischema.options;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.annotation.UiOption;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.option.PraxisUiOptionEnum;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rich filtering contract for entity lookups.
 */
public record LookupFilteringDescriptor(
        List<LookupFilterDefinition> availableFilters,
        Map<String, Object> defaultFilters,
        List<LookupSortOption> sortOptions,
        String defaultSort,
        List<String> quickFilterFields,
        String searchPlaceholder,
        List<LookupSearchStrategyDefinition> searchStrategies,
        @Nullable String defaultSearchStrategy
) {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Set<String> PRIVATE_OPTION_SOURCE_KEYS = Set.of(
            "attributes", "bindParameters", "context", "function", "hostContext",
            "package", "providerConfig", "rawEndpoint", "sql", "tenant", "user"
    );

    public LookupFilteringDescriptor(
            List<LookupFilterDefinition> availableFilters, Map<String, Object> defaultFilters,
            List<LookupSortOption> sortOptions, String defaultSort, List<String> quickFilterFields,
            String searchPlaceholder) {
        this(availableFilters, defaultFilters, sortOptions, defaultSort, quickFilterFields, searchPlaceholder, List.of(), null);
    }

    public LookupFilteringDescriptor(
            List<LookupFilterDefinition> availableFilters, Map<String, Object> defaultFilters,
            List<LookupSortOption> sortOptions, String defaultSort, List<String> quickFilterFields,
            String searchPlaceholder, List<LookupSearchStrategyDefinition> searchStrategies) {
        this(availableFilters, defaultFilters, sortOptions, defaultSort, quickFilterFields, searchPlaceholder, searchStrategies, null);
    }

    public LookupFilteringDescriptor {
        availableFilters = availableFilters == null ? List.of() : List.copyOf(availableFilters);
        defaultFilters = defaultFilters == null || defaultFilters.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(defaultFilters));
        sortOptions = sortOptions == null ? List.of() : List.copyOf(sortOptions);
        defaultSort = normalize(defaultSort);
        quickFilterFields = normalizeList(quickFilterFields);
        searchPlaceholder = normalize(searchPlaceholder);
        searchStrategies = searchStrategies == null ? List.of() : List.copyOf(searchStrategies);
        defaultSearchStrategy = normalize(defaultSearchStrategy);
        validateStrategies(searchStrategies);
        validateDefaultSearchStrategy(searchStrategies, defaultSearchStrategy);
        validateDefaultSort(sortOptions, defaultSort);
    }

    public static LookupFilteringDescriptor fromFilterDto(Class<?> filterDtoClass) {
        return builderFromFilterDto(filterDtoClass).build();
    }

    public static LookupFilteringDescriptor fromFilterDto(
            Class<?> filterDtoClass,
            OptionSourceRegistry registry
    ) {
        return builderFromFilterDto(filterDtoClass, registry).build();
    }

    public static LookupFilteringDescriptor fromFilterDto(
            Class<?> filterDtoClass,
            String... includedFields
    ) {
        return builderFromFilterDto(filterDtoClass).includedFields(includedFields).build();
    }

    public static LookupFilteringDescriptor fromFilterDto(
            Class<?> filterDtoClass,
            OptionSourceRegistry registry,
            String... includedFields
    ) {
        return builderFromFilterDto(filterDtoClass, registry).includedFields(includedFields).build();
    }

    public static FilterDtoBuilder builderFromFilterDto(Class<?> filterDtoClass) {
        return new FilterDtoBuilder(filterDtoClass, null);
    }

    public static FilterDtoBuilder builderFromFilterDto(Class<?> filterDtoClass, OptionSourceRegistry registry) {
        return new FilterDtoBuilder(filterDtoClass, registry);
    }

    public boolean isEmpty() {
        return availableFilters.isEmpty()
                && defaultFilters.isEmpty()
                && sortOptions.isEmpty()
                && defaultSort == null
                && quickFilterFields.isEmpty()
                && searchPlaceholder == null
                && searchStrategies.isEmpty()
                && defaultSearchStrategy == null;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!availableFilters.isEmpty()) {
            metadata.put("availableFilters", availableFilters.stream()
                    .map(LookupFilterDefinition::toMetadataMap)
                    .toList());
        }
        if (!defaultFilters.isEmpty()) {
            metadata.put("defaultFilters", defaultFilters);
        }
        if (!sortOptions.isEmpty()) {
            metadata.put("sortOptions", sortOptions.stream()
                    .map(LookupSortOption::toMetadataMap)
                    .toList());
        }
        if (defaultSort != null) {
            metadata.put("defaultSort", defaultSort);
        }
        if (!quickFilterFields.isEmpty()) {
            metadata.put("quickFilterFields", quickFilterFields);
        }
        if (searchPlaceholder != null) {
            metadata.put("searchPlaceholder", searchPlaceholder);
        }
        if (!searchStrategies.isEmpty()) {
            metadata.put("searchStrategies", searchStrategies.stream()
                    .map(LookupSearchStrategyDefinition::toMetadataMap).toList());
        }
        if (defaultSearchStrategy != null) {
            metadata.put("defaultSearchStrategy", defaultSearchStrategy);
        }
        return metadata;
    }

    /**
     * Resolves the public strategy selected for a non-empty search term.
     * A single declared strategy is selected automatically; multiple strategies
     * use {@link #defaultSearchStrategy()} when explicitly declared, otherwise require an
     * explicit key so the provider never has to infer business intent.
     */
    public LookupSearchStrategyDefinition resolveSearchStrategy(String requestedKey, String search) {
        String key = normalize(requestedKey);
        if (search == null || search.isBlank()) {
            if (key != null) {
                throw new IllegalArgumentException("Lookup searchStrategy requires a non-empty search term.");
            }
            return null;
        }
        if (searchStrategies.isEmpty()) {
            if (key != null) {
                throw new IllegalArgumentException("Lookup searchStrategy is not declared for this option source.");
            }
            return null;
        }
        if (key == null) {
            if (searchStrategies.size() == 1) {
                return searchStrategies.getFirst();
            }
            if (defaultSearchStrategy != null) {
                return searchStrategies.stream()
                        .filter(strategy -> defaultSearchStrategy.equals(strategy.key()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Lookup defaultSearchStrategy must reference a declared search strategy key."));
            }
            throw new IllegalArgumentException("Lookup searchStrategy is required when multiple search strategies are declared.");
        }
        return searchStrategies.stream()
                .filter(strategy -> key.equals(strategy.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported lookup searchStrategy: " + key));
    }

    private static void validateDefaultSort(List<LookupSortOption> sortOptions, String defaultSort) {
        if (defaultSort == null) {
            return;
        }
        boolean supported = sortOptions.stream()
                .map(LookupSortOption::key)
                .anyMatch(defaultSort::equals);
        if (!supported) {
            throw new IllegalArgumentException("Lookup defaultSort must reference a declared sort option key.");
        }
    }

    private static void validateStrategies(List<LookupSearchStrategyDefinition> strategies) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (LookupSearchStrategyDefinition strategy : strategies) {
            if (strategy == null || !keys.add(strategy.key())) {
                throw new IllegalArgumentException("Lookup search strategies must be non-null and use unique keys.");
            }
        }
    }

    private static void validateDefaultSearchStrategy(
            List<LookupSearchStrategyDefinition> strategies,
            String defaultSearchStrategy
    ) {
        if (defaultSearchStrategy == null) {
            return;
        }
        boolean supported = strategies.stream()
                .map(LookupSearchStrategyDefinition::key)
                .anyMatch(defaultSearchStrategy::equals);
        if (!supported) {
            throw new IllegalArgumentException("Lookup defaultSearchStrategy must reference a declared search strategy key.");
        }
    }

    public static final class FilterDtoBuilder {
        private final Class<?> filterDtoClass;
        private final OptionSourceRegistry optionSourceRegistry;
        private List<String> includedFields = List.of();
        private Map<String, Object> defaultFilters = Map.of();
        private List<LookupSortOption> sortOptions = List.of();
        private String defaultSort;
        private List<String> quickFilterFields;
        private String searchPlaceholder;
        private List<LookupSearchStrategyDefinition> searchStrategies = List.of();
        private String defaultSearchStrategy;

        private FilterDtoBuilder(Class<?> filterDtoClass, OptionSourceRegistry optionSourceRegistry) {
            this.filterDtoClass = Objects.requireNonNull(filterDtoClass, "filterDtoClass is required");
            this.optionSourceRegistry = optionSourceRegistry;
        }

        public FilterDtoBuilder includedFields(String... fields) {
            if (fields == null || fields.length == 0) {
                this.includedFields = List.of();
            } else {
                LinkedHashSet<String> cleaned = new LinkedHashSet<>();
                for (String field : fields) {
                    String norm = normalize(field);
                    if (norm != null) {
                        cleaned.add(norm);
                    }
                }
                this.includedFields = List.copyOf(cleaned);
            }
            return this;
        }

        public FilterDtoBuilder defaultFilters(Map<String, Object> values) {
            this.defaultFilters = values == null ? Map.of() : new LinkedHashMap<>(values);
            return this;
        }

        public FilterDtoBuilder sortOptions(List<LookupSortOption> values) {
            this.sortOptions = values == null ? List.of() : List.copyOf(values);
            return this;
        }

        public FilterDtoBuilder defaultSort(String value) {
            this.defaultSort = value;
            return this;
        }

        public FilterDtoBuilder quickFilterFields(List<String> values) {
            this.quickFilterFields = values == null ? List.of() : List.copyOf(values);
            return this;
        }

        public FilterDtoBuilder searchPlaceholder(String value) {
            this.searchPlaceholder = value;
            return this;
        }

        public FilterDtoBuilder searchStrategies(List<LookupSearchStrategyDefinition> values) {
            this.searchStrategies = values == null ? List.of() : List.copyOf(values);
            return this;
        }

        public FilterDtoBuilder defaultSearchStrategy(String value) {
            this.defaultSearchStrategy = value;
            return this;
        }

        public LookupFilteringDescriptor build() {
            Map<String, FilterMember> members = collectFilterMembers(filterDtoClass);
            List<FilterMember> selectedMembers = new ArrayList<>();
            if (!includedFields.isEmpty()) {
                for (String fieldName : includedFields) {
                    FilterMember member = members.get(fieldName);
                    if (member == null) {
                        throw new IllegalArgumentException(
                                "Filter field '" + fieldName + "' does not exist on " + filterDtoClass.getName());
                    }
                    Filterable filterable = member.findAnnotation(Filterable.class);
                    if (filterable == null) {
                        throw new IllegalArgumentException(
                                "Filter field '" + fieldName + "' on " + filterDtoClass.getName()
                                        + " must declare @Filterable.");
                    }
                    if (filterable.relations() != null && filterable.relations().length > 0) {
                        throw new IllegalArgumentException(
                                "Filter field '" + fieldName + "' on " + filterDtoClass.getName()
                                        + " uses multi-path @Filterable.relations() which is reserved for omnibox search.");
                    }
                    selectedMembers.add(member);
                }
            } else {
                for (FilterMember member : members.values()) {
                    Filterable filterable = member.findAnnotation(Filterable.class);
                    if (filterable == null || filterable.primarySearch()) {
                        continue;
                    }
                    if (filterable.relations() != null && filterable.relations().length > 0) {
                        continue;
                    }
                    if (!isSupportedFilterOperation(filterable.operation())) {
                        continue;
                    }
                    selectedMembers.add(member);
                }
            }

            List<LookupFilterDefinition> definitions = new ArrayList<>();
            List<String> derivedQuickFilters = new ArrayList<>();
            for (FilterMember member : selectedMembers) {
                Filterable filterable = member.findAnnotation(Filterable.class);
                LookupFilterDefinition def = toFilterDefinition(member, filterable, optionSourceRegistry);
                definitions.add(def);
                if (filterable.inline() && !filterable.primarySearch()) {
                    derivedQuickFilters.add(member.name());
                }
            }

            String effectivePlaceholder = normalize(searchPlaceholder);
            if (effectivePlaceholder == null) {
                effectivePlaceholder = resolvePrimarySearchPlaceholder(members.values());
            }
            List<String> effectiveQuickFilters = quickFilterFields != null ? quickFilterFields : derivedQuickFilters;

            return new LookupFilteringDescriptor(
                    definitions,
                    defaultFilters,
                    sortOptions,
                    defaultSort,
                    effectiveQuickFilters,
                    effectivePlaceholder,
                    searchStrategies,
                    defaultSearchStrategy
            );
        }
    }

    private record FilterMember(
            String name,
            Class<?> rawType,
            Type genericType,
            List<AnnotatedElement> elements
    ) {
        <A extends Annotation> A findAnnotation(Class<A> annotationType) {
            for (AnnotatedElement element : elements) {
                A merged = AnnotatedElementUtils.findMergedAnnotation(element, annotationType);
                if (merged != null) {
                    return merged;
                }
            }
            return null;
        }
    }

    private static Map<String, FilterMember> collectFilterMembers(Class<?> filterDtoClass) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> curr = filterDtoClass; curr != null && curr != Object.class && curr != Record.class; curr = curr.getSuperclass()) {
            hierarchy.add(curr);
        }
        Collections.reverse(hierarchy);

        Map<String, Class<?>> rawTypes = new LinkedHashMap<>();
        Map<String, Type> genericTypes = new LinkedHashMap<>();
        Map<String, List<AnnotatedElement>> elementsByName = new LinkedHashMap<>();

        for (Class<?> curr : hierarchy) {
            if (curr.isRecord() && curr.getRecordComponents() != null) {
                for (RecordComponent rc : curr.getRecordComponents()) {
                    rawTypes.putIfAbsent(rc.getName(), rc.getType());
                    genericTypes.putIfAbsent(rc.getName(), rc.getGenericType());
                    List<AnnotatedElement> els = elementsByName.computeIfAbsent(rc.getName(), ignored -> new ArrayList<>());
                    els.add(rc);
                    if (rc.getAccessor() != null) {
                        els.add(rc.getAccessor());
                    }
                }
            }
            for (Field field : curr.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                rawTypes.putIfAbsent(field.getName(), field.getType());
                genericTypes.putIfAbsent(field.getName(), field.getGenericType());
                elementsByName.computeIfAbsent(field.getName(), ignored -> new ArrayList<>()).add(field);
            }
            for (Method method : curr.getDeclaredMethods()) {
                String propName = resolveGetterPropertyName(curr, method);
                if (propName == null) {
                    continue;
                }
                rawTypes.putIfAbsent(propName, method.getReturnType());
                genericTypes.putIfAbsent(propName, method.getGenericReturnType());
                elementsByName.computeIfAbsent(propName, ignored -> new ArrayList<>()).add(method);
            }
        }

        Map<String, FilterMember> result = new LinkedHashMap<>();
        elementsByName.forEach((name, els) ->
                result.put(name, new FilterMember(name, rawTypes.get(name), genericTypes.get(name), List.copyOf(els))));
        return result;
    }

    private static String resolveGetterPropertyName(Class<?> owner, Method method) {
        if (Modifier.isStatic(method.getModifiers())
                || method.isSynthetic()
                || method.isBridge()
                || method.getParameterCount() != 0
                || method.getReturnType() == void.class) {
            return null;
        }
        String name = method.getName();
        if ("getClass".equals(name)) {
            return null;
        }
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

    private static String resolvePrimarySearchPlaceholder(Collection<FilterMember> members) {
        for (FilterMember member : members) {
            Filterable filterable = member.findAnnotation(Filterable.class);
            if (filterable != null && filterable.primarySearch()) {
                UISchema ui = member.findAnnotation(UISchema.class);
                if (ui != null) {
                    String placeholder = normalize(ui.placeholder());
                    if (placeholder != null) {
                        return placeholder;
                    }
                    String helpText = normalize(ui.helpText());
                    if (helpText != null) {
                        return helpText;
                    }
                }
            }
        }
        return null;
    }

    private static LookupFilterDefinition toFilterDefinition(
            FilterMember member,
            Filterable filterable,
            OptionSourceRegistry optionSourceRegistry
    ) {
        UISchema ui = member.findAnnotation(UISchema.class);
        Schema schema = member.findAnnotation(Schema.class);
        Class<?> elementType = resolveElementType(member.rawType(), member.genericType());
        String type = deriveFilterType(elementType, ui);
        OperatorSelection opSel = mapFilterOperation(filterable.operation(), type, member.name());

        String label = deriveFilterLabel(member.name(), ui, schema);
        boolean required = (ui != null && ui.required())
                || member.findAnnotation(NotNull.class) != null
                || member.findAnnotation(NotBlank.class) != null
                || member.findAnnotation(NotEmpty.class) != null;
        boolean hidden = ui != null && ui.hidden();

        List<Map<String, Object>> staticOptions = "enum".equals(type)
                ? extractStaticOptions(elementType, ui)
                : List.of();

        String optionsSource = null;
        Map<String, Object> embeddedOptionSource = Map.of();
        LinkedHashSet<String> dependsOn = new LinkedHashSet<>(parseUiDependsOn(ui));
        Map<String, String> dependencyFilterMap = new LinkedHashMap<>(parseUiDependencyFilterMap(ui));

        if (ui != null && !ui.endpoint().isBlank()) {
            EndpointRef ref = parseOptionSourceEndpoint(ui.endpoint());
            if (ref != null) {
                optionsSource = ref.sourceKey();
                if (optionSourceRegistry != null) {
                    OptionSourceDescriptor resolvedDesc = optionSourceRegistry
                            .resolveByResourcePathAndKey(ref.resourcePath(), ref.sourceKey())
                            .orElse(null);
                    if (resolvedDesc != null) {
                        embeddedOptionSource = sanitizeEmbeddedOptionSource(resolvedDesc.toMetadataMap());
                        dependsOn.addAll(resolvedDesc.dependsOn());
                        Map<String, String> mergedDeps = new LinkedHashMap<>(resolvedDesc.dependencyFilterMap());
                        mergedDeps.putAll(dependencyFilterMap);
                        dependencyFilterMap.clear();
                        dependencyFilterMap.putAll(mergedDeps);
                    }
                }
            }
        }

        String relation = normalize(filterable.relation());
        String targetPropertyPath = relation != null ? relation : member.name();

        return new LookupFilterDefinition(
                member.name(),
                label,
                type,
                opSel.operators(),
                opSel.defaultOperator(),
                optionsSource,
                staticOptions,
                embeddedOptionSource,
                List.copyOf(dependsOn),
                dependencyFilterMap,
                required,
                hidden,
                targetPropertyPath
        );
    }

    private static boolean isSupportedFilterOperation(Filterable.FilterOperation operation) {
        return switch (operation) {
            case LIKE, STARTS_WITH, EQUAL, IN, BETWEEN, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL -> true;
            default -> false;
        };
    }

    private record OperatorSelection(List<String> operators, String defaultOperator) {
    }

    private static OperatorSelection mapFilterOperation(
            Filterable.FilterOperation operation,
            String filterType,
            String fieldName
    ) {
        return switch (operation) {
            case LIKE -> {
                if (!"text".equals(filterType)) {
                    throw new IllegalArgumentException(
                            "Filter field '" + fieldName + "' uses @Filterable(LIKE) which requires 'text' filter type (resolved '" + filterType + "').");
                }
                yield new OperatorSelection(List.of("contains", "startsWith", "equals"), "contains");
            }
            case STARTS_WITH -> {
                if (!"text".equals(filterType)) {
                    throw new IllegalArgumentException(
                            "Filter field '" + fieldName + "' uses @Filterable(STARTS_WITH) which requires 'text' filter type (resolved '" + filterType + "').");
                }
                yield new OperatorSelection(List.of("startsWith", "contains", "equals"), "startsWith");
            }
            case EQUAL -> {
                if ("date".equals(filterType)) {
                    throw new IllegalArgumentException(
                            "Filter field '" + fieldName + "' of type 'date' does not support EQUAL in LookupFilterDefinition (use BETWEEN, GREATER_THAN, or LESS_THAN).");
                }
                yield new OperatorSelection(List.of("equals"), "equals");
            }
            case IN -> {
                if (!"enum".equals(filterType) && !"reference".equals(filterType)) {
                    throw new IllegalArgumentException(
                            "Filter field '" + fieldName + "' uses @Filterable(IN) which requires 'enum' or 'reference' filter type (resolved '" + filterType + "').");
                }
                yield new OperatorSelection(List.of("in", "equals"), "in");
            }
            case BETWEEN -> {
                if (!"date".equals(filterType) && !"number".equals(filterType)) {
                    throw new IllegalArgumentException(
                            "Filter field '" + fieldName + "' uses @Filterable(BETWEEN) which requires 'date' or 'number' filter type (resolved '" + filterType + "').");
                }
                yield new OperatorSelection(List.of("between"), "between");
            }
            case GREATER_THAN -> {
                if ("date".equals(filterType)) {
                    yield new OperatorSelection(List.of("after"), "after");
                }
                if ("number".equals(filterType)) {
                    yield new OperatorSelection(List.of("gt", "gte"), "gt");
                }
                throw new IllegalArgumentException(
                        "Filter field '" + fieldName + "' uses @Filterable(GREATER_THAN) which requires 'date' or 'number' filter type (resolved '" + filterType + "').");
            }
            case GREATER_OR_EQUAL -> {
                if ("date".equals(filterType)) {
                    yield new OperatorSelection(List.of("after"), "after");
                }
                if ("number".equals(filterType)) {
                    yield new OperatorSelection(List.of("gte", "gt"), "gte");
                }
                throw new IllegalArgumentException(
                        "Filter field '" + fieldName + "' uses @Filterable(GREATER_OR_EQUAL) which requires 'date' or 'number' filter type (resolved '" + filterType + "').");
            }
            case LESS_THAN -> {
                if ("date".equals(filterType)) {
                    yield new OperatorSelection(List.of("before"), "before");
                }
                if ("number".equals(filterType)) {
                    yield new OperatorSelection(List.of("lt", "lte"), "lt");
                }
                throw new IllegalArgumentException(
                        "Filter field '" + fieldName + "' uses @Filterable(LESS_THAN) which requires 'date' or 'number' filter type (resolved '" + filterType + "').");
            }
            case LESS_OR_EQUAL -> {
                if ("date".equals(filterType)) {
                    yield new OperatorSelection(List.of("before"), "before");
                }
                if ("number".equals(filterType)) {
                    yield new OperatorSelection(List.of("lte", "lt"), "lte");
                }
                throw new IllegalArgumentException(
                        "Filter field '" + fieldName + "' uses @Filterable(LESS_OR_EQUAL) which requires 'date' or 'number' filter type (resolved '" + filterType + "').");
            }
            default -> throw new IllegalArgumentException(
                    "Filter field '" + fieldName + "' uses unsupported @Filterable operation " + operation + " for LookupFilterDefinition.");
        };
    }

    private static Class<?> resolveElementType(Class<?> rawType, Type genericType) {
        if (rawType == null) {
            return String.class;
        }
        if (rawType.isArray()) {
            return rawType.getComponentType();
        }
        if (Collection.class.isAssignableFrom(rawType) && genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> cls) {
                return cls;
            }
        }
        return rawType;
    }

    private static String deriveFilterType(Class<?> elementType, UISchema ui) {
        if (ui != null) {
            FieldControlType ct = ui.controlType();
            if (ct == FieldControlType.ENTITY_LOOKUP
                    || ct == FieldControlType.INLINE_ENTITY_LOOKUP
                    || ct == FieldControlType.ASYNC_SELECT
                    || ct == FieldControlType.INLINE_ASYNC_SELECT
                    || parseOptionSourceEndpoint(ui.endpoint()) != null) {
                return "reference";
            }
            if ((ui.optionItems() != null && ui.optionItems().length > 0)
                    || (ui.optionEnum() != null && ui.optionEnum() != Void.class)
                    || ct == FieldControlType.SELECT
                    || ct == FieldControlType.SEARCHABLE_SELECT
                    || ct == FieldControlType.INLINE_SELECT
                    || ct == FieldControlType.INLINE_SEARCHABLE_SELECT
                    || ct == FieldControlType.MULTI_SELECT
                    || ct == FieldControlType.INLINE_MULTISELECT
                    || ct == FieldControlType.RADIO
                    || ct == FieldControlType.BUTTON_TOGGLE
                    || ct == FieldControlType.CHECKBOX
                    || ct == FieldControlType.TOGGLE
                    || ct == FieldControlType.INLINE_TOGGLE) {
                return "enum";
            }
            if (ui.type() == FieldDataType.DATE
                    || ct == FieldControlType.DATE_PICKER
                    || ct == FieldControlType.DATE_INPUT
                    || ct == FieldControlType.DATE_TIME_PICKER
                    || ct == FieldControlType.DATE_RANGE
                    || ct == FieldControlType.INLINE_DATE
                    || ct == FieldControlType.INLINE_DATE_RANGE) {
                return "date";
            }
            if (ui.type() == FieldDataType.NUMBER
                    || ct == FieldControlType.NUMERIC_TEXT_BOX
                    || ct == FieldControlType.CURRENCY_INPUT
                    || ct == FieldControlType.INLINE_NUMBER
                    || ct == FieldControlType.INLINE_CURRENCY) {
                return "number";
            }
            if (ui.type() == FieldDataType.BOOLEAN) {
                return "enum";
            }
        }
        if (elementType.isEnum() || elementType == Boolean.class || elementType == boolean.class) {
            return "enum";
        }
        if (elementType == LocalDate.class
                || elementType == LocalDateTime.class
                || elementType == OffsetDateTime.class
                || elementType == ZonedDateTime.class
                || elementType == Instant.class
                || Date.class.isAssignableFrom(elementType)) {
            return "date";
        }
        if (Number.class.isAssignableFrom(elementType)
                || elementType == int.class
                || elementType == long.class
                || elementType == double.class
                || elementType == float.class
                || elementType == short.class
                || elementType == byte.class) {
            return "number";
        }
        return "text";
    }

    private static String deriveFilterLabel(String fieldName, UISchema ui, Schema schema) {
        if (ui != null) {
            String uiLabel = normalize(ui.label());
            if (uiLabel != null) {
                return uiLabel;
            }
        }
        if (schema != null) {
            String title = normalize(schema.title());
            if (title != null) {
                return title;
            }
        }
        return fieldName;
    }

    private static List<Map<String, Object>> extractStaticOptions(Class<?> elementType, UISchema ui) {
        if (ui != null && ui.optionItems() != null && ui.optionItems().length > 0) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (UiOption item : ui.optionItems()) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("value", coerceStaticOptionValue(item.value(), elementType));
                option.put("label", item.label());
                String icon = normalize(item.icon());
                if (icon != null) {
                    option.put("icon", icon);
                }
                QuickFilterTone tone = item.tone();
                if (tone != null && tone != QuickFilterTone.UNSPECIFIED && !tone.wireValue().isEmpty()) {
                    option.put("tone", tone.wireValue());
                }
                String desc = normalize(item.description());
                if (desc != null) {
                    option.put("description", desc);
                }
                if (item.disabled()) {
                    option.put("disabled", true);
                }
                items.add(Collections.unmodifiableMap(option));
            }
            return List.copyOf(items);
        }
        Class<?> enumClass = null;
        if (ui != null && ui.optionEnum() != null && ui.optionEnum() != Void.class && ui.optionEnum().isEnum()) {
            enumClass = ui.optionEnum();
        } else if (elementType != null && elementType.isEnum()) {
            enumClass = elementType;
        }
        if (enumClass != null) {
            return extractEnumOptions(enumClass);
        }
        if (elementType == Boolean.class || elementType == boolean.class) {
            String trueLabel = ui != null && !ui.labelTrue().isBlank() ? ui.labelTrue().trim() : "Sim";
            String falseLabel = ui != null && !ui.labelFalse().isBlank() ? ui.labelFalse().trim() : "Não";
            return List.of(
                    Map.of("value", true, "label", trueLabel),
                    Map.of("value", false, "label", falseLabel)
            );
        }
        return List.of();
    }

    private static Object coerceStaticOptionValue(String rawValue, Class<?> elementType) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (elementType == Boolean.class || elementType == boolean.class) {
            if ("true".equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
        } else if (elementType == Integer.class || elementType == int.class
                || elementType == Long.class || elementType == long.class
                || elementType == Short.class || elementType == short.class) {
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                return rawValue;
            }
        } else if (Number.class.isAssignableFrom(elementType) || elementType == double.class || elementType == float.class) {
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException ignored) {
                return rawValue;
            }
        }
        return rawValue;
    }

    private static List<Map<String, Object>> extractEnumOptions(Class<?> enumClass) {
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null || constants.length == 0) {
            return List.of();
        }
        Method jsonValueMethod = findJsonValueMethod(enumClass);
        Method labelMethod = findFirstNoArgStringMethod(enumClass, "getLabel", "label", "getDescricao", "descricao", "getTitle", "title");
        Method iconMethod = findFirstNoArgStringMethod(enumClass, "getIcon", "icon");
        Method descMethod = findFirstNoArgStringMethod(enumClass, "getDescription", "description");

        List<Map<String, Object>> items = new ArrayList<>();
        for (Object constant : constants) {
            if (!(constant instanceof Enum<?> enumConstant)) {
                continue;
            }
            if (enumConstant instanceof PraxisUiOptionEnum praxisOption) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("value", praxisOption.value());
                option.put("label", praxisOption.label());
                if (praxisOption.icon() != null && !praxisOption.icon().isBlank()) {
                    option.put("icon", praxisOption.icon().trim());
                }
                if (praxisOption.tone() != null
                        && praxisOption.tone() != QuickFilterTone.UNSPECIFIED
                        && praxisOption.tone().wireValue() != null
                        && !praxisOption.tone().wireValue().isBlank()) {
                    option.put("tone", praxisOption.tone().wireValue());
                }
                items.add(Collections.unmodifiableMap(option));
                continue;
            }
            Map<String, Object> option = new LinkedHashMap<>();
            Object rawValue = resolveEnumRawValue(enumConstant, jsonValueMethod);
            option.put("value", rawValue instanceof Number || rawValue instanceof Boolean ? rawValue : String.valueOf(rawValue));

            Field constantField = findEnumConstantField(enumClass, enumConstant.name());
            UISchema constantUi = constantField != null ? constantField.getAnnotation(UISchema.class) : null;
            Schema constantSchema = constantField != null ? constantField.getAnnotation(Schema.class) : null;

            String label = resolveEnumLabel(enumConstant, constantUi, constantSchema, labelMethod);
            option.put("label", label);
            String icon = resolveEnumAttr(enumConstant, constantUi != null ? constantUi.icon() : "", iconMethod);
            if (!icon.isEmpty()) {
                option.put("icon", icon);
            }
            if (constantUi != null && !constantUi.tone().isBlank()) {
                option.put("tone", constantUi.tone().trim().toLowerCase(Locale.ROOT));
            }
            String desc = resolveEnumAttr(enumConstant, constantUi != null ? constantUi.description() : "", descMethod);
            if (!desc.isEmpty()) {
                option.put("description", desc);
            }
            if (constantUi != null && constantUi.disabled()) {
                option.put("disabled", true);
            }
            items.add(Collections.unmodifiableMap(option));
        }
        return List.copyOf(items);
    }

    private static Method findJsonValueMethod(Class<?> enumClass) {
        for (Method method : enumClass.getMethods()) {
            if (method.getParameterCount() == 0 && method.isAnnotationPresent(JsonValue.class)) {
                return method;
            }
        }
        return null;
    }

    private static Method findFirstNoArgStringMethod(Class<?> enumClass, String... names) {
        for (String name : names) {
            try {
                Method m = enumClass.getMethod(name);
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
                // continue
            }
        }
        return null;
    }

    private static Object resolveEnumRawValue(Enum<?> constant, Method jsonValueMethod) {
        if (jsonValueMethod != null) {
            try {
                Object value = jsonValueMethod.invoke(constant);
                if (value != null) {
                    return value;
                }
            } catch (Exception ignored) {
                // fallback to name()
            }
        }
        return constant.name();
    }

    private static Field findEnumConstantField(Class<?> enumClass, String name) {
        try {
            return enumClass.getField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static String resolveEnumLabel(Enum<?> constant, UISchema ui, Schema schema, Method labelMethod) {
        if (ui != null && !ui.label().isBlank()) {
            return ui.label().trim();
        }
        if (labelMethod != null) {
            try {
                Object val = labelMethod.invoke(constant);
                if (val instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            } catch (Exception ignored) {
                // fallback
            }
        }
        if (schema != null && !schema.title().isBlank()) {
            return schema.title().trim();
        }
        return constant.name();
    }

    private static String resolveEnumAttr(Enum<?> constant, String fromUi, Method method) {
        if (fromUi != null && !fromUi.isBlank()) {
            return fromUi.trim();
        }
        if (method != null) {
            try {
                Object val = method.invoke(constant);
                if (val instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return "";
    }

    private static List<String> parseUiDependsOn(UISchema ui) {
        if (ui == null || ui.dependsOn().isBlank()) {
            return List.of();
        }
        String raw = ui.dependsOn().trim();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw.startsWith("[") && raw.endsWith("]")) {
            try {
                List<String> parsed = JSON_MAPPER.readValue(raw, new TypeReference<>() {});
                for (String item : parsed) {
                    String norm = normalize(item);
                    if (norm != null) {
                        result.add(norm);
                    }
                }
                return List.copyOf(result);
            } catch (Exception ignored) {
                // fall through to comma-separated parsing
            }
        }
        for (String part : raw.split(",")) {
            String norm = normalize(part);
            if (norm != null) {
                result.add(norm);
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, String> parseUiDependencyFilterMap(UISchema ui) {
        if (ui == null || ui.extraProperties() == null || ui.extraProperties().length == 0) {
            return Map.of();
        }
        for (ExtensionProperty prop : ui.extraProperties()) {
            if (prop != null && "optionSource.dependencyFilterMap".equals(prop.name()) && !prop.value().isBlank()) {
                try {
                    Map<String, String> parsed = JSON_MAPPER.readValue(prop.value().trim(), new TypeReference<>() {});
                    Map<String, String> normalized = new LinkedHashMap<>();
                    parsed.forEach((k, v) -> {
                        String nk = normalize(k);
                        String nv = normalize(v);
                        if (nk != null && nv != null) {
                            normalized.put(nk, nv);
                        }
                    });
                    return Map.copyOf(normalized);
                } catch (Exception ex) {
                    throw new IllegalArgumentException(
                            "Invalid optionSource.dependencyFilterMap JSON on @UISchema.extraProperties: " + prop.value(), ex);
                }
            }
        }
        return Map.of();
    }

    private record EndpointRef(String resourcePath, String sourceKey) {
    }

    private static EndpointRef parseOptionSourceEndpoint(String rawEndpoint) {
        if (rawEndpoint == null || rawEndpoint.isBlank()) {
            return null;
        }
        String candidate = rawEndpoint.trim();
        int q = candidate.indexOf('?');
        if (q >= 0) {
            candidate = candidate.substring(0, q);
        }
        int h = candidate.indexOf('#');
        if (h >= 0) {
            candidate = candidate.substring(0, h);
        }
        try {
            URI uri = URI.create(candidate);
            if (uri.getPath() != null && !uri.getPath().isBlank()) {
                candidate = uri.getPath();
            }
        } catch (IllegalArgumentException ignored) {
            // keep literal path
        }
        while (candidate.contains("//")) {
            candidate = candidate.replace("//", "/");
        }
        if (!candidate.startsWith("/")) {
            candidate = "/" + candidate;
        }
        int marker = candidate.indexOf("/option-sources/");
        if (marker <= 0) {
            return null;
        }
        String resourcePath = candidate.substring(0, marker);
        if (resourcePath.length() > 1 && resourcePath.endsWith("/")) {
            resourcePath = resourcePath.substring(0, resourcePath.length() - 1);
        }
        String suffix = candidate.substring(marker + "/option-sources/".length());
        int nextSlash = suffix.indexOf('/');
        if (nextSlash <= 0) {
            return null;
        }
        String sourceKey = suffix.substring(0, nextSlash);
        String opPath = suffix.substring(nextSlash);
        if (!opPath.startsWith("/options/")) {
            return null;
        }
        return new EndpointRef(resourcePath, sourceKey);
    }

    private static Map<String, Object> sanitizeEmbeddedOptionSource(Map<String, Object> rawMetadata) {
        if (rawMetadata == null || rawMetadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        rawMetadata.forEach((key, value) -> {
            if (key == null || "filtering".equals(key) || PRIVATE_OPTION_SOURCE_KEYS.contains(key)) {
                return;
            }
            if (value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Collections.unmodifiableMap(sanitized);
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        values.forEach(value -> {
            String candidate = normalize(value);
            if (candidate != null) {
                normalized.add(candidate);
            }
        });
        return List.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
