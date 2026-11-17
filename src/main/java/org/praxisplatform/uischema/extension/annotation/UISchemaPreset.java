package org.praxisplatform.uischema.extension.annotation;

/**
 * Presets canônicos de apresentação para reduzir repetição declarativa de metadados de UI em DTOs.
 *
 * <p>
 * Presets definem apenas apresentação, controle, ícone e formatação base; valores explícitos em
 * {@link UISchema} sempre prevalecem sobre o preset (incluindo {@link org.praxisplatform.uischema.NumericFormat}).
 * Eles nunca substituem a documentação de domínio em {@code @Schema(description = ...)}.
 * </p>
 *
 * <pre>{@code
 * @Schema(description = "Data de admissão efetiva do colaborador.")
 * @UISchema(preset = UISchemaPreset.LOCAL_DATE)
 * private LocalDate admissionDate;
 *
 * @Schema(description = "Departamento de lotação atual.")
 * @UISchema(preset = UISchemaPreset.ENTITY_LOOKUP, group = "allocation")
 * private Long departmentId;
 * }</pre>
 */
public enum UISchemaPreset {
    NONE("none"),
    ENTERPRISE_ID("enterprise-id"),
    ENTERPRISE_CODE("enterprise-code"),
    ENTERPRISE_NAME("enterprise-name"),
    ENTERPRISE_STATUS("enterprise-status"),
    DATE_RANGE("date-range"),
    MONETARY_AMOUNT("monetary-amount"),
    BOOLEAN_FLAG("boolean-flag"),
    LEGAL_DOCUMENT_REFERENCE("legal-document-reference"),
    TENANT_LABEL("tenant-label"),
    AUDIT_TIMESTAMP("audit-timestamp"),
    CPF_CNPJ("cpf-cnpj"),
    EMAIL_CONTACT("email-contact"),
    PHONE_CONTACT("phone-contact"),
    INTEGER_COUNTER("integer-counter"),
    PERCENTAGE_RATE("percentage-rate"),
    /** Preset para data sem horario usando seletor de data e icone {@code calendar_today}. */
    LOCAL_DATE("local-date"),
    /** Preset para data e hora usando seletor de data e hora e icone {@code schedule}. */
    LOCAL_DATE_TIME("local-date-time"),
    /** Preset base para lookup de entidade governado por {@code optionSource}. */
    ENTITY_LOOKUP("entity-lookup");

    private final String value;

    UISchemaPreset(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
