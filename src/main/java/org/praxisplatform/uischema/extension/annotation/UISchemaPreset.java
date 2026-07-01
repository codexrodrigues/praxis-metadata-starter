package org.praxisplatform.uischema.extension.annotation;

/**
 * Canonical presentation presets for repetitive enterprise UI metadata.
 *
 * <p>
 * Presets only describe presentation and value handling. They must not replace
 * explicit {@code @Schema(description = ...)} domain documentation.
 * </p>
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
    AUDIT_TIMESTAMP("audit-timestamp");

    private final String value;

    UISchemaPreset(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
