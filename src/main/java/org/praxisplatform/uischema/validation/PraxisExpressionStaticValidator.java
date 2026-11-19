package org.praxisplatform.uischema.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validador estatico de expressoes declarativas de dominio (Spring Boot startup).
 *
 * <p>
 * Analisa sintaxe e referencias de propriedades em expressoes de micro visualizacoes
 * (toneExpr, valueExpr, targetExpr, totalExpr, baselineExpr) e JsonLogic contra os
 * campos reais disponiveis no Schema / DTO, prevenindo publicacao de contratos quebrados.
 * </p>
 */
public class PraxisExpressionStaticValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PraxisExpressionStaticValidator.class);

    private static final Set<String> BASE_RESERVED_KEYWORDS = Set.of(
            "true", "false", "null", "undefined", "NaN", "Infinity",
            "Math", "Number", "String", "Date", "Array", "Boolean", "Object",
            "parseInt", "parseFloat", "isNaN", "isFinite", "encodeURIComponent", "decodeURIComponent",
            "typeof", "instanceof", "in", "new", "this",
            // Funcoes nativas de SafeExpressionEvaluator (@praxisui/core e @praxisui/table)
            "round", "ceil", "floor", "abs", "min", "max",
            "now", "date", "yearsSince", "monthsSince", "daysSince",
            "coalesce", "len", "toNumber", "toString", "if",
            // Namespaces contextuais de linha e sub-recursos no drawer analitico
            "row", "item", "data", "form", "relations"
    );

    private static final Set<String> RESERVED_KEYWORDS = buildReservedKeywords();

    private static Set<String> buildReservedKeywords() {
        Set<String> keywords = new LinkedHashSet<>(BASE_RESERVED_KEYWORDS);
        keywords.addAll(QuickFilterTone.allowedWireValues());
        return Collections.unmodifiableSet(keywords);
    }

    private static final List<String> CALCULATION_MEMORY_SECTION_EXPR_KEYS = List.of(
            "titleExpr",
            "subtitleExpr",
            "legalBasisExpr",
            "trailsExpr"
    );

    private static final List<String> CALCULATION_TRAIL_EXPR_KEYS = List.of(
            "visibleWhen",
            "titleExpr",
            "subtitleExpr",
            "ruleCodeExpr",
            "formulaExpr",
            "baseExpr",
            "baseHintExpr",
            "additionExpr",
            "additionHintExpr",
            "deductionExpr",
            "deductionHintExpr",
            "factorExpr",
            "factorHintExpr",
            "capExpr",
            "capHintExpr",
            "targetExpr",
            "targetHintExpr",
            "resultExpr",
            "resultHintExpr",
            "legalBasisExpr",
            "legalPurposeExpr"
    );

    private static final Set<String> VALID_FORM_LAYOUT_MODES = Set.of(
            "flat",
            "vertical",
            "tabs",
            "accordion",
            "stepper"
    );

    private static final Set<String> VALID_FORM_LAYOUT_TABS_APPEARANCES = Set.of(
            "pills",
            "underline",
            "buttons"
    );

    private static final Set<String> VALID_FORM_LAYOUT_STEPPER_READ_MODES = Set.of(
            "presentation",
            "all",
            "none"
    );

    private static final List<String> VISUALIZATION_EXPR_KEYS = List.of(
            "toneExpr",
            "valueExpr",
            "targetExpr",
            "targetLabelExpr",
            "totalExpr",
            "baselineExpr",
            "minExpr",
            "maxExpr",
            "pointsExpr",
            "segmentsExpr",
            "itemsExpr",
            "thresholdsExpr",
            "titleExpr",
            "ariaLabelExpr",
            "fallbackTextExpr",
            "valueSuffixExpr"
    );

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$.]*)\\b");
    private static final Pattern INTERPOLATION_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private final JsonLogicExpressionValidator jsonLogicValidator;

    public PraxisExpressionStaticValidator() {
        this.jsonLogicValidator = new JsonLogicExpressionValidator();
    }

    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult success() {
            return new ValidationResult(true, Collections.emptyList());
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, List.copyOf(errors));
        }

        public static ValidationResult failure(String error) {
            return new ValidationResult(false, List.of(error));
        }
    }

    /**
     * Valida uma expressao textual (formula com ou sem '=', ou interpolacao '${...}')
     * contra o conjunto de propriedades validas do schema.
     */
    public ValidationResult validateExpression(String rawExpr, Set<String> validProperties, String contextPath) {
        if (rawExpr == null || rawExpr.isBlank()) {
            return ValidationResult.success();
        }

        List<String> errors = new ArrayList<>();
        String expr = rawExpr.trim();

        // 1. Validacao sintatica de delimitadores (parenteses, chaves, colchetes, aspas)
        validateDelimiters(expr, errors);

        // 2. Validacao de operador ternario (? :)
        validateTernaryOperators(expr, errors);

        // Se houver erro de sintaxe estrutural, interrompe para nao gerar falsos positivos de propriedades
        if (!errors.isEmpty()) {
            return ValidationResult.failure(errors);
        }

        // 3. Extracao e validacao de propriedades de dominio referenciadas
        if (validProperties != null && !validProperties.isEmpty()) {
            Set<String> referencedProps = extractReferencedProperties(expr);
            for (String prop : referencedProps) {
                if (!validProperties.contains(prop)) {
                    errors.add(String.format("Referenced property '%s' in %s does not exist in schema. Available properties: %s",
                            prop, contextPath != null ? contextPath : "expression", validProperties));
                }
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Valida um objeto JsonLogic (Map) contra os operadores canonicos e propriedades validas do schema.
     */
    public ValidationResult validateJsonLogic(Map<?, ?> jsonLogic, Set<String> validProperties, String contextPath) {
        if (jsonLogic == null || jsonLogic.isEmpty()) {
            return ValidationResult.success();
        }

        List<String> errors = new ArrayList<>();

        // Valida estrutura canônica JsonLogic
        try {
            jsonLogicValidator.validateCondition(jsonLogic, contextPath != null ? contextPath : "condition", false);
        } catch (IllegalArgumentException ex) {
            errors.add(ex.getMessage());
            return ValidationResult.failure(errors);
        }

        // Valida referencias a variaveis contra as propriedades validas do schema
        if (validProperties != null && !validProperties.isEmpty()) {
            Set<String> vars = extractJsonLogicVariables(jsonLogic);
            for (String var : vars) {
                if (!validProperties.contains(var)) {
                    errors.add(String.format("JsonLogic variable '%s' in %s does not exist in schema. Available properties: %s",
                            var, contextPath != null ? contextPath : "condition", validProperties));
                }
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Valida todas as expressoes presentes em um Schema OpenAPI enriquecido com x-ui.
     */
    public List<String> validateSchemaExpressions(String schemaName, Schema<?> schema, boolean strict) {
        return validateSchemaExpressions(schemaName, schema, Collections.emptyMap(), strict);
    }

    /**
     * Valida todas as expressoes presentes em um Schema OpenAPI enriquecido com x-ui,
     * resolvendo referencias {@code $ref} em {@code referencedSchemas} quando fornecidas.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<String> validateSchemaExpressions(
            String schemaName,
            Schema<?> schema,
            Map<String, Schema> referencedSchemas,
            boolean strict
    ) {
        if (schema == null) {
            return Collections.emptyList();
        }
        Map<String, Schema> safeRefs = referencedSchemas != null ? referencedSchemas : Collections.emptyMap();
        boolean hasProps = schema.getProperties() != null && !schema.getProperties().isEmpty();
        boolean hasRootUi = schema.getExtensions() != null && schema.getExtensions().get("x-ui") instanceof Map<?, ?>;
        if (!hasProps && !hasRootUi) {
            return Collections.emptyList();
        }

        Map<String, Schema> properties = hasProps ? schema.getProperties() : Collections.emptyMap();
        Set<String> validPropNames = properties.keySet();
        Set<String> declaredGroupsLower = new LinkedHashSet<>();
        collectDeclaredGroupsRecursive(
                schema,
                safeRefs,
                declaredGroupsLower,
                Collections.newSetFromMap(new IdentityHashMap<>())
        );
        List<String> schemaErrors = new ArrayList<>();

        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String propName = entry.getKey();
            Schema<?> propSchema = entry.getValue();
            if (propSchema == null || propSchema.getExtensions() == null) {
                continue;
            }

            Object rawUi = propSchema.getExtensions().get("x-ui");
            if (!(rawUi instanceof Map<?, ?> uiMap)) {
                continue;
            }

            // Inspeciona presentation.visualization
            Object rawPresentation = uiMap.get("presentation");
            if (rawPresentation instanceof Map<?, ?> presentation) {
                Object rawViz = presentation.get("visualization");
                if (rawViz instanceof Map<?, ?> viz) {
                    for (String exprKey : VISUALIZATION_EXPR_KEYS) {
                        validateVizField(exprKey, viz.get(exprKey), validPropNames, schemaName, propName, schemaErrors);
                    }
                }
            }

            // Inspeciona validacoes e condicoes JsonLogic se presentes
            Object rawRules = uiMap.get("rules");
            if (rawRules instanceof Map<?, ?> rules) {
                Object rawSlotOverrides = rules.get("slotOverrides");
                if (rawSlotOverrides instanceof List<?> slotOverrides) {
                    for (int i = 0; i < slotOverrides.size(); i++) {
                        Object rule = slotOverrides.get(i);
                        if (rule instanceof Map<?, ?> ruleMap && ruleMap.get("when") instanceof Map<?, ?> whenMap) {
                            ValidationResult res = validateJsonLogic(whenMap, validPropNames, schemaName + "." + propName + ".rules.slotOverrides[" + i + "].when");
                            schemaErrors.addAll(res.errors());
                        }
                    }
                }

                validateRuleExpressionOrLogic("visible", rules.get("visible"), validPropNames, schemaName, propName, schemaErrors);
                validateRuleExpressionOrLogic("disabled", rules.get("disabled"), validPropNames, schemaName, propName, schemaErrors);
                validateRuleExpressionOrLogic("required", rules.get("required"), validPropNames, schemaName, propName, schemaErrors);
                validateRuleExpressionOrLogic("computed", rules.get("computed"), validPropNames, schemaName, propName, schemaErrors);
            }
        }

        // Inspeciona x-ui.calculationMemory e x-ui.formLayout no nivel raiz do DTO (PLAT-EVO-20)
        if (hasRootUi && schema.getExtensions().get("x-ui") instanceof Map<?, ?> rootUiMap) {
            validateCalculationMemoryExpressions(rootUiMap.get("calculationMemory"), validPropNames, schemaName, schemaErrors);
            validateFormLayoutMetadata(rootUiMap.get("formLayout"), declaredGroupsLower, schemaName, schemaErrors);
        }

        if (!schemaErrors.isEmpty()) {
            if (strict) {
                throw new InvalidDomainExpressionException(schemaName, "schema", null, String.join("; ", schemaErrors));
            } else {
                for (String err : schemaErrors) {
                    LOGGER.error("Praxis expression validation error: {}", err);
                }
            }
        }

        return schemaErrors;
    }

    /**
     * Resolve uma classe DTO usando {@link CustomOpenApiResolver} e valida todas as expressoes
     * emitidas em {@code x-ui.presentation.visualization} e {@code x-ui.rules}.
     */
    public List<String> validateDtoClass(Class<?> dtoClass, boolean strict) {
        if (dtoClass == null) {
            return Collections.emptyList();
        }
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomOpenApiResolver(new ObjectMapper().findAndRegisterModules()));
        ResolvedSchema resolved = converters.resolveAsResolvedSchema(new AnnotatedType(dtoClass).resolveAsRef(false));
        if (resolved == null || resolved.schema == null) {
            return Collections.emptyList();
        }
        return validateSchemaExpressions(
                dtoClass.getSimpleName(),
                resolved.schema,
                resolved.referencedSchemas,
                strict
        );
    }

    /**
     * Resolve e valida uma colecao de classes DTO usando uma instancia compartilhada de {@link CustomOpenApiResolver}.
     */
    public List<String> validateDtoClasses(Collection<Class<?>> dtoClasses, boolean strict) {
        if (dtoClasses == null || dtoClasses.isEmpty()) {
            return Collections.emptyList();
        }
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomOpenApiResolver(new ObjectMapper().findAndRegisterModules()));
        List<String> allErrors = new ArrayList<>();
        for (Class<?> dtoClass : dtoClasses) {
            if (dtoClass == null) {
                continue;
            }
            ResolvedSchema resolved = converters.resolveAsResolvedSchema(new AnnotatedType(dtoClass).resolveAsRef(false));
            if (resolved != null && resolved.schema != null) {
                allErrors.addAll(validateSchemaExpressions(
                        dtoClass.getSimpleName(),
                        resolved.schema,
                        resolved.referencedSchemas,
                        strict
                ));
            }
        }
        return allErrors;
    }

    private void validateRuleExpressionOrLogic(String ruleName, Object ruleValue, Set<String> validProps,
                                               String schemaName, String propName, List<String> errors) {
        if (ruleValue == null) {
            return;
        }
        String context = schemaName + "." + propName + ".rules." + ruleName;
        if (ruleValue instanceof String exprStr) {
            if (!exprStr.isBlank()) {
                ValidationResult result = validateExpression(exprStr, validProps, context);
                errors.addAll(result.errors());
            }
        } else if (ruleValue instanceof Map<?, ?> logicMap) {
            Map<?, ?> targetMap = (logicMap.get("when") instanceof Map<?, ?> whenMap) ? whenMap : logicMap;
            ValidationResult result = validateJsonLogic(targetMap, validProps, context);
            errors.addAll(result.errors());
        }
    }

    private void validateVizField(String fieldName, Object fieldValue, Set<String> validProps,
                                  String schemaName, String propName, List<String> errors) {
        if (fieldValue instanceof String exprStr && !exprStr.isBlank()) {
            String context = schemaName + "." + propName + "." + fieldName;
            ValidationResult result = validateExpression(exprStr, validProps, context);
            errors.addAll(result.errors());
        }
    }

    private void validateCalculationMemoryExpressions(
            Object rawCalcMemory,
            Set<String> validProps,
            String schemaName,
            List<String> errors
    ) {
        if (rawCalcMemory == null) {
            return;
        }
        List<?> trailsList = null;
        if (rawCalcMemory instanceof Map<?, ?> calcMap) {
            for (String key : CALCULATION_MEMORY_SECTION_EXPR_KEYS) {
                Object rawVal = calcMap.get(key);
                if (rawVal instanceof String expr && !expr.isBlank()) {
                    ValidationResult res = validateExpression(expr, validProps, schemaName + ".calculationMemory." + key);
                    errors.addAll(res.errors());
                }
            }
            if (calcMap.get("trails") instanceof List<?> list) {
                trailsList = list;
            }
        } else if (rawCalcMemory instanceof List<?> list) {
            trailsList = list;
        }

        if (trailsList == null) {
            return;
        }
        for (int i = 0; i < trailsList.size(); i++) {
            if (!(trailsList.get(i) instanceof Map<?, ?> trailMap)) {
                continue;
            }
            String trailId = trailMap.get("id") instanceof String id && !id.isBlank() ? id : String.valueOf(i);
            String trailPrefix = schemaName + ".calculationMemory.trails[" + trailId + "]";
            for (String exprKey : CALCULATION_TRAIL_EXPR_KEYS) {
                if (trailMap.get(exprKey) instanceof String expr && !expr.isBlank()) {
                    ValidationResult res = validateExpression(expr, validProps, trailPrefix + "." + exprKey);
                    errors.addAll(res.errors());
                }
            }
            if (trailMap.get("baseExpr") instanceof String baseExpr && !baseExpr.isBlank()) {
                String normalizedBase = baseExpr.replaceAll("\\s+", "");
                for (String duplicateCandidateKey : List.of("additionExpr", "deductionExpr")) {
                    if (trailMap.get(duplicateCandidateKey) instanceof String candidateExpr
                            && !candidateExpr.isBlank()
                            && normalizedBase.equals(candidateExpr.replaceAll("\\s+", ""))) {
                        errors.add(trailPrefix + "." + duplicateCandidateKey
                                + " duplicates baseExpr ('" + baseExpr.trim()
                                + "'); avoid repeating the base parcel as an adjustment.");
                    }
                }
            }
            if (trailMap.get("steps") instanceof List<?> steps) {
                for (int s = 0; s < steps.size(); s++) {
                    if (steps.get(s) instanceof Map<?, ?> stepMap) {
                        if (stepMap.get("expr") instanceof String expr && !expr.isBlank()) {
                            errors.addAll(validateExpression(expr, validProps, trailPrefix + ".steps[" + s + "].expr").errors());
                        }
                        if (stepMap.get("valueExpr") instanceof String vExpr && !vExpr.isBlank()) {
                            errors.addAll(validateExpression(vExpr, validProps, trailPrefix + ".steps[" + s + "].valueExpr").errors());
                        }
                        if (stepMap.get("hintExpr") instanceof String hExpr && !hExpr.isBlank()) {
                            errors.addAll(validateExpression(hExpr, validProps, trailPrefix + ".steps[" + s + "].hintExpr").errors());
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectDeclaredGroupsRecursive(
            Schema<?> currentSchema,
            Map<String, Schema> referencedSchemas,
            Set<String> outGroupsLower,
            Set<Schema<?>> visited
    ) {
        if (currentSchema == null || !visited.add(currentSchema)) {
            return;
        }
        if (currentSchema.getExtensions() != null
                && currentSchema.getExtensions().get("x-ui") instanceof Map<?, ?> uiMap) {
            Object rawGroup = uiMap.get("group");
            if (rawGroup instanceof String groupStr && !groupStr.isBlank()) {
                outGroupsLower.add(groupStr.trim().toLowerCase(Locale.ROOT));
            }
        }
        String ref = currentSchema.get$ref();
        if (ref != null && !ref.isBlank() && !referencedSchemas.isEmpty()) {
            String refName = ref.startsWith("#/components/schemas/")
                    ? ref.substring("#/components/schemas/".length())
                    : ref;
            Schema<?> target = referencedSchemas.get(refName);
            if (target != null) {
                collectDeclaredGroupsRecursive(target, referencedSchemas, outGroupsLower, visited);
            }
        }
        if (currentSchema.getProperties() != null) {
            for (Object child : currentSchema.getProperties().values()) {
                if (child instanceof Schema<?> childSchema) {
                    collectDeclaredGroupsRecursive(childSchema, referencedSchemas, outGroupsLower, visited);
                }
            }
        }
        if (currentSchema.getItems() != null) {
            collectDeclaredGroupsRecursive(currentSchema.getItems(), referencedSchemas, outGroupsLower, visited);
        }
        if (currentSchema.getAllOf() != null) {
            for (Schema<?> part : currentSchema.getAllOf()) {
                collectDeclaredGroupsRecursive(part, referencedSchemas, outGroupsLower, visited);
            }
        }
        if (currentSchema.getOneOf() != null) {
            for (Schema<?> part : currentSchema.getOneOf()) {
                collectDeclaredGroupsRecursive(part, referencedSchemas, outGroupsLower, visited);
            }
        }
        if (currentSchema.getAnyOf() != null) {
            for (Schema<?> part : currentSchema.getAnyOf()) {
                collectDeclaredGroupsRecursive(part, referencedSchemas, outGroupsLower, visited);
            }
        }
    }

    private void validateFormLayoutMetadata(
            Object rawFormLayout,
            Set<String> declaredGroupsLower,
            String schemaName,
            List<String> errors
    ) {
        if (!(rawFormLayout instanceof Map<?, ?> layoutMap)) {
            return;
        }

        Object rawMode = layoutMap.get("mode");
        String mode = "";
        if (rawMode != null) {
            if (!(rawMode instanceof String m) || m.isBlank()) {
                errors.add("FormLayout.mode in " + schemaName
                        + " must be a non-blank string; supported modes: " + VALID_FORM_LAYOUT_MODES + ".");
            } else {
                mode = m.trim().toLowerCase(Locale.ROOT);
                if (!VALID_FORM_LAYOUT_MODES.contains(mode)) {
                    errors.add("FormLayout.mode '" + m.trim() + "' in " + schemaName
                            + " is unsupported; supported modes: " + VALID_FORM_LAYOUT_MODES + ".");
                }
            }
        }

        Object rawTabsAppearance = layoutMap.get("tabsAppearance");
        if (rawTabsAppearance != null) {
            if (!(rawTabsAppearance instanceof String appearance)) {
                errors.add("FormLayout.tabsAppearance in " + schemaName
                        + " must be a string; supported values: " + VALID_FORM_LAYOUT_TABS_APPEARANCES + ".");
            } else if (!appearance.isBlank()
                    && !VALID_FORM_LAYOUT_TABS_APPEARANCES.contains(appearance.trim().toLowerCase(Locale.ROOT))) {
                errors.add("FormLayout.tabsAppearance '" + appearance.trim() + "' in " + schemaName
                        + " is unsupported; supported values: " + VALID_FORM_LAYOUT_TABS_APPEARANCES + ".");
            }
        }

        Object rawStepperInReadMode = layoutMap.get("stepperInReadMode");
        if (rawStepperInReadMode != null) {
            if (!(rawStepperInReadMode instanceof String readMode)) {
                errors.add("FormLayout.stepperInReadMode in " + schemaName
                        + " must be a string; supported values: " + VALID_FORM_LAYOUT_STEPPER_READ_MODES + ".");
            } else if (!readMode.isBlank()
                    && !VALID_FORM_LAYOUT_STEPPER_READ_MODES.contains(readMode.trim().toLowerCase(Locale.ROOT))) {
                errors.add("FormLayout.stepperInReadMode '" + readMode.trim() + "' in " + schemaName
                        + " is unsupported; supported values: " + VALID_FORM_LAYOUT_STEPPER_READ_MODES + ".");
            }
        }

        for (String boolFlag : List.of("accordionMulti", "hideSectionHeaderInTabs", "hideSectionHeaderInStepper", "stepperReviewStep", "draftPersistence")) {
            Object rawFlag = layoutMap.get(boolFlag);
            if (rawFlag != null && !(rawFlag instanceof Boolean)) {
                errors.add("FormLayout." + boolFlag + " in " + schemaName + " must be a boolean.");
            }
        }

        String defaultTabId = layoutMap.get("defaultTabId") instanceof String tabId && !tabId.isBlank()
                ? tabId.trim()
                : null;

        Set<String> sectionIds = new LinkedHashSet<>();
        Object rawSections = layoutMap.get("sections");
        if (rawSections instanceof List<?> sections && !sections.isEmpty()) {
            for (int idx = 0; idx < sections.size(); idx++) {
                if (!(sections.get(idx) instanceof Map<?, ?> sectionMap)) {
                    errors.add("FormLayout sections[" + idx + "] in " + schemaName + " must be an object.");
                    continue;
                }
                String sectionId = sectionMap.get("id") instanceof String id && !id.isBlank()
                        ? id.trim()
                        : null;
                String sectionTitle = sectionMap.get("title") instanceof String title && !title.isBlank()
                        ? title.trim()
                        : null;
                if (sectionId == null || sectionTitle == null) {
                    errors.add("FormLayout sections[" + idx + "] in " + schemaName + " must declare non-blank 'id' and 'title'.");
                    continue;
                }
                if (!sectionIds.add(sectionId)) {
                    errors.add("FormLayout section id '" + sectionId + "' is duplicated in " + schemaName + ".");
                }
                Object rawSecOptional = sectionMap.get("optional");
                if (rawSecOptional != null && !(rawSecOptional instanceof Boolean)) {
                    errors.add("FormLayout section '" + sectionId + "'.optional in " + schemaName + " must be a boolean.");
                }
                if (sectionMap.get("groups") instanceof List<?> groups) {
                    for (Object rawGroup : groups) {
                        if (!(rawGroup instanceof String groupStr) || groupStr.isBlank()) {
                            continue;
                        }
                        String trimmedGroup = groupStr.trim();
                        if (!declaredGroupsLower.contains(trimmedGroup.toLowerCase(Locale.ROOT))) {
                            errors.add("FormLayout section '" + sectionId + "' in " + schemaName
                                    + " references unknown group '" + trimmedGroup
                                    + "'. Available x-ui.group values: " + declaredGroupsLower);
                        }
                    }
                }
            }

            if (defaultTabId != null && !sectionIds.contains(defaultTabId)) {
                errors.add("FormLayout.defaultTabId '" + defaultTabId + "' in " + schemaName
                        + " does not match any declared section id: " + sectionIds);
            }

            if ("stepper".equalsIgnoreCase(mode) && sections.size() < 2) {
                LOGGER.warn(
                        "FormLayout mode 'stepper' in {} declares {} section(s); < 2 visible sections degrade to vertical layout at runtime.",
                        schemaName,
                        sections.size()
                );
            }
        } else if ("stepper".equalsIgnoreCase(mode) && declaredGroupsLower.size() < 2) {
            LOGGER.warn(
                    "FormLayout mode 'stepper' in {} has {} distinct x-ui.group value(s); < 2 visible steps degrade to vertical layout at runtime.",
                    schemaName,
                    declaredGroupsLower.size()
            );
        }

        Object rawSteps = layoutMap.get("steps");
        if (rawSteps != null) {
            if (!(rawSteps instanceof List<?> steps)) {
                errors.add("FormLayout.steps in " + schemaName + " must be a list of step objects.");
            } else {
                Set<String> stepIds = new LinkedHashSet<>();
                for (int idx = 0; idx < steps.size(); idx++) {
                    if (!(steps.get(idx) instanceof Map<?, ?> stepMap)) {
                        errors.add("FormLayout steps[" + idx + "] in " + schemaName + " must be an object.");
                        continue;
                    }
                    String stepId = stepMap.get("id") instanceof String id && !id.isBlank()
                            ? id.trim()
                            : null;
                    if (stepId == null) {
                        errors.add("FormLayout steps[" + idx + "] in " + schemaName + " must declare a non-blank 'id'.");
                        continue;
                    }
                    if (!stepIds.add(stepId)) {
                        errors.add("FormLayout step id '" + stepId + "' is duplicated in " + schemaName + ".");
                    }
                    Object rawStepOptional = stepMap.get("optional");
                    if (rawStepOptional != null && !(rawStepOptional instanceof Boolean)) {
                        errors.add("FormLayout step '" + stepId + "'.optional in " + schemaName + " must be a boolean.");
                    }
                    Object rawSectionIds = stepMap.get("sectionIds");
                    if (!(rawSectionIds instanceof List<?> stepSectionIds) || stepSectionIds.isEmpty()) {
                        errors.add("FormLayout step '" + stepId + "' in " + schemaName + " must declare a non-empty 'sectionIds' list.");
                        continue;
                    }
                    List<String> validStepSectionIds = new ArrayList<>();
                    for (Object rawSecId : stepSectionIds) {
                        if (rawSecId instanceof String secId && !secId.isBlank()) {
                            String trimmedSecId = secId.trim();
                            validStepSectionIds.add(trimmedSecId);
                            if (!sectionIds.isEmpty() && !sectionIds.contains(trimmedSecId)) {
                                errors.add("FormLayout step '" + stepId + "' in " + schemaName
                                        + " references unknown sectionId '" + trimmedSecId
                                        + "'. Declared section ids: " + sectionIds);
                            }
                        }
                    }
                    if (validStepSectionIds.isEmpty()) {
                        errors.add("FormLayout step '" + stepId + "' in " + schemaName + " must declare at least one non-blank sectionId.");
                    }
                }
            }
        }
    }

    // --- Metodos Auxiliares de Analise Sintatica ---

    private void validateDelimiters(String expr, List<String> errors) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (inSingleQuote || inDoubleQuote) {
                continue;
            }

            if (c == '(' || c == '[' || c == '{') {
                stack.push(c);
            } else if (c == ')' || c == ']' || c == '}') {
                if (stack.isEmpty()) {
                    errors.add("Unmatched closing delimiter '" + c + "' in expression: " + expr);
                    return;
                }
                char open = stack.pop();
                if ((c == ')' && open != '(') ||
                    (c == ']' && open != '[') ||
                    (c == '}' && open != '{')) {
                    errors.add("Mismatched delimiters '" + open + "' and '" + c + "' in expression: " + expr);
                    return;
                }
            }
        }

        if (inSingleQuote || inDoubleQuote) {
            errors.add("Unclosed string literal in expression: " + expr);
        } else if (!stack.isEmpty()) {
            errors.add("Unclosed delimiter '" + stack.peek() + "' in expression: " + expr);
        }
    }

    private void validateTernaryOperators(String expr, List<String> errors) {
        String stripped = maskStringLiterals(expr);
        int questionMarks = 0;
        int colons = 0;

        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '?') {
                questionMarks++;
            } else if (c == ':') {
                colons++;
            }
        }

        if (questionMarks != colons) {
            errors.add(String.format("Unbalanced ternary operator (? and : count mismatch: %d '?' vs %d ':') in expression: %s",
                    questionMarks, colons, expr));
        }
    }

    private Set<String> extractReferencedProperties(String expr) {
        Set<String> properties = new LinkedHashSet<>();

        // Se for interpolacao (${...}), extrai os blocos internos
        Matcher interpMatcher = INTERPOLATION_PATTERN.matcher(expr);
        StringBuilder extractedText = new StringBuilder();
        boolean hasInterpolation = false;
        while (interpMatcher.find()) {
            hasInterpolation = true;
            extractedText.append(interpMatcher.group(1)).append(" ");
        }

        String toAnalyze = hasInterpolation ? extractedText.toString() : expr;
        if (toAnalyze.startsWith("=")) {
            toAnalyze = toAnalyze.substring(1).trim();
        }

        // Mascara literais de string
        String masked = maskStringLiterals(toAnalyze);

        Matcher matcher = IDENTIFIER_PATTERN.matcher(masked);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (RESERVED_KEYWORDS.contains(token)) {
                continue;
            }

            // Normaliza prefixos 'row.', 'item.', 'data.', 'form.'
            String propName;
            if (token.startsWith("row.")) {
                propName = token.substring(4);
            } else if (token.startsWith("item.")) {
                propName = token.substring(5);
            } else if (token.startsWith("data.")) {
                propName = token.substring(5);
            } else if (token.startsWith("form.")) {
                propName = token.substring(5);
            } else if (token.contains(".")) {
                // Token encadeado, ex: user.profile -> pega a raiz 'user' se for propriedade do DTO
                propName = token.substring(0, token.indexOf('.'));
            } else {
                propName = token;
            }

            // Pega o primeiro segmento se ainda tiver ponto (ex: row.address.city -> address)
            if (propName.contains(".")) {
                propName = propName.substring(0, propName.indexOf('.'));
            }

            if (!propName.isBlank() && !RESERVED_KEYWORDS.contains(propName) && !isNumeric(propName)) {
                properties.add(propName);
            }
        }

        return properties;
    }

    private String maskStringLiterals(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                sb.append(' ');
                escaped = false;
                continue;
            }
            if (c == '\\') {
                sb.append(' ');
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                sb.append(' ');
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                sb.append(' ');
                continue;
            }
            if (inSingleQuote || inDoubleQuote) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!Character.isDigit(c) && c != '.') return false;
        }
        return true;
    }

    private Set<String> extractJsonLogicVariables(Map<?, ?> map) {
        Set<String> vars = new LinkedHashSet<>();
        extractVariablesRecursive(map, vars);
        return vars;
    }

    private void extractVariablesRecursive(Object obj, Set<String> vars) {
        if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("var".equals(entry.getKey())) {
                    Object val = entry.getValue();
                    if (val instanceof String s && !s.isBlank()) {
                        vars.add(normalizeVarName(s));
                    } else if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) {
                        vars.add(normalizeVarName(s));
                    }
                } else {
                    extractVariablesRecursive(entry.getValue(), vars);
                }
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                extractVariablesRecursive(item, vars);
            }
        }
    }

    private String normalizeVarName(String var) {
        String name = var.trim();
        if (name.startsWith("row.")) {
            name = name.substring(4);
        } else if (name.startsWith("item.")) {
            name = name.substring(5);
        } else if (name.startsWith("data.")) {
            name = name.substring(5);
        } else if (name.startsWith("form.")) {
            name = name.substring(5);
        }
        if (name.contains(".")) {
            name = name.substring(0, name.indexOf('.'));
        }
        return name;
    }
}
