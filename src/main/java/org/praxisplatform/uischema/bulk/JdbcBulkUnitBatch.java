package org.praxisplatform.uischema.bulk;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Ordered results on the bound unit connection. The caller owns and closes the statement. */
final class JdbcBulkUnitBatch {
    private final PreparedStatement statement;
    private boolean result;
    private boolean consumed;

    JdbcBulkUnitBatch(PreparedStatement statement) throws SQLException {
        this.statement = statement;
        result = statement.execute();
    }

    <T> T rows(Read<T> read) throws SQLException {
        next();
        if (!result) throw corrupt();
        try (ResultSet rows = statement.getResultSet()) {
            if (rows == null) throw corrupt();
            return read.apply(rows);
        }
    }

    int updated() throws SQLException {
        next();
        if (result) throw corrupt();
        int count = statement.getUpdateCount();
        if (count < 0) throw corrupt();
        return count;
    }

    void end() throws SQLException {
        next();
        if (result || statement.getUpdateCount() != -1) throw corrupt();
    }

    private void next() throws SQLException {
        if (consumed) result = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        consumed = true;
    }

    private static BulkDurableExecutionException corrupt() {
        return new BulkDurableExecutionException(BulkDurableExecutionException.Reason.CORRUPT);
    }

    @FunctionalInterface interface Read<T> { T apply(ResultSet rows) throws SQLException; }
}
