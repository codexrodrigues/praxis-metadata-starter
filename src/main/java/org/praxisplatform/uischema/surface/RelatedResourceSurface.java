package org.praxisplatform.uischema.surface;

import java.util.List;

/**
 * Public metadata for a surface that projects a child collection from a parent item.
 */
public record RelatedResourceSurface(
        String parentResourceKey,
        String parentIdPathVariable,
        String childResourceKey,
        String childResourcePath,
        String childParentField,
        boolean selectable,
        String selectionKeyField,
        List<RelatedResourceChildOperation> childOperations
) {
    public RelatedResourceSurface {
        parentResourceKey = normalize(parentResourceKey);
        parentIdPathVariable = normalize(parentIdPathVariable);
        childResourceKey = normalize(childResourceKey);
        childResourcePath = normalizePath(childResourcePath);
        childParentField = normalize(childParentField);
        selectionKeyField = normalize(selectionKeyField);
        if (selectionKeyField == null) {
            selectionKeyField = "id";
        }
        childOperations = childOperations == null ? List.of() : List.copyOf(childOperations);
        boolean hasAnyRelatedMetadata = childResourceKey != null
                || childResourcePath != null
                || childParentField != null
                || selectable
                || !childOperations.isEmpty();
        boolean hasRequiredBinding = childResourceKey != null
                && childResourcePath != null
                && childParentField != null;
        if (hasAnyRelatedMetadata && !hasRequiredBinding) {
            throw new IllegalArgumentException(
                    "Related resource surfaces require childResourceKey, childResourcePath and childParentField."
            );
        }
    }

    public boolean present() {
        return childResourceKey != null || childResourcePath != null || childParentField != null || !childOperations.isEmpty();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizePath(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
