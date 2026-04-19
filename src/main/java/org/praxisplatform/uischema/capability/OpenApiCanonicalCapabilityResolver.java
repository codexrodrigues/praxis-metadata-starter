package org.praxisplatform.uischema.capability;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Resolve o mapa canonicamente publicado de operacoes a partir do OpenAPI agrupado.
 *
 * <p>
 * A implementacao interpreta o baseline resource-oriented diretamente do documento OpenAPI do
 * grupo, convertendo paths e metodos publicados em um mapa normalizado de capacidades. Ela
 * ignora paths de workflow item-level quando esses paths nao pertencem ao baseline CRUD/query.
 * </p>
 */
public class OpenApiCanonicalCapabilityResolver implements CanonicalCapabilityResolver {

    private static final String PATHS = "paths";

    private final OpenApiDocumentService openApiDocumentService;

    public OpenApiCanonicalCapabilityResolver(OpenApiDocumentService openApiDocumentService) {
        this.openApiDocumentService = openApiDocumentService;
    }

    @Override
    public Map<String, Boolean> resolve(String resourcePath) {
        String group = openApiDocumentService.resolveGroupFromPath(resourcePath);
        JsonNode openApiDocument = openApiDocumentService.getDocumentForGroup(group);
        return resolve(openApiDocument, resourcePath);
    }

    @Override
    public Map<String, Boolean> resolve(JsonNode openApiDocument, String resourcePath) {
        String basePath = normalizePath(resourcePath);
        JsonNode pathsNode = openApiDocument == null ? null : openApiDocument.path(PATHS);

        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("create", hasOperation(pathsNode, basePath, "post"));
        capabilities.put("update", hasOperation(pathsNode, basePath + "/{id}", "put")
                || hasOperation(pathsNode, basePath + "/{id}", "patch")
                || hasItemLevelWriteOperation(pathsNode, basePath, "put", "patch"));
        capabilities.put("delete", hasOperation(pathsNode, basePath + "/{id}", "delete")
                || hasOperation(pathsNode, basePath + "/batch", "delete"));
        capabilities.put("options", hasOperation(pathsNode, basePath + "/options/filter", "post")
                || hasOperation(pathsNode, basePath + "/options/by-ids", "get"));
        capabilities.put("optionSources", hasOperation(pathsNode, basePath + "/option-sources/{sourceKey}/options/filter", "post")
                || hasOperation(pathsNode, basePath + "/option-sources/{sourceKey}/options/by-ids", "get"));
        capabilities.put("byId", hasOperation(pathsNode, basePath + "/{id}", "get"));
        capabilities.put("all", hasOperation(pathsNode, basePath + "/all", "get"));
        capabilities.put("filter", hasOperation(pathsNode, basePath + "/filter", "post"));
        capabilities.put("cursor", hasOperation(pathsNode, basePath + "/filter/cursor", "post"));
        // Export is service opt-in. The base mapping can exist for every resource,
        // so path presence alone must not advertise runtime availability.
        capabilities.put("export", false);
        capabilities.put("statsGroupBy", hasOperation(pathsNode, basePath + "/stats/group-by", "post"));
        capabilities.put("statsTimeSeries", hasOperation(pathsNode, basePath + "/stats/timeseries", "post"));
        capabilities.put("statsDistribution", hasOperation(pathsNode, basePath + "/stats/distribution", "post"));
        return Map.copyOf(capabilities);
    }

    @Override
    public Map<String, CapabilityOperation> resolveCrudOperations(String resourcePath) {
        String group = openApiDocumentService.resolveGroupFromPath(resourcePath);
        JsonNode openApiDocument = openApiDocumentService.getDocumentForGroup(group);
        return resolveCrudOperations(openApiDocument, resourcePath);
    }

    @Override
    public Map<String, CapabilityOperation> resolveCrudOperations(JsonNode openApiDocument, String resourcePath) {
        String basePath = normalizePath(resourcePath);
        JsonNode pathsNode = openApiDocument == null ? null : openApiDocument.path(PATHS);

        Map<String, CapabilityOperation> operations = new LinkedHashMap<>();
        operations.put("create", new CapabilityOperation(
                "create",
                hasOperation(pathsNode, basePath, "post"),
                "COLLECTION",
                hasOperation(pathsNode, basePath, "post") ? "POST" : null,
                "create",
                AvailabilityDecision.allowAll()
        ));
        operations.put("view", new CapabilityOperation(
                "view",
                hasOperation(pathsNode, basePath + "/{id}", "get"),
                "ITEM",
                hasOperation(pathsNode, basePath + "/{id}", "get") ? "GET" : null,
                "self",
                AvailabilityDecision.allowAll()
        ));
        operations.put("edit", new CapabilityOperation(
                "edit",
                resolveEditSupported(pathsNode, basePath),
                "ITEM",
                resolveEditPreferredMethod(pathsNode, basePath),
                "edit",
                AvailabilityDecision.allowAll()
        ));
        operations.put("delete", new CapabilityOperation(
                "delete",
                hasOperation(pathsNode, basePath + "/{id}", "delete"),
                "ITEM",
                hasOperation(pathsNode, basePath + "/{id}", "delete") ? "DELETE" : null,
                "delete",
                AvailabilityDecision.allowAll()
        ));
        return Map.copyOf(operations);
    }

    private boolean hasOperation(JsonNode pathsNode, String path, String operation) {
        if (pathsNode == null || pathsNode.isMissingNode() || !StringUtils.hasText(path) || !StringUtils.hasText(operation)) {
            return false;
        }
        JsonNode pathNode = pathsNode.path(path);
        return pathNode != null && !pathNode.isMissingNode() && pathNode.has(operation);
    }

    private boolean hasItemLevelWriteOperation(JsonNode pathsNode, String basePath, String... methods) {
        if (pathsNode == null || pathsNode.isMissingNode() || !StringUtils.hasText(basePath) || methods == null || methods.length == 0) {
            return false;
        }

        String normalizedBasePath = normalizePath(basePath);
        String itemPrefix = normalizedBasePath + "/{";
        Iterator<String> pathIterator = pathsNode.fieldNames();
        while (pathIterator.hasNext()) {
            String candidatePath = pathIterator.next();
            if (!StringUtils.hasText(candidatePath)
                    || !candidatePath.startsWith(itemPrefix)
                    || isWorkflowLikeItemPath(normalizedBasePath, candidatePath)) {
                continue;
            }

            JsonNode candidateNode = pathsNode.path(candidatePath);
            for (String method : methods) {
                if (StringUtils.hasText(method) && candidateNode.has(method)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean resolveEditSupported(JsonNode pathsNode, String basePath) {
        return resolveEditPreferredMethod(pathsNode, basePath) != null;
    }

    private String resolveEditPreferredMethod(JsonNode pathsNode, String basePath) {
        if (hasOperation(pathsNode, basePath + "/{id}", "patch")) {
            return "PATCH";
        }
        if (hasOperation(pathsNode, basePath + "/{id}", "put")) {
            return "PUT";
        }
        if (hasItemLevelWriteOperation(pathsNode, basePath, "patch")) {
            return "PATCH";
        }
        if (hasItemLevelWriteOperation(pathsNode, basePath, "put")) {
            return "PUT";
        }
        return null;
    }

    private boolean isWorkflowLikeItemPath(String normalizedBasePath, String candidatePath) {
        int itemMarkerStart = normalizedBasePath.length() + 1;
        int itemMarkerEnd = candidatePath.indexOf('}', itemMarkerStart);
        if (itemMarkerEnd < 0) {
            return false;
        }

        String remainder = candidatePath.substring(itemMarkerEnd + 1);
        return remainder.startsWith("/actions/") || remainder.startsWith(":");
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        String normalized = path.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
