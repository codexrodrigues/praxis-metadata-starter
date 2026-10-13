package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** No database: verifies initial predicate construction/authentication and rejection ordering, not SQL semantics. */
class BulkManagedInitialScopeMembershipTest {
    private static final BulkExecutionRoleConfiguration ROLES =
            new BulkExecutionRoleConfiguration("managed_owner", Set.of("runtime"), Set.of(), Set.of("control"));

    @Test void initialMembershipQueriesUseNarrowAuthenticatedAbsentSchemaAlternative() throws Exception {
        var probe = new Probe(true, 0, 0);
        BulkExecutionMigrator.validateInitialManagedScopeAdmission(probe.connection, ROLES, true);
        verify(probe.authentication).setString(1, "managed_owner");
        var queries = probe.queries.stream().filter(s -> s.contains("pg_auth_members m")).toList();
        assertThat(queries).hasSize(2);
        queries.forEach(query -> assertThat(query)
                .contains("not exists(select 1 from pg_catalog.pg_namespace n where n.nspname='praxis_bulk')")
                .contains("current_user=session_user", "actor.oid=m.member and actor.rolname=current_user")
                .contains("m.admin_option and not m.inherit_option and not m.set_option")
                .contains("bootstrap.oid=m.grantor and bootstrap.rolsuper")
                .contains("same_actor.roleid=m.roleid and same_actor.member=m.member")
                .contains("and not same_actor.inherit_option and not same_actor.set_option)=1"));
        assertThat(probe.queries).allSatisfy(query -> assertThat(query).doesNotContain("create ", "grant ", "revoke "));
        assertThat(BulkExecutionMigrator.MANAGED_ADMIN_MEMBERSHIP_PREDICATE)
                .contains("n.nspname='praxis_bulk' and n.nspowner=m.member")
                .doesNotContain("actor.rolname=current_user", "not exists(");
    }

    @Test void wrongAuthenticatedOwnerRejectsBeforeMembershipOrCatalogReads() throws Exception {
        var probe = new Probe(false, 0, 0);
        assertThatThrownBy(() -> BulkExecutionMigrator.validateInitialManagedScopeAdmission(probe.connection, ROLES, true))
                .isInstanceOf(IllegalStateException.class).hasMessage("Initial migration owner identity differs");
        verify(probe.connection, never()).createStatement();
        assertThat(probe.queries).hasSize(1);
    }

    @Test void unsafeInternalRoleAttributesStillRejectBeforeMembershipInterpretation() throws Exception {
        var probe = new Probe(true, 1, 0);
        assertThatThrownBy(() -> BulkExecutionMigrator.validateInitialManagedScopeAdmission(probe.connection, ROLES, true))
                .isInstanceOf(IllegalStateException.class).hasMessage("Pre-existing internal role attributes are unsafe");
        assertThat(probe.queries).noneMatch(query -> query.contains("pg_auth_members m"));
    }

    @Test void unexpectedInternalMembershipStillRejectsBeforeSchemaGrantChecks() throws Exception {
        var probe = new Probe(true, 0, 1);
        assertThatThrownBy(() -> BulkExecutionMigrator.validateInitialManagedScopeAdmission(probe.connection, ROLES, true))
                .isInstanceOf(IllegalStateException.class).hasMessage("Pre-existing internal role membership is unsafe");
        assertThat(probe.queries).noneMatch(query -> query.contains("aclexplode"));
    }

    private static final class Probe {
        final Connection connection = mock(Connection.class);
        final PreparedStatement authentication = mock(PreparedStatement.class);
        final List<String> queries = new ArrayList<>();
        Probe(boolean authenticated, long unsafeAttributes, long unsafeMemberships) throws Exception {
            var identityRows = bool(authenticated);
            when(authentication.executeQuery()).thenReturn(identityRows);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String query = call.getArgument(0); queries.add(query);
                if (query.contains("current_user=session_user and current_user=?")) return authentication;
                var statement = mock(PreparedStatement.class);
                var membershipRows = count(unsafeMemberships);
                when(statement.executeQuery()).thenReturn(membershipRows); return statement;
            });
            var array = mock(java.sql.Array.class);
            when(connection.createArrayOf(anyString(), any())).thenReturn(array);
            when(connection.createStatement()).thenAnswer(call -> {
                var statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(queryCall -> {
                    String query = queryCall.getArgument(0); queries.add(query);
                    if (query.contains("select exists(")) return bool(false);
                    return count(query.contains("rolcanlogin") ? unsafeAttributes : 0);
                }); return statement;
            });
        }
        private static ResultSet count(long count) throws Exception {
            var rows = mock(ResultSet.class); when(rows.next()).thenReturn(true, false);
            when(rows.getLong(1)).thenReturn(count); return rows;
        }
        private static ResultSet bool(boolean value) throws Exception {
            var rows = mock(ResultSet.class); when(rows.next()).thenReturn(true, false);
            when(rows.getBoolean(1)).thenReturn(value); return rows;
        }
    }
}
