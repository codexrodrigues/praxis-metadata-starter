package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1a characterizes a baseline limit, not the desired restore contract. A real database-local
 * TEMPLATE copy retains the declared identity and can execute after only its origin was fenced.
 * C1b must replace that permission expectation once a mechanism is approved; frozen C1a evidence
 * remains historical. No authority rollback, cluster backup, succession or anti-clone is proved.
 */
class BulkCapacityCloneBaselinePostgresTest {
    private static final String ORIGIN = "capacity_local_1";
    private static final String COPY = "capacity_local_clone";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void cloneBeforeFenceRetainsTheCopiedBindingAndCanCommitAnAsyncUnit() throws Exception {
        try (var proof = new Proof()) {
            try (var scope = new BulkCapacityOccupancyPostgresFixture.SharedScope()) {
                var original = scope.local("c1a-origin-before-fence", 1);
                original.activate();
                UUID queue = original.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
                UUID active = original.install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
                var input = original.persist();
                var queued = original.enqueue(input, "c1a-clone-key", queue);
                var claimed = original.kernel.claim(original.context, queued.executionId(), "c1a-worker", active).orElseThrow();
                assertThat(claimed.control().epoch()).isEqualTo(2);
                var firstPhysical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
                var first = original.kernel.executeUnit(claimed.control(), 0, unit -> BulkUnitAdmission.admit(), unit -> {
                    firstPhysical.set(original.writeDomain(unit));
                    return BulkUnitMutationResult.confirmed();
                });
                assertThat(first.receiptPresent()).isTrue();
                assertThat(first.execution().nextOrdinal()).isEqualTo(1);
                assertThat(first.execution().status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
                original.assertPhysicalCommit(claimed.executionId(), firstPhysical.get());
                proof.physical("ORIGIN_ORDINAL_ZERO_CONFIRMED", firstPhysical.get());

                var authorityBefore = authorityRows(scope);
                var rowsBefore = fullRows(original.observer);
                var catalogBefore = catalog(original.ownerSource);
                var admin = new JdbcTemplate(scope.postgres.getPostgresDatabase());
                var rolesBefore = clusterRoles(admin);
                var envelope = envelope(admin, ORIGIN);
                assertThat(envelope.allowConnections()).isTrue();
                validate(original.ownerSource);
                // All fixture datasources are unpooled. Every earlier query/transaction has returned
                // and closed its loan; no callback or transaction spans this physical copy.
                copyDatabase(scope.postgres.getPostgresDatabase(), admin, envelope, proof);

                DataSource copyOwner = source(scope, envelope.owner(), COPY);
                var copyObserver = new JdbcTemplate(copyOwner);
                var copiedEnvelope = envelope(admin, COPY);
                assertThat(copiedEnvelope.oid()).isNotEqualTo(envelope.oid());
                sameEnvelope(envelope, copiedEnvelope);
                unchanged(fullRows(copyObserver), rowsBefore, "full copied rows, bytes and MVCC identities");
                unchanged(catalog(copyOwner), catalogBefore, "full copied catalog, owners and ACLs");
                unchanged(clusterRoles(admin), rolesBefore, "existing cluster roles and memberships");
                unchanged(authorityRows(scope), authorityBefore, "authority unchanged by local copy");
                validate(copyOwner);
                proof.phase("COPY_PARITY_CERTIFIED_BEFORE_KERNEL_CONSTRUCTION")
                        .put("originDatabaseOid", envelope.oid()).put("copyDatabaseOid", copiedEnvelope.oid());

                // Trusted test configuration is deliberately reused verbatim. No scope.local(),
                // migrate, new UUID, generation change, copied DTO authority or healing is involved.
                var copySource = source(scope, "bulk_runtime_test", COPY);
                var copySql = new JdbcTemplate(copySource);
                assertThat(copySql.queryForObject("select current_user", String.class)).isEqualTo("bulk_runtime_test");
                var copyRuntime = new BulkExecutionInfrastructure(copySource, new DataSourceTransactionManager(copySource),
                        original.context.namespaceId(), original.runtime.deploymentId(), original.runtime.roleConfiguration());
                var copyKernel = new JdbcBulkDurableExecution(copyRuntime, null, original.expected);
                var copyInstallation = new JdbcBulkCapacityInstallation(original.expected, copyOwner,
                        new DataSourceTransactionManager(copyOwner), envelope.owner(), copyRuntime, scope.provisioner,
                        scope.reader, Duration.ofSeconds(20), Duration.ofSeconds(3));
                copyInstallation.bootstrap();
                copyInstallation.registerAttestation(); // actual owner/runtime witness in the copy; global exact replay
                copyInstallation.activate();
                assertThat(copyInstallation.install(queue)).isFalse();
                assertThat(copyInstallation.install(active)).isFalse();
                unchanged(fullRows(copyObserver), rowsBefore, "reconstruction and exact installation replay preserve copied rows");
                unchanged(authorityRows(scope), authorityBefore, "reconstructed installer does not rewrite authority");
                proof.phase("COPIED_BINDING_RECONSTRUCTED_WITH_AUTHENTICATED_REPLAY");

                original.installation.fence();
                assertThat(original.observer.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class))
                        .isEqualTo("FENCED");
                assertThat(copyObserver.queryForObject("select state from praxis_bulk.praxis_bulk_capacity_marker", String.class))
                        .isEqualTo("ACTIVE");
                var originalAfterFence = fullRows(original.observer);
                var deniedAdmissions = new AtomicInteger();
                var deniedCallbacks = new AtomicInteger();
                assertThatThrownBy(() -> original.kernel.executeUnit(claimed.control(), 1, unit -> {
                    deniedAdmissions.incrementAndGet(); return BulkUnitAdmission.admit();
                }, unit -> {
                    deniedCallbacks.incrementAndGet(); original.writeDomain(unit);
                    return BulkUnitMutationResult.confirmed();
                })).isExactlyInstanceOf(BulkDurableExecutionException.class)
                        .satisfies(error -> assertThat(((BulkDurableExecutionException) error).reason())
                                .isEqualTo(BulkDurableExecutionException.Reason.FENCED));
                assertThat(deniedAdmissions).hasValue(0);
                assertThat(deniedCallbacks).hasValue(0);
                unchanged(fullRows(original.observer), originalAfterFence, "origin denial makes no durable or domain mutation");
                proof.phase("ORIGIN_FENCED_ORDINAL_ONE_DENIED_BEFORE_CALLBACK").put("safeReason", "FENCED")
                        .put("admissions", 0).put("callbacks", 0);

                var replayBefore = fullRows(copyObserver);
                var copyAdmissions = new AtomicInteger();
                var copyCallbacks = new AtomicInteger();
                var replay = copyKernel.executeUnit(claimed.control(), 0, unit -> {
                    copyAdmissions.incrementAndGet(); return BulkUnitAdmission.admit();
                }, unit -> {
                    copyCallbacks.incrementAndGet(); writeCopiedDomain(copyRuntime, copySql, copyObserver, unit, proof);
                    return BulkUnitMutationResult.confirmed();
                });
                assertThat(replay.receiptPresent()).isTrue();
                assertThat(replay.replayed()).isTrue();
                assertThat(copyAdmissions).hasValue(0);
                assertThat(copyCallbacks).hasValue(0);
                unchanged(fullRows(copyObserver), replayBefore, "copied confirmed prefix replay has no callback or mutation");
                proof.phase("COPIED_RECEIPT_ZERO_REPLAYED_WITHOUT_CALLBACK");

                var copyPhysical = new AtomicReference<BulkCapacityOccupancyPostgresFixture.PhysicalUnit>();
                var second = copyKernel.executeUnit(claimed.control(), 1, unit -> {
                    copyAdmissions.incrementAndGet(); return BulkUnitAdmission.admit();
                }, unit -> {
                    copyCallbacks.incrementAndGet();
                    copyPhysical.set(writeCopiedDomain(copyRuntime, copySql, copyObserver, unit, proof));
                    return BulkUnitMutationResult.confirmed();
                });
                assertThat(second.receiptPresent()).isTrue();
                assertThat(second.replayed()).isFalse();
                assertThat(second.execution().status()).isEqualTo(BulkDurableExecutionStatus.COMPLETED);
                assertThat(second.execution().nextOrdinal()).isEqualTo(2);
                assertThat(copyAdmissions).hasValue(1);
                assertThat(copyCallbacks).hasValue(1);
                assertCopiedCommit(copyObserver, claimed.executionId(), copyPhysical.get(), proof);
                assertThat(copyObserver.queryForObject("select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=?",
                        Long.class, claimed.executionId())).isEqualTo(2);
                assertThat(copyObserver.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_slot where current_execution_id=?",
                        Long.class, claimed.executionId())).isZero();
                assertThat(copyObserver.queryForMap("select kind,state from praxis_bulk.praxis_bulk_allocation where execution_id=?",
                        claimed.executionId())).containsEntry("kind", "EXECUTION_ASYNC").containsEntry("state", "RELEASED");
                unchanged(fullRows(original.observer), originalAfterFence, "copy commit does not alter fenced origin");
                unchanged(authorityRows(scope), authorityBefore, "authority issuance and attestations unchanged");
                unchanged(clusterRoles(admin), rolesBefore, "copy adds no login, role or membership");
                validate(original.ownerSource);
                validate(copyOwner);
                assertThat(backends(admin, ORIGIN)).isZero();
                assertThat(backends(admin, COPY)).isZero();
                proof.phase("LOCAL_COPY_BASELINE_LIMIT_CHARACTERIZED").put("copyAdmissions", 1).put("copyCallbacks", 1)
                        .put("originOrdinalOneReceipts", 0).put("copyOrdinalOneReceipts", 1)
                        .put("originBackendsBeforeCleanup", 0).put("copyBackendsBeforeCleanup", 0);
                original.assertionsComplete();
            }
            // This point is reached only after the owning SharedScope closed successfully.
            proof.phase("OWNED_SHARED_SCOPE_CLOSED");
            proof.complete = true;
        }
    }

    private static DataSource source(BulkCapacityOccupancyPostgresFixture.SharedScope scope, String role, String database) {
        return new DriverManagerDataSource(scope.postgres.getJdbcUrl(role, database), role, "");
    }

    private static void validate(DataSource source) {
        BulkExecutionMigrator.validate(source, BulkPostgresTestSupport.testRoleConfiguration());
    }

    private record DatabaseGrant(String grantor, String grantee, String privilege, boolean grantOption) { }
    private record DatabaseSetting(String role, String key, String value) { }
    private record Envelope(long oid, String owner, boolean allowConnections, int connectionLimit, boolean template,
                            String encoding, String collation, String ctype, String tablespace,
                            List<DatabaseGrant> grants, List<DatabaseSetting> settings) { }

    /** ACL defaults are expanded explicitly; complete grantor/grantee/options are compared. */
    private static Envelope envelope(JdbcTemplate admin, String database) {
        var row = admin.queryForMap("""
                select d.oid::bigint as oid,r.rolname as owner,d.datallowconn,d.datconnlimit,d.datistemplate,
                       pg_encoding_to_char(d.encoding) as encoding,d.datcollate,d.datctype,t.spcname
                from pg_database d join pg_roles r on r.oid=d.datdba join pg_tablespace t on t.oid=d.dattablespace
                where d.datname=?
                """, database);
        var grants = admin.query("""
                select grantor.rolname as grantor,grantee.rolname as grantee,a.privilege_type,a.is_grantable
                from pg_database d cross join lateral aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
                join pg_roles grantor on grantor.oid=a.grantor left join pg_roles grantee on grantee.oid=a.grantee
                where d.datname=? order by grantor.rolname,grantee.rolname nulls first,a.privilege_type,a.is_grantable
                """, (rs, index) -> new DatabaseGrant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), database);
        var settings = admin.query("""
                select r.rolname,v.value from pg_db_role_setting s join pg_database d on d.oid=s.setdatabase
                left join pg_roles r on r.oid=s.setrole cross join lateral unnest(s.setconfig) v(value)
                where d.datname=? order by r.rolname nulls first,v.value
                """, (rs, index) -> {
                    String raw = rs.getString(2);
                    int separator = raw.indexOf('=');
                    if (separator <= 0) throw new IllegalStateException("Unsupported observed database setting representation");
                    String key = raw.substring(0, separator);
                    // This bounded fixture copier is not a general backup/settings implementation.
                    if (!Set.of("search_path", "statement_timeout", "lock_timeout", "idle_in_transaction_session_timeout").contains(key))
                        throw new IllegalStateException("Unsupported observed database setting");
                    return new DatabaseSetting(rs.getString(1), key, raw.substring(separator + 1));
                }, database);
        return new Envelope(((Number) row.get("oid")).longValue(), (String) row.get("owner"),
                (Boolean) row.get("datallowconn"), ((Number) row.get("datconnlimit")).intValue(),
                (Boolean) row.get("datistemplate"), (String) row.get("encoding"), (String) row.get("datcollate"),
                (String) row.get("datctype"), (String) row.get("spcname"), List.copyOf(grants), List.copyOf(settings));
    }

    private static void sameEnvelope(Envelope a, Envelope b) {
        assertThat(a.owner().equals(b.owner()) && a.allowConnections() == b.allowConnections()
                && a.connectionLimit() == b.connectionLimit() && a.template() == b.template()
                && a.encoding().equals(b.encoding()) && a.collation().equals(b.collation())
                && a.ctype().equals(b.ctype()) && a.tablespace().equals(b.tablespace())
                && a.grants().equals(b.grants()) && a.settings().equals(b.settings()))
                .as("observed database envelope matches except physical name/OID").isTrue();
    }

    /** CREATE DATABASE runs outside a transaction. Only this disposable origin is quiesced. */
    private static void copyDatabase(DataSource cluster, JdbcTemplate admin, Envelope original, Proof proof) throws Exception {
        for (var grant : original.grants()) {
            if (!original.owner().equals(grant.grantor()) || !Set.of("CONNECT", "CREATE", "TEMPORARY").contains(grant.privilege()))
                throw new IllegalStateException("Unsupported observed database ACL grantor or privilege");
        }
        boolean connectionsDisabled = false;
        Throwable primary = null;
        try (var connection = cluster.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(20); // fixture copy only; kernel and host native budgets remain untouched
                statement.execute("alter database " + identifier(ORIGIN) + " allow_connections false");
                connectionsDisabled = true;
                assertThat(backends(admin, ORIGIN)).as("all original leases closed before physical copy").isZero();
                proof.phase("ORIGIN_QUIESCENT_WITH_NEW_CONNECTIONS_DISABLED").put("originBackends", 0);
                statement.execute("create database " + identifier(COPY) + " with template " + identifier(ORIGIN)
                        + " owner " + identifier(original.owner()) + " allow_connections false"
                        + " connection limit " + original.connectionLimit() + " is_template " + original.template());
            }
            transplantEnvelope(cluster, admin, original);
            proof.phase("DATABASE_TEMPLATE_COPY_AND_OBSERVED_ENVELOPE_TRANSPLANT_COMPLETE");
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (connectionsDisabled) {
                try {
                    admin.execute("alter database " + identifier(ORIGIN) + " allow_connections " + original.allowConnections());
                    assertThat(admin.queryForObject("select datallowconn from pg_database where datname=?", Boolean.class, ORIGIN))
                            .isEqualTo(original.allowConnections());
                    proof.phase("ORIGIN_CONNECTION_POLICY_RESTORED");
                } catch (Exception | Error cleanup) {
                    if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
                }
            }
        }
    }

    /** Transplant only values observed on the origin, including grantor and grant options. */
    private static void transplantEnvelope(DataSource cluster, JdbcTemplate admin, Envelope original) throws SQLException {
        var created = envelope(admin, COPY);
        assertThat(created.owner()).isEqualTo(original.owner());
        try (var connection = cluster.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            connection.setAutoCommit(false);
            boolean committed = false;
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(20);
                if (!created.grants().equals(original.grants())) {
                    var grantees = new LinkedHashSet<String>();
                    for (var grant : created.grants()) grantees.add(grant.grantee());
                    for (var grant : original.grants()) grantees.add(grant.grantee());
                    for (String grantee : grantees)
                        statement.execute("revoke all privileges on database " + identifier(COPY) + " from " + grantee(grantee));
                    statement.execute("set local role " + identifier(original.owner()));
                    for (var grant : original.grants())
                        statement.execute("grant " + grant.privilege() + " on database " + identifier(COPY) + " to "
                                + grantee(grant.grantee()) + (grant.grantOption() ? " with grant option" : ""));
                    statement.execute("set local role none");
                }
                // A new database should have none; a nonempty unexpected envelope is not silently cleared.
                assertThat(created.settings().isEmpty()).as("new TEMPLATE copy has no invented database settings").isTrue();
                for (var setting : original.settings()) {
                    String target = setting.role() == null ? "database " + identifier(COPY)
                            : "role " + identifier(setting.role()) + " in database " + identifier(COPY);
                    statement.execute("alter " + target + " set " + identifier(setting.key()) + " to " + literal(setting.value()));
                }
                statement.execute("alter database " + identifier(COPY) + " allow_connections " + original.allowConnections());
                connection.commit(); committed = true;
            } finally { if (!committed) connection.rollback(); }
        }
        sameEnvelope(original, envelope(admin, COPY));
    }

    private static String identifier(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid fixture identifier");
        return '"' + value.replace("\"", "\"\"") + '"';
    }
    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static String grantee(String role) { return role == null ? "PUBLIC" : identifier(role); }
    private static long backends(JdbcTemplate admin, String database) {
        return admin.queryForObject("select count(*) from pg_stat_activity where datname=?", Long.class, database);
    }

    /** All bulk/Flyway rows and the domain fixture, including bytea content and copied xmin. */
    private static Map<String, List<String>> fullRows(JdbcTemplate sql) {
        var result = new LinkedHashMap<String, List<String>>();
        var tables = sql.queryForList("""
                select n.nspname||'.'||c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where c.relkind='r' and (n.nspname='praxis_bulk'
                    or (n.nspname='public' and c.relname='occupancy_domain_witness')) order by 1
                """, String.class);
        assertThat(tables).contains("public.occupancy_domain_witness", "praxis_bulk.praxis_bulk_capacity_marker",
                "praxis_bulk." + BulkExecutionMigrator.HISTORY_TABLE);
        for (String table : tables) {
            if (!table.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")) throw new IllegalStateException("Unsafe fixture relation");
            result.put(table, sql.queryForList("select to_jsonb(t)::text from (select *,xmin::text as copy_xmin from "
                    + table + ") t order by 1", String.class));
        }
        return result;
    }

    /** Deparse under pg_catalog so qualification cannot depend on either connection's search_path. */
    private static Map<String, List<String>> catalog(DataSource owner) {
        return new JdbcTemplate(owner).execute((ConnectionCallback<Map<String, List<String>>>) connection -> {
            assertThat(connection.getAutoCommit()).isTrue();
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) { statement.execute("set local search_path=pg_catalog"); }
                var result = new LinkedHashMap<String, List<String>>();
                result.put("schemas", strings(connection, """
                        select jsonb_build_array(nspname,pg_get_userbyid(nspowner),nspacl)::text
                        from pg_namespace where nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("relations", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,c.relkind,pg_get_userbyid(c.relowner),c.relacl,c.reloptions)::text
                        from pg_class c join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("columns", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod),
                            a.attnotnull,a.attidentity,a.attgenerated,a.attacl,pg_get_expr(d.adbin,d.adrelid))::text
                        from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace
                        left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                        where n.nspname in ('praxis_bulk','public') and a.attnum>0 and not a.attisdropped order by 1
                        """));
                result.put("constraints", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,k.conname,k.contype,k.convalidated,k.condeferrable,
                            k.condeferred,pg_get_constraintdef(k.oid))::text
                        from pg_constraint k join pg_class c on c.oid=k.conrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("indexes", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,i.indisvalid,i.indisready,pg_get_indexdef(i.indexrelid))::text
                        from pg_index i join pg_class c on c.oid=i.indrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("functions", strings(connection, """
                        select jsonb_build_array(n.nspname,p.proname,pg_get_userbyid(p.proowner),p.proacl,
                            p.prosecdef,p.provolatile,p.proconfig,pg_get_functiondef(p.oid))::text
                        from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                result.put("triggers", strings(connection, """
                        select jsonb_build_array(n.nspname,c.relname,t.tgname,t.tgenabled,t.tgisinternal,pg_get_triggerdef(t.oid))::text
                        from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
                        where n.nspname in ('praxis_bulk','public') order by 1
                        """));
                return result;
            } finally { connection.rollback(); }
        });
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        var result = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static Map<String, List<String>> clusterRoles(JdbcTemplate admin) {
        return Map.of("roles", admin.queryForList("select to_jsonb(r)::text from pg_roles r order by 1", String.class),
                "memberships", admin.queryForList("select to_jsonb(m)::text from pg_auth_members m order by 1", String.class),
                "globalSettings", admin.queryForList("select to_jsonb(s)::text from pg_db_role_setting s where setdatabase=0 order by 1", String.class));
    }

    private static Map<String, List<String>> authorityRows(BulkCapacityOccupancyPostgresFixture.SharedScope scope) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String table : scope.authorityObserver.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='praxis_bulk_capacity' and c.relkind='r' order by 1
                """, String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalStateException("Unsafe fixture relation");
            result.put(table, scope.authorityObserver.queryForList("select to_jsonb(t)::text from praxis_bulk_capacity."
                    + table + " t order by 1", String.class));
        }
        return result;
    }

    private static void unchanged(Object actual, Object expected, String purpose) {
        assertThat(actual.equals(expected)).as(purpose).isTrue(); // Never print protected full-row snapshots.
    }

    private static BulkCapacityOccupancyPostgresFixture.PhysicalUnit writeCopiedDomain(BulkExecutionInfrastructure runtime,
            JdbcTemplate sql, JdbcTemplate observer, BulkExecutionUnit unit, Proof proof) {
        var physical = runtime.withConnection(connection -> {
            assertThat(connection.getAutoCommit()).isFalse();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select pg_backend_pid(),txid_current()")) {
                assertThat(rows.next()).isTrue();
                var value = new BulkCapacityOccupancyPostgresFixture.PhysicalUnit(unit.ordinal(), rows.getInt(1), rows.getLong(2));
                assertThat(rows.next()).isFalse();
                var same = sql.queryForMap("select pg_backend_pid() as pid,txid_current() as xid");
                assertThat(((Number) same.get("pid")).intValue()).isEqualTo(value.backendPid());
                assertThat(((Number) same.get("xid")).longValue()).isEqualTo(value.transactionId());
                return value;
            }
        });
        assertThat(sql.update("update occupancy_domain_witness set writes=writes+1,last_pid=pg_backend_pid(),last_xid=txid_current() where id=?",
                unit.ordinal() + 1)).isEqualTo(1);
        var absent = observer.queryForMap("""
                select pg_backend_pid() as observer_pid,writes,
                    (select count(*) from praxis_bulk.praxis_bulk_item_receipt where execution_id=? and unit_ordinal=?) as receipts
                from occupancy_domain_witness where id=?
                """, unit.executionId(), unit.ordinal(), unit.ordinal() + 1);
        assertThat(absent).containsEntry("writes", 0).containsEntry("receipts", 0L);
        int observerPid = ((Number) absent.get("observer_pid")).intValue();
        assertThat(observerPid).isNotEqualTo(physical.backendPid());
        proof.physical("COPY_UNCOMMITTED_DOMAIN_AND_RECEIPT_ABSENT", physical).put("observerPid", observerPid);
        return physical;
    }

    private static void assertCopiedCommit(JdbcTemplate observer, UUID execution,
            BulkCapacityOccupancyPostgresFixture.PhysicalUnit physical, Proof proof) {
        var row = observer.queryForMap("""
                select pg_backend_pid() as observer_pid,d.writes,d.last_pid,d.last_xid,d.xmin::text::bigint as domain_xid,
                       r.xmin::text::bigint as receipt_xid,r.owner_epoch,r.outcome
                from occupancy_domain_witness d join praxis_bulk.praxis_bulk_item_receipt r
                    on r.execution_id=? and r.unit_ordinal=? where d.id=?
                """, execution, physical.ordinal(), physical.ordinal() + 1);
        long xid = physical.transactionId() & 0xffffffffL;
        assertThat(row).containsEntry("writes", 1).containsEntry("last_pid", physical.backendPid())
                .containsEntry("last_xid", physical.transactionId()).containsEntry("domain_xid", xid)
                .containsEntry("receipt_xid", xid).containsEntry("owner_epoch", 2L).containsEntry("outcome", "CONFIRMED");
        int observerPid = ((Number) row.get("observer_pid")).intValue();
        assertThat(observerPid).isNotEqualTo(physical.backendPid());
        proof.physical("COPY_DOMAIN_AND_RECEIPT_COMMITTED_IN_ONE_PHYSICAL_TX", physical)
                .put("observerPid", observerPid).put("domainXid", xid).put("receiptXid", xid);
    }

    /** Only sanitized certificates are persisted. Full rows and configuration remain in memory. */
    private static final class Proof implements AutoCloseable {
        final ObjectNode manifest = JSON.createObjectNode().put("caseId", "c1a-clone-before-fence-baseline")
                .put("harnessPid", ProcessHandle.current().pid()).put("subprocesses", 0).put("barriersUsed", false)
                .put("scope", "DATABASE_LOCAL_TEMPLATE_COPY_BASELINE_CHARACTERIZATION");
        final com.fasterxml.jackson.databind.node.ArrayNode events = manifest.putArray("events");
        boolean complete;
        ObjectNode phase(String phase) { return events.addObject().put("phase", phase); }
        ObjectNode physical(String phase, BulkCapacityOccupancyPostgresFixture.PhysicalUnit unit) {
            return phase(phase).put("ordinal", unit.ordinal()).put("backendPid", unit.backendPid()).put("transactionId", unit.transactionId());
        }
        @Override public void close() throws Exception {
            manifest.put("caseOutcome", complete ? "BASELINE_LIMIT_CHARACTERIZED" : "INCOMPLETE_OR_FAILED");
            String configured = System.getProperty("praxis.bulk.proof.directory");
            Path directory = configured == null ? Files.createTempDirectory("praxis-c1a-manifest-") : Path.of(configured);
            Files.createDirectories(directory);
            Path file = directory.resolve("c1a-clone-before-fence-baseline.json");
            Files.writeString(file, manifest.toPrettyString(), StandardCharsets.UTF_8);
            if (configured == null) System.out.println("C1a baseline proof manifest: " + file.toAbsolutePath());
        }
    }
}
