package org.praxisplatform.uischema.command;

import java.util.Map;

/**
 * Public command execution envelope understood by Praxis and implemented by a host provider.
 */
public record ResourceCommandExecutionRequest(
        String resourceKey,
        String resourcePath,
        String commandId,
        ResourceCommandScope scope,
        Object resourceId,
        Object payload,
        ResourceCommandResponsePolicy responsePolicy,
        Map<String, Object> publicMetadata
) {

    public ResourceCommandExecutionRequest {
        if (resourceKey == null || resourceKey.isBlank()) {
            throw new IllegalArgumentException("resourceKey is required");
        }
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath is required");
        }
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId is required");
        }
        scope = scope == null ? ResourceCommandScope.COLLECTION : scope;
        if (scope == ResourceCommandScope.ITEM && resourceId == null) {
            throw new IllegalArgumentException("resourceId is required for item-scoped commands");
        }
        responsePolicy = responsePolicy == null
                ? ResourceCommandResponsePolicy.READ_AFTER_WRITE
                : responsePolicy;
        publicMetadata = publicMetadata == null ? Map.of() : Map.copyOf(publicMetadata);
    }

    public static ResourceCommandExecutionRequest collection(
            String resourceKey,
            String resourcePath,
            String commandId,
            Object payload,
            ResourceCommandResponsePolicy responsePolicy
    ) {
        return new ResourceCommandExecutionRequest(
                resourceKey,
                resourcePath,
                commandId,
                ResourceCommandScope.COLLECTION,
                null,
                payload,
                responsePolicy,
                Map.of()
        );
    }

    public static ResourceCommandExecutionRequest item(
            String resourceKey,
            String resourcePath,
            String commandId,
            Object resourceId,
            Object payload,
            ResourceCommandResponsePolicy responsePolicy
    ) {
        return new ResourceCommandExecutionRequest(
                resourceKey,
                resourcePath,
                commandId,
                ResourceCommandScope.ITEM,
                resourceId,
                payload,
                responsePolicy,
                Map.of()
        );
    }
}
