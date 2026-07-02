package org.praxisplatform.uischema.validation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JsonLogicExpressionValidatorParityTest {

    private static final Pattern DESCRIPTOR_PATTERN = Pattern.compile(
            "\\{ operator: '([^']+)', source: 'native', minArgs: (\\d+)(?:, maxArgs: (\\d+))?");
    private static final Pattern DEFAULT_OPERATOR_PATTERN = Pattern.compile(
            "\\{\\s*operator: '([^']+)',([\\s\\S]*?)\\n  \\}",
            Pattern.MULTILINE);
    private static final Pattern MIN_ARGS_PATTERN = Pattern.compile("minArgs: (\\d+)");
    private static final Pattern MAX_ARGS_PATTERN = Pattern.compile("maxArgs: (\\d+)");

    @Test
    void javaOperatorMatrixShouldMatchAngularRuntimeWhenWorkspaceIsAvailable() throws Exception {
        Path angularRuntime = Path.of("..", "praxis-ui-angular", "projects", "praxis-core",
                "src", "lib", "services", "praxis-json-logic.service.ts");
        assumeTrue(Files.exists(angularRuntime),
                "Angular runtime workspace is not available beside praxis-metadata-starter.");

        String source = Files.readString(angularRuntime);
        Map<String, JsonLogicExpressionValidator.OperatorArity> angular = new LinkedHashMap<>();
        collectNativeDescriptors(source, angular);
        collectDefaultOperators(source, angular);

        assertEquals(JsonLogicExpressionValidator.operatorArities(), angular);
    }

    private void collectNativeDescriptors(
            String source,
            Map<String, JsonLogicExpressionValidator.OperatorArity> target) {
        Matcher matcher = DESCRIPTOR_PATTERN.matcher(source);
        while (matcher.find()) {
            target.put(matcher.group(1), arity(matcher.group(2), matcher.group(3)));
        }
    }

    private void collectDefaultOperators(
            String source,
            Map<String, JsonLogicExpressionValidator.OperatorArity> target) {
        int start = source.indexOf("export const DEFAULT_JSON_LOGIC_OPERATORS");
        int end = source.indexOf("const ALL_RULE_CONTEXT_ROOTS");
        String block = source.substring(start, end);

        Matcher matcher = DEFAULT_OPERATOR_PATTERN.matcher(block);
        while (matcher.find()) {
            Matcher minMatcher = MIN_ARGS_PATTERN.matcher(matcher.group(2));
            if (!minMatcher.find()) {
                continue;
            }
            Matcher maxMatcher = MAX_ARGS_PATTERN.matcher(matcher.group(2));
            target.put(
                    matcher.group(1),
                    arity(minMatcher.group(1), maxMatcher.find() ? maxMatcher.group(1) : null));
        }
    }

    private JsonLogicExpressionValidator.OperatorArity arity(String minArgs, String maxArgs) {
        return new JsonLogicExpressionValidator.OperatorArity(
                Integer.parseInt(minArgs),
                maxArgs == null ? null : Integer.parseInt(maxArgs));
    }
}
