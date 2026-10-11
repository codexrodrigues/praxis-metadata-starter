package org.praxisplatform.uischema.bulk;

import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulkLiveAttestationTimeoutTest {
    @Test void capturesPreviousBeforeLimitingAndRestoresIt() throws Exception {
        var c = connection("75ms");
        var restore = mock(PreparedStatement.class); when(c.prepareStatement(anyString())).thenReturn(restore);
        BulkExecutionInfrastructure.attestWithBoundStatementTimeout(c, ignored -> {});
        verify(restore).setString(1, "75ms"); verify(restore).execute(); verify(restore).close();
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(c.createStatement()).executeQuery(sql.capture());
        assertThat(sql.getValue()).contains("previous as materialized", "interval '250 milliseconds'", "then '250ms' else value");
    }
    @Test void restorationCannotMaskPrimarySqlRuntimeOrErrorFailure() throws Exception {
        for (Throwable primary : new Throwable[] {new SQLException("primary sql"), new IllegalStateException("primary runtime"), new AssertionError("primary error")}) {
            for (Throwable restore : new Throwable[] {new SQLException("restore sql"), new IllegalStateException("restore runtime"), new AssertionError("restore error")}) {
                var c = connection("2s"); when(c.prepareStatement(anyString())).thenThrow(restore);
                assertThatThrownBy(() -> BulkExecutionInfrastructure.attestWithBoundStatementTimeout(c, ignored -> {
                    if (primary instanceof SQLException sql) throw sql;
                    if (primary instanceof RuntimeException runtime) throw runtime;
                    throw (Error) primary;
                })).isSameAs(primary);
                assertThat(primary.getSuppressed()).contains(restore);
            }
        }
    }
    @Test void restorationFailureWithoutPrimaryStillRejectsOperation() throws Exception {
        var c = connection("0"); var failure = new IllegalStateException("expired transaction");
        when(c.prepareStatement(anyString())).thenThrow(failure);
        assertThatThrownBy(() -> BulkExecutionInfrastructure.attestWithBoundStatementTimeout(c, ignored -> {})).isSameAs(failure);
    }
    private static Connection connection(String previous) throws Exception {
        var c = mock(Connection.class); var s = mock(Statement.class); var r = mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s); when(s.executeQuery(anyString())).thenReturn(r);
        when(r.next()).thenReturn(true, false); when(r.getString(1)).thenReturn(previous);
        return c;
    }
}
