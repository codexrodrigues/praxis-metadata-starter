package org.praxisplatform.uischema.customfields;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.validation.FieldError;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicEvaluationOptions;

/** Validates writes against the active operation and tenant definitions before resource hooks. */
public class CustomFieldValidator {
    private final CustomFieldDefinitionProvider definitions;
    public CustomFieldValidator(CustomFieldDefinitionProvider definitions) { this.definitions = Objects.requireNonNull(definitions); }
    public void validate(Object dto, String resourceKey, String tenantId, String schemaType) {
        validateAndGetReadOnlyKeys(dto, resourceKey, tenantId, schemaType);
    }
    /** Validates the request and returns the read-only keys from that exact definition snapshot. */
    public Set<String> validateAndGetReadOnlyKeys(Object dto, String resourceKey, String tenantId, String schemaType) {
        return prepare(dto, resourceKey, tenantId, schemaType).validateWithoutEntity();
    }
    /** The entity root is provided only by trusted server code, never inferred from a submitted DTO. */
    public Set<String> validateAndGetReadOnlyKeys(Object dto, String resourceKey, String tenantId, String schemaType, Object entity) {
        PreparedValidation prepared = prepare(dto, resourceKey, tenantId, schemaType);
        return entity == null ? prepared.validateWithoutEntity() : prepared.validateForUpdate(entity);
    }
    /** Captures one definition/input snapshot and validates primitive assignments before loading server state. */
    public PreparedValidation prepare(Object dto, String resourceKey, String tenantId, String schemaType) {
        if (dto == null || CustomFieldSupport.property(dto.getClass()).isEmpty()) return PreparedValidation.empty();
        String effectiveResource = CustomFieldSupport.resourceKey(dto.getClass(), resourceKey);
        List<CustomFieldDescriptor> active = List.copyOf(definitions.findDefinitions(effectiveResource, tenantId, schemaType));
        return prepareValues(CustomFieldSupport.read(dto), active, dto);
    }

    /**
     * Captures the public DTO contract, one definition snapshot and only the persisted facts
     * observed by domain-command rules. The callback is trusted server code, not a request mapper.
     * Client assignments must still use {@link #prepare} and its read-only checks before mutation.
     */
    public DomainMutationValidation prepareDomainMutation(Object projection, Object entity,
            String resourceKey, String tenantId, String schemaType) {
        if (projection == null || CustomFieldSupport.property(projection.getClass()).isEmpty())
            return DomainMutationValidation.empty();
        String effectiveResource = CustomFieldSupport.resourceKey(projection.getClass(), resourceKey);
        List<CustomFieldDescriptor> active = List.copyOf(definitions.findDefinitions(effectiveResource, tenantId, schemaType));
        boolean observesEntity = active.stream().filter(d -> !d.readOnly() && !d.validationRules().isEmpty())
                .anyMatch(d -> CustomFieldJsonLogic.requiresEntity(CustomFieldJsonLogic.expression(d.validationRules())));
        ObjectNode previous = null;
        if (observesEntity) {
            previous = CustomFieldJsonLogic.entityFacts(Objects.requireNonNull(entity, "Trusted entity is required"));
            // An entity marker is optional: the public DTO still publishes its persisted JSON container.
            if (CustomFieldSupport.property(entity.getClass()).isEmpty())
                previous.set("customFields", PraxisFactTreeBuilder.toObjectNode(CustomFieldSupport.read(projection)));
        }
        return new DomainMutationValidation(projection.getClass(), active, previous, projection == entity);
    }

    /** Request-local validation of a trusted domain result; no entity graph or DTO alias is retained. */
    public static final class DomainMutationValidation {
        private static final DomainMutationValidation EMPTY = new DomainMutationValidation(null, List.of(), null, false);
        private final Class<?> projectionType;
        private final List<CustomFieldDescriptor> active;
        private final ObjectNode previous;
        private final boolean entityProjection;
        private DomainMutationValidation(Class<?> projectionType, List<CustomFieldDescriptor> active, ObjectNode previous, boolean entityProjection) {
            this.projectionType = projectionType;
            this.active = List.copyOf(active);
            this.previous = previous == null ? null : previous.deepCopy();
            this.entityProjection = entityProjection;
        }
        public static DomainMutationValidation empty() { return EMPTY; }
        /** Validates current values and JSON Logic while allowing server-owned read-only determinations. */
        public void validate(Object result) {
            validate(result, result);
        }

        /** Uses the persisted container when present and the public projection as draft context. */
        public void validate(Object result, Object entity) {
            if (projectionType == null) return;
            if (result == null || !projectionType.isInstance(result)
                    || CustomFieldSupport.property(result.getClass()).isEmpty())
                throw new IllegalStateException("Domain command changed its custom-field projection contract");
            Object valuesSource = CustomFieldSupport.property(entity.getClass()).isPresent() ? entity : result;
            PreparedValidation prepared = prepareValues(CustomFieldSupport.read(valuesSource), active,
                    entityProjection ? () -> CustomFieldJsonLogic.entityFacts(result) : () -> result);
            ObjectNode facts = prepared.context;
            if (prepared.requiresEntity)
                facts = PraxisFactTreeBuilder.from(facts).put("entity", Objects.requireNonNull(previous, "Previous facts are required")).build();
            // A server result is not a client assignment: read-only request comparison belongs to prepare().
            prepared.evaluate(facts, CustomFieldJsonLogic.ROOTS);
        }
    }
    public void validate(Object dto, String resourceKey, String tenantId) { validate(dto, resourceKey, tenantId, null); }
    public void validateValues(Map<String, Object> values, List<CustomFieldDescriptor> active) {
        validateValues(values, active, Map.of("customFields", values), null);
    }
    /** Validates roots value/customFields/draft/entity without implicit clock, timezone or persistence. */
    public void validateValues(Map<String, Object> values, List<CustomFieldDescriptor> active, Object draft, Object entity) {
        PreparedValidation prepared = prepareValues(values, active, draft);
        if (entity == null) prepared.validateWithoutEntity();
        else prepared.validateForUpdate(entity);
    }
    private static PreparedValidation prepareValues(Map<String, Object> values, List<CustomFieldDescriptor> active, Object draft) {
        return prepareValues(values, active, () -> draft);
    }
    private static PreparedValidation prepareValues(Map<String, Object> values, List<CustomFieldDescriptor> active,
            java.util.function.Supplier<Object> draft) {
        Map<String, CustomFieldDescriptor> byKey = new LinkedHashMap<>();
        for (CustomFieldDescriptor descriptor : active) {
            if (byKey.putIfAbsent(descriptor.key(), descriptor) != null) throw new IllegalStateException("Duplicate custom field definition: " + descriptor.key());
        }
        List<FieldError> errors = new ArrayList<>();
        values.forEach((key, value) -> { if (!byKey.containsKey(key)) error(errors, key, "Unknown custom field"); });
        for (CustomFieldDescriptor d : byKey.values()) {
            Object value = values.get(d.key());
            if (d.readOnly()) {
                continue;
            }
            if (value == null) {
                if (d.required()) error(errors, d.key(), message(d.requiredMessage(), "Required custom field"));
                continue;
            }
            if (!matchesType(d, value)) { error(errors, d.key(), "Invalid custom field type: " + d.dataType()); continue; }
            if (value instanceof Number number) {
                BigDecimal decimal = new BigDecimal(number.toString());
                if (d.minimum() != null && decimal.compareTo(d.minimum()) < 0) error(errors, d.key(), "Value is below minimum");
                if (d.maximum() != null && decimal.compareTo(d.maximum()) > 0) error(errors, d.key(), "Value exceeds maximum");
            }
            if (value instanceof String text) {
                int length = text.codePointCount(0, text.length());
                if (d.minLength() != null && length < d.minLength()) error(errors, d.key(), "Value is shorter than minLength");
                if (d.maxLength() != null && length > d.maxLength()) error(errors, d.key(), "Value exceeds maxLength");
                if (d.pattern() != null && !Pattern.compile(d.pattern()).matcher(text).matches())
                    error(errors, d.key(), message(d.patternMessage(), "Value does not match pattern"));
            }
        }
        // All primitive prerequisites must be valid before a cross-field expression can execute.
        // Otherwise a missing/ill-typed required operand could mask a caller-correctable field error.
        if (!errors.isEmpty()) throw new CustomFieldValidationException(errors);
        List<CustomFieldDescriptor> rules = byKey.values().stream()
                .filter(d -> !d.readOnly() && !d.validationRules().isEmpty())
                // An omitted optional value has no local constraint to evaluate. Cross-field
                // rules still run: they may make that value conditionally required.
                .filter(d -> d.required() || values.get(d.key()) != null
                        || CustomFieldJsonLogic.observesOtherFields(CustomFieldJsonLogic.expression(d.validationRules())))
                .toList();
        Set<String> readOnly = new LinkedHashSet<>();
        byKey.values().stream().filter(CustomFieldDescriptor::readOnly).map(CustomFieldDescriptor::key).forEach(readOnly::add);
        Map<String, Object> submittedReadOnly = new LinkedHashMap<>();
        readOnly.forEach(key -> { if (values.containsKey(key)) submittedReadOnly.put(key, values.get(key)); });
        ObjectNode context = rules.isEmpty() ? null : CustomFieldJsonLogic.context(values, draft.get(), null);
        return new PreparedValidation(rules, context, readOnly, PraxisFactTreeBuilder.toObjectNode(submittedReadOnly));
    }
    private static void validateJsonLogic(CustomFieldDescriptor descriptor, ObjectNode context,
            List<String> roots, List<FieldError> errors) {
        ObjectNode facts = context.deepCopy();
        if (facts.path("customFields").has(descriptor.key()))
            facts.set("value", facts.path("customFields").get(descriptor.key()).deepCopy());
        else facts.putNull("value");
        try {
            var expression = CustomFieldJsonLogic.expression(descriptor.validationRules());
            // The canonical validator applies root availability even to defaults/unselected branches.
            // An absent server context is not an implicit permission to compare a missing value.
            CustomFieldJsonLogic.ENGINE.validate(expression,
                    new org.praxisplatform.rules.jsonlogic.model.JsonLogicValidationOptions(roots, null, false, null, null, true));
            boolean valid = CustomFieldJsonLogic.ENGINE.evaluateResult(expression,
                    facts, new JsonLogicEvaluationOptions(roots, null, false, null, null)).truthy();
            if (!valid) errors.add(new FieldError("customFields", "customFields." + descriptor.key(), null, false,
                    new String[] {"CUSTOM_FIELD_JSON_LOGIC"}, null,
                    message((String) descriptor.validationRules().get("message"), "Custom field rule was not satisfied")));
        } catch (PraxisJsonLogicException ex) {
            if (!descriptor.required() && facts.path("value").isNull()
                    && CustomFieldJsonLogic.observesOtherFields(CustomFieldJsonLogic.expression(descriptor.validationRules()))
                    && ex.getCode() == org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID
                    && !ex.getNullOperandPaths().isEmpty()
                    && ex.getNullOperandPaths().stream().allMatch(path -> path.equals(List.of("value")))) return;
            // Invalid definitions/types are infrastructure failures, not fabricated business denials.
            throw new IllegalStateException("Custom field rule could not be evaluated", ex);
        }
    }
    /** Immutable request-local preparation. No entity, DTO alias, tenant store or mutable head is retained. */
    public static final class PreparedValidation {
        private static final PreparedValidation EMPTY = new PreparedValidation(List.of(), null, Set.of(), null);
        private final List<CustomFieldDescriptor> rules;
        private final ObjectNode context;
        private final Set<String> readOnlyKeys;
        private final boolean requiresEntity;
        private final boolean requiresDraft;
        private final ObjectNode submittedReadOnly;

        private PreparedValidation(List<CustomFieldDescriptor> rules, ObjectNode context, Set<String> readOnlyKeys, ObjectNode submittedReadOnly) {
            this.rules = List.copyOf(rules);
            this.context = context == null ? null : context.deepCopy();
            this.readOnlyKeys = Collections.unmodifiableSet(new LinkedHashSet<>(readOnlyKeys));
            this.submittedReadOnly = submittedReadOnly == null ? null : submittedReadOnly.deepCopy();
            this.requiresEntity = rules.stream().anyMatch(rule ->
                    CustomFieldJsonLogic.requiresEntity(CustomFieldJsonLogic.expression(rule.validationRules())));
            this.requiresDraft = rules.stream().anyMatch(rule ->
                    CustomFieldJsonLogic.requiresDraft(CustomFieldJsonLogic.expression(rule.validationRules())));
        }
        /** Returns a preparation for a DTO without custom fields or an absent validator. */
        public static PreparedValidation empty() { return EMPTY; }

        /** Whether an admitted constraint observes the operation draft, including whole-context var. */
        public boolean requiresDraft() { return requiresDraft; }

        /** Explicit create context: entity is JSON null because no persisted item exists. */
        public Set<String> validateForCreate() {
            validateReadOnly(null);
            return evaluate(context == null ? null : PraxisFactTreeBuilder.from(context).put("entity", null).build(), CustomFieldJsonLogic.ROOTS);
        }
        /** Validates a trusted, hydrated operation draft while retaining the captured input/definitions. */
        public Set<String> validateForCreate(Object effectiveDraft) {
            validateReadOnly(null);
            return evaluate(createContext(effectiveDraft), CustomFieldJsonLogic.ROOTS);
        }
        private ObjectNode createContext(Object effectiveDraft) {
            if (context == null) return null;
            return PraxisFactTreeBuilder.from(withDraft(effectiveDraft)).put("entity", null).build();
        }
        /** Uses only the authorized state supplied by the resource lifecycle, before hooks/mapping. */
        public Set<String> validateForUpdate(Object entity) {
            if (entity == null) throw new IllegalStateException("Trusted entity is required for custom-field update validation");
            validateReadOnly(entity);
            // Value/draft-only constraints must not traverse unrelated JPA associations/getters.
            return evaluate(requiresEntity ? PraxisFactTreeBuilder.from(context).put("entity", CustomFieldJsonLogic.entityFacts(entity)).build() : context,
                    CustomFieldJsonLogic.ROOTS);
        }
        /** Uses the authorized persisted entity and an explicit effective draft prepared by the host. */
        public Set<String> validateForUpdate(Object entity, Object effectiveDraft) {
            if (entity == null) throw new IllegalStateException("Trusted entity is required for custom-field update validation");
            validateReadOnly(entity);
            ObjectNode facts = withDraft(effectiveDraft);
            return evaluate(requiresEntity ? PraxisFactTreeBuilder.from(facts).put("entity", CustomFieldJsonLogic.entityFacts(entity)).build() : facts,
                    CustomFieldJsonLogic.ROOTS);
        }
        private ObjectNode withDraft(Object effectiveDraft) {
            if (context == null || !requiresDraft) return context;
            if (effectiveDraft == null) throw new IllegalStateException("Trusted operation draft is required");
            return PraxisFactTreeBuilder.from(context).put("draft", effectiveDraft).build();
        }
        /** Standalone validation has no server entity root; expressions requiring it fail closed. */
        public Set<String> validateWithoutEntity() {
            if (requiresEntity) throw new IllegalStateException("Custom field rules require the current entity");
            validateReadOnly(null);
            return evaluate(context, CustomFieldJsonLogic.ROOTS_WITHOUT_ENTITY);
        }
        private void validateReadOnly(Object entity) {
            if (submittedReadOnly == null || submittedReadOnly.isEmpty()) return;
            ObjectNode persisted = PraxisFactTreeBuilder.toObjectNode(CustomFieldSupport.read(entity));
            List<FieldError> errors = new ArrayList<>();
            submittedReadOnly.properties().forEach(entry -> {
                if (!persisted.has(entry.getKey()) || !entry.getValue().equals(persisted.get(entry.getKey())))
                    error(errors, entry.getKey(), "Custom field is read-only");
            });
            if (!errors.isEmpty()) throw new CustomFieldValidationException(errors);
        }
        private Set<String> evaluate(ObjectNode facts, List<String> roots) {
            List<FieldError> errors = new ArrayList<>();
            for (CustomFieldDescriptor rule : rules) validateJsonLogic(rule, facts, roots, errors);
            if (!errors.isEmpty()) throw new CustomFieldValidationException(errors);
            return readOnlyKeys;
        }
    }
    public static boolean matchesType(CustomFieldDescriptor d, Object value) {
        try {
            return switch (d.dataType()) {
                case STRING -> value instanceof String s && (d.enumValues().isEmpty() || d.enumValues().contains(s));
                case INTEGER -> value instanceof Number n && new BigDecimal(n.toString()).stripTrailingZeros().scale() <= 0;
                case DECIMAL -> value instanceof Number n && new BigDecimal(n.toString()) != null;
                case BOOLEAN -> value instanceof Boolean;
                case DATE -> value instanceof LocalDate || value instanceof String s && LocalDate.parse(s) != null;
                case DATE_TIME -> value instanceof Instant || value instanceof OffsetDateTime || value instanceof LocalDateTime
                        || value instanceof String s && validDateTime(s);
                case ENUM -> value instanceof String s && d.enumValues().contains(s);
                case ARRAY -> value instanceof Collection<?> || value.getClass().isArray();
            };
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) { return false; }
    }
    private static boolean validDateTime(String value) {
        try { OffsetDateTime.parse(value); return true; }
        catch (java.time.format.DateTimeParseException ex) { LocalDateTime.parse(value); return true; }
    }
    private static String message(String configured, String fallback) { return configured == null || configured.isBlank() ? fallback : configured; }
    private static void error(List<FieldError> errors, String key, String message) {
        errors.add(new FieldError("customFields", "customFields." + key, message));
    }
}
