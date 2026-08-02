package org.praxisplatform.uischema.action;

/**
 * Canonical payload bindings for a collection selection and its per-record versions.
 */
public record ActionSelectionPolicy(
        String idsField,
        String versionsField,
        Integer maxItems
) {
    public ActionSelectionPolicy {
        idsField = blankToNull(idsField);
        versionsField = blankToNull(versionsField);
        maxItems = maxItems == null || maxItems <= 0 ? null : maxItems;
        if (versionsField != null && idsField == null) {
            throw new IllegalArgumentException("versionsField requires idsField");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
