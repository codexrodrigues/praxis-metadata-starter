package org.praxisplatform.uischema.validation;

import io.swagger.v3.oas.models.media.Schema;
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

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
            "true", "false", "null", "undefined", "NaN", "Infinity",
            "Math", "Number", "String", "Date", "Array", "Boolean", "Object",
            "parseInt", "parseFloat", "isNaN", "isFinite", "encodeURIComponent", "decodeURIComponent",
            "typeof", "instanceof", "in", "new", "this",
            // Semantic tones permitidos em expressoes
            "neutral", "info", "success", "warning", "danger", "critical"
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
    @SuppressWarnings("unchecked")
    public List<String> validateSchemaExpressions(String schemaName, Schema<?> schema, boolean strict) {
        if (schema == null || schema.getProperties() == null || schema.getProperties().isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Schema> properties = schema.getProperties();
        Set<String> validPropNames = properties.keySet();
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
                    validateVizField("toneExpr", viz.get("toneExpr"), validPropNames, schemaName, propName, schemaErrors);
                    validateVizField("valueExpr", viz.get("valueExpr"), validPropNames, schemaName, propName, schemaErrors);
                    validateVizField("targetExpr", viz.get("targetExpr"), validPropNames, schemaName, propName, schemaErrors);
                    validateVizField("totalExpr", viz.get("totalExpr"), validPropNames, schemaName, propName, schemaErrors);
                    validateVizField("baselineExpr", viz.get("baselineExpr"), validPropNames, schemaName, propName, schemaErrors);
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
