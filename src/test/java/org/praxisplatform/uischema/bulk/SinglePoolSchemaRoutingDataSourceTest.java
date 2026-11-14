package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SinglePoolSchemaRoutingDataSourceTest {

    @Test
    void transactionalCheckoutAppliesLocalSearchPathOnceLazilyAndSkipsPostCommitPhantomWork() throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection physical = mock(Connection.class);
        PreparedStatement searchPathStmt = mock(PreparedStatement.class);
        PreparedStatement domainQuery = mock(PreparedStatement.class);
        when(pool.getConnection()).thenReturn(physical);
        when(physical.getAutoCommit()).thenReturn(true);
        when(physical.prepareStatement("SELECT set_config('search_path', ?, ?)")).thenReturn(searchPathStmt);
        when(physical.prepareStatement("SELECT id FROM employees WHERE id = ?")).thenReturn(domainQuery);
        when(physical.prepareStatement("SELECT count(*) FROM dependents")).thenReturn(domainQuery);
        org.mockito.Mockito.doAnswer(inv -> {
            boolean next = inv.getArgument(0);
            when(physical.getAutoCommit()).thenReturn(next);
            return null;
        }).when(physical).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());

        var ds = new SinglePoolSchemaRoutingDataSource(
                pool, () -> SinglePoolSchemaRoutingDataSource.SchemaRoute.of("tenant_a", "public", "public"));

        try (Connection routed = ds.getConnection()) {
            routed.setReadOnly(true);
            routed.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            routed.setAutoCommit(false);

            try (PreparedStatement first = routed.prepareStatement("SELECT id FROM employees WHERE id = ?")) {
                assertThat(first).isNotSameAs(domainQuery);
                assertThat(first.getConnection()).isSameAs(routed);
                first.executeQuery();
            }
            try (PreparedStatement second = routed.prepareStatement("SELECT count(*) FROM dependents")) {
                assertThat(second.getConnection()).isSameAs(routed);
                second.executeQuery();
            }
            routed.commit();
            routed.setAutoCommit(true);
        }

        InOrder order = inOrder(physical, searchPathStmt, domainQuery);
        order.verify(physical).setReadOnly(true);
        order.verify(physical).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(physical).setAutoCommit(false);
        order.verify(physical).prepareStatement("SELECT set_config('search_path', ?, ?)");
        order.verify(searchPathStmt).setString(1, "tenant_a, public");
        order.verify(searchPathStmt).setBoolean(2, true);
        order.verify(searchPathStmt).execute();
        order.verify(physical).prepareStatement("SELECT id FROM employees WHERE id = ?");
        order.verify(domainQuery).executeQuery();
        order.verify(physical).prepareStatement("SELECT count(*) FROM dependents");
        order.verify(domainQuery).executeQuery();
        order.verify(physical).commit();
        order.verify(physical).setAutoCommit(true);
        order.verify(physical).close();

        verify(domainQuery, times(2)).executeQuery();
        verify(physical, times(1)).prepareStatement("SELECT set_config('search_path', ?, ?)");
        verify(searchPathStmt, never()).setBoolean(2, false);
    }

    @Test
    void autoCommitStatementSetsSessionSearchPathOnceAndResetsOnClose() throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection physical = mock(Connection.class);
        PreparedStatement searchPathStmt = mock(PreparedStatement.class);
        Statement queryStmt = mock(Statement.class);
        when(pool.getConnection()).thenReturn(physical);
        when(physical.getAutoCommit()).thenReturn(true);
        when(physical.prepareStatement("SELECT set_config('search_path', ?, ?)")).thenReturn(searchPathStmt);
        when(physical.createStatement()).thenReturn(queryStmt);

        var ds = new SinglePoolSchemaRoutingDataSource(
                pool, () -> SinglePoolSchemaRoutingDataSource.SchemaRoute.of("tenant_b", "public", "public"));

        try (Connection routed = ds.getConnection();
             Statement stmt = routed.createStatement()) {
            assertThat(stmt.getConnection()).isSameAs(routed);
            stmt.executeQuery("SELECT current_setting('search_path')");
            stmt.executeQuery("SELECT current_setting('search_path')");
        }

        verify(physical, times(2)).prepareStatement("SELECT set_config('search_path', ?, ?)");
        InOrder order = inOrder(searchPathStmt, queryStmt, physical);
        order.verify(searchPathStmt).setString(1, "tenant_b, public");
        order.verify(searchPathStmt).setBoolean(2, false);
        order.verify(searchPathStmt).execute();
        order.verify(queryStmt, times(2)).executeQuery("SELECT current_setting('search_path')");
        order.verify(searchPathStmt).setString(1, "public");
        order.verify(searchPathStmt).setBoolean(2, false);
        order.verify(searchPathStmt).execute();
        order.verify(physical).close();
    }

    @Test
    void sequentialTransactionsAndSetTransactionStatementsPreservePostgresProtocol() throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection physical = mock(Connection.class);
        PreparedStatement searchPathStmt = mock(PreparedStatement.class);
        PreparedStatement domainStmt = mock(PreparedStatement.class);
        Statement rawStmt = mock(Statement.class);
        Savepoint savepoint = mock(Savepoint.class);
        when(pool.getConnection()).thenReturn(physical);
        when(physical.getAutoCommit()).thenReturn(false);
        when(physical.prepareStatement("SELECT set_config('search_path', ?, ?)")).thenReturn(searchPathStmt);
        when(physical.prepareStatement("SELECT 1")).thenReturn(domainStmt);
        when(physical.createStatement()).thenReturn(rawStmt);
        when(physical.setSavepoint()).thenReturn(savepoint);

        var ds = new SinglePoolSchemaRoutingDataSource(
                pool, () -> SinglePoolSchemaRoutingDataSource.SchemaRoute.of("tenant_a", "public", "public"));

        try (Connection routed = ds.getConnection()) {
            try (Statement s = routed.createStatement()) {
                s.executeUpdate("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
            }
            verify(physical, never()).prepareStatement("SELECT set_config('search_path', ?, ?)");

            Savepoint sp = routed.setSavepoint();
            assertThat(sp).isSameAs(savepoint);
            routed.rollback(sp);
            try (PreparedStatement reused = routed.prepareStatement("SELECT 1")) {
                reused.executeQuery();
                routed.commit();

                reused.executeQuery();
                routed.rollback();
            }
        }

        InOrder order = inOrder(rawStmt, physical, domainStmt);
        order.verify(rawStmt).executeUpdate("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
        order.verify(physical).prepareStatement("SELECT set_config('search_path', ?, ?)");
        order.verify(physical).setSavepoint();
        order.verify(physical).rollback(savepoint);
        order.verify(physical).prepareStatement("SELECT 1");
        order.verify(domainStmt).executeQuery();
        order.verify(physical).commit();
        order.verify(physical).prepareStatement("SELECT set_config('search_path', ?, ?)");
        order.verify(domainStmt).executeQuery();
        order.verify(physical).rollback();
        verify(physical, times(2)).prepareStatement("SELECT set_config('search_path', ?, ?)");
        verify(physical, times(1)).prepareStatement("SELECT 1");
        verify(searchPathStmt, times(2)).setBoolean(2, true);
        verify(searchPathStmt, never()).setBoolean(2, false);
    }

    @Test
    void statementProxyPreservesIdentitySemanticsAndBlocksConnectionAndStatementUnwrapping() throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection physical = mock(Connection.class);
        PreparedStatement domainStmt = mock(PreparedStatement.class);
        when(pool.getConnection()).thenReturn(physical);
        when(physical.getAutoCommit()).thenReturn(false);
        when(physical.prepareStatement("SELECT set_config('search_path', ?, ?)")).thenReturn(mock(PreparedStatement.class));
        when(physical.prepareStatement("SELECT 1")).thenReturn(domainStmt);

        var ds = new SinglePoolSchemaRoutingDataSource(
                pool, () -> SinglePoolSchemaRoutingDataSource.SchemaRoute.of("tenant_a", "public", "public"));

        try (Connection routed = ds.getConnection();
             PreparedStatement stmt = routed.prepareStatement("SELECT 1")) {
            assertThat(stmt.equals(stmt)).isTrue();
            assertThat(stmt.equals(domainStmt)).isFalse();
            Set<Statement> registry = new HashSet<>();
            registry.add(stmt);
            assertThat(registry.contains(stmt)).isTrue();
            assertThat(registry.remove(stmt)).isTrue();
            assertThat(registry).isEmpty();
            assertThat(stmt.toString()).contains("PreparedStatement");

            assertThat(routed.isWrapperFor(Connection.class)).isFalse();
            assertThat(routed.isWrapperFor(CustomVendorConnection.class)).isFalse();
            assertThat(routed.isWrapperFor(Wrapper.class)).isTrue();
            assertThat(routed.unwrap(Wrapper.class)).isSameAs(routed);

            assertThat(stmt.isWrapperFor(PreparedStatement.class)).isFalse();
            assertThat(stmt.isWrapperFor(CustomVendorStatement.class)).isFalse();
            assertThat(stmt.isWrapperFor(Wrapper.class)).isTrue();
            assertThat(stmt.unwrap(Wrapper.class)).isSameAs(stmt);

            assertThatThrownBy(() -> routed.unwrap(Connection.class))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("42501");
            assertThatThrownBy(() -> routed.unwrap(CustomVendorConnection.class))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("42501");
            assertThatThrownBy(() -> stmt.unwrap(PreparedStatement.class))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("42501");
            assertThatThrownBy(() -> stmt.unwrap(CustomVendorStatement.class))
                    .isInstanceOf(SQLException.class)
                    .extracting(ex -> ((SQLException) ex).getSQLState())
                    .isEqualTo("42501");
        }
    }

    @Test
    void abortsPhysicalConnectionWhenDirtySessionSearchPathCannotBeResetOnClose() throws Exception {
        DataSource pool = mock(DataSource.class);
        Connection physical = mock(Connection.class);
        PreparedStatement applyStmt = mock(PreparedStatement.class);
        PreparedStatement resetStmt = mock(PreparedStatement.class);
        Statement domainStmt = mock(Statement.class);
        when(pool.getConnection()).thenReturn(physical);
        when(physical.getAutoCommit()).thenReturn(true);
        when(physical.createStatement()).thenReturn(domainStmt);
        when(physical.prepareStatement("SELECT set_config('search_path', ?, ?)"))
                .thenReturn(applyStmt)
                .thenReturn(resetStmt);
        when(resetStmt.execute()).thenThrow(new SQLException("statement timeout", "57014"));

        var ds = new SinglePoolSchemaRoutingDataSource(
                pool, () -> SinglePoolSchemaRoutingDataSource.SchemaRoute.of("tenant_b", "public", "public"));

        try (Connection routed = ds.getConnection();
             Statement stmt = routed.createStatement()) {
            stmt.executeQuery("SELECT 1");
        }

        InOrder order = inOrder(resetStmt, physical);
        order.verify(resetStmt).execute();
        order.verify(physical).abort(any(Executor.class));
        order.verify(physical).close();
    }

    private interface CustomVendorConnection {
    }

    private interface CustomVendorStatement {
    }
}
