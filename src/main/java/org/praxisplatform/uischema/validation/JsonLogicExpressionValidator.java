package org.praxisplatform.uischema.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the canonical Praxis Json Logic operator matrix consumed by the
 * Angular runtime before x-ui is published by the metadata starter.
 */
public final class JsonLogicExpressionValidator {

    public record OperatorArity(int minArgs, Integer maxArgs) {
    }

    private static final Map<String, OperatorArity> OPERATOR_ARITIES = createOperatorArities();

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
