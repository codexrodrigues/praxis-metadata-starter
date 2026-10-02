package org.praxisplatform.uischema.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;

/** Servlet unit tests; these do not prove HTTP security ordering or a publication DB transaction. */
class OpenApiProducerCaptureAccessTest {
    private static final String PATH = "/v3/api-docs/a";

    @Test
    void nonceHas256BitsIsUniqueAndAuthorizesOneExactGet() {
        var access = new OpenApiProducerCaptureAccess();
        try (var first = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5));
             var second = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5))) {
            assertThat(Base64.getUrlDecoder().decode(first.token())).hasSize(32);
            assertThat(first.token()).matches("[A-Za-z0-9_-]{43}");
            assertThat(second.token()).isNotEqualTo(first.token());
            assertThat(access.consume(request("", PATH, first.token()))).isTrue();
            first.requireConsumed();
            assertThat(access.consume(request("", PATH, first.token()))).isFalse();
            first.requireConsumed(); // Replay denial must not erase the original exact consumption.
            notConsumed(second);
        }
    }

    @Test
    void methodQueryRedispatchAttributesAndDuplicateHeadersCannotOpenProducer() {
        List<Consumer<MockHttpServletRequest>> invalid = List.of(
                request -> request.setMethod("HEAD"), request -> request.setMethod("POST"),
                request -> request.setQueryString(""), request -> request.setQueryString("group=a"),
                request -> request.setDispatcherType(DispatcherType.FORWARD),
                request -> request.setDispatcherType(DispatcherType.INCLUDE),
                request -> request.setDispatcherType(DispatcherType.ASYNC),
                request -> request.setDispatcherType(DispatcherType.ERROR),
                request -> request.setAttribute("jakarta.servlet.forward.request_uri", PATH),
                request -> request.setAttribute("javax.servlet.forward.request_uri", PATH),
                request -> request.setAttribute("jakarta.servlet.error.request_uri", PATH),
                request -> request.addHeader(OpenApiProducerCaptureAccess.HEADER, "duplicate"));
        var access = new OpenApiProducerCaptureAccess();
        for (var mutation : invalid) {
            try (var lease = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5))) {
                var request = request("", PATH, lease.token()); mutation.accept(request);
                assertThat(access.consume(request)).isFalse();
                notConsumed(lease);
            }
        }
    }

    @Test
    void wrongPathEncodingAndContextCannotUseAnIssuedGrant() {
        var access = new OpenApiProducerCaptureAccess();
        for (String path : List.of("/v3/api-docs/b", "/v3/api-docs/%61", "/v3/api-docs/a/", "/v3/api-docs/a;x=1")) {
            try (var lease = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5))) {
                assertThat(access.consume(request("", path, lease.token()))).isFalse();
                notConsumed(lease);
                assertThat(access.consume(request("", PATH, lease.token()))).isFalse();
                notConsumed(lease);
            }
        }
        try (var lease = access.issue("/host", PATH, TimeUnit.SECONDS.toNanos(5))) {
            var mismatch = request("/host", PATH, lease.token()); mismatch.setContextPath("/other");
            assertThat(access.consume(mismatch)).isFalse();
            notConsumed(lease);
        }
        try (var lease = access.issue("/host", PATH, TimeUnit.SECONDS.toNanos(5))) {
            assertThat(access.consume(request("/host", PATH, lease.token()))).isTrue();
            lease.requireConsumed();
        }
    }

    @Test
    void unknownMissingCommaJoinedAndClosedTokensAreDenied() {
        var access = new OpenApiProducerCaptureAccess();
        assertThat(access.consume(request("", PATH, null))).isFalse();
        assertThat(access.consume(request("", PATH, "x".repeat(43)))).isFalse();
        var lease = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5));
        assertThat(access.consume(request("", PATH, lease.token() + "," + lease.token()))).isFalse();
        lease.close(); lease.close();
        assertThat(access.consume(request("", PATH, lease.token()))).isFalse();
    }

    @Test
    void expirationUsesMonotonicElapsedTimeWithoutRenewalAndHandlesNanoTimeWrap() {
        var now = new AtomicLong(Long.MAX_VALUE - 50);
        var access = new OpenApiProducerCaptureAccess(now::get);
        try (var expired = access.issue("", PATH, 100)) {
            now.addAndGet(100);
            assertThat(access.consume(request("", PATH, expired.token()))).isFalse();
            assertThat(access.consume(request("", PATH, expired.token()))).isFalse();
            notConsumed(expired);
        }
        try (var live = access.issue("", PATH, 100)) {
            now.addAndGet(99);
            assertThat(access.consume(request("", PATH, live.token()))).isTrue();
            live.requireConsumed();
        }
    }

    @Test
    void anIssuedLeaseCannotAttestAnAbsentOrInvalidProducerConsumption() {
        var access = new OpenApiProducerCaptureAccess();
        try (var lease = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5))) {
            notConsumed(lease);
            assertThat(access.consume(request("", PATH, null))).isFalse();
            notConsumed(lease);
            var duplicate = request("", PATH, lease.token());
            duplicate.addHeader(OpenApiProducerCaptureAccess.HEADER, lease.token());
            assertThat(access.consume(duplicate)).isFalse();
            notConsumed(lease);
            assertThat(access.consume(request("", PATH, lease.token()))).isTrue();
            lease.requireConsumed();
        }
    }

    @Test
    void attestationExpiresAtTheOriginalDeadlineEvenAfterSuccessfulConsumption() {
        var now = new AtomicLong(Long.MAX_VALUE - 50);
        var access = new OpenApiProducerCaptureAccess(now::get);
        try (var lease = access.issue("", PATH, 100)) {
            now.addAndGet(99);
            assertThat(access.consume(request("", PATH, lease.token()))).isTrue();
            lease.requireConsumed();
            now.incrementAndGet();
            notConsumed(lease);
            assertThat(access.consume(request("", PATH, lease.token()))).isFalse();
            notConsumed(lease);
        }
    }

    @Test
    void expirationDuringExactRequestValidationCannotAttestAConsumption() {
        var now = new AtomicLong(0);
        var access = new OpenApiProducerCaptureAccess(now::get);
        try (var lease = access.issue("", PATH, 100)) {
            var request = new MockHttpServletRequest(new MockServletContext()) {
                @Override public String getRequestURI() { now.set(100); return PATH; }
            };
            request.setContextPath(""); request.setMethod("GET"); request.setDispatcherType(DispatcherType.REQUEST);
            request.addHeader(OpenApiProducerCaptureAccess.HEADER, lease.token());
            assertThat(access.consume(request)).isFalse();
            notConsumed(lease);
        }
    }

    @Test
    void closeInvalidatesAttestationForConsumedAndUnconsumedLeasesWithoutAllowingLateConsumption() {
        var access = new OpenApiProducerCaptureAccess();
        var consumed = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5));
        assertThat(access.consume(request("", PATH, consumed.token()))).isTrue();
        consumed.requireConsumed();
        consumed.close(); consumed.close();
        notConsumed(consumed);
        assertThat(access.consume(request("", PATH, consumed.token()))).isFalse();
        notConsumed(consumed);
        var unconsumed = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5));
        unconsumed.close();
        assertThat(access.consume(request("", PATH, unconsumed.token()))).isFalse();
        notConsumed(unconsumed);
    }

    @Test
    void aConsumerReleasedAfterConcurrentCloseCannotRecoverTheLease() throws Exception {
        var access = new OpenApiProducerCaptureAccess();
        var entered = new CountDownLatch(1); var start = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try (var lease = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5))) {
            var late = pool.submit(() -> {
                entered.countDown(); if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("consumer start timed out");
                boolean result = access.consume(request("", PATH, lease.token()));
                notConsumed(lease);
                return result;
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue(); lease.close(); start.countDown();
            assertThat(late.get(5, TimeUnit.SECONDS)).isFalse();
            notConsumed(lease);
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void capacityIsBoundedAndCloseOrExpiryRecoversCapacity() {
        var now = new AtomicLong(100);
        var access = new OpenApiProducerCaptureAccess(now::get);
        var leases = new ArrayList<OpenApiProducerCaptureAccess.Lease>();
        try {
            for (int index = 0; index < 256; index++) leases.add(access.issue("", PATH, 100));
            assertThatThrownBy(() -> access.issue("", PATH, 100)).isInstanceOf(IllegalStateException.class);
            leases.removeLast().close();
            try (var recovered = access.issue("", PATH, 100)) { assertThat(recovered.token()).isNotBlank(); }
            now.addAndGet(100);
            try (var recovered = access.issue("", PATH, 100)) { assertThat(recovered.token()).isNotBlank(); }
        } finally { leases.forEach(OpenApiProducerCaptureAccess.Lease::close); }
    }

    @Test
    void issuanceRejectsNoncanonicalIdentityAndExpiredOrUnboundedLifetime() {
        var access = new OpenApiProducerCaptureAccess();
        for (String path : List.of("relative", "/v3/api-docs/a?x=1", "/v3/api-docs/%61", "/v3/../api-docs", "/v3/api-docs/"))
            assertThatThrownBy(() -> access.issue("", path, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> access.issue("/host/", PATH, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> access.issue("", PATH, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> access.issue("", PATH, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> access.issue("", PATH, TimeUnit.MILLISECONDS.toNanos(Integer.MAX_VALUE) + 1_000_000))
                .isInstanceOf(IllegalArgumentException.class);
        try (var configured = access.issue("", PATH, TimeUnit.SECONDS.toNanos(90))) {
            assertThat(access.consume(request("", PATH, configured.token()))).isTrue();
        }
    }

    @Test
    void lifetimeIncludesTheTransportContractsLastFractionalMillisecondWithoutRenewal() {
        var now = new AtomicLong(10);
        var access = new OpenApiProducerCaptureAccess(now::get);
        long lifetime = TimeUnit.MILLISECONDS.toNanos(Integer.MAX_VALUE) + 999_999;
        try (var lease = access.issue("", PATH, lifetime)) {
            now.addAndGet(lifetime - 1);
            assertThat(access.consume(request("", PATH, lease.token()))).isTrue();
        }
        try (var expired = access.issue("", PATH, lifetime)) {
            now.addAndGet(lifetime);
            assertThat(access.consume(request("", PATH, expired.token()))).isFalse();
        }
    }

    @Test
    void twoConcurrentConsumersHaveExactlyOneWinner() throws Exception {
        var access = new OpenApiProducerCaptureAccess();
        var entered = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try (var lease = access.issue("", PATH, TimeUnit.SECONDS.toNanos(5))) {
            java.util.concurrent.Callable<Boolean> consume = () -> {
                entered.countDown(); if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("consumer start timed out");
                boolean result = access.consume(request("", PATH, lease.token()));
                lease.requireConsumed(); // Both threads must observe the one successful consumption.
                return result;
            };
            var first = pool.submit(consume); var second = pool.submit(consume);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            lease.requireConsumed();
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private static void notConsumed(OpenApiProducerCaptureAccess.Lease lease) {
        assertThatThrownBy(lease::requireConsumed).isInstanceOf(IllegalStateException.class)
                .hasMessage("Producer capture was not consumed within its live lease");
    }

    private static MockHttpServletRequest request(String context, String path, String token) {
        var servlet = new MockServletContext(); servlet.setContextPath(context);
        var request = new MockHttpServletRequest(servlet);
        request.setContextPath(context); request.setRequestURI(context + path);
        request.setMethod("GET"); request.setDispatcherType(DispatcherType.REQUEST);
        if (token != null) request.addHeader(OpenApiProducerCaptureAccess.HEADER, token);
        return request;
    }
}
