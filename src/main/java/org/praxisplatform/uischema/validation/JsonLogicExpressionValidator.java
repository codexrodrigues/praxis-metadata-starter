package org.praxisplatform.uischema.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the canonical Praxis Json Logic operator matrix consumed by the
 * Angular runtime before x-ui is published by the metadata starter.
 */
public final class JsonLogicExpressionValidator {

    public record OperatorArity(int minArgs, Integer maxArgs) {
    }

    private static final Map<String, OperatorArity> OPERATOR_ARITIES = createOperatorArities();
    private static final Set<String> ORDERED_COMPARISON_OPERATORS = Set.of(">", ">=", "<", "<=");
    private static final Set<String> NUMERIC_OPERATORS = Set.of("+", "-", "*", "/", "%", "min", "max",
            "round", "ceil", "floor", "abs");
    private static final Set<String> STRING_BINARY_OPERATORS = Set.of("startsWith", "endsWith", "matches");
    private static final Set<String> ARRAY_SOURCE_OPERATORS = Set.of("map", "filter", "reduce", "all", "some", "none");

    public void validateCondition(Object expression, String propertyPath, boolean allowNull) {
        if (expression == null) {
            if (allowNull) {
                return;
            }
            throw invalid(propertyPath, "must be a canonical Json Logic object, but was null");
        }
        if (!(expression instanceof Map<?, ?> expressionMap)) {
            throw invalid(propertyPath, "must be a canonical Json Logic object");
        }
        validateObject(expressionMap, propertyPath, true);
    }

    public static Map<String, OperatorArity> operatorArities() {
        return OPERATOR_ARITIES;
    }

    private void validateValue(Object value, String path, boolean topLevel) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return;
        }
        if (value instanceof List<?> values) {
            if (topLevel) {
                throw invalid(path, "top-level Json Logic condition cannot be an array");
            }
            for (int index = 0; index < values.size(); index++) {
                validateValue(values.get(index), path + "[" + index + "]", false);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            validateObject(map, path, topLevel);
            return;
        }
        throw invalid(path, "contains an unsupported JSON value type: " + value.getClass().getSimpleName());
    }

    private void validateObject(Map<?, ?> expression, String path, boolean topLevel) {
        if (expression.size() != 1) {
            if (topLevel) {
                throw invalid(path, "must contain exactly one Json Logic operator");
            }
            for (Map.Entry<?, ?> entry : expression.entrySet()) {
                validateValue(entry.getValue(), path + "." + entry.getKey(), false);
            }
            return;
        }

        Map.Entry<?, ?> entry = expression.entrySet().iterator().next();
        if (!(entry.getKey() instanceof String operator)) {
            throw invalid(path, "operator key must be a string");
        }

        if ("var".equals(operator)) {
            validateVar(entry.getValue(), path);
            return;
        }

        OperatorArity arity = OPERATOR_ARITIES.get(operator);
        if (arity == null) {
            throw invalid(path, "uses unsupported Json Logic operator `" + operator + "`");
        }

        List<?> args = toArgumentList(entry.getValue());
        validateArity(operator, args.size(), arity, path);
        validateLiteralArgumentShape(operator, args, path);
        for (int index = 0; index < args.size(); index++) {
            validateValue(args.get(index), path + "." + operator + "[" + index + "]", false);
        }
    }

    private void validateVar(Object rawArgs, String path) {
        if (rawArgs instanceof String) {
            return;
        }
        if (rawArgs instanceof List<?> args && args.size() >= 1 && args.size() <= 2) {
            if (!(args.get(0) instanceof String)) {
                throw invalid(path, "`var` requires a string path as the first argument");
            }
            if (args.size() == 2) {
                validateValue(args.get(1), path + ".var[1]", false);
            }
            return;
        }
        throw invalid(path, "`var` requires a string path or [path, defaultValue]");
    }

    private List<?> toArgumentList(Object rawArgs) {
        return rawArgs instanceof List<?> args ? args : List.of(rawArgs);
    }

    private void validateArity(String operator, int count, OperatorArity arity, String path) {
        if (count < arity.minArgs()) {
            throw invalid(path, "operator `" + operator + "` requires at least "
                    + arity.minArgs() + " arguments");
        }
        if (arity.maxArgs() != null && count > arity.maxArgs()) {
            throw invalid(path, "operator `" + operator + "` accepts at most "
                    + arity.maxArgs() + " arguments");
        }
    }

    private void validateLiteralArgumentShape(String operator, List<?> args, String path) {
        if (ORDERED_COMPARISON_OPERATORS.contains(operator)) {
            validateComparableLiteralPair(operator, args, path);
            return;
        }
        if (NUMERIC_OPERATORS.contains(operator)) {
            validateNumericLiteralArguments(operator, args, path);
            return;
        }
        if ("substr".equals(operator)) {
            validateStringLiteralArgument(operator, args.get(0), path, 0);
            validateNumericLiteralArgument(operator, args.get(1), path, 1);
            if (args.size() == 3) {
                validateNumericLiteralArgument(operator, args.get(2), path, 2);
            }
            return;
        }
        if ("in".equals(operator)) {
            validateInLiteralArguments(args, path);
            return;
        }
        if ("contains".equals(operator)) {
            validateContainsLiteralArguments(args, path);
            return;
        }
        if (STRING_BINARY_OPERATORS.contains(operator)) {
            validateStringLiteralArgument(operator, args.get(0), path, 0);
            validateStringLiteralArgument(operator, args.get(1), path, 1);
            return;
        }
        if ("len".equals(operator)) {
            validateLenLiteralArgument(args.get(0), path);
            return;
        }
        if ("jsonGet".equals(operator) || "hasKey".equals(operator)) {
            validateObjectLiteralArgument(operator, args.get(0), path, 0);
            validateStringLiteralArgument(operator, args.get(1), path, 1);
            return;
        }
        if (ARRAY_SOURCE_OPERATORS.contains(operator)) {
            validateArrayLiteralArgument(operator, args.get(0), path, 0);
            return;
        }
        if ("weekdayIn".equals(operator)) {
            validateArrayLiteralArgument(operator, args.get(1), path, 1);
            return;
        }
        if ("inLast".equals(operator)) {
            validateNumericLiteralArgument(operator, args.get(1), path, 1);
            validateStringLiteralArgument(operator, args.get(2), path, 2);
        }
    }

    private void validateComparableLiteralPair(String operator, List<?> args, String path) {
        Object left = args.get(0);
        Object right = args.get(1);
        if (isDynamicExpression(left) || isDynamicExpression(right)) {
            return;
        }
        boolean comparable = (left instanceof Number && right instanceof Number)
                || (left instanceof String && right instanceof String);
        if (!comparable) {
            throw invalid(path, "operator `" + operator + "` requires comparable literal operands");
        }
    }

    private void validateNumericLiteralArguments(String operator, List<?> args, String path) {
        for (int index = 0; index < args.size(); index++) {
            validateNumericLiteralArgument(operator, args.get(index), path, index);
        }
    }

    private void validateNumericLiteralArgument(String operator, Object arg, String path, int index) {
        if (isDynamicExpression(arg)) {
            return;
        }
        if (!(arg instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw invalid(path + "." + operator + "[" + index + "]",
                    "must be a finite numeric literal or expression");
        }
    }

    private void validateStringLiteralArgument(String operator, Object arg, String path, int index) {
        if (isDynamicExpression(arg)) {
            return;
        }
        if (!(arg instanceof String)) {
            throw invalid(path + "." + operator + "[" + index + "]",
                    "must be a string literal or expression");
        }
    }

    private void validateArrayLiteralArgument(String operator, Object arg, String path, int index) {
        if (isDynamicExpression(arg)) {
            return;
        }
        if (!(arg instanceof List<?>)) {
            throw invalid(path + "." + operator + "[" + index + "]",
                    "must be an array literal or expression");
        }
    }

    private void validateObjectLiteralArgument(String operator, Object arg, String path, int index) {
        if (isDynamicExpression(arg)) {
            return;
        }
        if (!(arg instanceof Map<?, ?> map) || isExpressionObject(map)) {
            throw invalid(path + "." + operator + "[" + index + "]",
                    "must be an object literal or expression");
        }
    }

    private void validateInLiteralArguments(List<?> args, String path) {
        Object needle = args.get(0);
        Object haystack = args.get(1);
        if (haystack instanceof String && !isDynamicExpression(needle) && !(needle instanceof String)) {
            throw invalid(path + ".in[0]", "must be a string literal when haystack is a string literal");
        }
        if (!isDynamicExpression(haystack) && !(haystack instanceof String) && !(haystack instanceof List<?>)) {
            throw invalid(path + ".in[1]", "must be a string, array literal, or expression");
        }
    }

    private void validateContainsLiteralArguments(List<?> args, String path) {
        Object container = args.get(0);
        Object candidate = args.get(1);
        if (container instanceof String && !isDynamicExpression(candidate) && !(candidate instanceof String)) {
            throw invalid(path + ".contains[1]", "must be a string literal when container is a string literal");
        }
        if (!isDynamicExpression(container) && !(container instanceof String) && !(container instanceof List<?>)) {
            throw invalid(path + ".contains[0]", "must be a string, array literal, or expression");
        }
    }

    private void validateLenLiteralArgument(Object arg, String path) {
        if (isDynamicExpression(arg)) {
            return;
        }
        if (!(arg instanceof String) && !(arg instanceof List<?>) && !(arg instanceof Map<?, ?> map && !isExpressionObject(map))) {
            throw invalid(path + ".len[0]", "must be a string, array, object literal, or expression");
        }
    }

    private boolean isDynamicExpression(Object value) {
        return value instanceof Map<?, ?> map && isExpressionObject(map);
    }

    private boolean isExpressionObject(Map<?, ?> value) {
        return value.size() == 1 && value.keySet().iterator().next() instanceof String operator
                && OPERATOR_ARITIES.containsKey(operator);
    }

    private IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException(path + " " + reason + ".");
    }

    private static Map<String, OperatorArity> createOperatorArities() {
        Map<String, OperatorArity> arities = new LinkedHashMap<>();
        put(arities, "var", 1, 2);
        put(arities, "==", 2, 2);
        put(arities, "===", 2, 2);
        put(arities, "!=", 2, 2);
        put(arities, "!==", 2, 2);
        put(arities, ">", 2, 2);
        put(arities, ">=", 2, 2);
        put(arities, "<", 2, 2);
        put(arities, "<=", 2, 2);
        put(arities, "!", 1, 1);
        put(arities, "!!", 1, 1);
        put(arities, "and", 1, null);
        put(arities, "or", 1, null);
        put(arities, "if", 2, null);
        put(arities, "in", 2, 2);
        put(arities, "cat", 1, null);
        put(arities, "substr", 2, 3);
        put(arities, "merge", 1, null);
        put(arities, "map", 2, 2);
        put(arities, "filter", 2, 2);
        put(arities, "reduce", 2, 3);
        put(arities, "all", 2, 2);
        put(arities, "some", 2, 2);
        put(arities, "none", 2, 2);
        put(arities, "+", 1, null);
        put(arities, "-", 1, null);
        put(arities, "*", 1, null);
        put(arities, "/", 2, null);
        put(arities, "%", 2, 2);
        put(arities, "min", 1, null);
        put(arities, "max", 1, null);
        put(arities, "contains", 2, 2);
        put(arities, "startsWith", 2, 2);
        put(arities, "endsWith", 2, 2);
        put(arities, "matches", 2, 2);
        put(arities, "isBlank", 1, 1);
        put(arities, "len", 1, 1);
        put(arities, "round", 1, 1);
        put(arities, "ceil", 1, 1);
        put(arities, "floor", 1, 1);
        put(arities, "abs", 1, 1);
        put(arities, "coalesce", 1, null);
        put(arities, "now", 0, 0);
        put(arities, "date", 1, 1);
        put(arities, "yearsSince", 1, 1);
        put(arities, "monthsSince", 1, 1);
        put(arities, "daysSince", 1, 1);
        put(arities, "toNumber", 1, 1);
        put(arities, "stringify", 1, 1);
        put(arities, "jsonGet", 2, 2);
        put(arities, "hasKey", 2, 2);
        put(arities, "isToday", 1, 1);
        put(arities, "inLast", 3, 3);
        put(arities, "weekdayIn", 2, 2);
        return Map.copyOf(arities);
    }

    private static void put(Map<String, OperatorArity> arities, String operator, int min, Integer max) {
        arities.put(operator, new OperatorArity(min, max));
    }
}
