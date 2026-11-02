package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public capabilities advertised by an entity lookup source.
 */
public record LookupCapabilities(
        boolean filter,
        boolean byIds,
        boolean detail,
        boolean create,
        boolean edit,
        boolean navigateToDetail,
        boolean multiSelect,
        boolean recent,
        boolean favorites,
        boolean auditSnapshot
) {

    /** Read, search and inspect an existing directory; does not advertise writes or private UI storage. */
    public static LookupCapabilities standardDirectory() {
        return new LookupCapabilities(true, true, true, false, false, true, true, false, false, false);
    }

    public static LookupCapabilities defaults() {
        return new LookupCapabilities(true, true, false, false, false, false, false, false, false, false);
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("filter", filter);
        metadata.put("byIds", byIds);
        metadata.put("detail", detail);
        metadata.put("create", create);
        metadata.put("edit", edit);
        metadata.put("navigateToDetail", navigateToDetail);
        metadata.put("multiSelect", multiSelect);
        metadata.put("recent", recent);
        metadata.put("favorites", favorites);
        metadata.put("auditSnapshot", auditSnapshot);
        return metadata;
    }
}
