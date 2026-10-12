package org.praxisplatform.uischema.bulk;

import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Budget arithmetic and JDBC interaction only; physical PostgreSQL evidence is a separate gate. */
class JdbcBulkProposalCaptureBudgetTest {
    private static final Instant DB_NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final Class<?> BUDGET;
    static {
        try { BUDGET = Class.forName(JdbcBulkProposalStore.class.getName() + "$CaptureBudget"); }
        catch (ClassNotFoundException error) { throw new ExceptionInInitializerError(error); }
    }

    @Test void initializationAndEveryCheckUseOneRoundTripWithFreshClock() throws Throwable {
        var f = new Fixture();
        var calls = new AtomicInteger();
        var budget = f.budget(() -> { calls.incrementAndGet(); return Duration.ofSeconds(2); });
        for (int index = 0; index < 10; index++) invoke(budget, "check");
        assertThat(calls).hasValue(10);
        verify(f.connection, times(1)).createStatement();
        var initialSql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.initial).executeQuery(initialSql.capture());
        assertThat(initialSql.getValue()).contains("current_setting('statement_timeout')",
                "current_setting('lock_timeout')", "clock_timestamp()");
        verify(f.tighten, times(10)).executeQuery();
        verify(f.tighten, never()).execute();
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.connection, times(10)).prepareStatement(sql.capture());
        assertThat(sql.getAllValues()).allSatisfy(value -> assertThat(value).contains(
                "current_setting('statement_timeout') = '0'",
                "current_setting('statement_timeout')::interval > ?::interval",
                "then ? else current_setting('statement_timeout') end, true)",
                "current_setting('lock_timeout')::interval > ?::interval",
                "then ? else current_setting('lock_timeout') end, true)", "clock_timestamp()"));
        verify(f.tighten, times(10)).setString(eq(1), matches("[0-9]+ms"));
        verify(f.tighten, times(10)).setString(eq(2), matches("[0-9]+ms"));
        verify(f.tighten, times(10)).setString(eq(3), matches("[0-9]+ms"));
        verify(f.tighten, times(10)).setString(eq(4), matches("[0-9]+ms"));
    }

    @Test void callbackSupplierStillPerformsSqlAndKeepsTheInitialCeiling() throws Throwable {
        var f = new Fixture();
        var budget = f.budget(() -> Duration.ofSeconds(20));
        Duration first = (Duration) invoke(budget, "get");
        Duration second = (Duration) invoke(budget, "get");
        assertThat(first).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(second).isPositive().isLessThanOrEqualTo(first);
        verify(f.tighten, times(2)).execute();
        verify(f.tighten, never()).executeQuery();
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.connection, times(2)).prepareStatement(sql.capture());
        assertThat(sql.getAllValues()).allSatisfy(value -> assertThat(value).doesNotContain("clock_timestamp()"));
    }

    @Test void participationElapsedIsNotChargedAgainToTheLaterInitialSample() throws Throwable {
        var f = new Fixture(); long now = System.nanoTime();
        var budget = f.budget(() -> Duration.ofSeconds(2), now - Duration.ofSeconds(5).toNanos(),
                now, Duration.ofSeconds(2));
        assertThat((Duration) invoke(budget, "get")).isGreaterThan(Duration.ofSeconds(1));
    }

    @Test void supplierElapsedAndTheOriginalThirtySecondCeilingAreStillCharged() throws Throwable {
        var f = new Fixture(); long now = System.nanoTime();
        var sampled = f.budget(() -> Duration.ofSeconds(20), now - Duration.ofSeconds(5).toNanos(),
                now - Duration.ofSeconds(1).toNanos(), Duration.ofSeconds(2));
        assertThat((Duration) invoke(sampled, "get")).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
        var expired = f.budget(() -> Duration.ofSeconds(20), now - Duration.ofSeconds(31).toNanos(),
                now, Duration.ofSeconds(20));
        assertThatThrownBy(() -> invoke(expired, "get")).isInstanceOf(BulkProposalStorageException.class);
    }

    @Test void freshDatabaseExpiryCannotBeRenewedByTheCaller() throws Throwable {
        var f = new Fixture(); var budget = f.budget(() -> Duration.ofSeconds(20));
        for (Instant clock : new Instant[] {f.proposal.expiresAt(), f.proposal.expiresAt().plusSeconds(1)}) {
            when(f.tighten.executeQuery()).thenAnswer(ignored -> rows(clock));
            assertThatThrownBy(() -> invoke(budget, "check")).isInstanceOf(BulkProposalStorageException.class);
        }
        assertThat(f.proposal.expiresAt()).isEqualTo(DB_NOW.plusSeconds(900));
    }

    @Test void databaseExpiryAlsoCapsTheMonotonicRemainingAndRejectsExpiredInitialization() throws Throwable {
        var f = new Fixture();
        when(f.initial.executeQuery(anyString())).thenAnswer(ignored -> rows(f.proposal.expiresAt().minusSeconds(1)));
        var budget = f.budget(() -> Duration.ofSeconds(20));
        assertThat((Duration) invoke(budget, "get")).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
        when(f.initial.executeQuery(anyString())).thenAnswer(ignored -> rows(f.proposal.expiresAt()));
        assertThatThrownBy(() -> f.budget(() -> Duration.ofSeconds(20))).isInstanceOf(BulkProposalStorageException.class);
    }

    @Test void exhaustedCallerAndSubMillisecondBudgetsNeverIssueClampSql() throws Throwable {
        for (Duration remaining : new Duration[] {Duration.ZERO, Duration.ofNanos(-1), Duration.ofNanos(1)}) {
            var f = new Fixture(); var budget = f.budget(() -> remaining);
            assertThatThrownBy(() -> invoke(budget, "get")).isInstanceOf(BulkProposalStorageException.class);
            verify(f.connection, never()).prepareStatement(anyString());
        }
    }

    @Test void sqlFailureFromCallbackSupplierRemainsSanitized() throws Throwable {
        var f = new Fixture(); var budget = f.budget(() -> Duration.ofSeconds(2));
        when(f.tighten.execute()).thenThrow(new SQLException("protected sql text", "57014"));
        assertThatThrownBy(() -> invoke(budget, "get")).isInstanceOf(BulkProposalStorageException.class)
                .hasNoCause().satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
    }

    @Test void restoresExactPreviousLimits() throws Throwable {
        var f = new Fixture(); var budget = f.budget(() -> Duration.ofSeconds(2));
        invokeRestore(budget, null);
        verify(f.tighten).setString(1, "75ms"); verify(f.tighten).setString(2, "100ms");
        verify(f.tighten).execute(); verify(f.tighten).close();
    }

    @Test void restorationCannotReplaceSqlRuntimeOrErrorPrimary() throws Throwable {
        for (int p = 0; p < 3; p++) for (int r = 0; r < 3; r++) {
            var f = new Fixture(); var budget = f.budget(() -> Duration.ofSeconds(2));
            Throwable primary = failure(p), restore = failure(r);
            when(f.connection.prepareStatement(anyString())).thenThrow(restore);
            assertThatThrownBy(() -> {
                try { throw primary; } finally { invokeRestore(budget, primary); }
            }).isSameAs(primary);
            assertThat(primary.getSuppressed()).containsExactly(restore);
        }
    }

    @Test void safePrimaryNeverReceivesProtectedRestoreDetailsOrSelfSuppression() throws Throwable {
        for (int r = 0; r < 3; r++) {
            var f = new Fixture(); var budget = f.budget(() -> Duration.ofSeconds(2));
            var primary = new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
            when(f.connection.prepareStatement(anyString())).thenThrow(failure(r));
            invokeRestore(budget, primary);
            assertThat(primary).hasNoCause(); assertThat(primary.getSuppressed()).isEmpty();
        }
        var f = new Fixture(); var budget = f.budget(() -> Duration.ofSeconds(2));
        var primary = new AssertionError("primary");
        when(f.connection.prepareStatement(anyString())).thenThrow(primary);
        invokeRestore(budget, primary); assertThat(primary.getSuppressed()).isEmpty();
    }

    @Test void restorationWithoutPrimaryRejectsSuccessForAllFailureKinds() throws Throwable {
        for (int r = 0; r < 3; r++) {
            var f = new Fixture(); var budget = f.budget(() -> Duration.ofSeconds(2));
            Throwable restore = failure(r);
            when(f.connection.prepareStatement(anyString())).thenThrow(restore);
            assertThatThrownBy(() -> invokeRestore(budget, null)).isSameAs(restore);
        }
    }

    private static Throwable failure(int kind) {
        return switch (kind) { case 0 -> new SQLException("protected SQL", "57014");
            case 1 -> new IllegalStateException("protected runtime"); default -> new AssertionError("protected error"); };
    }
    private static Object invoke(Object budget, String method) throws Throwable {
        var reflected = BUDGET.getDeclaredMethod(method); reflected.setAccessible(true);
        try { return reflected.invoke(budget); } catch (InvocationTargetException error) { throw error.getCause(); }
    }
    private static void invokeRestore(Object budget, Throwable primary) throws Throwable {
        var reflected = BUDGET.getDeclaredMethod("restore", Throwable.class); reflected.setAccessible(true);
        try { reflected.invoke(budget, primary); } catch (InvocationTargetException error) { throw error.getCause(); }
    }
    private static ResultSet rows(Instant clock) throws Exception {
        var rows = mock(ResultSet.class); when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("75ms"); when(rows.getString(2)).thenReturn("100ms");
        when(rows.getObject(3, OffsetDateTime.class)).thenReturn(clock.atOffset(ZoneOffset.UTC));
        return rows;
    }
    private static final class Fixture {
        final Connection connection = mock(Connection.class);
        final Statement initial = mock(Statement.class);
        final PreparedStatement tighten = mock(PreparedStatement.class);
        final BulkStoredProposal proposal = new BulkStoredProposal(UUID.randomUUID(), DB_NOW,
                DB_NOW.plusSeconds(900), BulkSnapshotStorageCodecTest.proposal().snapshot(),
                BulkSnapshotStorageCodecTest.CONTROL_EXPECTATION);
        Fixture() throws Exception {
            when(connection.createStatement()).thenReturn(initial);
            when(initial.executeQuery(anyString())).thenAnswer(ignored -> rows(DB_NOW));
            when(connection.prepareStatement(anyString())).thenReturn(tighten);
            when(tighten.executeQuery()).thenAnswer(ignored -> rows(DB_NOW));
        }
        Object budget(Supplier<Duration> caller) throws Throwable {
            long now = System.nanoTime(); return budget(caller, now, now, Duration.ofSeconds(2));
        }
        Object budget(Supplier<Duration> caller, long began, long initialBegan, Duration initial) throws Throwable {
            var constructor = BUDGET.getDeclaredConstructor(Connection.class, BulkStoredProposal.class,
                    Supplier.class, long.class, long.class, Duration.class);
            constructor.setAccessible(true);
            try { return constructor.newInstance(connection, proposal, caller, began, initialBegan, initial); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
    }
}
