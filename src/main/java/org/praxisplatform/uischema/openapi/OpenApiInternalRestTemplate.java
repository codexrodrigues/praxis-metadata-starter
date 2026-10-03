package org.praxisplatform.uischema.openapi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

/**
 * Metadata-owned HTTP transport with one lazily created JDK client. The read timeout bounds
 * waiting for the complete response (headers and body), reduced to the remaining composition
 * admission budget. Timeout/interruption requests best-effort client cancellation; it does not
 * cancel server work or bound arbitrary host interceptors, error handlers or source customizers.
 * No executor is created per request. Closing this client shuts down its owned JDK client.
 */
public final class OpenApiInternalRestTemplate extends RestTemplate implements AutoCloseable {
    private final DeadlineRequestFactory ownedFactory;
    private volatile ClientHttpRequestFactory configuredFactory;
    private volatile long transportRevision;
    private final ThreadLocal<ClientHttpRequestFactory> activeFactory = new ThreadLocal<>();
    private final ThreadLocal<ProducerRequest> producerRequest = new ThreadLocal<>();
    public static final String PRODUCER_CAPTURE_HEADER = OpenApiProducerCaptureAccess.HEADER;

    /** Returns a token only inside the owned exact-route producer scope. Never an IAM credential. */
    public String producerCaptureToken(String exactPath) {
        ProducerRequest request = producerRequest.get();
        if (request == null) return null;
        if (!request.path().equals(exactPath))
            throw new IllegalStateException("Producer capture cannot change its exact source route");
        return request.token();
    }

    <T> T withProducerCapture(OpenApiProducerCaptureAccess access, String contextPath, String exactPath,
            Deadline deadline, Supplier<T> action) {
        if (!hasUnmodifiedProducerTransport())
            throw new IllegalStateException("Producer capture cannot use interceptors or request initializers");
        if (producerRequest.get() != null)
            throw new IllegalStateException("Nested producer capture is not supported");
        try (var lease = access.issue(contextPath, exactPath, deadline.remainingNanos())) {
            producerRequest.set(new ProducerRequest(exactPath, lease.token()));
            try {
                return withDeadline(deadline, () -> {
                    T result = action.get();
                    lease.requireConsumed();
                    return result;
                });
            } finally { producerRequest.remove(); }
        }
    }

    private record ProducerRequest(String path, String token) {}

    public OpenApiInternalRestTemplate(Duration connectTimeout, Duration readTimeout) {
        this(new DeadlineRequestFactory(connectTimeout, readTimeout));
    }

    private OpenApiInternalRestTemplate(DeadlineRequestFactory factory) {
        super(factory);
        this.ownedFactory = factory;
    }

    @Override
    public void close() { ownedFactory.close(); }

    @Override
    public synchronized void setRequestFactory(ClientHttpRequestFactory factory) {
        long next = Math.incrementExact(transportRevision);
        transportRevision = next;
        super.setRequestFactory(factory);
        configuredFactory = factory;
    }

    @Override
    public synchronized void setInterceptors(java.util.List<org.springframework.http.client.ClientHttpRequestInterceptor> interceptors) {
        long next = Math.incrementExact(transportRevision);
        transportRevision = next;
        super.setInterceptors(java.util.List.copyOf(interceptors));
    }

    @Override
    public java.util.List<org.springframework.http.client.ClientHttpRequestInterceptor> getInterceptors() {
        return java.util.List.copyOf(super.getInterceptors());
    }

    @Override
    public synchronized void setClientHttpRequestInitializers(java.util.List<org.springframework.http.client.ClientHttpRequestInitializer> initializers) {
        long next = Math.incrementExact(transportRevision);
        transportRevision = next;
        super.setClientHttpRequestInitializers(java.util.List.copyOf(initializers));
    }

    @Override
    public java.util.List<org.springframework.http.client.ClientHttpRequestInitializer> getClientHttpRequestInitializers() {
        return java.util.List.copyOf(super.getClientHttpRequestInitializers());
    }

    @Override
    public ClientHttpRequestFactory getRequestFactory() {
        ClientHttpRequestFactory captured = activeFactory == null ? null : activeFactory.get();
        return captured == null ? super.getRequestFactory() : captured;
    }

    long transportRevision() { return transportRevision; }

    synchronized void requireTransportRevision(long captured) {
        if (!hasOwnedRequestFactory() || transportRevision != captured)
            throw new IllegalStateException("OpenAPI HTTP transport changed during bulk composition");
    }

    boolean hasOwnedRequestFactory() {
        return configuredFactory == ownedFactory;
    }

    boolean hasUnmodifiedProducerTransport() {
        // Interceptors may synthesize a response or rewrite its URI/headers without invoking
        // the owned transport. Initializers can rewrite the exact producer request as well.
        return hasOwnedRequestFactory() && super.getInterceptors().isEmpty()
                && super.getClientHttpRequestInitializers().isEmpty();
    }

    <T> T withDeadline(Deadline deadline, Supplier<T> action) {
        if (ownedFactory.deadline.get() != null)
            throw new IllegalStateException("Nested HTTP composition budgets are not supported");
        deadline.remainingNanos();
        long capturedRevision;
        synchronized (this) {
            if (producerRequest.get() != null && !hasUnmodifiedProducerTransport())
                throw new IllegalStateException("Producer capture transport changed before request admission");
            if (!hasOwnedRequestFactory())
                throw new IllegalStateException("Fresh bulk composition requires the Metadata-owned HTTP request factory");
            capturedRevision = transportRevision;
            // A concurrent setter must not redirect this request to an unbounded replacement.
            activeFactory.set(super.getRequestFactory());
        }
        ownedFactory.deadline.set(deadline);
        try {
            T result = action.get();
            requireTransportRevision(capturedRevision);
            deadline.remainingNanos();
            return result;
        } finally {
            ownedFactory.deadline.remove();
            activeFactory.remove();
        }
    }

    static int positiveMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        long millis;
        try { millis = value.toMillis(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException(name + " is too large", overflow); }
        if (millis < 1 || millis > Integer.MAX_VALUE)
            throw new IllegalArgumentException(name + " must be between 1ms and " + Integer.MAX_VALUE + "ms");
        return (int) millis;
    }

    static final class Deadline {
        private final long started = System.nanoTime();
        private final long timeoutNanos;

        Deadline(Duration timeout) {
            positiveMillis(timeout, "bulkCompositionTimeout");
            timeoutNanos = timeout.toNanos();
        }

        long remainingNanos() {
            long remaining = timeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0) throw new IllegalStateException("Bulk OpenAPI composition admission budget exhausted");
            return remaining;
        }

    }

    private static final class DeadlineRequestFactory implements ClientHttpRequestFactory {
        private static final Set<String> TRANSPORT_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade");
        private final Duration connectTimeout;
        private final Duration responseTimeout;
        private final ThreadLocal<Deadline> deadline = new ThreadLocal<>();
        private HttpClient client;
        private boolean closed;

        private DeadlineRequestFactory(Duration connectTimeout, Duration responseTimeout) {
            positiveMillis(connectTimeout, "connectTimeout");
            positiveMillis(responseTimeout, "readTimeout");
            this.connectTimeout = connectTimeout;
            this.responseTimeout = responseTimeout;
        }

        private synchronized HttpClient client() {
            if (closed) throw new IllegalStateException("The internal OpenAPI HTTP client is closed");
            if (client == null) client = HttpClient.newBuilder().connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            return client;
        }

        private synchronized void close() {
            closed = true;
            if (client != null) client.shutdownNow();
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            Deadline composition = deadline.get();
            return new ClientHttpRequest() {
                private final HttpHeaders headers = new HttpHeaders();
                private final Map<String, Object> attributes = new LinkedHashMap<>();
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();
                @Override public HttpMethod getMethod() { return method; }
                @Override public URI getURI() { return uri; }
                @Override public HttpHeaders getHeaders() { return headers; }
                @Override public Map<String, Object> getAttributes() { return attributes; }
                @Override public OutputStream getBody() { return body; }
                @Override public ClientHttpResponse execute() throws IOException {
                    Deadline request = new Deadline(responseTimeout);
                    HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofNanos(remaining(request, composition)));
                    headers.forEach((name, values) -> {
                        // These connection-level headers are supplied by the JDK transport.
                        if (!TRANSPORT_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
                            values.forEach(value -> builder.header(name, value));
                    });
                    builder.method(method.name(), body.size() == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
                    var response = client().sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                    try {
                        // ofByteArray completes only after the whole response has been received;
                        // ofInputStream would leave a slow body outside this bounded wait.
                        HttpResponse<byte[]> complete = response.get(remaining(request, composition), TimeUnit.NANOSECONDS);
                        remaining(request, composition);
                        if (composition != null && complete.statusCode() / 100 == 3)
                            throw new IOException("Fresh OpenAPI composition cannot follow a redirected source");
                        return bufferedResponse(complete);
                    } catch (InterruptedException interrupted) {
                        response.cancel(true);
                        Thread.currentThread().interrupt();
                        throw new IOException("Internal OpenAPI HTTP request interrupted", interrupted);
                    } catch (TimeoutException timeout) {
                        response.cancel(true);
                        throw new IOException("Internal OpenAPI HTTP response budget exhausted", timeout);
                    } catch (ExecutionException failed) {
                        if (failed.getCause() instanceof IOException io) throw io;
                        throw new IOException("Internal OpenAPI HTTP request failed", failed.getCause());
                    } catch (RuntimeException failed) {
                        response.cancel(true);
                        throw failed;
                    }
                }
            };
        }

        private long remaining(Deadline request, Deadline composition) {
            long remaining = request.remainingNanos();
            return composition == null ? remaining : Math.min(remaining, composition.remainingNanos());
        }

        private ClientHttpResponse bufferedResponse(HttpResponse<byte[]> response) {
            HttpHeaders headers = new HttpHeaders();
            response.headers().map().forEach(headers::put);
            return new ClientHttpResponse() {
                private final InputStream body = new ByteArrayInputStream(response.body());
                @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(response.statusCode()); }
                @Override public String getStatusText() {
                    HttpStatus status = HttpStatus.resolve(response.statusCode());
                    return status == null ? "" : status.getReasonPhrase();
                }
                @Override public HttpHeaders getHeaders() { return HttpHeaders.readOnlyHttpHeaders(headers); }
                @Override public InputStream getBody() { return body; }
                @Override public void close() { /* The response body is already completely buffered. */ }
            };
        }
    }
}
