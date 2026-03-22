package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;

@Component
public class OpenApiDocsSupport {

    @Value("${app.openapi.internal-base-url:}")
    private String openApiInternalBaseUrl;

    @Autowired(required = false)
    private OpenApiGroupResolver openApiGroupResolver;

    public String resolveGroupFromPath(String path) {
        if (!StringUtils.hasText(path)) {
            return "application";
        }
        if (openApiGroupResolver != null) {
            String resolved = openApiGroupResolver.resolveGroup(path);
            if (StringUtils.hasText(resolved)) {
                return resolved;
            }
        }

        String[] segments = path.split("/");
        if (segments.length >= 4) {
            return String.join("-", java.util.Arrays.copyOfRange(segments, 1, 4));
        }
        if (segments.length >= 2 && StringUtils.hasText(segments[1])) {
            return segments[1];
        }
        return "application";
    }

    public JsonNode fetchOpenApiDocument(RestTemplate restTemplate, String openApiBasePath, String group, Logger logger) {
        String baseUrl = resolveOpenApiBaseUrl();
        String baseDocUrl = baseUrl + openApiBasePath;
        String groupDocUrl = StringUtils.hasText(group)
                ? baseDocUrl + "/" + UriUtils.encodePathSegment(group, StandardCharsets.UTF_8)
                : baseDocUrl;

        try {
            JsonNode groupDoc = restTemplate.getForObject(groupDocUrl, JsonNode.class);
            if (groupDoc != null) {
                return groupDoc;
            }
            logger.warn("OpenAPI group document {} returned null; falling back to {}", groupDocUrl, baseDocUrl);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                logger.warn("OpenAPI group document {} not found (group={}); falling back to {}",
                        groupDocUrl, group, baseDocUrl);
            } else {
                throw new IllegalStateException(
                        "Failed to fetch OpenAPI group document " + groupDocUrl + " (status " + ex.getStatusCode() + ")",
                        ex
                );
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fetch OpenAPI group document " + groupDocUrl, ex);
        }

        JsonNode fallbackDoc = restTemplate.getForObject(baseDocUrl, JsonNode.class);
        if (fallbackDoc == null) {
            throw new IllegalStateException("OpenAPI document is null for group " + group + " and fallback " + baseDocUrl);
        }
        return fallbackDoc;
    }

    public JsonNode selectPreferredContentNode(JsonNode contentRoot) {
        if (contentRoot == null || contentRoot.isMissingNode()) {
            return contentRoot;
        }
        JsonNode applicationJson = contentRoot.path("application/json");
        if (!applicationJson.isMissingNode()) {
            return applicationJson;
        }
        JsonNode any = contentRoot.path("*/*");
        if (!any.isMissingNode()) {
            return any;
        }
        Iterator<JsonNode> values = contentRoot.elements();
        return values.hasNext() ? values.next() : contentRoot;
    }

    public String inferMediaType(JsonNode contentRoot) {
        if (contentRoot == null || contentRoot.isMissingNode()) {
            return null;
        }
        if (!contentRoot.path("application/json").isMissingNode()) {
            return "application/json";
        }
        if (!contentRoot.path("*/*").isMissingNode()) {
            return "*/*";
        }
        Iterator<String> fieldNames = contentRoot.fieldNames();
        return fieldNames.hasNext() ? fieldNames.next() : null;
    }

    private String resolveOpenApiBaseUrl() {
        if (StringUtils.hasText(openApiInternalBaseUrl)) {
            return openApiInternalBaseUrl.replaceAll("/+$", "");
        }
        return Objects.requireNonNull(
                ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
        );
    }
}
