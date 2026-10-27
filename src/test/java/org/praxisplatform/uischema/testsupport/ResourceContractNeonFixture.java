package org.praxisplatform.uischema.testsupport;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLSocket;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.PGStream;
import org.postgresql.core.QueryExecutorBase;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * One disposable resource-contract database and ordinary runtime on the reviewed Neon child.
 * The privileged assignment owner only provisions and removes this fixture. Hibernate and every
 * HTTP request use the separately generated ordinary role. Cleanup rechecks recorded object OIDs.
 */
public final class ResourceContractNeonFixture implements AutoCloseable {
    private static final Map<Class<?>, ResourceContractNeonFixture> FIXTURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");
    private final String label;
    private final String host;
    private final String owner;
    private final String ownerPassword;
    private final String ownerDatabase;
    private final String database = "metadata_contracts_" + UUID.randomUUID().toString().replace("-", "");
    private final String runtime = "metadata_contract_" + UUID.randomUUID().toString().replace("-", "");
    private final String schema = "resource_contract";
    private final String password;
    private long databaseOid;
    private long runtimeOid;
    private long schemaOid;
    private long ownerOid;
    private boolean closed;
    private boolean runtimeCreationAttempted;
    private boolean databaseCreationAttempted;
    private boolean preflightVerified;

    public static ResourceContractNeonFixture open(Class<?> testClass, String label) {
        if (FIXTURES.containsKey(testClass)) throw new IllegalStateException("Resource fixture already assigned to class");
        try {
            ResourceContractNeonFixture fixture = new ResourceContractNeonFixture(label);
            FIXTURES.put(testClass, fixture);
            return fixture;
        } catch (SQLException failure) {
            throw new IllegalStateException("Assigned Neon resource fixture failed (SQLState "
                    + failure.getSQLState() + ")");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Assigned Neon private fixture file unavailable");
        }
    }

    static void closeForClass(Class<?> testClass) throws SQLException {
        ResourceContractNeonFixture fixture = FIXTURES.get(testClass);
        if (fixture != null) { fixture.close(); FIXTURES.remove(testClass, fixture); }
    }

    private ResourceContractNeonFixture(String label) throws java.io.IOException, SQLException {
        if (!Set.of("custom-fields", "composite-id").contains(label)) throw new IllegalArgumentException("Unknown resource fixture");
        this.label = label;
        Path file = privatePath("PRAXIS_BULK_NEON_CONNECTION_FILE");
        requirePrivate(file);
        URI uri;
        try { uri = URI.create(Files.readString(file).trim()); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid assigned owner URI"); }
        host = required("PRAXIS_BULK_NEON_EXPECTED_HOST");
        owner = required("PRAXIS_BULK_NEON_EXPECTED_OWNER");
        if (!host.matches("ep-[a-z0-9.-]+\\.neon\\.tech") || host.contains("-pooler")
                || !host.equals(uri.getHost()) || !Set.of("postgres", "postgresql").contains(uri.getScheme())
                || uri.getPort() != -1 && uri.getPort() != 5432 || uri.getFragment() != null
                || !required("PRAXIS_METADATA_NEON_EXPECTED_BRANCH").matches("br-[a-z0-9-]+")) {
            throw new IllegalStateException("Explicit reviewed direct Neon child required");
        }
        String[] credentials = (uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo()).split(":", 2);
        if (credentials.length != 2 || !owner.equals(decode(credentials[0])) || !owner.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
            throw new IllegalStateException("Assigned owner identity mismatch");
        ownerPassword = decode(credentials[1]);
        ownerDatabase = uri.getPath() == null ? "" : uri.getPath().substring(1);
        if (ownerPassword.isBlank() || !ownerDatabase.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
            throw new IllegalStateException("Assigned owner database or credential absent");
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        password = HexFormat.of().formatHex(random);
        // Record the new secret before opening any connection that could include it in a failure.
        recordSecret();
        journal("INTENT");
        try {
            try (Connection admin = connect(ownerDatabase, owner, ownerPassword)) {
                checkOwner(admin);
                runtimeCreationAttempted = true;
                execute(admin, "create role " + runtime + " login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '" + password + "'");
                runtimeOid = oid(admin, "select oid from pg_roles where rolname=?", runtime);
                journal("RUNTIME_CREATED");
                databaseCreationAttempted = true;
                execute(admin, "create database " + database + " owner " + owner + " template template0");
                databaseOid = oid(admin, "select oid from pg_database where datname=?", database);
                journal("DATABASE_CREATED");
                execute(admin, "revoke all on database " + database + " from public");
                execute(admin, "grant connect on database " + database + " to " + runtime);
            }
            try (Connection admin = connect(database, owner, ownerPassword)) {
                execute(admin, "revoke all on schema public from public");
                execute(admin, "create schema " + schema + " authorization " + owner);
                execute(admin, "grant usage,create on schema " + schema + " to " + runtime);
                schemaOid = oid(admin, "select oid from pg_namespace where nspname=?", schema);
                journal("SCHEMA_CREATED");
            }
            verifyRuntime();
            preflightVerified = true;
        } catch (SQLException | java.io.IOException | RuntimeException failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void bind(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> jdbcUrl(database) + "&currentSchema=" + schema);
        properties.add("spring.datasource.username", () -> runtime);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
        properties.add("spring.datasource.hikari.schema", () -> schema);
    }

    /** Runs before Spring/Hibernate DDL and again before each test's own data reset. */
    public void verifyRuntime() {
        try (Connection connection = connect(database, runtime, password)) {
            connection.setSchema(schema);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("""
                    select current_user,current_database(),current_schema(),
                           current_setting('server_version_num')::integer/10000,
                           r.rolsuper or r.rolcreatedb or r.rolcreaterole or r.rolreplication or r.rolbypassrls,
                           exists(select 1 from pg_roles n where n.rolname='neon_superuser' and pg_has_role(current_user,n.oid,'MEMBER')),
                           n.oid,n.nspowner,has_schema_privilege(current_user,n.oid,'USAGE'),has_schema_privilege(current_user,n.oid,'CREATE'),
                           session_user=current_user,exists(select 1 from pg_auth_members where member=r.oid)
                    from pg_roles r cross join pg_namespace n where r.rolname=current_user and n.nspname=?
                    """)) {
                query.setString(1, schema);
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || !runtime.equals(rows.getString(1)) || !database.equals(rows.getString(2))
                            || !schema.equals(rows.getString(3)) || rows.getInt(4) != 17 || rows.getBoolean(5)
                            || rows.getBoolean(6) || rows.getLong(7) != schemaOid || rows.getLong(8) != ownerOid
                            || !rows.getBoolean(9) || !rows.getBoolean(10) || !rows.getBoolean(11)
                            || rows.getBoolean(12) || rows.next())
                        throw new IllegalStateException("Ordinary PG17 resource fixture identity/privilege mismatch before DDL");
                }
            } finally { connection.rollback(); }
        } catch (SQLException failure) {
            throw new IllegalStateException("Ordinary Neon fixture preflight failed (SQLState " + failure.getSQLState() + ")");
        }
    }

    @Override public void close() throws SQLException {
        if (closed) return;
        if (runtimeCreationAttempted && runtimeOid == 0 || databaseCreationAttempted && databaseOid == 0)
            throw new IllegalStateException("Unknown fixture creation outcome; retain private journal and reconcile observed OIDs before retry");
        try (Connection admin = connect(ownerDatabase, owner, ownerPassword)) {
            checkOwner(admin);
            if (databaseOid != 0) {
                requireMatchingOid(oid(admin, "select oid from pg_database where datname=? and datdba=" + ownerOid, database), databaseOid);
                try (Connection target = connect(database, owner, ownerPassword)) {
                    if (schemaOid != 0) requireMatchingOid(oid(target, "select oid from pg_namespace where nspname=? and nspowner=" + ownerOid, schema), schemaOid);
                }
                execute(admin, "drop database " + database + " with (force)");
                databaseOid = 0;
                databaseCreationAttempted = false;
            }
            if (runtimeOid != 0) {
                requireMatchingOid(oid(admin, "select oid from pg_roles where rolname=?", runtime), runtimeOid);
                execute(admin, "drop role " + runtime); runtimeOid = 0;
                runtimeCreationAttempted = false;
            }
        }
        try { journal("CLOSED"); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Fixture cleanup journal unavailable"); }
        closed = true;
        if (preflightVerified)
            System.out.println("NEON_RESOURCE_FIXTURE=" + label + ",PG17,ordinary-runtime,OID-cleanup,PASS");
        else System.out.println("NEON_RESOURCE_CLEANUP=" + label + ",CLOSED");
    }

    private void checkOwner(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "select current_user,current_database(),current_setting('server_version_num')::integer/10000,r.oid,session_user=current_user,r.rolsuper,r.rolcreatedb,r.rolcreaterole from pg_roles r where r.rolname=current_user")) {
            if (!rows.next() || !owner.equals(rows.getString(1)) || !ownerDatabase.equals(rows.getString(2)) || rows.getInt(3) != 17)
                throw new IllegalStateException("Assigned PG17 setup owner mismatch");
            if (!rows.getBoolean(5) || rows.getBoolean(6) || !rows.getBoolean(7) || !rows.getBoolean(8))
                throw new IllegalStateException("Assigned setup owner must be session-identical, non-superuser CREATEDB/CREATEROLE");
            if (ownerOid != 0 && ownerOid != rows.getLong(4)) throw new IllegalStateException("Assigned owner OID changed");
            ownerOid = rows.getLong(4);
        }
    }

    private Connection connect(String target, String user, String secret) throws SQLException {
        Properties credentials = new Properties(); credentials.setProperty("user", user); credentials.setProperty("password", secret);
        Connection connection = DriverManager.getConnection(jdbcUrl(target), credentials);
        try { requireTlsSocket(connection); return connection; }
        catch (RuntimeException failure) { connection.close(); throw failure; }
    }
    private String jdbcUrl(String target) {
        return "jdbc:postgresql://" + host + ":5432/" + target
                + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require&connectTimeout=10&socketTimeout=30";
    }
    private static void execute(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement()) { statement.setQueryTimeout(30); statement.execute(sql); }
    }
    private static long oid(Connection c, String sql, String name) throws SQLException {
        try (var query = c.prepareStatement(sql)) {
            query.setString(1, name); try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Expected fixture object missing");
                long value = rows.getLong(1); if (rows.next()) throw new IllegalStateException("Ambiguous fixture object"); return value;
            }
        }
    }
    private void recordSecret() throws java.io.IOException {
        Path path = privatePath("PRAXIS_METADATA_NEON_FIXTURE_CREDENTIALS_FILE");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE));
        requirePrivate(path);
        String key = "resourcePassword." + label + "=";
        if (Files.readString(path).lines().anyMatch(line -> line.startsWith(key)))
            throw new IllegalStateException("Resource fixture credentials already exist; refusing fixture reuse");
        Files.writeString(path, key + password + "\n", StandardOpenOption.APPEND);
    }
    static void requirePrivate(Path file) throws java.io.IOException {
        if (!file.isAbsolute() || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(file).equals(PRIVATE) || Files.size(file) > 65536)
            throw new IllegalStateException("Fixture credential file must be private and regular");
    }
    static void requireMatchingOid(long observed, long expected) {
        if (expected <= 0 || observed != expected) throw new IllegalStateException("Fixture object OID/owner changed; cleanup refused");
    }
    private void journal(String state) throws java.io.IOException {
        Path path = Path.of(privatePath("PRAXIS_METADATA_NEON_FIXTURE_CREDENTIALS_FILE") + ".journal");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE));
        requirePrivate(path);
        Files.writeString(path, "label=" + label + ";database=" + database + ";runtime=" + runtime
                + ";ownerOid=" + ownerOid + ";databaseOid=" + databaseOid + ";runtimeOid=" + runtimeOid
                + ";schemaOid=" + schemaOid + ";state=" + state + "\n", StandardOpenOption.APPEND);
    }
    private static Path privatePath(String name) {
        Path path = Path.of(required(name));
        if (!path.isAbsolute()) throw new IllegalStateException("Assigned fixture paths must be absolute");
        return path;
    }
    private static void requireTlsSocket(Connection connection) {
        // Neon terminates client TLS at the proxy; pg_stat_ssl is not a client TLS witness.
        try {
            var field = QueryExecutorBase.class.getDeclaredField("pgStream"); field.setAccessible(true);
            PGStream stream = (PGStream) field.get(connection.unwrap(BaseConnection.class).getQueryExecutor());
            if (!(stream.getSocket() instanceof SSLSocket socket) || !socket.getSession().isValid()
                    || !Set.of("TLSv1.2", "TLSv1.3").contains(socket.getSession().getProtocol()))
                throw new IllegalStateException("Verified TLS socket witness required");
        } catch (ReflectiveOperationException | SQLException failure) {
            throw new IllegalStateException("Pinned PostgreSQL driver TLS witness unavailable");
        }
    }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name); return value;
    }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
}
