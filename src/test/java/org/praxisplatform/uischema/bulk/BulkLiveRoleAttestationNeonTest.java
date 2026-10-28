package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit disposable-database proof: packaged bytes never cache live grant authority. */
@EnabledIfEnvironmentVariable(named="BULK_LIVE_PROOF_ENABLED",matches="true")
class BulkLiveRoleAttestationNeonTest {
    @Test void excessiveRuntimeGrantIsRejectedAfterWarmupAndRemovedAfterProof() throws Exception {
        String url=required("SPRING_DATASOURCE_URL"), runtime=required("SPRING_DATASOURCE_USERNAME");
        assertThat(runtime).matches("[a-z_][a-z_0-9]*");
        var roles=new BulkExecutionRoleConfiguration(required("BULK_OWNER_USERNAME"),Set.of(runtime),Set.of(),
                Set.of(required("BULK_LIVE_CONTROL_ROLE")));
        try(var connection=DriverManager.getConnection(url,runtime,required("SPRING_DATASOURCE_PASSWORD"));
            var owner=DriverManager.getConnection(url,required("BULK_OWNER_USERNAME"),required("BULK_OWNER_PASSWORD"));
            var admin=owner.createStatement()) {
            var originalColumns=updateColumns(owner,runtime);
            var grouped = BulkCatalogPhase.captureLive(connection, roles);
            var ungrouped = BulkCatalogPhase.captureAccess(connection, roles);
            assertThat(grouped.tableAcl).containsExactlyInAnyOrderElementsOf(ungrouped.tableAcl);
            BulkExecutionMigrator.validateLiveRuntimeRoleAccess(connection,roles);
            try {
                admin.executeUpdate("grant update on praxis_bulk.praxis_bulk_namespace_binding to \""+runtime+"\"");
                assertThatThrownBy(()->BulkExecutionMigrator.validateLiveRuntimeRoleAccess(connection,roles))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                admin.executeUpdate("revoke update on praxis_bulk.praxis_bulk_namespace_binding from \""+runtime+"\"");
                // PostgreSQL's table-level REVOKE also removes corresponding column grants.
                for(String column:originalColumns) {
                    assertThat(column).matches("[a-z_][a-z_0-9]*");
                    admin.executeUpdate("grant update (\""+column+"\") on praxis_bulk.praxis_bulk_namespace_binding to \""+runtime+"\"");
                }
            }
            assertThat(updateColumns(owner,runtime)).isEqualTo(originalColumns);
            BulkExecutionMigrator.validateLiveRuntimeRoleAccess(connection,roles);
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement();
                var rows=statement.executeQuery("select namespace_id from praxis_bulk.praxis_bulk_namespace_binding limit 1 for share")) {
                assertThat(rows.next()).isTrue();
            } finally { connection.rollback();connection.setAutoCommit(true); }
        }
    }
    private static Set<String> updateColumns(Connection owner,String role) throws SQLException {
        var columns=new java.util.TreeSet<String>();
        try(var query=owner.prepareStatement("select column_name from information_schema.column_privileges where table_schema='praxis_bulk' and table_name='praxis_bulk_namespace_binding' and grantee=? and privilege_type='UPDATE'")) {
            query.setString(1,role);try(var rows=query.executeQuery()){while(rows.next())columns.add(rows.getString(1));}
        }
        return Set.copyOf(columns);
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing explicit live-proof setting: "+name);
        return value;
    }
}
