package org.praxisplatform.uischema.bulk;

import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Operation identity must match independently at the control lock and execution lock. */
class BulkPreparationControlTest {
    @Test void retainsTheExactLockedControl() throws Exception {
        var rows = rows("operation-a", 7L);
        var control = JdbcBulkDurableExecution.preparationControl(rows, "operation-a");
        assertThat(control).isEqualTo(new JdbcBulkOperationControl.Snapshot("READY", 7, "descriptor", "revision"));
    }

    @Test void rejectsAControlForAnotherOperationEvenWithTheSameGeneration() throws Exception {
        var rows = rows("operation-b", 7L);
        assertThatThrownBy(() -> JdbcBulkDurableExecution.preparationControl(rows, "operation-a"))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
    }

    @Test void absentControlRemainsAvailableForReceiptReplayButCannotGrantReady() throws Exception {
        assertThat(JdbcBulkDurableExecution.preparationControl(rows("operation-a", null), "operation-a")).isNull();
    }

    @Test void rejectsDuplicatedControlResults() throws Exception {
        var rows = rows("operation-a", 7L);
        when(rows.next()).thenReturn(true, true);
        assertThatThrownBy(() -> JdbcBulkDurableExecution.preparationControl(rows, "operation-a"))
                .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                        error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.CORRUPT));
    }

    private ResultSet rows(String operation, Long generation) throws Exception {
        var rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(operation);
        when(rows.getString(2)).thenReturn("READY");
        when(rows.getObject(3, Long.class)).thenReturn(generation);
        when(rows.getString(4)).thenReturn("descriptor");
        when(rows.getString(5)).thenReturn("revision");
        return rows;
    }
}
