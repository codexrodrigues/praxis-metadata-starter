package org.praxisplatform.uischema.command;

import java.util.Map;

/**
 * Public message emitted by a governed resource command.
 */
public record ResourceCommandMessage(
        ResourceCommandErrorCategory category,
        String code,
        String message,
        String target,
        Map<String, Object> metadata
) {

    public ResourceCommandMessage {
        category = category == null ? ResourceCommandErrorCategory.UNEXPECTED_SANITIZED : category;
        code = code == null || code.isBlank() ? category.name() : code;
        message = message == null ? "" : message;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
