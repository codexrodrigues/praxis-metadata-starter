package org.praxisplatform.uischema.bulk;

import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The former ACL-only phase now shares eight catalog reads with the structural checks. */
class BulkTableAclSnapshotTest {
    private static final BulkExecutionRoleConfiguration ROLES = new BulkExecutionRoleConfiguration(
            "owner", Set.of("runtime"), Set.of(), Set.of("control"));

    @Test void eightReadsCaptureFactsWithoutValidatingUnrequestedChecks() throws Exception {
        var c = mock(Connection.class);
        var phase = capture(c, Map.of(0, List.of(relation("table")), 5, List.of(
                acl("table", "owner", "T:SELECT", true, true),
                acl("table", "runtime", "T:SELECT", false, false),
                acl("table", "runtime", "C:field:UPDATE", false, false),
                acl("table", "PUBLIC", "C:field:SELECT", false, false))));
        assertThat(phase.privileges("table", "runtime")).containsExactlyInAnyOrder("T:SELECT", "C:field:UPDATE");
        assertThat(phase.privileges("table", "PUBLIC")).containsExactly("C:field:SELECT");
        assertThat(phase.privileges("table", "absent")).isEmpty();
        // Missing V19 and role facts are not an implicit full validation during capture.
        assertThat(BulkCapacityOccupancyCatalog.installed(phase)).isFalse();
        verify(c, times(7)).createStatement();
        verify(c, times(1)).prepareStatement(anyString());
    }

    @Test void distinguishesAbsentTableFromExistingTableWithoutAcl() throws Exception {
        var phase = capture(mock(Connection.class), Map.of(0, List.of(relation("present"))));
        assertThat(phase.privileges("present", "runtime")).isEmpty();
        assertThatThrownBy(() -> phase.privileges("absent", "runtime"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("absent from ACL snapshot");
    }

    @Test void preservesPairGrantOptionScopeAndSchemaWideNonOwnerScope() throws Exception {
        var phase = capture(mock(Connection.class), Map.of(0, List.of(relation("expected"), relation("outside")),
                5, List.of(acl("expected", "owner", "T:SELECT", true, true),
                        acl("expected", "runtime", "T:SELECT", false, false),
                        acl("outside", "runtime", "C:id:UPDATE", true, false))));
        assertThat(phase.privileges("expected", "runtime")).containsExactly("T:SELECT");
        assertThat(phase.allPrivileges("owner")).isEmpty();
        assertThatThrownBy(() -> phase.privileges("expected", "owner")).hasMessageContaining("grant options are forbidden");
        assertThatThrownBy(() -> phase.allPrivileges("runtime")).hasMessageContaining("V19 table grant option forbidden");
    }

    @Test void retainsGrantsOnUnexpectedRelationsInWholeSchemaComparison() throws Exception {
        var phase = capture(mock(Connection.class), Map.of(5, List.of(acl("outside", "capacity-owner", "T:SELECT", false, false))));
        assertThat(phase.allPrivileges("capacity-owner")).containsEntry("outside", Set.of("T:SELECT"));
    }

    @Test void recapturesSameConnectionWithoutMutatingPreviousFacts() throws Exception {
        var c = mock(Connection.class);
        var before = capture(c, Map.of(0, List.of(relation("table"))));
        var after = capture(c, Map.of(0, List.of(relation("table")), 5, List.of(acl("table", "runtime", "T:SELECT", false, false))));
        assertThat(before.privileges("table", "runtime")).isEmpty();
        assertThat(after.privileges("table", "runtime")).containsExactly("T:SELECT");
        assertThatThrownBy(() -> after.relations.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsPartialInventoryAndWrongRelationKindsOnlyWhenRequested() throws Exception {
        var partial = capture(mock(Connection.class), Map.of(0, List.of(relation(BulkCapacityOccupancyCatalog.SLOT))));
        assertThatThrownBy(() -> BulkCapacityOccupancyCatalog.installed(partial)).hasMessageContaining("partial");
        var wrong = capture(mock(Connection.class), Map.of(0, List.of(Map.of("relname", BulkCapacityOccupancyCatalog.SLOT, "relkind", "v"))));
        assertThatThrownBy(() -> BulkCapacityOccupancyCatalog.installed(wrong)).hasMessageContaining("kind differs");
    }

    @Test void functionIdentityPreservesOverloadsDuplicatesAndNullConfig() throws Exception {
        var row = new LinkedHashMap<String,Object>(); row.put("signature", "f(p_id uuid)"); row.put("oid", 42L); row.put("proconfig", null);
        var phase = capture(mock(Connection.class), Map.of(3, List.of(row, Map.of("signature", "f()", "oid", 43L))));
        assertThat(phase.function("f(p_id uuid)").number("oid")).isEqualTo(42);
        assertThat(phase.function("f(p_id uuid)").value("proconfig")).isNull();
        assertThatThrownBy(() -> phase.function("absent()")).hasMessageContaining("absent or ambiguous");
        var duplicate = capture(mock(Connection.class), Map.of(3, List.of(row, row)));
        assertThatThrownBy(() -> duplicate.function("f(p_id uuid)")).hasMessageContaining("absent or ambiguous");
    }

    @Test void membershipsPreserveRootScopeAndAdminOption() throws Exception {
        var phase = capture(mock(Connection.class), Map.of(7, List.of(
                Map.of("kind", "member", "root", "runtime", "child", "runtime-child", "admin", false),
                Map.of("kind", "member", "root", "control", "child", "other", "admin", true))));
        assertThat(phase.members(Set.of("runtime"), "admin")).containsExactly("runtime-child");
        assertThatThrownBy(() -> phase.members(Set.of("control"), "admin")).hasMessage("admin");
    }

    @Test void constraintChecksKeepBackingIndexAndDeferrabilityFlags() throws Exception {
        var valid = new LinkedHashMap<String,Object>();
        valid.put("relname", "table"); valid.put("conname", "key"); valid.put("definition", "PRIMARY KEY (id)");
        valid.put("convalidated", true); valid.put("condeferrable", false); valid.put("condeferred", false);
        for (String flag : List.of("indisvalid", "indisready", "indislive", "indimmediate")) valid.put(flag, true);
        checkConstraints(valid);
        for (String flag : List.of("indisvalid", "indisready", "indislive", "indimmediate", "convalidated", "condeferrable", "condeferred")) {
            var invalid = new LinkedHashMap<>(valid); invalid.put(flag, !Boolean.TRUE.equals(valid.get(flag)));
            assertThatThrownBy(() -> checkConstraints(invalid)).hasMessageContaining("constraint attributes differ");
        }
    }

    @Test void phaseRowsAreValidatedOnlyWhenTheirMarkerIsRequested() throws Exception {
        Class<?> rowType = Class.forName(BulkExecutionMigrator.class.getName() + "$CapacityBootstrapRow");
        var row = rowType.getDeclaredConstructor(int.class, String.class); row.setAccessible(true);
        Class<?> phasesType = Class.forName(BulkExecutionMigrator.class.getName() + "$CapacityPhases");
        var constructor = phasesType.getDeclaredConstructor(Map.class); constructor.setAccessible(true);
        Object phases = constructor.newInstance(Map.of("v18", List.of(row.newInstance(18, "PENDING")),
                "v19", List.of(row.newInstance(19, "invalid"))));
        var phase = phasesType.getDeclaredMethod("phase", String.class, int.class); phase.setAccessible(true);
        assertThat(phase.invoke(phases, "v18", 18)).isEqualTo("PENDING");
        assertThatThrownBy(() -> phase.invoke(phases, "v19", 19)).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test void restrictedAndRoleCapturesDoNotReadStructuralOrOwnerData() throws Exception {
        var accessSql = new ArrayList<String>();
        BulkCatalogPhase.captureAccess(emptyConnection(accessSql), ROLES);
        assertThat(accessSql).hasSize(5).allSatisfy(sql -> assertThat(sql)
                .doesNotContain("pg_attrdef", "pg_constraint", "pg_index", "from praxis_bulk.", "flyway", "pg_catalog.pg_catalog"));
        var roleSql = new ArrayList<String>();
        BulkCatalogPhase.captureRoles(emptyConnection(roleSql), ROLES);
        assertThat(roleSql).hasSize(4).allSatisfy(sql -> assertThat(sql)
                .doesNotContain("pg_attrdef", "pg_constraint", "pg_index", "pg_proc", "pg_trigger", "from praxis_bulk.", "flyway", "pg_catalog.pg_catalog"));
    }

    @Test void liveBatchConsumesExactlySevenResultsAndClosesResources() throws Exception {
        var fixture = liveFixture(7);
        var catalog = BulkCatalogPhase.captureLive(fixture.connection(), ROLES);
        assertThat(catalog.identity).hasSize(1);
        assertThat(catalog.identity.get(0).text("session_identity")).isEqualTo("runtime");
        assertThat(catalog.relations).isEmpty();
        verify(fixture.statement()).execute();
        verify(fixture.statement(), times(7)).getMoreResults(Statement.CLOSE_CURRENT_RESULT);
        verify(fixture.statement()).close();
        verify(fixture.first()).free(); verify(fixture.second()).free();
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.connection()).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("pg_catalog.to_regprocedure", "pg_catalog.pg_get_triggerdef")
                .doesNotContain("from praxis_bulk.", "pg_attrdef", "pg_constraint", "pg_index", "pg_catalog.pg_catalog");
        assertThatThrownBy(() -> catalog.relations.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void liveBatchRejectsMissingExtraUpdateAndNullResults() throws Exception {
        for (int count : List.of(0, 3, 6, 8)) {
            var fixture = liveFixture(count);
            assertThatThrownBy(() -> BulkCatalogPhase.captureLive(fixture.connection(), ROLES))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("result");
            verify(fixture.statement()).close(); verify(fixture.first()).free(); verify(fixture.second()).free();
        }
        var update = liveFixture(7); when(update.statement().getUpdateCount()).thenReturn(0);
        assertThatThrownBy(() -> BulkCatalogPhase.captureLive(update.connection(), ROLES)).hasMessageContaining("extra results");
        var missing = liveFixture(7); when(missing.statement().getResultSet()).thenReturn(null);
        assertThatThrownBy(() -> BulkCatalogPhase.captureLive(missing.connection(), ROLES)).hasMessageContaining("is null");
    }

    @Test void liveBatchFailureStopsCaptureAndFreesArrays() throws Exception {
        var fixture = liveFixture(7);
        var failure = new SQLException("intermediate catalog failure");
        when(fixture.statement().getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenThrow(failure);
        assertThatThrownBy(() -> BulkCatalogPhase.captureLive(fixture.connection(), ROLES)).isSameAs(failure);
        verify(fixture.statement()).close(); verify(fixture.first()).free(); verify(fixture.second()).free();
    }

    @Test void secondArrayAllocationFailureFreesFirstArray() throws Exception {
        var fixture = liveFixture(7);
        when(fixture.connection().createArrayOf(eq("text"), any())).thenReturn(fixture.first()).thenThrow(new SQLException("second array"));
        assertThatThrownBy(() -> BulkCatalogPhase.captureLive(fixture.connection(), ROLES)).hasMessage("second array");
        verify(fixture.first()).free(); verify(fixture.statement()).close();
    }

    @Test void liveIdentityIsNeverInferredFromMissingNullOrDifferentEffectiveRole() throws Exception {
        for (var values : List.of(Map.<String,Object>of(), Map.<String,Object>of("session_identity", "runtime"),
                Map.<String,Object>of("session_identity", "runtime", "effective_identity", "owner"),
                Map.<String,Object>of("session_identity", "other", "effective_identity", "other"))) {
            var fixture = liveFixture(7);
            var result = rows(List.of(values)); when(fixture.statement().getResultSet()).thenReturn(result);
            var catalog = BulkCatalogPhase.captureLive(fixture.connection(), ROLES);
            var method = BulkExecutionMigrator.class.getDeclaredMethod("validateConnectionIdentity", BulkCatalogPhase.class, Set.class, String.class);
            method.setAccessible(true);
            assertThatThrownBy(() -> method.invoke(null, catalog, Set.of("runtime"), null)).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    private record LiveFixture(Connection connection, PreparedStatement statement, java.sql.Array first, java.sql.Array second) {}
    private static LiveFixture liveFixture(int count) throws Exception {
        var c = mock(Connection.class); var statement = mock(PreparedStatement.class);
        var first = mock(java.sql.Array.class); var second = mock(java.sql.Array.class);
        when(c.createArrayOf(eq("text"), any())).thenReturn(first, second);
        when(c.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(count > 0); when(statement.getUpdateCount()).thenReturn(-1);
        var resultSets = new ArrayList<ResultSet>();
        for (int i = 0; i < count; i++) resultSets.add(rows(i == 0
                ? List.of(Map.of("session_identity", "runtime", "effective_identity", "runtime", "legacy_cas_absent", true)) : List.of()));
        var position = new AtomicInteger();
        when(statement.getResultSet()).thenAnswer(call -> resultSets.get(position.get()));
        when(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)).thenAnswer(call -> position.incrementAndGet() < count);
        return new LiveFixture(c, statement, first, second);
    }

    private static Connection emptyConnection(List<String> queries) throws Exception {
        var c = mock(Connection.class); var statement = mock(Statement.class); var prepared = mock(PreparedStatement.class);
        when(c.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(call -> { queries.add(call.getArgument(0)); return rows(List.of()); });
        when(c.prepareStatement(anyString())).thenAnswer(call -> { queries.add(call.getArgument(0)); return prepared; });
        when(prepared.executeQuery()).thenAnswer(call -> rows(List.of()));
        return c;
    }

    private static void checkConstraints(Map<String,Object> row) throws Exception {
        var phase = capture(mock(Connection.class), Map.of(0, List.of(relation("table")), 1, List.of(row)));
        var m = BulkCapacityOccupancyCatalog.class.getDeclaredMethod("constraints", BulkCatalogPhase.class, String.class, Map.class);
        m.setAccessible(true);
        try { m.invoke(null, phase, "table", Map.of("key", "PRIMARY KEY (id)")); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    private static Map<String,Object> relation(String table) { return Map.of("relname",table,"relkind","r"); }
    private static Map<String,Object> acl(String table, String role, String permission, boolean grantable, boolean owner) {
        return Map.of("relname",table,"role",role,"permission",permission,"is_grantable",grantable,"is_owner",owner);
    }
    private static BulkCatalogPhase capture(Connection c, Map<Integer,List<Map<String,Object>>> data) throws Exception {
        var position = new AtomicInteger();
        when(c.createStatement()).thenAnswer(call -> {
            var statement = mock(Statement.class);
            var result = rows(data.getOrDefault(position.getAndIncrement(), List.of()));
            when(statement.executeQuery(anyString())).thenReturn(result);
            return statement;
        });
        var statement = mock(PreparedStatement.class);
        when(c.prepareStatement(anyString())).thenReturn(statement);
        var result = rows(data.getOrDefault(7,List.of()));
        when(statement.executeQuery()).thenReturn(result);
        return BulkCatalogPhase.capture(c, ROLES);
    }
    private static ResultSet rows(List<Map<String,Object>> values) throws Exception {
        var r = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        var names = values.isEmpty() ? List.<String>of() : new ArrayList<>(values.get(0).keySet());
        when(r.getMetaData()).thenReturn(metadata); when(metadata.getColumnCount()).thenReturn(names.size());
        when(metadata.getColumnLabel(anyInt())).thenAnswer(call -> names.get(call.<Integer>getArgument(0)-1));
        var index = new AtomicInteger(-1);
        when(r.next()).thenAnswer(call -> index.incrementAndGet()<values.size());
        when(r.getObject(anyInt())).thenAnswer(call -> values.get(index.get()).get(names.get(call.<Integer>getArgument(0)-1)));
        return r;
    }
}
