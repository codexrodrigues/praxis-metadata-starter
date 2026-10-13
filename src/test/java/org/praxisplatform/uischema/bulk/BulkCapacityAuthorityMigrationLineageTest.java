package org.praxisplatform.uischema.bulk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.praxisplatform.uischema.bulk.BulkCapacityAuthorityMigrationLineage.HistoryRow;

/** No database: history provenance/selection and source invariants, not a managed-installation proof. */
class BulkCapacityAuthorityMigrationLineageTest {
    private static final String ROOT = "/db/praxis-bulk-capacity-migration-lineage/original/";
    private static final String[] NAMES = {"V1__capacity_authority.sql", "V2__capacity_binding_attestation.sql"};
    private static final String[] DESCRIPTIONS = {"capacity authority", "capacity binding attestation"};
    // Published rc164 bytes, independently retained; not computed by the candidate resolver.
    private static final String[] ORIGINAL_SHA = {
        "b1b8a9f02a3d6a4287378b44af16faf8ca11f178697cdfb6aee151291047f944",
        "4bf8706c63e4f1836c1da8eda9dedce0ce6c014d0563c7f5af8231c2e91cbf5c"};
    private static final int[] ORIGINAL_CRC = {1885160110, -495632648};

    @Test void freshAndExactSchemaOnlySelectTwoManagedResources() {
        for (var history : List.of(List.<HistoryRow>of(), List.of(marker()))) {
            var selected = BulkCapacityAuthorityMigrationLineage.resolve(history);
            assertThat(selected.appliedVersion()).isZero();
            assertThat(selected.schemaMarkerOnly()).isEqualTo(!history.isEmpty());
            assertThat(selected.getResources("V", new String[]{".sql"})).hasSize(2);
            assertThat(selected.getResource(NAMES[0])).isNotNull();
            assertThat(selected.getResource("V3__unknown.sql")).isNull();
            assertThat(selected.checksum(1)).isNotEqualTo(ORIGINAL_CRC[0]);
            assertThat(selected.checksum(2)).isNotEqualTo(ORIGINAL_CRC[1]);
        }
    }

    @Test void publishedV1V2KeepExactBytesAndChecksumsWhilePendingV2IsManaged() throws Exception {
        var original = BulkCapacityAuthorityMigrationLineage.resolve(List.of(original(1), original(2)));
        var mixed = BulkCapacityAuthorityMigrationLineage.resolve(List.of(original(1)));
        var managed = BulkCapacityAuthorityMigrationLineage.resolve(List.of());
        for (int v = 1; v <= 2; v++) {
            byte[] bytes = resource(ROOT + NAMES[v-1]);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo(ORIGINAL_SHA[v-1]);
            assertThat(original.sql(v)).isEqualTo(new String(bytes, StandardCharsets.UTF_8));
            assertThat(original.checksum(v)).isEqualTo(ORIGINAL_CRC[v-1]);
        }
        assertThat(mixed.sql(1)).isEqualTo(original.sql(1));
        assertThat(mixed.sql(2)).isEqualTo(managed.sql(2));
        assertThat(BulkCapacityAuthorityMigrationLineage.resolve(List.of(original(1), canonical(2)))
                .appliedVersion()).isEqualTo(2);
        assertThat(BulkCapacityAuthorityMigrationLineage.resolve(List.of(canonical(1), canonical(2)))
                .appliedVersion()).isEqualTo(2);
    }

    @Test void rejectsFailedWrongChecksumDescriptionTypeScriptOrVersion() {
        for (HistoryRow row : List.of(
                new HistoryRow(1,"1",DESCRIPTIONS[0],"SQL",NAMES[0],ORIGINAL_CRC[0],false),
                new HistoryRow(1,"1",DESCRIPTIONS[0],"SQL",NAMES[0],1,true),
                new HistoryRow(1,"1","unknown","SQL",NAMES[0],ORIGINAL_CRC[0],true),
                new HistoryRow(1,"1",DESCRIPTIONS[0],"JDBC",NAMES[0],ORIGINAL_CRC[0],true),
                new HistoryRow(1,"1",DESCRIPTIONS[0],"SQL","V1__unknown.sql",ORIGINAL_CRC[0],true),
                new HistoryRow(1,"01",DESCRIPTIONS[0],"SQL",NAMES[0],ORIGINAL_CRC[0],true),
                new HistoryRow(1,"1",DESCRIPTIONS[0],"SQL",NAMES[0],null,true))) {
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrationLineage.resolve(List.of(row)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void rejectsGapsDuplicatesWrongRankAndReverseCanonicalCutover() {
        for (var rows : List.of(List.of(original(2)), List.of(original(1),original(1)),
                List.of(original(2),original(1)), List.of(canonical(1),original(2)),
                List.of(new HistoryRow(3,"1",DESCRIPTIONS[0],"SQL",NAMES[0],ORIGINAL_CRC[0],true)),
                List.of(original(1),original(2), new HistoryRow(3,"3","unknown","SQL","V3__unknown.sql",1,true)))) {
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrationLineage.resolve(rows))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void rejectsForgedDuplicateAndLateSchemaMarkers() {
        for (var rows : List.of(List.of(marker(),marker()), List.of(original(1),marker()),
                List.of(new HistoryRow(0,null,"baseline","SCHEMA","\"praxis_bulk_capacity\"",null,true)),
                List.of(new HistoryRow(0,null,"<< Flyway Schema Creation >>","SCHEMA","\"praxis_bulk\"",null,true)),
                List.of(new HistoryRow(0,null,"<< Flyway Schema Creation >>","SCHEMA","\"praxis_bulk_capacity\"",1,true)))) {
            assertThatThrownBy(() -> BulkCapacityAuthorityMigrationLineage.resolve(rows))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void managedResourcesPreserveProtectedFunctionBodiesAndExactCatalogPhotographs() throws Exception {
        var canonical = BulkCapacityAuthorityMigrationLineage.resolve(List.of());
        var pattern = Pattern.compile("create function praxis_bulk_capacity\\.([a-z_]+)\\s*\\([^;]*?as \\$\\$(.*?)\\$\\$;",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        int total = 0;
        for (int v=1; v<=2; v++) {
            String original = new String(resource(ROOT+NAMES[v-1]),StandardCharsets.UTF_8);
            var oldFunctions = pattern.matcher(original).results().map(m -> m.group()).toList();
            var newFunctions = pattern.matcher(canonical.sql(v)).results().map(m -> m.group()).toList();
            assertThat(newFunctions).containsExactlyElementsOf(oldFunctions);
            total += oldFunctions.size();
            assertThat(BulkCapacityAuthorityMigrator.catalogQuery(canonical.sql(v)))
                .isEqualTo(BulkCapacityAuthorityMigrator.catalogQuery(original));
            String preflight = canonical.sql(v).substring(0, canonical.sql(v).indexOf("end;"));
            assertThat(preflight).contains("and not (m.admin_option and not m.inherit_option and not m.set_option",
                    "bootstrap.rolsuper", "same_actor.set_option)=1", "capacity functional role has nested membership",
                    "unsafe capacity role");
            assertThat(canonical.sql(v)).contains("with inherit false, set true granted by current_user",
                    "revoke praxis_bulk_capacity_definer from %I granted by current_user",
                    "revoke create on schema praxis_bulk_capacity from praxis_bulk_capacity_definer");
        }
        assertThat(total).isEqualTo(8);
    }

    @Test void sameAdministrativePredicateAsDomainExceptAuthoritySchema() {
        assertThat(BulkCapacityAuthorityMigrator.MANAGED_ADMIN_MEMBERSHIP_PREDICATE)
                .isEqualTo(BulkExecutionMigrator.MANAGED_ADMIN_MEMBERSHIP_PREDICATE
                        .replace("n.nspname='praxis_bulk'", "n.nspname='praxis_bulk_capacity'"));
    }

    @Test void versionGuardRejectsPg14AndOtherProductsWithoutStatements() throws Exception {
        var connection = mock(java.sql.Connection.class);
        var metadata = mock(java.sql.DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getDatabaseMajorVersion()).thenReturn(14);
        assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.requirePostgres17(connection))
            .isInstanceOf(IllegalStateException.class);
        when(metadata.getDatabaseMajorVersion()).thenReturn(17);
        BulkCapacityAuthorityMigrator.requirePostgres17(connection);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.requirePostgres17(connection))
            .isInstanceOf(IllegalStateException.class);
        verify(connection, never()).createStatement();
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test void exactSchemaMarkerCanContinueButUnknownOwnerOrInstalledObjectsCannot() throws Exception {
        var canonicalMarker = BulkCapacityAuthorityMigrationLineage.resolve(List.of(marker()));
        BulkCapacityAuthorityMigrator.assertEmptyAuthorityPrefix(emptyPrefix("owner",0,0,"owner"),
                "owner",canonicalMarker);
        assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.assertEmptyAuthorityPrefix(
                emptyPrefix("other",0,0,"owner"),"owner",canonicalMarker))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema owner");
        assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.assertEmptyAuthorityPrefix(
                emptyPrefix("owner",1,0,"owner"),"owner",canonicalMarker))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("contains objects");
        assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.assertEmptyAuthorityPrefix(
                emptyPrefix("owner",0,1,"owner"),"owner",canonicalMarker))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("contains objects");
        assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.assertEmptyAuthorityPrefix(
                emptyPrefix("owner",0,0,"other"),"owner",canonicalMarker))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("history owner");
        assertThatThrownBy(() -> BulkCapacityAuthorityMigrator.assertEmptyAuthorityPrefix(
                emptyPrefix("owner",0,0,"owner"),"owner",BulkCapacityAuthorityMigrationLineage.resolve(List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("history is not canonical");
    }

    @Test void absentSchemaNeedsNoHistoryOrAuthorityDdl() throws Exception {
        var connection=emptyPrefix(null,0,0,null);
        BulkCapacityAuthorityMigrator.assertEmptyAuthorityPrefix(connection,"owner",
                BulkCapacityAuthorityMigrationLineage.resolve(List.of()));
        verify(connection,times(1)).prepareStatement(anyString());
    }

    private static java.sql.Connection emptyPrefix(String schemaOwner,int relations,int functions,String historyOwner)
            throws Exception {
        var connection=mock(java.sql.Connection.class);
        var stmts=new ArrayList<java.sql.PreparedStatement>();
        for (Object value : new Object[]{schemaOwner,relations,functions,historyOwner}) {
            var statement=mock(java.sql.PreparedStatement.class);
            var result=mock(java.sql.ResultSet.class);
            when(statement.executeQuery()).thenReturn(result);
            when(result.next()).thenReturn(true,false);
            if (value instanceof Integer n) when(result.getInt(1)).thenReturn(n);
            else when(result.getString(1)).thenReturn((String)value);
            stmts.add(statement);
        }
        var guard=mock(java.sql.PreparedStatement.class);
        var guardRows=mock(java.sql.ResultSet.class);
        when(guard.executeQuery()).thenReturn(guardRows);
        when(guardRows.next()).thenReturn(true,false);
        when(guardRows.getBoolean(1)).thenReturn(true);
        var structural = new ArrayList<java.sql.PreparedStatement>();
        structural.add(tuples(new String[]{"praxis_bulk_capacity_schema_history|praxis_bulk_capacity_schema_history_pk|p|PRIMARY KEY (installed_rank)|t|f|f|{1}|-|||"}));
        structural.add(tuples(new String[]{
            "praxis_bulk_capacity_schema_history|praxis_bulk_capacity_schema_history_pk|CREATE UNIQUE INDEX praxis_bulk_capacity_schema_history_pk ON praxis_bulk_capacity_schema_history USING btree (installed_rank)|t|t|t|t|t|-",
            "praxis_bulk_capacity_schema_history|praxis_bulk_capacity_schema_history_s_idx|CREATE INDEX praxis_bulk_capacity_schema_history_s_idx ON praxis_bulk_capacity_schema_history USING btree (success)|f|f|t|t|t|-"}));
        var results = new ArrayList<java.sql.ResultSet>();
        results.add(guardRows);
        for (var statement : structural) results.add(statement.executeQuery());
        var resultIndex = new java.util.concurrent.atomic.AtomicInteger();
        when(guard.execute()).thenReturn(true);
        when(guard.getResultSet()).thenAnswer(call -> results.get(resultIndex.get()));
        when(guard.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT))
                .thenAnswer(call -> resultIndex.incrementAndGet() < results.size());
        when(guard.getUpdateCount()).thenReturn(-1);
        stmts.add(guard);
        var cursor=new java.util.concurrent.atomic.AtomicInteger();
        when(connection.prepareStatement(anyString())).thenAnswer(call -> stmts.get(cursor.getAndIncrement()));
        return connection;
    }

    @Test void emptyHistoryStructureOrAclFailureStopsBeforeStructuralFollowups() throws Exception {
        var connection=mock(java.sql.Connection.class);
        var statement=mock(java.sql.PreparedStatement.class);
        var rows=mock(java.sql.ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(1)).thenReturn(false);
        assertThatThrownBy(() -> BulkCapacityAuthorityCatalog.validateEmptyHistory(connection))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("structure or ACL");
        verify(connection,times(1)).prepareStatement(anyString());
    }

    private static java.sql.PreparedStatement tuples(String[] values) throws Exception {
        var statement=mock(java.sql.PreparedStatement.class);
        var rows=mock(java.sql.ResultSet.class);
        var cursor=new java.util.concurrent.atomic.AtomicInteger(-1);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenAnswer(call -> cursor.incrementAndGet()<values.length);
        when(rows.getString(anyInt())).thenAnswer(call -> {
            String value=values[cursor.get()].split("\\|",-1)[(int)call.getArgument(0)-1];
            return value.equals("-") ? null : value;
        });
        return statement;
    }

    private static HistoryRow marker() {
        return new HistoryRow(0,null,"<< Flyway Schema Creation >>","SCHEMA","\"praxis_bulk_capacity\"",null,true);
    }
    private static HistoryRow original(int v) {
        return new HistoryRow(v,Integer.toString(v),DESCRIPTIONS[v-1],"SQL",NAMES[v-1],ORIGINAL_CRC[v-1],true);
    }
    private static HistoryRow canonical(int v) {
        return new HistoryRow(v,Integer.toString(v),DESCRIPTIONS[v-1],"SQL",NAMES[v-1],
                BulkCapacityAuthorityMigrationLineage.resolve(List.of()).checksum(v),true);
    }
    private static byte[] resource(String name) throws Exception {
        try (var stream=BulkCapacityAuthorityMigrationLineageTest.class.getResourceAsStream(name)) {
            assertThat(stream).isNotNull(); return stream.readAllBytes();
        }
    }
}
