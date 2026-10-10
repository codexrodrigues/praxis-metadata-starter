package org.praxisplatform.uischema.bulk;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BulkMigrationLineageTest {
    @Test void freshResolutionExposesOneOrderedCanonicalResourcePerVersion() {
        var selected = BulkMigrationLineage.resolve(List.of());
        assertThat(selected.appliedVersion()).isZero();
        assertThat(selected.getResources("V", new String[] {".sql"})).hasSize(20);
        assertThat(selected.getResource("V5__bulk_governed_lifecycle.sql")).isNotNull();
        assertThat(selected.getResource("V999__unknown.sql")).isNull();
        assertThat(selected.getResources("R", new String[] {".sql"})).isEmpty();
    }

    @Test void acceptsTheOriginalFailedInstallationSuccessfulPrefixWithoutRewritingIt() {
        var selected = BulkMigrationLineage.resolve(prefix(4));
        assertThat(selected.appliedVersion()).isEqualTo(4);
        assertThat(selected.checksum(1)).isEqualTo(1798980909);
        assertThat(selected.checksum(2)).isEqualTo(-1793384903);
        assertThat(selected.checksum(3)).isEqualTo(1230433769);
        assertThat(selected.checksum(4)).isEqualTo(-503188587);
    }

    @Test void rejectsFailedUnknownScriptAndUnknownChecksumBeforeResourceResolution() {
        var original = prefix(4);
        var row = original.get(4);
        for (var altered : List.of(
                new BulkMigrationLineage.HistoryRow(row.rank(), row.version(), row.description(), row.type(), row.script(), row.checksum(), false),
                new BulkMigrationLineage.HistoryRow(row.rank(), row.version(), row.description(), row.type(), "V4__untrusted.sql", row.checksum(), true),
                new BulkMigrationLineage.HistoryRow(row.rank(), row.version(), row.description(), row.type(), row.script(), row.checksum() + 1, true),
                new BulkMigrationLineage.HistoryRow(row.rank(), row.version(), "unknown description", row.type(), row.script(), row.checksum(), true),
                new BulkMigrationLineage.HistoryRow(row.rank(), row.version(), row.description(), "JDBC", row.script(), row.checksum(), true))) {
            var history = new ArrayList<>(original);
            history.set(4, altered);
            assertThatThrownBy(() -> BulkMigrationLineage.resolve(history)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void rejectsHolesDuplicateVersionsRanksAndReorderedRows() {
        var original = prefix(4);
        var hole = new ArrayList<>(original); hole.remove(2);
        var duplicate = new ArrayList<>(original); duplicate.add(original.get(4));
        var reordered = new ArrayList<>(original); java.util.Collections.swap(reordered, 2, 3);
        var rank = new ArrayList<>(original);
        var row = rank.get(4);
        rank.set(4, new BulkMigrationLineage.HistoryRow(99, row.version(), row.description(), row.type(), row.script(), row.checksum(), true));
        for (var history : List.of(hole, duplicate, reordered, rank)) {
            assertThatThrownBy(() -> BulkMigrationLineage.resolve(history)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void rejectsUnknownOrRepeatedSchemaMarker() {
        var original = prefix(4);
        var duplicate = new ArrayList<>(original); duplicate.add(0, original.get(0));
        assertThatThrownBy(() -> BulkMigrationLineage.resolve(duplicate)).isInstanceOf(IllegalStateException.class);
        var unknown = new ArrayList<>(original);
        unknown.set(0, new BulkMigrationLineage.HistoryRow(0, null, "baseline", "BASELINE", "unknown", null, true));
        assertThatThrownBy(() -> BulkMigrationLineage.resolve(unknown)).isInstanceOf(IllegalStateException.class);
    }

    @Test void originalNineteenPrefixRetainsKnownHistoricalBodiesAndCanonicalPendingResource() throws Exception {
        var selected = BulkMigrationLineage.resolve(catalogPrefix(19, false));
        var canonical = BulkMigrationLineage.resolve(List.of());
        assertThat(selected.appliedVersion()).isEqualTo(19);
        assertThat(selected.sql(5)).isNotEqualTo(canonical.sql(5));
        assertThat(selected.sql(19)).isNotEqualTo(canonical.sql(19));
        assertThat(selected.sql(20)).isEqualTo(canonical.sql(20));
        assertThat(BulkMigrationLineage.resolve(catalogPrefix(4, false)).sql(5)).isEqualTo(canonical.sql(5));
    }

    @Test void rejectsOriginalBodyAfterCanonicalCutoverIncludingAcrossAnUnchangedVersion() throws Exception {
        var atSix = catalogPrefix(6, true);
        atSix.set(5, catalogPrefix(6, false).get(5));
        assertThatThrownBy(() -> BulkMigrationLineage.resolve(atSix)).isInstanceOf(IllegalStateException.class);
        var acrossTwelve = catalogPrefix(13, true);
        acrossTwelve.set(12, catalogPrefix(13, false).get(12));
        assertThatThrownBy(() -> BulkMigrationLineage.resolve(acrossTwelve)).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsNonCanonicalVersionIdentityEvenWithKnownScriptAndChecksum() throws Exception {
        var history = catalogPrefix(5, false);
        var row = history.get(4);
        history.set(4, new BulkMigrationLineage.HistoryRow(row.rank(), "05", row.description(), row.type(),
                row.script(), row.checksum(), row.success()));
        assertThatThrownBy(() -> BulkMigrationLineage.resolve(history)).isInstanceOf(IllegalStateException.class);
    }

    private static ArrayList<BulkMigrationLineage.HistoryRow> catalogPrefix(int size, boolean canonical) throws Exception {
        var rows = new ArrayList<BulkMigrationLineage.HistoryRow>();
        try (var stream = BulkMigrationLineageTest.class.getResourceAsStream("/db/praxis-bulk-migration-lineage/catalog.properties")) {
            assertThat(stream).isNotNull();
            var properties = new java.util.Properties();
            properties.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            for (int version = 1; version <= size; version++) {
                var fields = properties.getProperty(Integer.toString(version)).split("\\|", -1);
                rows.add(new BulkMigrationLineage.HistoryRow(version, Integer.toString(version), fields[1], "SQL",
                        fields[0], Integer.parseInt(fields[canonical ? 5 : 4]), true));
            }
        }
        return rows;
    }

    private static List<BulkMigrationLineage.HistoryRow> prefix(int size) {
        var rows = new ArrayList<BulkMigrationLineage.HistoryRow>();
        rows.add(new BulkMigrationLineage.HistoryRow(0, null, "<< Flyway Schema Creation >>", "SCHEMA", "\"praxis_bulk\"", null, true));
        String[] names = {"bulk_execution", "bulk_evaluation_evidence", "bulk_durable_execution", "bulk_governed_admission"};
        int[] checksums = {1798980909, -1793384903, 1230433769, -503188587};
        for (int version = 1; version <= size; version++) {
            rows.add(new BulkMigrationLineage.HistoryRow(version, Integer.toString(version), names[version - 1].replace('_', ' '),
                    "SQL", "V" + version + "__" + names[version - 1] + ".sql", checksums[version - 1], true));
        }
        return rows;
    }
}
