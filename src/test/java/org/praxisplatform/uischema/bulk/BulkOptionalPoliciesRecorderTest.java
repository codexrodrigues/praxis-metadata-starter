package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** JDBC doubles exercise observer isolation; these tests claim no PostgreSQL timing result. */
class BulkOptionalPoliciesRecorderTest {
    private static final String SIX_SELECTS_TWELVE_BINDS = String.join(";",
            java.util.Collections.nCopies(6, "select ?, ?"));

    @Test
    void syntheticSdkReserveHasAStrictDerivedBoundaryAndAnExplicitlyNarrowerMarker() {
        assertThat(BulkOptionalPoliciesNeonTest.TRANSPORT_FLOOR_MILLIS).isEqualTo(100);
        assertThat(BulkOptionalPoliciesNeonTest.TRANSPORT_SUFFIX_EXECUTES).isEqualTo(11);
        assertThat(BulkOptionalPoliciesNeonTest.TRANSPORT_SUFFIX_OVERHEAD_MILLIS).isEqualTo(400);
        assertThat(BulkOptionalPoliciesNeonTest.TRANSPORT_PRE_POLICY_RESERVE_MILLIS).isEqualTo(1500);
        for (long insufficient : new long[] {-1, 0, 1499, 1500}) {
            assertThat(BulkOptionalPoliciesNeonTest.hasSyntheticPrePolicyReserve(insufficient))
                    .as("insufficient synthetic SDK reserve: %s", insufficient).isFalse();
        }
        for (long sufficient : new long[] {1501, 2081, 2098, 2100, 2147}) {
            assertThat(BulkOptionalPoliciesNeonTest.hasSyntheticPrePolicyReserve(sufficient))
                    .as("sufficient synthetic SDK reserve: %s", sufficient).isTrue();
        }
        assertThat(BulkOptionalPoliciesNeonTest.ORDERED_TRANSPORT_MARKER).isEqualTo(
                "NEON_PROOF_ORDERED_UNIT_TRANSPORT=sdk-synthetic,5s-unit,100ms-floor,pre-policy-reserve-over-1500ms,"
                + "39-executes,one-domain-effect-and-receipt,completed,replay-without-callback");
    }

    @Test
    void unrelatedAndFlooredExecutesDoNotInvokeTheAuthorizedReaderObserver() throws Exception {
        var recorder = new BulkOptionalPoliciesNeonTest.MeasuredDataSource(source(), () -> {
            throw new AssertionError("reader stack inspection escaped its scope");
        });
        try (var connection = recorder.getConnection()) {
            connection.prepareStatement("select 1").execute();
            recorder.beginUnitFloor();
            try {
                connection.prepareStatement("update fixture set status='UNIT_IN_FLIGHT', active_attempt_id=?").execute();
            } finally {
                recorder.endUnitFloor();
            }
        }
        assertThat(recorder.unitExecutes()).isEqualTo(1);
        assertThat(recorder.unitElapsedNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(100));
        assertThat(recorder.authorizedInspectionBatches()).isZero();
    }

    @Test
    void theScopedObserverStillRequiresTheCanonicalOriginAndExactBatchShape() throws Exception {
        var originCalls = new AtomicInteger();
        var recorder = new BulkOptionalPoliciesNeonTest.MeasuredDataSource(source(),
                () -> originCalls.incrementAndGet() == 1);
        try (var connection = recorder.getConnection(); var ignored = recorder.observeAuthorizedInspection()) {
            connection.prepareStatement(SIX_SELECTS_TWELVE_BINDS).execute();
            // Other statements made by the real reader/provider do not become batch witnesses.
            connection.prepareStatement("select 1").execute();
            assertThat(recorder.authorizedInspectionBatches()).isEqualTo(1);
            assertThat(originCalls).hasValue(2);
            assertThatThrownBy(recorder::beginUnitFloor).isInstanceOf(AssertionError.class);
        }
        try (var connection = recorder.getConnection()) {
            connection.prepareStatement(SIX_SELECTS_TWELVE_BINDS).execute();
        }
        assertThat(originCalls).hasValue(2);
        assertThat(recorder.authorizedInspectionBatches()).isEqualTo(1);
    }

    @Test
    void malformedBatchFailsAndClosesTheObserverEvenOnAssertionFailure() throws Exception {
        for (String invalid : new String[] {
                String.join(";", java.util.Collections.nCopies(5, "select ?, ?")),
                String.join(";", java.util.Collections.nCopies(6, "select ?")) }) {
            var originCalls = new AtomicInteger();
            var recorder = new BulkOptionalPoliciesNeonTest.MeasuredDataSource(source(), () -> {
                originCalls.incrementAndGet();
                return true;
            });
            assertThatThrownBy(() -> {
                try (var connection = recorder.getConnection(); var ignored = recorder.observeAuthorizedInspection()) {
                    connection.prepareStatement(invalid).execute();
                }
            }).isInstanceOf(AssertionError.class);
            try (var connection = recorder.getConnection()) {
                connection.prepareStatement(SIX_SELECTS_TWELVE_BINDS).execute();
            }
            assertThat(originCalls).hasValue(1);
            assertThat(recorder.authorizedInspectionBatches()).isZero();
        }
    }

    private static DataSource source() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        return source;
    }
}
