package org.praxisplatform.uischema.validation;

/**
 * Exceção lançada quando uma expressão declarativa de domínio (em @MicroVisualization,
 * toneExpr, valueExpr ou JsonLogic) possui sintaxe inválida ou referencia propriedades inexistentes no DTO/Schema.
 */
public class InvalidDomainExpressionException extends RuntimeException {

    private final String schemaName;
    private final String propertyName;
    private final String expression;
    private final String details;

    public InvalidDomainExpressionException(String schemaName, String propertyName, String expression, String details) {
        super(formatMessage(schemaName, propertyName, expression, details));
        this.schemaName = schemaName;
        this.propertyName = propertyName;
        this.expression = expression;
        this.details = details;
    }

    private static String formatMessage(String schemaName, String propertyName, String expression, String details) {
        StringBuilder sb = new StringBuilder();
        sb.append("Praxis Domain Expression Error on ");
        if (schemaName != null && !schemaName.isBlank()) {
            sb.append(schemaName).append(".");
        }
        sb.append(propertyName != null ? propertyName : "<unknown>")
          .append(": ")
          .append(details);
        if (expression != null && !expression.isBlank()) {
            sb.append(" [Expression: '").append(expression).append("']");
        }
        return sb.toString();
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public String getExpression() {
        return expression;
    }

    public String getDetails() {
        return details;
    }
}
