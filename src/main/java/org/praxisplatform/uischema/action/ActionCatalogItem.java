package org.praxisplatform.uischema.action;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.praxisplatform.uischema.capability.AvailabilityDecision;

import java.util.List;

/**
 * Representacao serializavel de uma action semantica pronta para clientes documentais e runtimes UI.
 *
 * <p>
 * Cada item referencia uma operacao HTTP real que representa um comando explicito de negocio.
 * Quando a operacao tiver payload de entrada ou saida, os campos de schema apontam para os
 * schemas canonicos da operacao, sem redefini-los inline no catalogo.
 * </p>
 */
public record ActionCatalogItem(
        String id,
        String resourceKey,
        ActionScope scope,
        String title,
        String description,
        String operationId,
        String path,
        String method,
        String requestSchemaId,
        String requestSchemaUrl,
        String responseSchemaId,
        String responseSchemaUrl,
        List<String> allowedStates,
        AvailabilityDecision availability,
        int order,
        String successMessage,
        List<String> tags,
        ActionExecutionContract execution,
        @JsonInclude(JsonInclude.Include.NON_NULL) String legalPurpose,
        @JsonInclude(JsonInclude.Include.NON_NULL) String legalBasisRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String icon,
        @JsonInclude(JsonInclude.Include.NON_NULL) String tone,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> modifiedFields,
        @JsonInclude(JsonInclude.Include.NON_NULL) String disallowedStateReason
) {
    public ActionCatalogItem {
        legalPurpose = legalPurpose != null && !legalPurpose.isBlank() ? legalPurpose.trim() : null;
        legalBasisRef = legalBasisRef != null && !legalBasisRef.isBlank() ? legalBasisRef.trim() : null;
        icon = icon != null && !icon.isBlank() ? icon.trim() : null;
        tone = tone != null && !tone.isBlank() ? tone.trim() : null;
        modifiedFields = modifiedFields == null || modifiedFields.isEmpty() ? null : List.copyOf(modifiedFields);
        disallowedStateReason = disallowedStateReason != null && !disallowedStateReason.isBlank()
                ? disallowedStateReason.trim()
                : null;
    }

    public ActionCatalogItem(
            String id,
            String resourceKey,
            ActionScope scope,
            String title,
            String description,
            String operationId,
            String path,
            String method,
            String requestSchemaId,
            String requestSchemaUrl,
            String responseSchemaId,
            String responseSchemaUrl,
            List<String> allowedStates,
            AvailabilityDecision availability,
            int order,
            String successMessage,
            List<String> tags,
            ActionExecutionContract execution,
            String legalPurpose,
            String legalBasisRef
    ) {
        this(
                id,
                resourceKey,
                scope,
                title,
                description,
                operationId,
                path,
                method,
                requestSchemaId,
                requestSchemaUrl,
                responseSchemaId,
                responseSchemaUrl,
                allowedStates,
                availability,
                order,
                successMessage,
                tags,
                execution,
                legalPurpose,
                legalBasisRef,
                null,
                null,
                null,
                null
        );
    }

    public ActionCatalogItem(
            String id,
            String resourceKey,
            ActionScope scope,
            String title,
            String description,
            String operationId,
            String path,
            String method,
            String requestSchemaId,
            String requestSchemaUrl,
            String responseSchemaId,
            String responseSchemaUrl,
            List<String> allowedStates,
            AvailabilityDecision availability,
            int order,
            String successMessage,
            List<String> tags,
            ActionExecutionContract execution
    ) {
        this(
                id,
                resourceKey,
                scope,
                title,
                description,
                operationId,
                path,
                method,
                requestSchemaId,
                requestSchemaUrl,
                responseSchemaId,
                responseSchemaUrl,
                allowedStates,
                availability,
                order,
                successMessage,
                tags,
                execution,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
