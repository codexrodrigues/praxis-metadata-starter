package org.praxisplatform.uischema.bulk;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;

/**
 * Invocation-local, internal resolution of the one canonical migration chain.
 * Historical resources retain their original bytes; pending versions use the
 * current canonical resource. No caller selects a migration mode or repairs history.
 */
final class BulkMigrationLineage implements ResourceProvider {
    private static final int LAST_VERSION = 20;
    private static final String ROOT = "/db/praxis-bulk-migration-lineage/";
    private static final String CANONICAL_ROOT = "/db/praxis-bulk-migrations/";
    private final Map<Integer, MigrationResource> resources;
    private final List<HistoryRow> history;

    private BulkMigrationLineage(Map<Integer, MigrationResource> resources, List<HistoryRow> history) {
        this.resources = Map.copyOf(resources);
        this.history = List.copyOf(history);
    }

    static BulkMigrationLineage select(Connection connection) throws SQLException {
        return resolve(readHistory(connection));
    }

    /** Exact equality detects history changes between selection and physical attestation. */
    void requireSameHistory(Connection connection) throws SQLException {
        require(history.equals(readHistory(connection)), "Bulk migration history changed during attestation");
    }

    String sql(int version) {
        MigrationResource resource = resources.get(version);
        require(resource != null, "Unknown canonical bulk migration version");
        return resource.text;
    }

    int checksum(int version) {
        MigrationResource resource = resources.get(version);
        require(resource != null, "Unknown canonical bulk migration version");
        return resource.checksum;
    }

    boolean hasManagedAppliedVersion(int version) {
        Descriptor descriptor = catalog().get(version);
        require(descriptor != null, "Unknown canonical bulk migration version");
        return !descriptor.original.text.equals(descriptor.canonical.text)
                && history.stream().anyMatch(row -> Integer.toString(version).equals(row.version)
                        && row.checksum != null && row.checksum == descriptor.canonical.checksum);
    }

    int appliedVersion() {
        return history.stream().filter(row -> row.version != null)
                .mapToInt(row -> Integer.parseInt(row.version)).max().orElse(0);
    }

    static BulkMigrationLineage resolve(List<HistoryRow> history) {
        Map<Integer, Descriptor> catalog = catalog();
        var selected = new LinkedHashMap<Integer, MigrationResource>();
        for (var entry : catalog.entrySet()) selected.put(entry.getKey(), entry.getValue().canonical);
        int expectedVersion = 1;
        int expectedRank = 1;
        boolean canonicalCutover = false;
        boolean schemaMarkerSeen = false;
        for (HistoryRow row : history) {
            require(row.success, "Failed bulk migration history cannot be adopted");
            if (row.version == null) {
                require(!schemaMarkerSeen && expectedVersion == 1 && row.rank == 0
                                && "SCHEMA".equals(row.type) && "\"praxis_bulk\"".equals(row.script)
                                && "<< Flyway Schema Creation >>".equals(row.description) && row.checksum == null,
                        "Unknown bulk schema history marker");
                schemaMarkerSeen = true;
                continue;
            }
            require(Integer.toString(expectedVersion).equals(row.version) && row.rank == expectedRank,
                    "Bulk migration history is not a complete ordered prefix");
            Descriptor descriptor = catalog.get(expectedVersion);
            require(descriptor != null && "SQL".equals(row.type)
                            && descriptor.script.equals(row.script) && descriptor.description.equals(row.description),
                    "Unknown bulk migration history identity");
            boolean original = row.checksum != null && row.checksum == descriptor.original.checksum;
            boolean canonical = row.checksum != null && row.checksum == descriptor.canonical.checksum;
            require(original || canonical, "Unknown bulk migration history checksum");
            if (!descriptor.original.text.equals(descriptor.canonical.text)) {
                // A CRC collision cannot prove which different body was applied.
                require(descriptor.original.checksum != descriptor.canonical.checksum,
                        "Ambiguous canonical bulk migration provenance");
                if (canonical) canonicalCutover = true;
                else require(!canonicalCutover, "Unreachable bulk migration lineage mixture");
            }
            selected.put(expectedVersion, original ? descriptor.original : descriptor.canonical);
            expectedVersion++;
            expectedRank++;
        }
        return new BulkMigrationLineage(selected, history);
    }

    private static List<HistoryRow> readHistory(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            int timeout = statement.getQueryTimeout();
            statement.setQueryTimeout(timeout > 0 ? Math.min(timeout, 10) : 10);
            try (var exists = statement.executeQuery(
                    "select pg_catalog.to_regclass('praxis_bulk.praxis_bulk_schema_history') is not null")) {
                require(exists.next(), "Unable to inspect bulk migration history");
                if (!exists.getBoolean(1)) return List.of();
            }
            var history = new ArrayList<HistoryRow>();
            try (var rows = statement.executeQuery("""
                    select installed_rank, version, description, type, script, checksum, success
                    from praxis_bulk.praxis_bulk_schema_history order by installed_rank
                    """)) {
                while (rows.next()) {
                    require(history.size() < 64, "Bulk migration history exceeds canonical bounds");
                    history.add(new HistoryRow(rows.getInt(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), (Integer) rows.getObject(6), rows.getBoolean(7)));
                }
            }
            return List.copyOf(history);
        }
    }

    private static Map<Integer, Descriptor> catalog() {
        var properties = new LinkedHashMap<String, String>();
        try (InputStream stream = BulkMigrationLineage.class.getResourceAsStream(ROOT + "catalog.properties")) {
            require(stream != null, "Canonical bulk migration provenance catalog is absent");
            byte[] bytes = stream.readNBytes(16385);
            require(bytes.length <= 16384, "Canonical bulk migration catalog exceeds bounds");
            for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int split = line.indexOf('=');
                require(split > 0 && split == line.lastIndexOf('='), "Invalid bulk migration catalog property");
                String key = line.substring(0, split);
                require(properties.putIfAbsent(key, line.substring(split + 1)) == null,
                        "Duplicate canonical bulk migration catalog property");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load bulk migration provenance", failure);
        }
        require(properties.size() == LAST_VERSION, "Canonical bulk migration catalog is incomplete");
        var catalog = new LinkedHashMap<Integer, Descriptor>();
        for (int version = 1; version <= LAST_VERSION; version++) {
            String entry = properties.get(Integer.toString(version));
            require(entry != null, "Canonical bulk migration catalog has a hole");
            String[] fields = entry.split("\\|", -1);
            require(fields.length == 6 && fields[0].startsWith("V" + version + "__")
                            && fields[0].endsWith(".sql") && !fields[0].contains("/"),
                    "Invalid canonical bulk migration catalog entry");
            String original = read(ROOT + "original/" + fields[0], fields[2]);
            String canonical = read(CANONICAL_ROOT + fields[0], fields[3]);
            MigrationResource originalResource = new MigrationResource(fields[0], original, Integer.parseInt(fields[4]));
            MigrationResource canonicalResource = new MigrationResource(fields[0], canonical, Integer.parseInt(fields[5]));
            require(original.equals(canonical) || originalResource.checksum != canonicalResource.checksum,
                    "Ambiguous canonical bulk migration provenance");
            catalog.put(version, new Descriptor(fields[0], fields[1], originalResource, canonicalResource));
        }
        return catalog;
    }

    private static String read(String path, String expectedSha256) {
        try (InputStream stream = BulkMigrationLineage.class.getResourceAsStream(path)) {
            require(stream != null, "Canonical bulk migration resource is absent");
            byte[] bytes = stream.readAllBytes();
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                            .equals(expectedSha256), "Canonical bulk migration resource digest differs");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Unable to read canonical bulk migration resource", failure);
        }
    }

    @Override public LoadableResource getResource(String name) {
        return resources.values().stream().filter(resource -> resource.filename.equals(name)
                        || ("db/praxis-bulk-migrations/" + resource.filename).equals(name))
                .findFirst().orElse(null);
    }

    @Override public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        return resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue)
                .filter(resource -> resource.filename.startsWith(prefix)
                        && Arrays.stream(suffixes).anyMatch(resource.filename::endsWith))
                .map(resource -> (LoadableResource) resource).toList();
    }

    record HistoryRow(int rank, String version, String description, String type, String script,
                      Integer checksum, boolean success) { }
    private record Descriptor(String script, String description,
                              MigrationResource original, MigrationResource canonical) { }

    private static final class MigrationResource extends LoadableResource {
        private final String filename;
        private final String text;
        private final int checksum;
        private MigrationResource(String filename, String text, int expectedChecksum) {
            this.filename = filename;
            this.text = text;
            CRC32 crc = new CRC32();
            String normalized = text.startsWith("\uFEFF") ? text.substring(1) : text;
            normalized.lines().forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
            checksum = (int) crc.getValue();
            require(checksum == expectedChecksum, "Canonical bulk Flyway checksum differs");
        }
        @Override public Reader read() { return new StringReader(text); }
        @Override public String getFilename() { return filename; }
        @Override public String getRelativePath() { return filename; }
        @Override public String getAbsolutePath() { return "classpath:" + CANONICAL_ROOT + filename; }
        @Override public String getAbsolutePathOnDisk() { return null; }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
