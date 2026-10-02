package org.praxisplatform.uischema.openapi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.util.UriUtils;

/**
 * One prepared global OpenAPI response set. This value is not publication authority:
 * the owner must validate its epoch/transport and commit the durable generation before serving it.
 */
public final class OpenApiPublicationCandidate {
    private final Object owner;
    private final long epoch;
    private final long transportRevision;
    private final String basePath;
    private final String contextPath;
    private final Set<String> groups;
    private final Map<String, OpenApiDocumentCapture> responses;
    private final String digest;

    OpenApiPublicationCandidate(Object owner, long epoch, long transportRevision, String basePath, String contextPath,
            Set<String> groups, Map<String, OpenApiDocumentCapture> responses) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.epoch = epoch;
        this.transportRevision = transportRevision;
        this.basePath = requireBasePath(basePath);
        this.contextPath = Objects.requireNonNull(contextPath, "contextPath");
        if (!contextPath.isEmpty()) requireBasePath(contextPath);
        this.groups = Set.copyOf(groups);
        if (this.groups.isEmpty()) throw new IllegalArgumentException("At least one published group is required");
        var expected = new java.util.HashSet<String>();
        expected.add(basePath);
        expected.add(basePath + "/swagger-config");
        for (String group : this.groups) {
            requireGroup(group);
            if (!expected.add(groupPath(basePath, group)))
                throw new IllegalArgumentException("OpenAPI group collides with a reserved publication route");
        }
        this.responses = Map.copyOf(responses);
        if (!expected.equals(this.responses.keySet()))
            throw new IllegalArgumentException("Publication must capture exactly root, config and all declared groups");
        requireOpenApiDocument(this.responses.get(basePath).document());
        for (String group : this.groups)
            requireOpenApiDocument(this.responses.get(groupPath(basePath, group)).document());
        requireSwaggerConfig(this.responses.get(basePath + "/swagger-config").document());
        this.digest = digest(this.responses);
    }

    public String digest() { return digest; }
    public Set<String> groups() { return groups; }
    public String basePath() { return basePath; }
    public String contextPath() { return contextPath; }
    public OpenApiDocumentCapture response(String exactPath) {
        var capture = responses.get(exactPath);
        if (capture == null) throw new IllegalArgumentException("Response is outside the prepared publication");
        return capture;
    }
    public com.fasterxml.jackson.databind.JsonNode groupDocument(String group) {
        if (!groups.contains(group)) throw new IllegalArgumentException("Group is outside the prepared publication");
        return response(groupPath(basePath, group)).document();
    }
    public Set<String> responsePaths() { return responses.keySet(); }

    boolean belongsTo(Object expectedOwner, long expectedEpoch, long expectedTransportRevision) {
        return owner == expectedOwner && epoch == expectedEpoch && transportRevision == expectedTransportRevision;
    }

    static String groupPath(String basePath, String group) {
        requireGroup(group);
        return requireBasePath(basePath) + "/" + UriUtils.encodePathSegment(group, StandardCharsets.UTF_8);
    }

    static String requireBasePath(String basePath) {
        if (basePath == null || !basePath.matches("(?:/[A-Za-z0-9_.-]+)+")
                || java.util.Arrays.stream(basePath.substring(1).split("/"))
                        .anyMatch(segment -> segment.equals(".") || segment.equals("..")))
            throw new IllegalArgumentException("OpenAPI base path must be canonical");
        return basePath;
    }

    static void requireGroup(String group) {
        if (group == null || !group.matches("[A-Za-z0-9_.-]+")
                || group.equals("swagger-config") || group.endsWith(".yaml")
                || group.equals(".") || group.equals("..") || group.contains("/") || group.contains("\\")
                || group.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("OpenAPI group identity is invalid or reserved");
    }

    private static void requireOpenApiDocument(com.fasterxml.jackson.databind.JsonNode document) {
        try { OpenApiRequestSchemaReader.version(document.path("openapi")); }
        catch (IllegalStateException invalid) {
            throw new IllegalArgumentException("Publication requires an OpenAPI 3.0.x or 3.1.x document", invalid);
        }
        var info = document.path("info");
        if (!info.isObject() || !nonblankText(info.path("title")) || !nonblankText(info.path("version"))
                || !document.path("paths").isObject())
            throw new IllegalArgumentException("Publication requires OpenAPI info and paths, not an arbitrary JSON response");
    }

    /** The producer must advertise exactly this captured local group set, without external aliases. */
    private void requireSwaggerConfig(com.fasterxml.jackson.databind.JsonNode document) {
        var urls = document.get("urls");
        var url = document.get("url");
        if (urls == null || !urls.isArray() || urls.isEmpty())
            throw new IllegalArgumentException("Grouped publication requires nonempty swagger-config urls");
        var names = new java.util.HashSet<String>();
        var expectedUrls = groups.stream().map(group -> contextPath + groupPath(basePath, group))
                .collect(java.util.stream.Collectors.toSet());
        var actualUrls = new java.util.HashSet<String>();
        for (var entry : urls) {
            if (!entry.isObject() || !nonblankText(entry.path("name")) || !nonblankText(entry.path("url"))
                    || !names.add(entry.path("name").textValue()))
                throw new IllegalArgumentException("swagger-config urls must have unique names and textual URLs");
            if (!actualUrls.add(entry.path("url").textValue()))
                throw new IllegalArgumentException("swagger-config must not alias a captured group URL");
        }
        if (!actualUrls.equals(expectedUrls))
            throw new IllegalArgumentException("swagger-config URLs differ from the captured local group routes");
        if (url != null && (!nonblankText(url) || !(contextPath + basePath).equals(url.textValue())))
            throw new IllegalArgumentException("swagger-config url must identify the captured local root");
        var configUrl = document.get("configUrl");
        if (configUrl != null && (!nonblankText(configUrl)
                || !(contextPath + basePath + "/swagger-config").equals(configUrl.textValue())))
            throw new IllegalArgumentException("swagger-config configUrl must identify the captured local config");
    }

    private static boolean nonblankText(com.fasterxml.jackson.databind.JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank();
    }

    private static String digest(Map<String, OpenApiDocumentCapture> responses) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update("praxis-openapi-publication-1".getBytes(StandardCharsets.UTF_8));
            for (String path : responses.keySet().stream().sorted().toList()) {
                byte[] key = path.getBytes(StandardCharsets.UTF_8);
                byte[] body = responses.get(path).bytes();
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(key.length).array());
                hash.update(key);
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(body.length).array());
                hash.update(body);
            }
            return "sha256:" + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
