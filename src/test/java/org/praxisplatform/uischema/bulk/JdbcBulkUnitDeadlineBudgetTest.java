package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC interaction only; execution of PostgreSQL's clock/CASE/GUC semantics needs the Neon gate. */
class JdbcBulkUnitDeadlineBudgetTest {
    private static final Instant DEADLINE = Instant.parse("2026-10-12T12:00:05Z");

    @Test void controlLimitsShareOneStatementAndRetainTheOneSecondCap() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        JdbcBulkDurableExecution.setControlLockBudget(connection);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("set_config('statement_timeout', ?, true)",
                "set_config('lock_timeout', ?, true)").doesNotContain("clock_timestamp", "idle_in_transaction");
        verify(statement).setString(1, "1000ms"); verify(statement).setString(2, "1000ms");
        verify(statement).execute(); verify(statement).close();
        verify(statement, never()).executeQuery();
        verifyNoMoreInteractions(connection);
    }

    @Test void databaseClockAndThreeLimitsShareOneStatementAndTheOriginalAbsoluteDeadline() throws Exception {
        var f = new Fixture(4500);
        JdbcBulkDurableExecution.setDeadlineBudget(f.connection, DEADLINE);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(f.connection).prepareStatement(sql.capture());
        String query = sql.getValue();
        assertThat(query).contains("with remaining as materialized", "floor(extract(epoch",
                "least(millis, ?)", "idle_in_transaction_session_timeout");
        assertThat(query.split("clock_timestamp\\(\\)", -1)).hasSize(2);
        // SQL's CASE must surround each volatile set_config, not only the returned balance.
        assertThat(query.split("case when millis > 0 then set_config", -1)).hasSize(4);
        assertThat(query).doesNotContain("greatest(", "now()", "statement_timestamp()");
        verify(f.statement).setObject(1, DEADLINE.atOffset(ZoneOffset.UTC));
        verify(f.statement).setLong(2, 1000L);
        verify(f.statement).executeQuery(); verify(f.statement, never()).execute();
        verify(f.rows).close(); verify(f.statement).close();
        verifyNoMoreInteractions(f.connection);
    }

    @Test void repeatedClampResamplesDatabaseClockWithoutRenewingTheDeadline() throws Exception {
        var f = new Fixture(4500);
        when(f.rows.getLong(1)).thenReturn(4500L, 1200L, 1L);
        when(f.rows.next()).thenReturn(true, false, true, false, true, false);
        for (int i = 0; i < 3; i++) JdbcBulkDurableExecution.setDeadlineBudget(f.connection, DEADLINE);
        verify(f.statement, times(3)).setObject(1, DEADLINE.atOffset(ZoneOffset.UTC));
        verify(f.statement, times(3)).executeQuery();
        verify(f.connection, times(3)).prepareStatement(anyString());
    }

    @Test void expiredZeroAndSubMillisecondRoundedToZeroAreRejected() throws Exception {
        for (long remaining : new long[] {-1000, -1, 0}) {
            var f = new Fixture(remaining);
            assertExpired(f);
            verify(f.rows).close(); verify(f.statement).close();
            verify(f.statement, never()).execute();
        }
    }

    @Test void positiveSubsecondRemainderIsNotRoundedUpOrRejectedByJava() throws Exception {
        for (long remaining : new long[] {1, 999}) {
            var f = new Fixture(remaining);
            JdbcBulkDurableExecution.setDeadlineBudget(f.connection, DEADLINE);
            verify(f.statement).setLong(2, 1000L);
        }
    }

    @Test void missingNullOrAmbiguousResultFailsClosed() throws Exception {
        var missing = new Fixture(1000); when(missing.rows.next()).thenReturn(false); assertExpired(missing);
        var nullValue = new Fixture(1000); when(nullValue.rows.wasNull()).thenReturn(true); assertExpired(nullValue);
        var duplicate = new Fixture(1000); when(duplicate.rows.next()).thenReturn(true, true); assertExpired(duplicate);
    }

    @Test void queryFailurePropagatesWithoutAnotherStatementAndClosesResources() throws Exception {
        var f = new Fixture(1000);
        var failure = new SQLException("private test diagnostic", "57014");
        when(f.statement.executeQuery()).thenThrow(failure);
        assertThatThrownBy(() -> JdbcBulkDurableExecution.setDeadlineBudget(f.connection, DEADLINE)).isSameAs(failure);
        verify(f.connection).prepareStatement(anyString()); verify(f.statement).close();
        verify(f.statement, never()).execute();
    }

    @Test void controlFailureCannotDispatchAnotherClamp() throws Exception {
        var f = new Fixture(1000);
        var failure = new SQLException("private test diagnostic", "57014");
        when(f.statement.execute()).thenThrow(failure);
        assertThatThrownBy(() -> JdbcBulkDurableExecution.setControlLockBudget(f.connection)).isSameAs(failure);
        verify(f.connection).prepareStatement(anyString()); verify(f.statement).close();
        verify(f.statement, never()).executeQuery();
    }

    private static void assertExpired(Fixture f) {
        assertThatThrownBy(() -> JdbcBulkDurableExecution.setDeadlineBudget(f.connection, DEADLINE))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.DEADLINE_EXCEEDED));
    }
    private static final class Fixture {
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);
        Fixture(long remaining) throws Exception {
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rows);
            when(rows.next()).thenReturn(true, false);
            when(rows.getLong(1)).thenReturn(remaining);
        }
    }
}
