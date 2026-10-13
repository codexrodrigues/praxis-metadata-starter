package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipFile;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Six isolated Neon component cases; no embedded database or claim of ORIGINAL predecessor execution. */
@EnabledIfEnvironmentVariable(named="PRAXIS_BULK_CAPACITY_MANAGED_NEON_FIXTURE_FILE",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class BulkCapacityAuthorityManagedNeonTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SCHEMA="praxis_bulk_capacity", DEFINER="praxis_bulk_capacity_definer";
    private static final String FRESH="capacity_managed_fresh_20261012", V1="capacity_managed_v1_20261012";
    private static final String PROVISIONER="capacity_managed_provisioner_20261012", ALLOCATOR="capacity_managed_allocator_20261012";
    private static final String READER="capacity_managed_reader_20261012", NEGATIVE="capacity_managed_negative_20261012";
    // Both isolated databases intentionally share three cluster-global ordinary callers; V1 must run first.
    private static final List<String> TABLES=List.of("authority_identity","deployment_capacity","tenant_capacity","capacity_binding",
            "capacity_request","capacity_token","fairness_cursor","binding_attestation");
    @RegisterExtension final BulkAsyncPublicAdmissionNeonTest.FailFast failFast=new BulkAsyncPublicAdmissionNeonTest.FailFast();
    private final List<HikariDataSource> pools=new ArrayList<>();
    private HikariDataSource freshOwner,v1Owner;
    private BulkCapacityAuthorityMigrator.RoleConfiguration roles;
    private BulkCapacityAuthorityMigrator.Identity freshIdentity,v1Identity;
    private Path output;
    private URI uri;
    private JsonNode credentials;
    private String owner,jarSha,currentPhase="PREFLIGHT";
    private boolean continued;
    private String priorPassSha;

    @BeforeAll void setup() throws Exception {
        try {
            output=Path.of(required("PRAXIS_BULK_CAPACITY_MANAGED_NEON_PROOF_DIR"));
            Files.createDirectories(output);Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rwx------"));
            Path ownerFile=privateFile("PRAXIS_BULK_CAPACITY_MANAGED_NEON_OWNER_FILE");
            Path fixtureFile=privateFile("PRAXIS_BULK_CAPACITY_MANAGED_NEON_FIXTURE_FILE");
            Path identitiesFile=privateFile("PRAXIS_BULK_CAPACITY_MANAGED_NEON_IDENTITIES_FILE");
            String start=System.getenv().getOrDefault("PRAXIS_BULK_CAPACITY_MANAGED_NEON_START","FRESH");
            assertThat(start).isIn("FRESH","CONTINUE_MIGRATED");continued=start.equals("CONTINUE_MIGRATED");
            if(!continued){
                assertThat(System.getenv("PRAXIS_BULK_CAPACITY_MANAGED_CONTINUATION_FILE")).isNull();
                assertThat(System.getenv("PRAXIS_BULK_CAPACITY_MANAGED_CONTINUATION_RECEIPT_FILE")).isNull();
                assertThat(System.getenv("PRAXIS_BULK_CAPACITY_MANAGED_CONTINUATION_RECEIPT_SHA256")).isNull();
            }
            credentials=JSON.readTree(fixtureFile.toFile());var identities=JSON.readTree(identitiesFile.toFile());
            uri=URI.create(Files.readString(ownerFile).strip());
            assertThat(uri.getScheme()).isIn("postgres","postgresql");
            assertThat(uri.getHost()).isEqualTo("ep-jolly-surf-adozrbml.c-2.us-east-1.aws.neon.tech")
                    .isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_HOST")).doesNotContain("-pooler");
            assertThat(credentials.path("branch").asText()).isEqualTo("br-little-glade-adwtqnv6").isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_BRANCH"));
            assertThat(credentials.path("endpoint").asText()).isEqualTo(uri.getHost());
            assertThat(credentials.path("databases").toString()).isEqualTo("[\""+FRESH+"\",\""+V1+"\"]");
            assertThat(identities.path("branch").asText()).isEqualTo(credentials.path("branch").asText());
            assertThat(identities.path("status").asText()).isEqualTo("PENDING_SETUP_AND_MIGRATION");
            String[] auth=uri.getRawUserInfo().split(":",2);owner=decode(auth[0]);
            assertThat(owner).isEqualTo("neondb_owner").isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_OWNER"));
            roles=new BulkCapacityAuthorityMigrator.RoleConfiguration(owner,PROVISIONER,ALLOCATOR,READER);
            freshIdentity=identity(identities,"fresh");v1Identity=identity(identities,"v1");
            assertThat(freshIdentity.authorityId()).isNotEqualTo(v1Identity.authorityId());
            attestCandidate();
            if(continued) validateContinuation();
            freshOwner=pool(source(FRESH,owner,decode(auth[1])),4);v1Owner=pool(source(V1,owner,decode(auth[1])),4);
            for(var ds:List.of(freshOwner,v1Owner)){
                var sql=new JdbcTemplate(ds);
                assertThat(sql.queryForObject("select current_user=session_user and current_user=?",Boolean.class,owner)).isTrue();
                assertThat(sql.queryForObject("select current_setting('server_version_num')::int",Integer.class)).isGreaterThanOrEqualTo(170000);
                assertThat(sql.queryForObject("select not rolsuper and rolcreaterole and rolcreatedb from pg_roles where rolname=current_user",Boolean.class)).isTrue();
                if(!continued){
                    assertThat(sql.queryForObject("select to_regnamespace('praxis_bulk_capacity') is null and to_regnamespace('praxis_bulk') is null",Boolean.class)).isTrue();
                    assertThat(sql.queryForObject("select count(*) from pg_roles where rolname like 'praxis_bulk_%'",Integer.class)).isZero();
                }
                for(String login:List.of(PROVISIONER,ALLOCATOR,READER,NEGATIVE)){
                    assertThat(sql.queryForObject("select rolcanlogin and rolinherit and not rolsuper and not rolcreaterole and not rolcreatedb and not rolreplication and not rolbypassrls from pg_roles where rolname=?",Boolean.class,login)).isTrue();
                    if(!continued) assertThat(sql.queryForObject("select count(*) from pg_auth_members where member=(select oid from pg_roles where rolname=?)",Integer.class,login)).isZero();
                }
            }
            phase("PREFLIGHT");
            if(continued){
                currentPhase="CONTINUATION_VALIDATION";
                BulkCapacityAuthorityMigrator.validate(v1Owner,v1Identity,roles);
                BulkCapacityAuthorityMigrator.validate(freshOwner,freshIdentity,roles);
                assertManagedDefiner(v1Owner);assertManagedDefiner(freshOwner);phase("CONTINUATION_VALIDATION");
            }
        }catch(Exception|Error primary){failure(primary);try{cleanup();}catch(Exception cleanup){primary.addSuppressed(cleanup);}throw primary;}
    }

    @Test @Order(2) void freshManagedOwnerMigratesValidatesAndReplaysWithoutDrift() throws Exception {
        currentPhase="FRESH";
        if(!continued) assertThat(BulkCapacityAuthorityMigrator.migrate(freshOwner,freshIdentity,roles)).isEqualTo(2);
        BulkCapacityAuthorityMigrator.validate(freshOwner,freshIdentity,roles);assertManagedDefiner(freshOwner);
        var sql=new JdbcTemplate(freshOwner);
        assertThat(sql.queryForList("select version from praxis_bulk_capacity.praxis_bulk_capacity_schema_history where type='SQL' and success order by installed_rank",String.class)).containsExactly("1","2");
        for(String table:TABLES) for(String login:List.of(PROVISIONER,ALLOCATOR,READER))
            assertThat(sql.queryForObject("select has_table_privilege(?,?,'INSERT,UPDATE,DELETE,TRUNCATE')",Boolean.class,login,SCHEMA+"."+table)).isFalse();
        for(String login:List.of(PROVISIONER,ALLOCATOR,READER)){
            var caller=pool(source(FRESH,login,password(login)),2);var direct=new JdbcTemplate(caller);
            assertThat(direct.queryForObject("select current_user=session_user and current_user=?",Boolean.class,login)).isTrue();
            assertThatThrownBy(()->direct.update("delete from praxis_bulk_capacity.capacity_request")) .isInstanceOf(RuntimeException.class).satisfies(error->assertThat(hasSqlState(error,"42501")).isTrue());
            if(login.equals(READER)){
                var infrastructure=new BulkCapacityAuthorityInfrastructure(caller,new DataSourceTransactionManager(caller),freshIdentity,
                        BulkCapacityAuthorityInfrastructure.Access.READER,roles);
                assertThat(new JdbcBulkCapacityIssuer.CapacityReader(infrastructure).readIssuedToken(UUID.randomUUID())).isEmpty();
            }
        }
        var before=snapshot(freshOwner);
        assertThat(BulkCapacityAuthorityMigrator.migrate(freshOwner,freshIdentity,roles)).isZero();
        BulkCapacityAuthorityMigrator.validate(freshOwner,freshIdentity,roles);
        assertThat(snapshot(freshOwner)).isEqualTo(before);writeSnapshot("fresh-after",before);phase("FRESH_REPLAY");
    }

    @Test @Order(1) void newManagedV1UpgradesToV2AndPreservesItsHistoryExactly() throws Exception {
        assertThat(continued).as("The completed V1 case must be excluded from CONTINUE_MIGRATED selection").isFalse();
        currentPhase="NEW_MANAGED_V1";
        try(var connection=v1Owner.getConnection()){
            var lineage=BulkCapacityAuthorityMigrationLineage.select(connection);
            assertThat(Flyway.configure().dataSource(v1Owner).resourceProvider(lineage)
                    .locations("classpath:db/praxis-bulk-capacity-migrations").schemas(SCHEMA).defaultSchema(SCHEMA)
                    .table(BulkCapacityAuthorityMigrator.HISTORY_TABLE).createSchemas(true).baselineOnMigrate(false)
                    .cleanDisabled(true).validateOnMigrate(true).target("1").load().migrate().migrationsExecuted).isEqualTo(1);
        }
        var before=snapshot(v1Owner);writeSnapshot("new-v1-before",before);
        var v1Rows=new JdbcTemplate(v1Owner).queryForList("select to_jsonb(t)::text from praxis_bulk_capacity.praxis_bulk_capacity_schema_history t where version='1'",String.class);
        assertThat(v1Rows).hasSize(1);assertManagedDefiner(v1Owner);
        currentPhase="NEW_MANAGED_V1_TO_V2";
        assertThat(BulkCapacityAuthorityMigrator.migrate(v1Owner,v1Identity,roles)).isEqualTo(1);
        BulkCapacityAuthorityMigrator.validate(v1Owner,v1Identity,roles);
        assertThat(new JdbcTemplate(v1Owner).queryForList("select to_jsonb(t)::text from praxis_bulk_capacity.praxis_bulk_capacity_schema_history t where version='1'",String.class)).isEqualTo(v1Rows);
        var completed=snapshot(v1Owner);writeSnapshot("new-v2-after",completed);
        assertThat(BulkCapacityAuthorityMigrator.migrate(v1Owner,v1Identity,roles)).isZero();
        BulkCapacityAuthorityMigrator.validate(v1Owner,v1Identity,roles);assertThat(snapshot(v1Owner)).isEqualTo(completed);
        phase("NEW_MANAGED_V1_TO_V2_REPLAY");
    }

    @ParameterizedTest @Order(3) @ValueSource(strings={"UNEXPECTED_ADMIN","INHERIT","SET"})
    void unsafeManagedEdgesRejectWithoutHealing(String kind) throws Exception {
        currentPhase=kind;var sql=new JdbcTemplate(freshOwner);
        Map<String,Object> before=bothSnapshots();String member=NEGATIVE;
        String options=switch(kind){case "UNEXPECTED_ADMIN"->"admin true, inherit false, set false";
            case "INHERIT"->"admin false, inherit true, set false";case "SET"->"admin false, inherit false, set true";default->throw new AssertionError();};
        boolean granted=false;Throwable primary=null;
        try{
            sql.execute("grant "+DEFINER+" to "+member+" with "+options+" granted by current_user");granted=true;
            assertThat(sql.queryForObject("select count(*) from pg_auth_members m join pg_roles r on r.oid=m.roleid join pg_roles u on u.oid=m.member join pg_roles g on g.oid=m.grantor where r.rolname=? and u.rolname=? and g.rolname=?",Integer.class,DEFINER,member,owner)).isEqualTo(1);
            var bad=bothSnapshots();writeSnapshot("negative-"+kind+"-before-rejection",bad);
            assertThatThrownBy(()->BulkCapacityAuthorityMigrator.validate(freshOwner,freshIdentity,roles)).isInstanceOf(IllegalStateException.class).hasMessage("Capacity definer membership changed");
            assertThat(bothSnapshots()).isEqualTo(bad);
            assertThatThrownBy(()->BulkCapacityAuthorityMigrator.migrate(freshOwner,freshIdentity,roles)).isInstanceOf(IllegalStateException.class).hasMessage("Capacity definer membership changed");
            assertThat(bothSnapshots()).isEqualTo(bad);
        }catch(Exception|Error e){primary=e;failure(e);throw e;
        }finally{
            if(granted)try{sql.execute("revoke "+DEFINER+" from "+member+" granted by current_user");}
            catch(Exception|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
        assertThat(bothSnapshots()).isEqualTo(before);
        BulkCapacityAuthorityMigrator.validate(freshOwner,freshIdentity,roles);BulkCapacityAuthorityMigrator.validate(v1Owner,v1Identity,roles);
        assertManagedDefiner(freshOwner);writeSnapshot("negative-"+kind+"-restored",before);phase(kind);
    }

    @Test @Order(4) void nativeAdministrativeCycleRejectsAndGrantorPredicateIsExact() throws Exception {
        currentPhase="NATIVE_ADMIN_CYCLE_AND_GRANTOR_PREDICATE";
        var before=bothSnapshots();var sql=new JdbcTemplate(freshOwner);
        assertManagedDefiner(freshOwner);
        assertThat(sql.queryForObject("select current_user=session_user and current_user=? and not rolsuper from pg_roles where rolname=current_user",Boolean.class,owner)).isTrue();
        var grantTx=new TransactionTemplate(new DataSourceTransactionManager(freshOwner));
        // Roll back even an unexpected success: this oracle must never leave a new membership.
        assertThatThrownBy(()->grantTx.executeWithoutResult(status->{
            try{sql.execute("grant "+DEFINER+" to "+owner+" with admin true, inherit false, set false granted by current_user");}
            finally{status.setRollbackOnly();}
        })).isInstanceOf(RuntimeException.class).satisfies(error->assertThat(hasSqlState(error,"0LP01")).isTrue());
        assertThat(bothSnapshots()).isEqualTo(before);

        // Evaluate the actual SDK predicate over a derived row, without catalog writes.
        // This proves grantor discrimination, not SDK rejection of a persisted impossible edge.
        String predicateSql="select ("+BulkCapacityAuthorityMigrator.MANAGED_ADMIN_MEMBERSHIP_PREDICATE+") from ("+
                "select source.roleid,source.member,source.admin_option,source.inherit_option,source.set_option,"+
                "case when ? then (select oid from pg_catalog.pg_roles where rolname=current_user) else source.grantor end grantor "+
                "from pg_catalog.pg_auth_members source where source.roleid=to_regrole(?) and source.member=to_regrole(?)"+
                ") m";
        assertThat(sql.queryForList(predicateSql,Boolean.class,false,DEFINER,owner)).containsExactly(true);
        assertThat(sql.queryForList(predicateSql,Boolean.class,true,DEFINER,owner)).containsExactly(false);
        BulkCapacityAuthorityMigrator.validate(freshOwner,freshIdentity,roles);
        BulkCapacityAuthorityMigrator.validate(v1Owner,v1Identity,roles);
        assertThat(bothSnapshots()).isEqualTo(before);
        writeSnapshot("native-admin-cycle-and-grantor-predicate-unchanged",before);
        phase("NATIVE_ADMIN_CYCLE_AND_GRANTOR_PREDICATE");
    }

    private static boolean hasSqlState(Throwable failure,String expected){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(int i=0;i<8 && failure!=null && seen.add(failure);i++,failure=failure.getCause())
            if(failure instanceof java.sql.SQLException sql && expected.equals(sql.getSQLState()))return true;
        return false;
    }
    private void assertManagedDefiner(DataSource ds){
        var sql=new JdbcTemplate(ds);
        var edges=sql.queryForList("select member.rolname member,m.admin_option,m.inherit_option,m.set_option,grantor.rolname grantor,grantor.rolsuper grantor_super from pg_auth_members m join pg_roles role on role.oid=m.roleid join pg_roles member on member.oid=m.member join pg_roles grantor on grantor.oid=m.grantor where role.rolname=?",DEFINER);
        assertThat(edges).containsExactly(Map.of("member",owner,"admin_option",true,"inherit_option",false,"set_option",false,"grantor","cloud_admin","grantor_super",true));
        assertThat(sql.queryForObject("select pg_has_role(?,?,'USAGE') or pg_has_role(?,?,'SET')",Boolean.class,owner,DEFINER,owner,DEFINER)).isFalse();
        assertThat(sql.queryForObject("select rolcanlogin or rolinherit or rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls from pg_roles where rolname=?",Boolean.class,DEFINER)).isFalse();
    }
    private Map<String,Object> bothSnapshots(){return Map.of("fresh",snapshot(freshOwner),"v1",snapshot(v1Owner));}
    private Map<String,List<String>> snapshot(DataSource ds){
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        return tx.execute(status->{var sql=new JdbcTemplate(ds);sql.execute("set local timezone='UTC'");sql.execute("set local statement_timeout='3s'");sql.execute("set local lock_timeout='1s'");var result=new TreeMap<String,List<String>>();
            for(String table:sql.queryForList("select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk_capacity' and c.relkind in('r','p') order by c.relname",String.class))
                result.put("rows."+table,sql.queryForList("select to_jsonb(t)::text from praxis_bulk_capacity."+table+" t order by to_jsonb(t)::text",String.class));
            result.put("schemas",sql.queryForList("select to_jsonb(t)::text from(select nspname,pg_get_userbyid(nspowner) owner,nspacl::text acl from pg_namespace where nspname in('public','praxis_bulk_capacity')) t order by to_jsonb(t)::text",String.class));
            result.put("relations",sql.queryForList("select to_jsonb(t)::text from(select c.relname,c.relkind,pg_get_userbyid(c.relowner) owner,c.relacl::text acl,c.relrowsecurity,c.relforcerowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk_capacity') t order by to_jsonb(t)::text",String.class));
            result.put("functions",sql.queryForList("select to_jsonb(t)::text from(select p.proname,pg_get_function_identity_arguments(p.oid) args,pg_get_userbyid(p.proowner) owner,p.proacl::text acl,p.proconfig,pg_get_functiondef(p.oid) definition from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='praxis_bulk_capacity') t order by to_jsonb(t)::text",String.class));
            result.put("columns",sql.queryForList("select to_jsonb(t)::text from(select c.relname,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod) type,a.attnotnull,a.attacl::text acl,pg_get_expr(d.adbin,d.adrelid) default_expr from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace left join pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum where n.nspname='praxis_bulk_capacity' and a.attnum>0 and not a.attisdropped) t order by to_jsonb(t)::text",String.class));
            result.put("constraints",sql.queryForList("select to_jsonb(t)::text from(select c.relname,k.conname,k.contype,pg_get_constraintdef(k.oid) definition from pg_constraint k join pg_class c on c.oid=k.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='praxis_bulk_capacity') t order by to_jsonb(t)::text",String.class));
            result.put("databaseAcl",sql.queryForList("select to_jsonb(t)::text from(select datname,pg_get_userbyid(datdba) owner,datacl::text acl from pg_database where datname=current_database()) t",String.class));
            result.put("roles",sql.queryForList("select to_jsonb(t)::text from(select rolname,rolcanlogin,rolinherit,rolsuper,rolcreaterole,rolcreatedb,rolreplication,rolbypassrls from pg_roles where rolname like 'praxis_bulk_capacity_%' or rolname in('"+owner+"','"+PROVISIONER+"','"+ALLOCATOR+"','"+READER+"','"+NEGATIVE+"')) t order by to_jsonb(t)::text",String.class));
            result.put("memberships",sql.queryForList("select to_jsonb(t)::text from(select role.rolname role,member.rolname member,m.admin_option,m.inherit_option,m.set_option,grantor.rolname grantor,grantor.rolsuper grantor_super from pg_auth_members m join pg_roles role on role.oid=m.roleid join pg_roles member on member.oid=m.member join pg_roles grantor on grantor.oid=m.grantor where role.rolname like 'praxis_bulk_capacity_%') t order by to_jsonb(t)::text",String.class));
            return result;});
    }
    private BulkCapacityAuthorityMigrator.Identity identity(JsonNode input,String name){var value=input.path("identities").path(name);return new BulkCapacityAuthorityMigrator.Identity(value.path("deployment").asText(),input.path("environment").asText(),UUID.fromString(value.path("authorityId").asText()),value.path("authorityEpoch").asLong());}
    private HikariDataSource pool(PGSimpleDataSource ds,int max){var p=new HikariDataSource();p.setDataSource(ds);p.setMaximumPoolSize(max);p.setMinimumIdle(1);pools.add(p);return p;}
    private PGSimpleDataSource source(String db,String login,String password){var s=new PGSimpleDataSource();s.setServerNames(new String[]{uri.getHost()});s.setDatabaseName(db);s.setUser(login);s.setPassword(password);s.setSslMode("verify-full");s.setProperty(PGProperty.SSL_FACTORY,"org.postgresql.ssl.DefaultJavaSSLFactory");s.setProperty(PGProperty.CHANNEL_BINDING,"require");s.setConnectTimeout(10);s.setSocketTimeout(30);return s;}
    private String password(String role){String value=credentials.path("rolePasswords").path(role).asText();assertThat(value).isNotEmpty();return value;}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Required proof binding absent");return value;}
    private static String decode(String value){return URLDecoder.decode(value,StandardCharsets.UTF_8);}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Path privateFile(String env)throws Exception{Path p=Path.of(required(env));assertThat(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).isTrue();assertThat(Files.getPosixFilePermissions(p)).isEqualTo(PosixFilePermissions.fromString("rw-------"));return p;}
    private void validateContinuation()throws Exception{
        Path binding=privateFile("PRAXIS_BULK_CAPACITY_MANAGED_CONTINUATION_FILE");
        Path receipt=privateFile("PRAXIS_BULK_CAPACITY_MANAGED_CONTINUATION_RECEIPT_FILE");
        assertThat(hash(Files.readAllBytes(receipt))).isEqualTo(required("PRAXIS_BULK_CAPACITY_MANAGED_CONTINUATION_RECEIPT_SHA256"));
        var b=JSON.readTree(binding.toFile());var r=JSON.readTree(receipt.toFile());
        assertThat(b.path("status").asText()).isEqualTo("APPROVED_BY_ROOT");assertThat(b.path("startMode").asText()).isEqualTo("CONTINUE_MIGRATED");
        assertThat(b.path("branch").asText()).isEqualTo("br-little-glade-adwtqnv6").isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_BRANCH"));
        assertThat(b.path("endpoint").asText()).isEqualTo(uri.getHost());
        assertThat(Instant.now()).isBefore(Instant.parse(b.path("expiresAt").asText()));
        assertThat(b.path("priorSnapshotSha256").asText()).isEqualTo("6497bdd62e93cd39d89f9c6535c268ae1b7fc10b29099981aa6267ecc37f7ab1");
        assertThat(b.path("priorReceiptSha256").asText()).isEqualTo("223034e520cc937011bf43803120d8e9cfd71a081d51ffbfa9926749385015b6");
        assertThat(b.path("priorJarSha256").asText()).isEqualTo("5c37f1acedad505167b20427d99f790697f3af2bd5a7931f7b54116fff8a1ac3");
        assertThat(b.path("candidateJarSha256").asText()).isEqualTo(jarSha);
        for(String key:List.of("priorSnapshot","priorReceipt","priorPass","priorRunLog","priorProcess","priorJar","runtimeFreeze","fixtureSource","fixtureClass","controlPlane","identities"))
            assertThat(hash(Files.readAllBytes(Path.of(b.path(key+"Path").asText())))).isEqualTo(b.path(key+"Sha256").asText());
        assertThat(hash(Files.readAllBytes(privateFile("PRAXIS_BULK_CAPACITY_MANAGED_NEON_IDENTITIES_FILE"))))
                .isEqualTo(b.path("identitiesSha256").asText());
        try(var bytes=getClass().getResourceAsStream("BulkCapacityAuthorityManagedNeonTest.class")){
            assertThat(hash(bytes.readAllBytes())).isEqualTo(b.path("fixtureClassSha256").asText());
        }
        var prior=JSON.readTree(Path.of(b.path("priorPassPath").asText()).toFile());
        assertThat(prior.path("status").asText()).isEqualTo("PASS");assertThat(prior.path("phase").asText()).isEqualTo("NEW_MANAGED_V1_TO_V2_REPLAY");
        assertThat(prior.path("candidateJarSha256").asText()).isEqualTo(b.path("priorJarSha256").asText());priorPassSha=b.path("priorPassSha256").asText();
        assertThat(r.path("status").asText()).isEqualTo("CONTINUATION_PREFLIGHT_PASS");
        assertThat(r.path("bindingSha256").asText()).isEqualTo(hash(Files.readAllBytes(binding)));
        assertThat(r.path("allConnectionsClosed").asBoolean()).isTrue();assertThat(r.path("priorProcessIdentityInactive").asBoolean()).isTrue();
        assertThat(r.path("snapshotSha256").asText()).isEqualTo(b.path("priorSnapshotSha256").asText());
        assertThat(hash(Files.readAllBytes(Path.of(r.path("snapshotPath").asText())))).isEqualTo(r.path("snapshotSha256").asText());
        assertThat(java.time.Duration.between(Instant.parse(r.path("observedAt").asText()),Instant.now()).toMillis()).isBetween(0L,299999L);
        var control=JSON.readTree(Path.of(b.path("controlPlanePath").asText()).toFile());
        var observed=java.time.LocalDateTime.parse(control.path("observedAt").asText(),java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
                .toInstant(java.time.ZoneOffset.UTC);
        assertThat(java.time.Duration.between(observed,Instant.now()).toMillis()).isBetween(0L,899999L);
    }

    private void attestCandidate()throws Exception{
        Path jar=Path.of(required("PRAXIS_BULK_CAPACITY_MANAGED_CANDIDATE_JAR"));jarSha=required("PRAXIS_BULK_CAPACITY_MANAGED_CANDIDATE_JAR_SHA256");assertThat(hash(Files.readAllBytes(jar))).isEqualTo(jarSha);
        try(var zip=new ZipFile(jar.toFile())){
            for(String name:List.of("org/praxisplatform/uischema/bulk/BulkCapacityAuthorityMigrator.class","org/praxisplatform/uischema/bulk/BulkCapacityAuthorityMigrationLineage.class","org/praxisplatform/uischema/bulk/BulkCapacityAuthorityCatalog.class",
                    "org/praxisplatform/uischema/bulk/BulkCapacityAuthorityInfrastructure.class",
                    "org/praxisplatform/uischema/bulk/BulkCapacityAuthorityInfrastructure$Access.class",
                    "org/praxisplatform/uischema/bulk/BulkCapacityAuthorityCatalog$ReadBatch.class",
                    "org/praxisplatform/uischema/bulk/BulkCapacityAuthorityCatalog$ReadBatch$Read.class",
                    "org/praxisplatform/uischema/bulk/BulkCapacityAuthorityCatalog$ReadBatch$Check.class",
                    "org/praxisplatform/uischema/bulk/BulkCapacityAuthorityCatalog$V2Constraint.class",
                    "org/praxisplatform/uischema/bulk/BulkCapacityAuthorityCatalog$V2Index.class",
                    "db/praxis-bulk-capacity-migrations/V1__capacity_authority.sql","db/praxis-bulk-capacity-migrations/V2__capacity_binding_attestation.sql",
                    "db/praxis-bulk-capacity-migration-lineage/catalog.properties",
                    "db/praxis-bulk-capacity-migration-lineage/original/V1__capacity_authority.sql",
                    "db/praxis-bulk-capacity-migration-lineage/original/V2__capacity_binding_attestation.sql"))
                try(var loaded=getClass().getClassLoader().getResourceAsStream(name);var packaged=zip.getInputStream(zip.getEntry(name))){assertThat(loaded.readAllBytes()).as(name).isEqualTo(packaged.readAllBytes());}
        }
    }
    private void writeSnapshot(String name,Object value)throws Exception{Path file=output.resolve(name+".private.json");Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(file,JSON.writeValueAsString(value));}
    private void phase(String name)throws Exception{
        var value=JSON.createObjectNode().put("status","PASS").put("phase",name).put("at",Instant.now().toString())
                .put("candidateJarSha256",jarSha).put("ownerCreatedOnChild",false).put("startMode",continued?"CONTINUE_MIGRATED":"FRESH");
        if(continued)value.put("priorV1SqlCase","REUSED").put("priorV1PassSha256",priorPassSha).put("freshSqlMigration2","REUSED");
        writeSnapshot(name,value);System.out.println("NEON_AUTHORITY_PHASE="+name+",PASS");
    }
    private void failure(Throwable primary){if(output==null)return;try{var value=JSON.createObjectNode().put("status","FAILED").put("phase",currentPhase).put("exceptionClass",primary.getClass().getName());writeSnapshot("failure",value);}catch(Exception e){primary.addSuppressed(e);}}
    @AfterAll void cleanup()throws Exception{Exception failure=null;for(int i=pools.size()-1;i>=0;i--)try{pools.get(i).close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}if(failure!=null)throw failure;}
}
