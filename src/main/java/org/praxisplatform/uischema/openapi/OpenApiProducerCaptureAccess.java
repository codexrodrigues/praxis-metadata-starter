package org.praxisplatform.uischema.openapi;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Process-local, single-use access to one exact producer GET. This is not authentication:
 * the Servlet request must already have passed the host's security chain.
 */
final class OpenApiProducerCaptureAccess {
    static final String HEADER = "X-Praxis-OpenApi-Producer-Capture";
    private static final int MAX_ACTIVE_LEASES = 256;
    // The owned transport validates truncated milliseconds; retain its last fractional millisecond.
    private static final long MAX_LIFETIME_NANOS = TimeUnit.MILLISECONDS.toNanos(Integer.MAX_VALUE) + 999_999;
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier monotonicClock;
    private final Map<String, Grant> grants = new HashMap<>();

    OpenApiProducerCaptureAccess() { this(System::nanoTime); }
    OpenApiProducerCaptureAccess(LongSupplier monotonicClock) {
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    /** The caller supplies its remaining live budget, never a renewed composition deadline. */
    synchronized Lease issue(String contextPath, String exactPath, long lifetimeNanos) {
        Objects.requireNonNull(contextPath, "contextPath");
        if (!contextPath.isEmpty()) OpenApiPublicationCandidate.requireBasePath(contextPath);
        OpenApiPublicationCandidate.requireBasePath(exactPath);
        if (lifetimeNanos <= 0 || lifetimeNanos > MAX_LIFETIME_NANOS)
            throw new IllegalArgumentException("Producer lifetime must fit the remaining bounded transport budget");
        long now = monotonicClock.getAsLong();
        cleanup(now);
        if (grants.size() >= MAX_ACTIVE_LEASES)
            throw new IllegalStateException("Producer capture lease capacity is exhausted");
        byte[] entropy = new byte[32];
        String token;
        do {
            random.nextBytes(entropy);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        } while (grants.containsKey(token));
        var grant = new Grant(contextPath, exactPath, now, lifetimeNanos);
        grants.put(token, grant);
        return new Lease(token, grant);
    }

    synchronized boolean consume(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        long now = monotonicClock.getAsLong();
        cleanup(now);
        if (!"GET".equals(request.getMethod()) || request.getDispatcherType() != DispatcherType.REQUEST
                || request.getQueryString() != null || hasRedispatchAttributes(request)) return false;
        var values = request.getHeaders(HEADER);
        if (values == null || !values.hasMoreElements()) return false;
        String token = values.nextElement();
        if (values.hasMoreElements() || token == null || token.length() != 43) return false;
        // Retire the presented grant atomically, including a route/context mismatch.
        Grant grant = grants.remove(token);
        if (grant == null || grant.closed || grant.expired(now)) return false;
        if (!Objects.equals(request.getContextPath(), request.getServletContext().getContextPath())
                || !grant.contextPath.equals(request.getContextPath())
                || !(grant.contextPath + grant.exactPath).equals(request.getRequestURI())
                || grant.expired(monotonicClock.getAsLong())) return false;
        grant.consumed = true;
        return true;
    }

    static boolean hasRedispatchAttributes(HttpServletRequest request) {
        var names = request.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.startsWith("jakarta.servlet.forward.") || name.startsWith("jakarta.servlet.include.")
                    || name.startsWith("jakarta.servlet.error.") || name.startsWith("javax.servlet.forward.")
                    || name.startsWith("javax.servlet.include.") || name.startsWith("javax.servlet.error.")) return true;
        }
        return false;
    }

    private void cleanup(long now) {
        grants.values().removeIf(grant -> grant.expired(now)); // At most 256 entries.
    }

    final class Lease implements AutoCloseable {
        private final String token;
        private final Grant grant;
        private Lease(String token, Grant grant) { this.token = token; this.grant = grant; }
        String token() { return token; }

        /** Attests the exact successful consumption only while the original lease remains live and open. */
        void requireConsumed() {
            synchronized (OpenApiProducerCaptureAccess.this) {
                long now = monotonicClock.getAsLong();
                if (grant.closed || !grant.consumed || grant.expired(now))
                    throw new IllegalStateException("Producer capture was not consumed within its live lease");
            }
        }

        @Override public void close() {
            synchronized (OpenApiProducerCaptureAccess.this) {
                grant.closed = true;
                grants.remove(token, grant);
                cleanup(monotonicClock.getAsLong());
            }
        }
    }

    /** All mutable state is guarded by the enclosing access owner's monitor, including after map removal. */
    private static final class Grant {
        private final String contextPath;
        private final String exactPath;
        private final long issuedAt;
        private final long lifetimeNanos;
        private boolean consumed;
        private boolean closed;

        private Grant(String contextPath, String exactPath, long issuedAt, long lifetimeNanos) {
            this.contextPath = contextPath; this.exactPath = exactPath;
            this.issuedAt = issuedAt; this.lifetimeNanos = lifetimeNanos;
        }
        boolean expired(long now) { return now - issuedAt >= lifetimeNanos; }
    }
}
