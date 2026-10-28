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
            BulkExecutionMigrator.validateLiveRuntimeRoleAccess(connection,roles);
            try {
                admin.executeUpdate("grant update on praxis_bulk.praxis_bulk_namespace_binding to \""+runtime+"\"");
                assertThatThrownBy(()->BulkExecutionMigrator.validateLiveRuntimeRoleAccess(connection,roles))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                admin.executeUpdate("revoke update on praxis_bulk.praxis_bulk_namespace_binding from \""+runtime+"\"");
            }
            BulkExecutionMigrator.validateLiveRuntimeRoleAccess(connection,roles);
        }
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalStateException("Missing explicit live-proof setting: "+name);
        return value;
    }
}
