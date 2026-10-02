package org.praxisplatform.uischema.openapi;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Set;

/**
 * Serves only immutable published JSON. The host registers this filter after security for
 * every dispatch type; the producer lease never grants IAM access or changes that ordering.
 */
public final class GovernedOpenApiPublicationFilter implements Filter {
    private static final int MAX_ROUTE_CHARACTERS = 8192;
    private static final int MAX_DECODING_PASSES = 16;
    private final CachedOpenApiDocumentService documents;
    private final String basePath;

    public GovernedOpenApiPublicationFilter(CachedOpenApiDocumentService documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.basePath = OpenApiPublicationCandidate.requireBasePath(documents.bulkOpenApiBasePath());
    }

    /** Registration attestation only; the host must separately prove security order and dispatch coverage. */
    @Override
    public void init(FilterConfig config) throws ServletException {
        if (config == null || config.getServletContext() == null || config.getFilterName() == null)
            throw new ServletException("Publication filter registration is missing");
        var context = config.getServletContext();
        var registration = context.getFilterRegistration(config.getFilterName());
        if (registration == null || !getClass().getName().equals(registration.getClassName())
                || !Set.copyOf(registration.getUrlPatternMappings()).equals(Set.of("/*"))
                || !registration.getServletNameMappings().isEmpty())
            throw new ServletException("Publication filter must cover the complete Servlet context");
        String contextPath = context.getContextPath();
        if (contextPath == null) throw new ServletException("Publication Servlet context is missing");
        try { if (!contextPath.isEmpty()) OpenApiPublicationCandidate.requireBasePath(contextPath); }
        catch (IllegalArgumentException invalid) { throw new ServletException("Publication Servlet context is invalid"); }
        documents.markBulkOpenApiServingInstalled(contextPath);
    }

    @Override
    public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        if (!(input instanceof HttpServletRequest request) || !(output instanceof HttpServletResponse response)) {
            chain.doFilter(input, output); return;
        }
        String context = request.getContextPath();
        String containerContext = request.getServletContext().getContextPath();
        String rawUri = request.getRequestURI();
        String routingPath = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (!intercepts(rawUri, context, containerContext) && !intercepts(routingPath, "", "")) {
            chain.doFilter(input, output); return;
        }
        String exactPath = context == null || rawUri == null || !rawUri.startsWith(context)
                ? null : rawUri.substring(context.length());
        if (!Objects.equals(context, containerContext) || exactPath == null || !jsonRoute(exactPath)
                || !canonicalLiteral(exactPath) || request.getQueryString() != null
                || request.getDispatcherType() != DispatcherType.REQUEST
                || OpenApiProducerCaptureAccess.hasRedispatchAttributes(request)
                || (!routingPath.isEmpty() && !routingPath.equals(exactPath))) {
            unavailable(response); return;
        }
        var producerHeaders = request.getHeaders(OpenApiProducerCaptureAccess.HEADER);
        boolean producer = producerHeaders != null && producerHeaders.hasMoreElements();
        if (producer) {
            producerHeaders.nextElement();
            if (!"GET".equals(request.getMethod()) || producerHeaders.hasMoreElements()) {
                unavailable(response); return;
            }
            boolean consumed;
            try { consumed = documents.consumeBulkOpenApiProducer(request); }
            catch (RuntimeException unavailable) { unavailable(response); return; }
            if (consumed) chain.doFilter(input, output);
            else unavailable(response);
            return;
        }
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            unavailable(response); return;
        }
        byte[] bytes;
        try {
            OpenApiDocumentCapture capture = documents.publishedBulkOpenApiResponse(context, exactPath);
            if (capture == null) { unavailable(response); return; }
            bytes = capture.bytes();
        } catch (RuntimeException unavailable) { unavailable(response); return; }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLengthLong(bytes.length);
        response.setHeader("Cache-Control", "no-store");
        if ("GET".equals(request.getMethod())) response.getOutputStream().write(bytes);
    }

    private boolean intercepts(String rawPath, String requestContext, String containerContext) {
        if (rawPath == null || rawPath.isEmpty()) return false;
        // Classification is scoped to OpenAPI. Preserve a recognized prefix before a later
        // malformed escape, but leave unrelated malformed paths to the container/security.
        String candidate = rawPath.substring(0, Math.min(rawPath.length(), MAX_ROUTE_CHARACTERS));
        for (int pass = 0; pass <= MAX_DECODING_PASSES; pass++) {
            String normalized = normalizedSegments(candidate);
            if (family(candidate) || family(normalized)
                    || family(withoutContext(candidate, requestContext))
                    || family(withoutContext(candidate, containerContext))
                    || family(withoutContext(normalized, requestContext))
                    || family(withoutContext(normalized, containerContext))
                    || family(normalizedSegments(withoutContext(candidate, requestContext)))
                    || family(normalizedSegments(withoutContext(candidate, containerContext)))) return true;
            if (candidate.indexOf('%') < 0 || pass == MAX_DECODING_PASSES) return false;
            String next = URLDecoder.decode(candidate.replace("+", "%2B")
                    .replaceAll("%(?![0-9a-fA-F]{2})", "%25"), StandardCharsets.UTF_8);
            if (next.equals(candidate)) return false;
            candidate = next;
        }
        return false;
    }

    private boolean family(String path) {
        return path != null && (path.equals(basePath) || path.startsWith(basePath + "/")
                || path.startsWith(basePath + "."));
    }
    private boolean jsonRoute(String path) { return path.equals(basePath) || path.startsWith(basePath + "/"); }
    private boolean canonicalLiteral(String path) {
        if (path.length() > MAX_ROUTE_CHARACTERS) return false;
        try { return OpenApiPublicationCandidate.requireBasePath(path).equals(path); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static String withoutContext(String path, String context) {
        if (path == null || context == null || context.isEmpty()) return path;
        return path.equals(context) || path.startsWith(context + "/") ? path.substring(context.length()) : path;
    }
    private static String normalizedSegments(String raw) {
        if (raw == null) return null;
        var segments = new ArrayDeque<String>();
        for (String segment : raw.replace('\\', '/').split("/")) {
            int matrix = segment.indexOf(';');
            if (matrix >= 0) segment = segment.substring(0, matrix);
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) { if (!segments.isEmpty()) segments.removeLast(); }
            else segments.addLast(segment);
        }
        return "/" + String.join("/", segments);
    }
    private static void unavailable(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(0);
    }
}
