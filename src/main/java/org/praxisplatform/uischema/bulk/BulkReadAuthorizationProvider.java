package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Host-owned authorization for protected bulk reads. Metadata invokes this provider only inside
 * its operational read-only snapshot; the provider must join the transaction bound to the exact
 * {@link #executionInfrastructure()} instance and must not open an independent connection.
 *
 * <p>This SPI is server-side only. It neither authorizes mutation nor publishes a capability,
 * endpoint, cursor or readiness state.</p>
 */
public interface BulkReadAuthorizationProvider {

    String confirmationOperationId();

    BulkExecutionInfrastructure executionInfrastructure();

    GlobalDecision preAuthorize(Context context, Duration remainingBudget);

    ScopeDecision authorize(Context context, String creatorSubjectId,
            List<Target> fullTargetSet, Duration remainingBudget);

    enum GlobalDecision { ALLOWED, DENIED, UNAVAILABLE }

    enum State { AUTHORIZED_ALL, DENIED_OR_REDUCED, AUTHORITY_UNAVAILABLE }

    /** Trusted current request identity and operation binding, never populated from raw headers. */
    @JsonIgnoreType
    record Context(String namespaceId, String resourceKey, String operationId,
            String requesterSubjectId) {
        public Context {
            BulkContractChecks.text(namespaceId, "namespaceId");
            BulkContractChecks.text(resourceKey, "resourceKey");
            BulkContractChecks.text(operationId, "operationId");
            BulkContractChecks.text(requesterSubjectId, "requesterSubjectId");
        }

        @Override public String toString() { return "BulkReadAuthorizationContext[protected]"; }
    }

    /** Minimal protected dependency view. Facts are never a public response projection. */
    @JsonIgnoreType
    final class Target {
        private final int ordinal;
        private final Object wireIdentity;
        private final JsonNode facts;

        public Target(int ordinal, Object wireIdentity, JsonNode facts) {
            if (ordinal < 0 || ordinal > 9_999)
                throw new IllegalArgumentException("Target ordinal is outside bulk limits");
            if (!(wireIdentity instanceof String text && !text.isEmpty())
                    && !(wireIdentity instanceof Integer))
                throw new IllegalArgumentException("Target wire identity must be canonical");
            if (facts == null || !facts.isObject())
                throw new IllegalArgumentException("Target facts must be a protected object");
            this.ordinal = ordinal;
            this.wireIdentity = wireIdentity;
            this.facts = BulkCanonicalJson.normalize(facts);
        }

        public int ordinal() { return ordinal; }
        public Object wireIdentity() { return wireIdentity; }
        public JsonNode facts() { return facts.deepCopy(); }
        @Override public String toString() { return "BulkReadAuthorizationTarget[protected]"; }
    }

    /** Opaque current-scope proof. Only a complete authorization carries a fingerprint. */
    @JsonIgnoreType
    final class ScopeDecision {
        private static final int SHA256_BYTES = 32;
        private final State state;
        private final byte[] scopeFingerprint;

        private ScopeDecision(State state, byte[] scopeFingerprint) {
            this.state = Objects.requireNonNull(state, "state");
            if (state == State.AUTHORIZED_ALL) {
                if (scopeFingerprint == null || scopeFingerprint.length != SHA256_BYTES)
                    throw new IllegalArgumentException("Authorized reads require a SHA-256 scope fingerprint");
                this.scopeFingerprint = scopeFingerprint.clone();
            } else {
                if (scopeFingerprint != null)
                    throw new IllegalArgumentException("Denied reads cannot carry a scope fingerprint");
                this.scopeFingerprint = null;
            }
        }

        public static ScopeDecision authorized(byte[] fingerprint) {
            return new ScopeDecision(State.AUTHORIZED_ALL, fingerprint);
        }
        public static ScopeDecision denied() {
            return new ScopeDecision(State.DENIED_OR_REDUCED, null);
        }
        public static ScopeDecision unavailable() {
            return new ScopeDecision(State.AUTHORITY_UNAVAILABLE, null);
        }
        public State state() { return state; }
        public byte[] scopeFingerprint() {
            return scopeFingerprint == null ? null : scopeFingerprint.clone();
        }

        @Override public boolean equals(Object other) {
            return this == other || other instanceof ScopeDecision that
                    && state == that.state && Arrays.equals(scopeFingerprint, that.scopeFingerprint);
        }
        @Override public int hashCode() { return 31 * state.hashCode() + Arrays.hashCode(scopeFingerprint); }
        @Override public String toString() { return "BulkReadAuthorizationDecision[" + state + "]"; }
    }
}
