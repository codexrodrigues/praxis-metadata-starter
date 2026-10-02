package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.uischema.openapi.OpenApiContentSupport;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

/**
 * Componente de suporte para resolucao e leitura de documentos OpenAPI do starter.
 *
 * <p>
 * Esta classe concentra a logica compartilhada usada pelos controladores documentais para
 * resolver grupos OpenAPI, buscar o documento correto e escolher o content-type mais adequado
 * ao extrair schemas e exemplos. Ela evita duplicacao de heuristicas entre {@code /schemas/filtered}
 * e {@code /schemas/catalog}.
 * </p>
 */
@Component
public class OpenApiDocsSupport {

    @Value("${app.openapi.internal-base-url:}")
    private String openApiInternalBaseUrl;

    @Autowired(required = false)
    private OpenApiGroupResolver openApiGroupResolver;

    /**
     * Resolve o grupo OpenAPI mais adequado a partir do path do recurso.
     *
     * <p>
     * Primeiro tenta usar {@link OpenApiGroupResolver} quando presente. Se nao houver resolvedor
     * configurado ou ele nao retornar valor util, aplica um fallback baseado nos primeiros segmentos
     * relevantes do path.
     * </p>
     *
     * @param path path do recurso HTTP
     * @return nome do grupo OpenAPI resolvido
     */
    public String resolveGroupFromPath(String path) {
        if (!StringUtils.hasText(path)) {
            return "application";
        }
        String normalizedPath = decodePath(path);
        if (openApiGroupResolver != null) {
            String resolved = openApiGroupResolver.resolveGroup(normalizedPath);
            if (StringUtils.hasText(resolved)) {
                return resolved;
            }
        }

        String fallbackPath = truncateAtFirstPathVariable(normalizedPath);
        String[] segments = fallbackPath.split("/");
        if (segments.length >= 4) {
            return String.join("-", java.util.Arrays.copyOfRange(segments, 1, 4));
        }
        if (segments.length >= 2 && StringUtils.hasText(segments[1])) {
            return segments[1];
        }
        return "application";
    }

    private String decodePath(String path) {
        try {
            return UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return path;
        }
    }

    /**
     * Busca o documento OpenAPI de um grupo especifico, com fallback para o documento base.
     *
     * <p>
     * O metodo tenta primeiro {@code /v3/api-docs/{group}}. Se o grupo nao existir, faz fallback
     * para o documento base configurado. Esse comportamento e importante para manter resiliencia em
     * ambientes onde nem todos os recursos estao organizados por grupos explicitamente publicados.
     * </p>
     *
     * @param restTemplate cliente HTTP usado para buscar o documento
     * @param openApiBasePath path base do endpoint OpenAPI
     * @param group grupo desejado
     * @param logger logger do chamador para observabilidade
     * @return documento OpenAPI carregado como {@link JsonNode}
     */
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

    /**
     * Fetches only the named group document. Unlike {@link #fetchOpenApiDocument}, this method
     * never substitutes the ungrouped document: callers using group identity as evidence must
     * fail closed when that exact publication is absent.
     */
    public JsonNode fetchOpenApiGroupDocument(RestTemplate restTemplate, String openApiBasePath,
                                               String group, Logger logger) {
        if (!StringUtils.hasText(group)) {
            throw new IllegalArgumentException("OpenAPI group name must not be blank");
        }
        String groupDocUrl = resolveOpenApiBaseUrl() + openApiBasePath + "/"
                + UriUtils.encodePathSegment(group, StandardCharsets.UTF_8);
        try {
            JsonNode document = restTemplate.getForObject(groupDocUrl, JsonNode.class);
            if (document == null) {
                throw new IllegalStateException("OpenAPI group document is null: " + groupDocUrl);
            }
            return document;
        } catch (HttpStatusCodeException ex) {
            logger.error("Exact OpenAPI group document {} failed with status {}", groupDocUrl, ex.getStatusCode());
            throw new IllegalStateException("Failed to fetch exact OpenAPI group document " + groupDocUrl
                    + " (status " + ex.getStatusCode() + ")", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            logger.error("Failed to fetch exact OpenAPI group document {}", groupDocUrl, ex);
            throw new IllegalStateException("Failed to fetch exact OpenAPI group document " + groupDocUrl, ex);
        }
    }

    /**
     * Fetches the exact named group with request cache revalidation directives. Lifecycle
     * publication uses this method after durable suspension so a process-local or compliant HTTP
     * cache cannot silently provide an old OpenAPI representation. It never falls back to the
     * ungrouped document.
     */
    public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate restTemplate, String openApiBasePath,
                                                   String group, Logger logger) {
        if (!StringUtils.hasText(group)) throw new IllegalArgumentException("OpenAPI group name must not be blank");
        String groupDocUrl = resolveOpenApiBaseUrl() + openApiBasePath + "/"
                + UriUtils.encodePathSegment(group, StandardCharsets.UTF_8);
        try {
            var request = RequestEntity.get(java.net.URI.create(groupDocUrl))
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store")
                    .accept(MediaType.APPLICATION_JSON)
                    .build();
            ResponseEntity<JsonNode> response = restTemplate.exchange(request, JsonNode.class);
            JsonNode document = response.getBody();
            if (document == null) throw new IllegalStateException("Fresh OpenAPI group document is null: " + groupDocUrl);
            return document;
        } catch (Exception ex) {
            logger.error("Failed to fetch fresh exact OpenAPI group document {}", groupDocUrl, ex);
            if (ex instanceof IllegalStateException illegalStateException) throw illegalStateException;
            throw new IllegalStateException("Failed to fetch fresh exact OpenAPI group document " + groupDocUrl, ex);
        }
    }

    /**
     * Captures one exact group response using the publication owner's configured mapper.
     * The caller must govern mapper changes through the publication lifecycle.
     */
    public org.praxisplatform.uischema.openapi.OpenApiDocumentCapture fetchFreshOpenApiGroupCapture(
            RestTemplate restTemplate, String openApiBasePath, String group, Logger logger,
            com.fasterxml.jackson.databind.ObjectMapper publicationMapper) {
        java.util.Objects.requireNonNull(publicationMapper, "publicationMapper");
        if (!StringUtils.hasText(group)) throw new IllegalArgumentException("OpenAPI group name must not be blank");
        return fetchFreshOpenApiResponseCapture(restTemplate,
                openApiBasePath + "/" + UriUtils.encodePathSegment(group, StandardCharsets.UTF_8), logger, publicationMapper);
    }

    /** Captures one exact local producer route: HTTP 200 application/json, never a redirect or fallback. */
    public org.praxisplatform.uischema.openapi.OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(
            RestTemplate restTemplate, String exactPath, Logger logger,
            com.fasterxml.jackson.databind.ObjectMapper publicationMapper) {
        java.util.Objects.requireNonNull(publicationMapper, "publicationMapper");
        if (exactPath == null || !exactPath.startsWith("/") || exactPath.contains("?") || exactPath.contains("#")
                || exactPath.contains("\\") || exactPath.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Exact OpenAPI source path is required");
        String groupDocUrl = resolveOpenApiBaseUrl() + exactPath;
        try {
            var builder = RequestEntity.get(java.net.URI.create(groupDocUrl))
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store")
                    .accept(MediaType.APPLICATION_JSON);
            if (restTemplate instanceof org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate internal) {
                String token = internal.producerCaptureToken(exactPath);
                if (token != null) builder.header(
                        org.praxisplatform.uischema.openapi.OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER, token);
            }
            var request = builder.build();
            ResponseEntity<byte[]> response = restTemplate.exchange(request, byte[].class);
            MediaType contentType = response.getHeaders().getContentType();
            if (response.getStatusCode().value() != HttpStatus.OK.value()
                    || contentType == null || !"application".equalsIgnoreCase(contentType.getType())
                    || !"json".equalsIgnoreCase(contentType.getSubtype())
                    || (contentType.getCharset() != null && !StandardCharsets.UTF_8.equals(contentType.getCharset())))
                throw new IllegalStateException("Fresh OpenAPI producer requires HTTP 200 application/json UTF-8: " + groupDocUrl);
            byte[] document = response.getBody();
            if (document == null) throw new IllegalStateException("Fresh OpenAPI group document is null: " + groupDocUrl);
            return org.praxisplatform.uischema.openapi.OpenApiDocumentCapture.parse(
                    document, publicationMapper);
        } catch (Exception ex) {
            logger.error("Failed to fetch fresh exact OpenAPI group document {}", groupDocUrl, ex);
            if (ex instanceof IllegalStateException illegalStateException) throw illegalStateException;
            throw new IllegalStateException("Failed to fetch fresh exact OpenAPI group document " + groupDocUrl, ex);
        }
    }

    /**
     * Seleciona o content node preferencial dentro de um bloco OpenAPI {@code content}.
     *
     * <p>
     * A precedencia e {@code application/json}, depois o media type curinga, e por fim o primeiro media type
     * disponivel. Isso padroniza a leitura de request/response bodies em documentos heterogeneos.
     * </p>
     *
     * @param contentRoot no raiz do bloco {@code content}
     * @return no preferencial para leitura de schema e exemplos
     */
    public JsonNode selectPreferredContentNode(JsonNode contentRoot) {
        return OpenApiContentSupport.preferredContent(contentRoot);
    }

    /**
     * Infere o media type preferencial a partir de um bloco OpenAPI {@code content}.
     *
     * @param contentRoot no raiz do bloco {@code content}
     * @return media type preferencial ou {@code null} quando inexistente
     */
    public String inferMediaType(JsonNode contentRoot) {
        return OpenApiContentSupport.preferredMediaType(contentRoot);
    }

    private String truncateAtFirstPathVariable(String path) {
        String[] segments = path.split("/");
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            if (segment.startsWith("{") && segment.endsWith("}")) {
                break;
            }
            builder.append('/').append(segment);
        }
        return builder.length() == 0 ? "/" : builder.toString();
    }

    /** Local publication uses this exact Servlet context, never a guessed origin or remote bridge. */
    public String localPublicationContextPath() {
        if (usesConfiguredInternalBaseUrl())
            throw new IllegalStateException("Local publication cannot use a configured remote OpenAPI origin");
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet))
            throw new IllegalStateException("Local publication requires an active Servlet request context");
        var request = servlet.getRequest();
        String containerContext = request.getServletContext().getContextPath();
        if (!java.util.Objects.equals(containerContext, request.getContextPath()))
            throw new IllegalStateException("Publication request context differs from the Servlet container context");
        return containerContext;
    }

    private String resolveOpenApiBaseUrl() {
        if (StringUtils.hasText(openApiInternalBaseUrl)) {
            return openApiInternalBaseUrl.replaceAll("/+$", "");
        }
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        int localPort = request.getLocalPort() > 0 ? request.getLocalPort() : request.getServerPort();
        UriComponentsBuilder builder = UriComponentsBuilder.newInstance()
                .scheme("http")
                .host(resolveLocalHost(request));
        if (localPort > 0 && localPort != 80) {
            builder.port(localPort);
        }
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath)) {
            builder.path(contextPath);
        }
        return builder.build().toUriString();
    }

    /** Whether this helper was configured to fetch documentation from a different URL/process. */
    public boolean usesConfiguredInternalBaseUrl() {
        return StringUtils.hasText(openApiInternalBaseUrl);
    }

    private String resolveLocalHost(HttpServletRequest request) {
        String localAddress = request.getLocalAddr();
        if (StringUtils.hasText(localAddress) && !"0:0:0:0:0:0:0:1".equals(localAddress)) {
            return localAddress;
        }
        String localName = request.getLocalName();
        if (StringUtils.hasText(localName) && !"0:0:0:0:0:0:0:1".equals(localName)) {
            return localName;
        }
        return "localhost";
    }
}
