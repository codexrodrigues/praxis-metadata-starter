package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selection rules advertised by an entity lookup source.
 */
public record LookupSelectionPolicy(
        String selectablePropertyPath,
        String statusPropertyPath,
        List<String> allowedStatuses,
        List<String> blockedStatuses,
        boolean allowRetainInvalidExistingValue,
        String disabledReasonTemplate,
        String validationMessageTemplate
) {

    public LookupSelectionPolicy {
        selectablePropertyPath = normalize(selectablePropertyPath);
        statusPropertyPath = normalize(statusPropertyPath);
        allowedStatuses = allowedStatuses == null ? List.of() : List.copyOf(allowedStatuses);
        blockedStatuses = blockedStatuses == null ? List.of() : List.copyOf(blockedStatuses);
        disabledReasonTemplate = normalize(disabledReasonTemplate);
        validationMessageTemplate = normalize(validationMessageTemplate);
    }

    public boolean isEmpty() {
        return selectablePropertyPath == null
                && statusPropertyPath == null
                && allowedStatuses.isEmpty()
                && blockedStatuses.isEmpty()
                && !allowRetainInvalidExistingValue
                && disabledReasonTemplate == null
                && validationMessageTemplate == null;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (selectablePropertyPath != null) {
            metadata.put("selectablePropertyPath", selectablePropertyPath);
        }
        if (statusPropertyPath != null) {
            metadata.put("statusPropertyPath", statusPropertyPath);
        }
        if (!allowedStatuses.isEmpty()) {
            metadata.put("allowedStatuses", allowedStatuses);
        }
        if (!blockedStatuses.isEmpty()) {
            metadata.put("blockedStatuses", blockedStatuses);
        }
        metadata.put("allowRetainInvalidExistingValue", allowRetainInvalidExistingValue);
        if (disabledReasonTemplate != null) {
            metadata.put("disabledReasonTemplate", disabledReasonTemplate);
        }
        if (validationMessageTemplate != null) {
            metadata.put("validationMessageTemplate", validationMessageTemplate);
        }
        return metadata;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
