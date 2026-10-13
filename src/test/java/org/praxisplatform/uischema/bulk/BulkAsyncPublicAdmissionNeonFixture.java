package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.consumer.BulkAsyncPublicAdmissionConsumer;
import org.praxisplatform.uischema.openapi.*;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Child-only trusted provisioning; the external consumer does not receive this owner helper. */
final class BulkAsyncPublicAdmissionNeonFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String NS="async-public-neon", DEP="async-public-neon-deployment", TENANT="async-public-neon-tenant", ENV="proof";
    static final String SUBJECT="async-public-neon-operator";
    final Path output;
    final HikariDataSource owner, authorityOwner;
    HikariDataSource runtimeSource, controlSource;
    final BulkExecutionInfrastructure runtime;
    final BulkControlPlaneInfrastructure control;
    final BulkExecutionRoleConfiguration roles;
    final BulkCapacityBinding capacity;
    final JdbcBulkCapacityInstallation installation;
    final JdbcBulkCapacityIssuer issuer;
    final JdbcTemplate observer;
    final Properties workerInput=new Properties();
    org.springframework.web.context.support.AnnotationConfigWebApplicationContext context;
    OpenApiInternalRestTemplate client;
    final BulkOperationLifecycle lifecycle;
    final BulkAsyncPublicAdmissionConsumer consumer;
    String currentPhase="PREFLIGHT";
    final List<String> phases=new ArrayList<>();
    final long setupStarted=System.nanoTime();
    long phaseStarted=setupStarted;
    static final String PUBLICATION_JAR_SHA256="9ab1dc36d7207b15a8166c7e25e987edb43ccd8164b628e3b655e70a21132738";
    static final String PUBLICATION_RUNTIME_FREEZE_SHA256="5fa7b93bb5d897be9f52a8b71420207e26bdab94ffcb4576d85ad7043034c415";
    boolean continuedDomain, continuedAuthoritySchema, continuedPublication, continuedPublishedQueue, continuedAfterDrain, domainValidated;

    BulkAsyncPublicAdmissionNeonFixture() throws Exception {
        output=Path.of(required("PRAXIS_BULK_ASYNC_NEON_PROOF_DIR"));
        try {
        Files.createDirectories(output);Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rwx------"));
        Path candidate=Path.of(required("PRAXIS_BULK_ASYNC_CANDIDATE_JAR")).toRealPath();
        String candidateSha=required("PRAXIS_BULK_ASYNC_CANDIDATE_JAR_SHA256");
        assertThat(candidateSha).matches("[a-f0-9]{64}");
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(candidate))))
                .isEqualTo(candidateSha);
        Path workerClasspath=Path.of(required("PRAXIS_BULK_ASYNC_WORKER_CLASSPATH_FILE"));
        assertThat(Files.isRegularFile(workerClasspath)).isTrue();
        for(String entry:Files.readString(workerClasspath).strip().split(java.io.File.pathSeparator))
            assertThat(entry.replace('\\','/')).doesNotEndWith("/target/classes");
        URI uri=URI.create(Files.readString(Path.of(required("PRAXIS_BULK_NEON_CONNECTION_FILE"))).strip());
        assertThat(uri.getScheme()).isIn("postgresql","postgres");
        assertThat(uri.getHost()).isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_HOST")).doesNotContain("-pooler");
        String[] user=uri.getRawUserInfo().split(":",2);
        String ownerRole=decode(user[0]);assertThat(ownerRole).isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_OWNER"));
        var inputs=JSON.readTree(Path.of(required("PRAXIS_BULK_ASYNC_NEON_FIXTURE_CREDENTIALS_FILE")).toFile());
        assertThat(inputs.path("branch").asText()).isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_BRANCH"));
        assertThat(inputs.path("endpoint").asText()).isEqualTo(uri.getHost());
        assertThat(inputs.path("databases").toString()).isEqualTo("[\"async_admission_domain_20261012\",\"async_admission_authority_20261012\"]");
        String rr="async_admission_runtime_20261012", cr="async_admission_control_20261012";
        String pp="async_admission_provisioner_20261012", aa="async_admission_allocator_20261012", rd="async_admission_reader_20261012";
        String startMode=startMode(System.getenv("PRAXIS_BULK_ASYNC_NEON_START"),System.getenv("PRAXIS_BULK_ASYNC_CONTINUATION_FILE")!=null);
        continuedAuthoritySchema=startMode.equals("CONTINUE_DOMAIN_AUTHORITY_SCHEMA");
        continuedPublishedQueue=startMode.equals("CONTINUE_PUBLISHED_AFTER_EXPIRED_QUEUE");
        continuedAfterDrain=startMode.equals("CONTINUE_PUBLISHED_AFTER_DRAIN");
        continuedPublication=startMode.equals("CONTINUE_FROM_PUBLICATION")||continuedPublishedQueue||continuedAfterDrain;
        continuedDomain=!startMode.equals("FRESH");
        if(continuedDomain) validateContinuation(
                Path.of(required("PRAXIS_BULK_ASYNC_CONTINUATION_FILE")),
                Path.of(required("PRAXIS_BULK_ASYNC_CONTINUATION_RECEIPT_FILE")),
                required("PRAXIS_BULK_ASYNC_CONTINUATION_RECEIPT_SHA256"),candidateSha,hash(workerClasspath),continuedAuthoritySchema,continuedPublication,continuedPublishedQueue,continuedAfterDrain);
        else {
            assertThat(System.getenv("PRAXIS_BULK_ASYNC_CONTINUATION_RECEIPT_FILE")).isNull();
            assertThat(System.getenv("PRAXIS_BULK_ASYNC_CONTINUATION_RECEIPT_SHA256")).isNull();
        }
        JsonNode continuation=continuedDomain?JSON.readTree(Path.of(required("PRAXIS_BULK_ASYNC_CONTINUATION_FILE")).toFile()):null;
        owner=ownerPool(source(uri,"async_admission_domain_20261012",ownerRole,decode(user[1])));
        authorityOwner=ownerPool(source(uri,"async_admission_authority_20261012",ownerRole,decode(user[1])));
        observer=new JdbcTemplate(owner);
        assertThat(observer.queryForObject("select current_user=session_user and current_user=?",Boolean.class,ownerRole)).isTrue();
        assertThat(observer.queryForObject("select not rolsuper and rolcreaterole and rolcreatedb from pg_roles where rolname=current_user",Boolean.class)).isTrue();
        assertThat(observer.queryForObject("select current_setting('server_version_num')::integer",Integer.class)).isGreaterThanOrEqualTo(170000);
        if(!continuedDomain) assertThat(observer.queryForObject("select to_regnamespace('praxis_bulk') is null",Boolean.class)).isTrue();
        if(!continuedAuthoritySchema&&!continuedPublication) assertThat(new JdbcTemplate(authorityOwner).queryForObject("select to_regnamespace('praxis_bulk_capacity') is null",Boolean.class)).isTrue();
        for(String role:List.of(rr,cr,pp,aa,rd))
            assertThat(observer.queryForObject("select rolcanlogin and rolinherit and not rolsuper and not rolcreaterole and not rolcreatedb and not rolreplication and not rolbypassrls from pg_roles where rolname=?",Boolean.class,role)).isTrue();
        phase("PREFLIGHT");
        currentPhase="MIGRATION";
        roles=new BulkExecutionRoleConfiguration(ownerRole,Set.of(rr),Set.of(),Set.of(cr));
        if(continuedDomain){
            currentPhase="DOMAIN_VALIDATION";
            BulkExecutionMigrator.validate(owner,roles);
            domainValidated=true;
            phase("DOMAIN_VALIDATION");
        }else{
            assertThat(BulkExecutionMigrator.migrate(owner,Map.of(NS,DEP),roles,
                    List.of(new BulkOperationControlIdentity(NS,BulkAsyncPublicAdmissionConsumer.OPERATION)))).isEqualTo(20);
            BulkExecutionMigrator.validate(owner,roles);
            assertThat(BulkExecutionMigrator.migrate(owner,Map.of(NS,DEP),roles)).isZero();
        }
        if(continuedDomain) currentPhase=continuedPublication?"AUTHORITY_VALIDATION":"AUTHORITY_MIGRATION";
        var identity=(continuedAuthoritySchema||continuedPublication)
                ? preservedAuthorityIdentity(JSON.readTree(Path.of(JSON.readTree(Path.of(required("PRAXIS_BULK_ASYNC_CONTINUATION_FILE")).toFile())
                    .path("priorIdentityPath").asText()).toFile()))
                : new BulkCapacityAuthorityMigrator.Identity(DEP,ENV,UUID.randomUUID(),1);
        Path identityPending=output.resolve("authority-identity.pending.private.json");
        Files.createFile(identityPending,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        var identityReceipt=JSON.createObjectNode().put("status","PENDING_UNCERTAIN").put("authorityId",identity.authorityId().toString())
                .put("authorityEpoch",identity.expectedAuthorityEpoch()).put("deployment",DEP).put("environment",ENV)
                .put("branch",required("PRAXIS_BULK_NEON_EXPECTED_BRANCH")).put("candidateJarSha256",candidateSha)
                .put("at",java.time.Instant.now().toString());
        if(continuedAuthoritySchema||continuedPublication) identityReceipt.put("priorPendingIdentitySha256","59fb3c8253939116ac91388238963bec89b698f8a409b01cdd4aabaa86324781")
                .put("identityAdoption","REUSED_RUN3_PENDING");
        Files.writeString(identityPending,identityReceipt.toString());
        var authorityRoles=new BulkCapacityAuthorityMigrator.RoleConfiguration(ownerRole,pp,aa,rd);
        if(!continuedPublication) assertThat(BulkCapacityAuthorityMigrator.migrate(authorityOwner,identity,authorityRoles)).isEqualTo(2);
        BulkCapacityAuthorityMigrator.validate(authorityOwner,identity,authorityRoles);
        phase(continuedPublication?"AUTHORITY_VALIDATION":continuedDomain?"AUTHORITY_MIGRATION":"MIGRATION");
        currentPhase="CAPACITY";
        runtimeSource=pool(source(uri,"async_admission_domain_20261012",rr,password(inputs,rr)));
        controlSource=pool(source(uri,"async_admission_domain_20261012",cr,password(inputs,cr)));
        runtime=new BulkExecutionInfrastructure(runtimeSource,new DataSourceTransactionManager(runtimeSource),NS,DEP,roles);
        control=new BulkControlPlaneInfrastructure(controlSource,new DataSourceTransactionManager(controlSource),NS,DEP,cr,runtime);
        var provisioner=authority(source(uri,"async_admission_authority_20261012",pp,password(inputs,pp)),identity,
                authorityRoles,BulkCapacityAuthorityInfrastructure.Access.PROVISIONER);
        var allocator=authority(source(uri,"async_admission_authority_20261012",aa,password(inputs,aa)),identity,
                authorityRoles,BulkCapacityAuthorityInfrastructure.Access.ALLOCATOR);
        var reading=authority(source(uri,"async_admission_authority_20261012",rd,password(inputs,rd)),identity,
                authorityRoles,BulkCapacityAuthorityInfrastructure.Access.READER);
        var reader=new JdbcBulkCapacityIssuer.CapacityReader(reading);issuer=new JdbcBulkCapacityIssuer(allocator,reading);
        // Only the same assigned child and original installation: no restored/clone identity adoption.
        capacity=continuedPublication?preservedCapacityBinding(continuation.path("capacityIdentity"),identity)
                :new BulkCapacityBinding(DEP,TENANT,ENV,"async-neon-binding",1,UUID.randomUUID(),UUID.randomUUID(),identity.authorityId(),1);
        installation=new JdbcBulkCapacityInstallation(capacity,owner,new DataSourceTransactionManager(owner),ownerRole,
                runtime,provisioner,reader,Duration.ofSeconds(20),Duration.ofSeconds(3));
        if(!continuedPublication){
            installation.bootstrap();provisioner.enrollBinding(TENANT,capacity.bindingId(),capacity.generation());
            installation.registerAttestation();installation.activate();
            install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);install(JdbcBulkCapacityIssuer.CapacityClass.ACTIVE);
        }
        phase("CAPACITY");
        currentPhase="PUBLICATION";
        if(!continuedPublication){
            observer.execute("create table public.async_admission_domain(id integer primary key,writes integer not null,last_pid integer,last_xid bigint)");
            observer.execute("create table public.async_admission_grant(subject text primary key,allowed boolean not null,revision integer not null,lock_anchor boolean not null default true)");
            observer.update("insert into public.async_admission_grant(subject,allowed,revision) values(?,true,1)",SUBJECT);
            for(int id=1;id<=12;id++)observer.update("insert into public.async_admission_domain values(?,0,null,null)",id);
            observer.execute("grant select,update on public.async_admission_domain to "+rr);
            observer.execute("grant select,update(lock_anchor) on public.async_admission_grant to "+rr);
        }
        context=BulkCrudStructuralCompilerTest.context(BulkCrudStructuralCompilerTest.CrudController.class);
        var fresh=new AtomicInteger();var value=BulkCrudOperationalCompositionTest.document();
        value.put("openapi","3.0.3");value.putObject("info").put("title","ASYNC public SDK fixture").put("version","1");
        var filterHolder = new org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter[1];
        var registration = mock(jakarta.servlet.FilterRegistration.class);
        org.mockito.Mockito.when(registration.getClassName()).thenReturn(org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter.class.getName());
        org.mockito.Mockito.when(registration.getUrlPatternMappings()).thenReturn(List.of("/*"));
        org.mockito.Mockito.when(registration.getServletNameMappings()).thenReturn(List.of());
        var servletContext = new org.springframework.mock.web.MockServletContext() {
            @Override public jakarta.servlet.FilterRegistration getFilterRegistration(String name) { return registration; }
        };
        var source = new OpenApiDocsSupport() {
            @Override public String localPublicationContextPath() { return ""; }
            @Override public org.praxisplatform.uischema.openapi.OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(
                    RestTemplate client, String path, org.slf4j.Logger logger, ObjectMapper mapper) {
                // Servlet admission fixture; actual HTTP/security is proved separately by the Boot test.
                var request = new org.springframework.mock.web.MockHttpServletRequest(servletContext, "GET", path);
                request.setServletPath(path);
                request.addHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER,
                        ((OpenApiInternalRestTemplate) client).producerCaptureToken(path));
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                try {
                    filterHolder[0].doFilter(request, response, (input, output) -> {
                        if (path.endsWith("/crud")) fresh.incrementAndGet();
                        byte[] bytes = path.endsWith("/swagger-config")
                                ? "{\"urls\":[{\"name\":\"crud\",\"url\":\"/v3/api-docs/crud\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                : mapper.writeValueAsBytes(value);
                        output.getOutputStream().write(bytes);
                    });
                    if (response.getStatus() != 200) throw new IllegalStateException("Producer capture rejected");
                    return org.praxisplatform.uischema.openapi.OpenApiDocumentCapture.parse(response.getContentAsByteArray(), mapper);
                } catch (Exception failure) { throw new IllegalStateException("Servlet producer fixture failed", failure); }
            }
            @Override public String resolveGroupFromPath(String path) { return "crud"; }
            @Override public JsonNode fetchOpenApiDocument(RestTemplate client, String base, String group, org.slf4j.Logger logger) { return value.deepCopy(); }
            @Override public JsonNode fetchOpenApiGroupDocument(RestTemplate client, String base, String group, org.slf4j.Logger logger) { return value.deepCopy(); }
            @Override public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate client, String base, String group, org.slf4j.Logger logger) {
                fresh.incrementAndGet(); return value.deepCopy();
            }
        };
        var localClient = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10));
        var documents = new CachedOpenApiDocumentService(localClient, new ObjectMapper(), source, true);
        ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");
        filterHolder[0] = new org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter(documents);
        filterHolder[0].init(new org.springframework.mock.web.MockFilterConfig(servletContext, "bulkPublication"));

        client=localClient;
        var mvc=context.getBean(RequestMappingHandlerMapping.class);
        var bindings=BulkResourceOperationBindings.from(mvc);
        var resolver=new OpenApiCanonicalOperationResolver(documents,mvc,bindings,List.of("crud"));
        var async=new BulkCrudOperationalCompositionTest.Provider(BulkAsyncPublicAdmissionConsumer.OPERATION,"async-proof-r1",
                BulkCrudOperationalCompositionTest.UNIFORM_POINTER,BulkMode.UNIFORM_UPDATE,runtime) {
            @Override public BulkOperationalProfile profile(){return new BulkOperationalProfile(Set.of(BulkMode.UNIFORM_UPDATE),
                    Set.of(BulkExecutionMode.ASYNC),Set.of(BulkSelectionMode.EXPLICIT),12,65536,Duration.ofMinutes(2),Duration.ofSeconds(5));}
            @Override public Optional<BulkCapacityBinding> asynchronousCapacityBinding(){return Optional.of(capacity);}
        };
        var sync=new BulkCrudOperationalCompositionTest.Provider("crud.items","r1",BulkCrudOperationalCompositionTest.ITEMS_POINTER,BulkMode.PER_ITEM_UPDATE,runtime);
        lifecycle=new BulkOperationLifecycle(bindings,resolver,documents,mock(ActionDefinitionRegistry.class),
                BulkCrudStructuralCompilerTest.mapper(),new FilteredSchemaReferenceResolver(),runtime,control,List.of(async,sync));
        if(continuedPublishedQueue||continuedAfterDrain){
            currentPhase="RECONCILE_PUBLISHED";
            var before=snapshot();
            if(continuedAfterDrain){
                assertHistoricalSnapshot(JSON.readTree(Path.of(continuation.path("priorSnapshotPath").asText()).toFile()),before);
                assertAlreadyDrained(before,continuation.path("expiredQueue"));
            }
            lifecycle.reconcilePublished(new BulkOperationControlIdentity(NS,BulkAsyncPublicAdmissionConsumer.OPERATION),1);
            assertThat(snapshot()).isEqualTo(before);
        }else lifecycle.publish(new BulkOperationControlIdentity(NS,BulkAsyncPublicAdmissionConsumer.OPERATION),0);
        var projection=lifecycle.projectReadyCapabilities(BulkAsyncPublicAdmissionConsumer.RESOURCE,Map::copyOf);
        assertThat(projection.values()).anySatisfy(contract->assertThat(contract.executionModes()).containsExactly(BulkExecutionMode.ASYNC));
        phase("PUBLICATION");
        consumer=new BulkAsyncPublicAdmissionConsumer(runtime,lifecycle,capacity,SUBJECT);
        if(continuedPublishedQueue) drainExpiredQueue(continuation.path("expiredQueue"));
        workerInput.setProperty("host",uri.getHost());workerInput.setProperty("database","async_admission_domain_20261012");
        workerInput.setProperty("runtimeRole",rr);workerInput.setProperty("runtimePassword",password(inputs,rr));
        workerInput.setProperty("ownerRole",ownerRole);workerInput.setProperty("controlRole",cr);
        workerInput.setProperty("namespace",NS);workerInput.setProperty("deployment",DEP);workerInput.setProperty("tenant",TENANT);
        workerInput.setProperty("environment",ENV);workerInput.setProperty("binding",capacity.bindingId());
        workerInput.setProperty("generation",Long.toString(capacity.generation()));workerInput.setProperty("databaseId",capacity.databaseId().toString());
        workerInput.setProperty("attestationId",capacity.attestationId().toString());workerInput.setProperty("authorityId",capacity.authorityId().toString());
        workerInput.setProperty("authorityEpoch",Long.toString(capacity.authorityEpoch()));
        workerInput.setProperty("candidateJar",candidate.toString());workerInput.setProperty("candidateJarSha256",candidateSha);
        } catch(Exception | Error primary) {
            try {var failure=JSON.createObjectNode().put("status","FAILED").put("phase",currentPhase)
                    .put("exceptionClass",primary.getClass().getName());
                Files.writeString(output.resolve("failure.private.json"),failure.toString());
                Files.setPosixFilePermissions(output.resolve("failure.private.json"),PosixFilePermissions.fromString("rw-------"));
            } catch(Exception diagnostic) {primary.addSuppressed(diagnostic);}
            try {close();} catch(Exception cleanup) {primary.addSuppressed(cleanup);}
            throw primary;
        }
    }

    static String startMode(String value,boolean continuationPresent){
        String mode=value==null?"FRESH":value;
        assertThat(mode).isIn("FRESH","CONTINUE_DOMAIN_MIGRATED","CONTINUE_DOMAIN_AUTHORITY_SCHEMA","CONTINUE_FROM_PUBLICATION","CONTINUE_PUBLISHED_AFTER_EXPIRED_QUEUE","CONTINUE_PUBLISHED_AFTER_DRAIN");
        assertThat(continuationPresent).isEqualTo(!mode.equals("FRESH"));
        return mode;
    }
    static BulkCapacityAuthorityMigrator.Identity preservedAuthorityIdentity(JsonNode value) {
        assertThat(value.path("status").asText()).isEqualTo("PENDING_UNCERTAIN");
        assertThat(value.path("branch").asText()).isEqualTo("br-polished-mud-adr67xib");
        assertThat(value.path("deployment").asText()).isEqualTo(DEP);
        assertThat(value.path("environment").asText()).isEqualTo(ENV);
        assertThat(value.path("authorityEpoch").asLong()).isEqualTo(1);
        return new BulkCapacityAuthorityMigrator.Identity(DEP,ENV,UUID.fromString(value.path("authorityId").asText()),1);
    }
    static BulkCapacityBinding preservedCapacityBinding(JsonNode value,BulkCapacityAuthorityMigrator.Identity authority) {
        assertThat(value.path("deploymentId").asText()).isEqualTo(DEP);
        assertThat(value.path("tenantId").asText()).isEqualTo(TENANT);
        assertThat(value.path("environment").asText()).isEqualTo(ENV);
        assertThat(value.path("bindingId").asText()).isEqualTo("async-neon-binding");
        assertThat(value.path("generation").asLong()).isEqualTo(1);
        assertThat(value.path("authorityId").asText()).isEqualTo(authority.authorityId().toString());
        assertThat(value.path("authorityEpoch").asLong()).isEqualTo(authority.expectedAuthorityEpoch());
        return new BulkCapacityBinding(DEP,TENANT,ENV,value.path("bindingId").asText(),1,
                UUID.fromString(value.path("databaseId").asText()),UUID.fromString(value.path("attestationId").asText()),
                authority.authorityId(),authority.expectedAuthorityEpoch());
    }
    static String hash(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    static void validateContinuation(Path bindingPath,Path receiptPath,String receiptSha,String jarSha,String cpSha,boolean schemaPrefix,boolean fromPublication,boolean publishedQueue,boolean afterDrain) throws Exception {
        assertThat(hash(receiptPath)).isEqualTo(receiptSha);
        var b=JSON.readTree(bindingPath.toFile());var r=JSON.readTree(receiptPath.toFile());
        assertThat(b.path("status").asText()).isEqualTo("APPROVED_BY_ROOT");
        assertThat(b.path("startMode").asText()).isEqualTo(afterDrain?"CONTINUE_PUBLISHED_AFTER_DRAIN":publishedQueue?"CONTINUE_PUBLISHED_AFTER_EXPIRED_QUEUE":fromPublication?"CONTINUE_FROM_PUBLICATION":schemaPrefix?"CONTINUE_DOMAIN_AUTHORITY_SCHEMA":"CONTINUE_DOMAIN_MIGRATED");
        assertThat(b.path("branch").asText()).isEqualTo("br-polished-mud-adr67xib").isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_BRANCH"));
        assertThat(b.path("endpoint").asText()).isEqualTo("ep-winter-mouse-ad836gvm.c-2.us-east-1.aws.neon.tech").isEqualTo(required("PRAXIS_BULK_NEON_EXPECTED_HOST"));
        assertThat(java.time.Instant.now()).isBefore(java.time.Instant.parse(b.path("expiresAt").asText()));
        assertThat(b.path("priorSnapshotSha256").asText()).isEqualTo(afterDrain?"86ece7029f9ac4ae896cb62f673075413018fc687fcf1d84d95e3f4eb68d4e95":publishedQueue?"0ddc8a1077e70c515b89a0d493f0fe1cac8c7d134a3e5440e8c2a95968115dc7":fromPublication?"dc44c098184becec126749dd69aad154dd722480df197da3a0be64291074963d":schemaPrefix?"9455c02c551bd2190817d2c7765e0e1d4d0c6f194fd00e0cb668d4b292ce2c34":"854254e9a6da0b86b10adb365f258473afe00e1c7c68795e28f7412fc1500886");
        assertThat(b.path("priorReceiptSha256").asText()).isEqualTo(afterDrain?"47471f459c98801e250662188b27fd0a8b61424b57462d32212a0ddf1c0cf2b7":publishedQueue?"0c3c2a8f288663a21d5ef041b9f20c1225bd31953c35658acf5f91594b1c1e63":fromPublication?"360e755d71b3aa780724d4b9c89881b55a825057bb4fd8555706270413253e87":schemaPrefix?"a80972f22eae966fcd27296dad90cb0bee8874e70b4b4dc232e90f9452299df3":"2e77ca1851ff6813e67dc9f37cd7bde779f35d8fa968732dee013d2c7ca623ff");
        assertThat(b.path("candidateJarSha256").asText()).isEqualTo(fromPublication?PUBLICATION_JAR_SHA256:schemaPrefix?"5d36d666ab30685a19df3db3e39041b84372e0da5973ef1a58e941bfee95dd87":"63360ee9b0c3a496ab29487e268df0ee74170d51912aca5fb9a38df72ff65e99").isEqualTo(jarSha);
        assertThat(b.path("runtimeFreezeSha256").asText()).isEqualTo(fromPublication?PUBLICATION_RUNTIME_FREEZE_SHA256:schemaPrefix?"132da5e81ff71d9bd233d0ad9e67782e02f2672f4141932833f4347a06b03c2b":"740b6f5f36dfb5d3e4e4e201c88a04e4960ef1b84d642b33a120db342c86e0ae");
        assertThat(b.path("workerClasspathSha256").asText()).isEqualTo(cpSha);
        for(String key:List.of("priorSnapshot","priorReceipt","priorRunLog","runtimeFreeze","fixtureSource","controlPlane"))
            assertThat(hash(Path.of(b.path(key+"Path").asText()))).isEqualTo(b.path(key+"Sha256").asText());
        byte[] compiled;
        try(var stream=BulkAsyncPublicAdmissionNeonFixture.class.getResourceAsStream("BulkAsyncPublicAdmissionNeonFixture.class")){compiled=stream.readAllBytes();}
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(compiled)))
                .isEqualTo(b.path("fixtureClassSha256").asText());
        assertThat(r.path("status").asText()).isEqualTo("CONTINUATION_PREFLIGHT_PASS");
        assertThat(r.path("bindingSha256").asText()).isEqualTo(hash(bindingPath));
        assertThat(r.path("allConnectionsClosed").asBoolean()).isTrue();
        if(publishedQueue){
            assertThat(b.path("newRunPrefix").asText()).isEqualTo("run-6");
            assertThat(Path.of(required("PRAXIS_BULK_ASYNC_NEON_PROOF_DIR")).getFileName().toString()).isEqualTo("run-6");
            expiredQueueIdentity(b.path("expiredQueue"));
        }
        if(afterDrain){
            assertThat(b.path("newRunPrefix").asText()).isEqualTo("run-7");
            assertThat(Path.of(required("PRAXIS_BULK_ASYNC_NEON_PROOF_DIR")).getFileName().toString()).isEqualTo("run-7");
            queueCustodyIdentity(b.path("expiredQueue"),"STOPPED");
            assertThat(b.path("expiredQueue").path("terminalReason").asText()).isEqualTo("DEADLINE_EXCEEDED");
        }
        if(fromPublication){
            assertThat(PUBLICATION_JAR_SHA256).matches("[a-f0-9]{64}");
            assertThat(PUBLICATION_RUNTIME_FREEZE_SHA256).matches("[a-f0-9]{64}");
        }
        if(schemaPrefix||fromPublication){
            assertThat(r.path("priorProcessIdentityInactive").asBoolean()).isTrue();
            assertThat(b.path("priorIdentitySha256").asText()).isEqualTo("59fb3c8253939116ac91388238963bec89b698f8a409b01cdd4aabaa86324781");
            for(String key:List.of("priorIdentity","priorProcess","preflightScript","asyncRuntimeFreeze","authorityRuntimeFreeze"))
                assertThat(hash(Path.of(b.path(key+"Path").asText()))).isEqualTo(b.path(key+"Sha256").asText());
            assertThat(r.path("preflightScriptSha256").asText()).isEqualTo(b.path("preflightScriptSha256").asText());
            var original=preservedAuthorityIdentity(JSON.readTree(Path.of(b.path("priorIdentityPath").asText()).toFile()));
            if(fromPublication){
                assertThat(hash(Path.of(b.path("catalogRuntimeFreezePath").asText()))).isEqualTo(b.path("catalogRuntimeFreezeSha256").asText());
                preservedCapacityBinding(b.path("capacityIdentity"),original);
                assertThat(b.path("continuationProvenance").asText()).isEqualTo("CONTINUATION_ONLY");
            }
        }else assertThat(r.path("ownedPidsAbsent").toString()).isEqualTo("[12798,12923]");
        assertThat(r.path("snapshotSha256").asText()).isEqualTo(b.path("priorSnapshotSha256").asText());
        assertThat(hash(Path.of(r.path("snapshotPath").asText()))).isEqualTo(r.path("snapshotSha256").asText());
        long age=java.time.Duration.between(java.time.Instant.parse(r.path("observedAt").asText()),java.time.Instant.now()).toMillis();
        assertThat(age).isBetween(0L,299999L);
        var control=JSON.readTree(Path.of(b.path("controlPlanePath").asText()).toFile());
        var observed=java.time.LocalDateTime.parse(control.path("observedAt").asText(),java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
                .toInstant(java.time.ZoneOffset.UTC);
        assertThat(java.time.Duration.between(observed,java.time.Instant.now()).toMillis()).isBetween(0L,899999L);
    }

    static UUID expiredQueueIdentity(JsonNode value) { return queueCustodyIdentity(value,"QUEUED"); }
    static UUID queueCustodyIdentity(JsonNode value,String state) {
        assertThat(state).isIn("QUEUED","STOPPED");
        assertThat(value.path("status").asText()).isEqualTo(state);
        assertThat(value.path("idempotencyKey").asText()).isEqualTo("public-confirmation");
        assertThat(value.path("idempotencyKeyDigest").asText()).matches("sha256:[a-f0-9]{64}");
        UUID.fromString(value.path("proposalId").asText());
        assertThat(value.path("nextOrdinal").asInt(-1)).isZero();
        assertThat(value.path("deadlineAt").asText()).isNotBlank();
        java.time.Instant.parse(value.path("deadlineAt").asText());
        return UUID.fromString(value.path("executionId").asText());
    }
    private void drainExpiredQueue(JsonNode value) throws Exception {
        currentPhase="DRAIN_EXPIRED_QUEUE";
        UUID execution=expiredQueueIdentity(value);
        var before=snapshot();var authorityBefore=authoritySnapshot();
        assertThat(before.get("execution")).hasSize(1);
        var old=JSON.readTree(before.get("execution").getFirst());
        assertThat(old.path("execution_id").asText()).isEqualTo(execution.toString());
        assertThat(old.path("status").asText()).isEqualTo("QUEUED");
        assertThat(old.path("idempotency_key_digest").asText()).isEqualTo(value.path("idempotencyKeyDigest").asText());
        assertThat(old.path("proposal_id").asText()).isEqualTo(value.path("proposalId").asText());
        assertThat(old.path("next_ordinal").asInt()).isZero();
        var deadline=java.time.OffsetDateTime.parse(old.path("deadline_at").asText()).toInstant();
        assertThat(deadline).isEqualTo(java.time.Instant.parse(value.path("deadlineAt").asText())).isBefore(java.time.Instant.now());
        assertThat(count("item_receipt",execution)).isZero();assertThat(count("admission",execution)).isZero();
        var admits=new AtomicInteger();var mutations=new AtomicInteger();var cleanups=new AtomicInteger();
        var worker=consumer.worker(admits,mutations,cleanups,false);Throwable primary=null;
        try{
            worker.start();long end=System.nanoTime()+Duration.ofSeconds(45).toNanos();
            while(!"STOPPED".equals(observer.queryForObject("select status from praxis_bulk.praxis_bulk_execution where execution_id=?",String.class,execution))){
                assertThat(System.nanoTime()).isLessThan(end);Thread.sleep(25);
            }
        }catch(Exception|Error failure){primary=failure;throw failure;
        }finally{
            try{var stopped=new java.util.concurrent.CountDownLatch(1);worker.stop(stopped::countDown);
                assertThat(stopped.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(worker.isRunning()).isFalse();
            }catch(Exception|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
        assertThat(admits).hasValue(0);assertThat(mutations).hasValue(0);assertThat(cleanups).hasValue(0);
        var after=snapshot();var terminal=JSON.readTree(after.get("execution").getFirst());
        assertThat(terminal.path("execution_id").asText()).isEqualTo(execution.toString());
        assertThat(terminal.path("status").asText()).isEqualTo("STOPPED");
        assertThat(terminal.path("terminal_reason_code").asText()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(java.time.OffsetDateTime.parse(terminal.path("deadline_at").asText()).toInstant()).isEqualTo(deadline);
        assertThat(terminal.path("next_ordinal").asInt()).isZero();
        assertThat(count("item_receipt",execution)).isZero();assertThat(count("admission",execution)).isZero();
        assertDrainDelta(before,after,execution);
        assertThat(after.get("capacity_occupation")).isEqualTo(before.get("capacity_occupation"));
        assertThat(observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_capacity_slot where current_execution_id is not null",Long.class)).isZero();
        assertThat(authoritySnapshot()).isEqualTo(authorityBefore);
        phase("DRAIN_EXPIRED_QUEUE");
    }

    static void assertHistoricalSnapshot(JsonNode baseline,Map<String,List<String>> current) throws Exception {
        var rows=baseline.path("async_admission_domain_20261012").path("rows");
        for(var entry:current.entrySet()){
            String table=entry.getKey().equals("domain")?"public.async_admission_domain":entry.getKey().equals("grant")?"public.async_admission_grant":"praxis_bulk.praxis_bulk_"+entry.getKey();
            assertThat(rows.has(table)).isTrue();
            var expected=new ArrayList<JsonNode>();for(var row:rows.path(table))expected.add(JSON.readTree(row.path("row").asText()));
            var actual=new ArrayList<JsonNode>();for(String row:entry.getValue())actual.add(JSON.readTree(row));
            assertThat(actual).as(table).containsExactlyInAnyOrderElementsOf(expected);
        }
    }
    static void assertAlreadyDrained(Map<String,List<String>> current,JsonNode value) throws Exception {
        UUID execution=queueCustodyIdentity(value,"STOPPED");
        assertThat(value.path("terminalReason").asText()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(current.get("execution")).hasSize(1);var row=JSON.readTree(current.get("execution").getFirst());
        assertThat(row.path("execution_id").asText()).isEqualTo(execution.toString());
        assertThat(row.path("proposal_id").asText()).isEqualTo(value.path("proposalId").asText());
        assertThat(row.path("idempotency_key_digest").asText()).isEqualTo(value.path("idempotencyKeyDigest").asText());
        assertThat(row.path("status").asText()).isEqualTo("STOPPED");
        assertThat(row.path("terminal_reason_code").asText()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(row.path("next_ordinal").asInt()).isZero();
        assertThat(java.time.OffsetDateTime.parse(row.path("deadline_at").asText()).toInstant()).isEqualTo(java.time.Instant.parse(value.path("deadlineAt").asText()));
        assertThat(current.get("item_receipt")).isEmpty();assertThat(current.get("admission")).isEmpty();
        assertThat(current.get("capacity_occupation")).hasSize(1);
        var history=JSON.readTree(current.get("capacity_occupation").getFirst());
        assertThat(history.path("execution_id").asText()).isEqualTo(execution.toString());
        assertThat(current.get("capacity_slot")).hasSize(2);
        for(String slot:current.get("capacity_slot")){
            var parsed=JSON.readTree(slot);assertThat(parsed.path("current_execution_id").isNull()).isTrue();assertThat(parsed.path("current_owner_epoch").isNull()).isTrue();
        }
        int released=0;for(String allocation:current.get("allocation")){
            var parsed=JSON.readTree(allocation);
            if(parsed.path("kind").asText().equals("EXECUTION_ASYNC")){
                released++;assertThat(parsed.path("execution_id").asText()).isEqualTo(execution.toString());
                assertThat(parsed.path("state").asText()).isEqualTo("RELEASED");
                assertThat(parsed.path("release_reason").asText()).isEqualTo("TERMINAL_RECONCILED");
                assertThat(parsed.path("released_at")).isEqualTo(row.path("terminal_at"));
            }
        }
        assertThat(released).isEqualTo(1);
    }

    static void assertDrainDelta(Map<String,List<String>> before,Map<String,List<String>> after,UUID execution) throws Exception {
        assertThat(after.keySet()).isEqualTo(before.keySet());
        for(String key:before.keySet())if(!Set.of("execution","allocation","capacity_slot","capacity_occupation").contains(key))
            assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
        assertThat(before.get("execution")).hasSize(1);assertThat(after.get("execution")).hasSize(1);
        var oldExecution=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(before.get("execution").getFirst());
        var newExecution=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(after.get("execution").getFirst());
        assertThat(newExecution.path("execution_id").asText()).isEqualTo(execution.toString());
        assertThat(newExecution.path("status").asText()).isEqualTo("STOPPED");
        assertThat(newExecution.path("terminal_reason_code").asText()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(newExecution.path("terminal_at").isTextual()).isTrue();
        assertThat(newExecution.path("updated_at")).isEqualTo(newExecution.path("terminal_at"));
        var mutableExecution=List.of("status","terminal_reason_code","terminal_at","updated_at");
        var oldImmutable=oldExecution.deepCopy();var newImmutable=newExecution.deepCopy();
        oldImmutable.remove(mutableExecution);newImmutable.remove(mutableExecution);
        assertThat(newImmutable).isEqualTo(oldImmutable);
        var oldAllocations=rowsBy(before.get("allocation"),"allocation_id");var newAllocations=rowsBy(after.get("allocation"),"allocation_id");
        assertThat(newAllocations.keySet()).isEqualTo(oldAllocations.keySet());int released=0;
        for(var entry:oldAllocations.entrySet()){
            var old=entry.getValue();var current=newAllocations.get(entry.getKey());
            if(old.path("kind").asText().equals("EXECUTION_ASYNC")&&old.path("execution_id").asText().equals(execution.toString())){
                released++;assertThat(old.path("state").asText()).isEqualTo("QUEUED");
                assertThat(current.path("state").asText()).isEqualTo("RELEASED");
                assertThat(current.path("release_reason").asText()).isEqualTo("TERMINAL_RECONCILED");
                assertThat(current.path("released_at")).isEqualTo(newExecution.path("terminal_at"));
                var expected=old.deepCopy();expected.put("state","RELEASED");expected.set("released_at",newExecution.path("terminal_at"));expected.put("release_reason","TERMINAL_RECONCILED");
                assertThat(current).isEqualTo(expected);
            }else assertThat(current).isEqualTo(old);
        }
        assertThat(released).isEqualTo(1);
        var oldSlots=rowsBy(before.get("capacity_slot"),"token_id");var newSlots=rowsBy(after.get("capacity_slot"),"token_id");
        assertThat(newSlots.keySet()).isEqualTo(oldSlots.keySet());int queueReleased=0;
        for(var entry:oldSlots.entrySet()){
            var expected=entry.getValue().deepCopy();
            if(expected.path("capacity_class").asText().equals("QUEUE")){
                queueReleased++;assertThat(expected.path("current_execution_id").asText()).isEqualTo(execution.toString());
                expected.putNull("current_execution_id");expected.putNull("current_owner_epoch");
            }
            assertThat(newSlots.get(entry.getKey())).isEqualTo(expected);
        }
        assertThat(queueReleased).isEqualTo(1);assertThat(before.get("capacity_occupation")).hasSize(1);
        assertThat(after.get("capacity_occupation")).isEqualTo(before.get("capacity_occupation"));
    }
    private static Map<String,com.fasterxml.jackson.databind.node.ObjectNode> rowsBy(List<String> rows,String key) throws Exception {
        var values=new TreeMap<String,com.fasterxml.jackson.databind.node.ObjectNode>();
        for(String row:rows){var parsed=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(row);
            assertThat(values.put(parsed.path(key).asText(),parsed)).isNull();}
        return values;
    }

    void install(JdbcBulkCapacityIssuer.CapacityClass type){
        issuer.requestCapacity(new JdbcBulkCapacityIssuer.Request(UUID.randomUUID(),DEP,TENANT,capacity.bindingId(),type,1));
        var issued=issuer.allocateNext(type).orElseThrow();
        assertThat(issued.capacityClass()).isEqualTo(type);
        // install reads and verifies the issued token through the bounded authority reader.
        assertThat(installation.install(issued.tokenId())).isTrue();
    }
    void phase(String name) throws Exception {
        phases.add(name);var receipt=JSON.createObjectNode().put("phase",name).put("status","PASS")
                .put("at",java.time.Instant.now().toString()).put("ownerCreatedOnChild",false)
                .put("startMode",continuedAfterDrain?"CONTINUE_PUBLISHED_AFTER_DRAIN":continuedPublishedQueue?"CONTINUE_PUBLISHED_AFTER_EXPIRED_QUEUE":continuedPublication?"CONTINUE_FROM_PUBLICATION":continuedAuthoritySchema?"CONTINUE_DOMAIN_AUTHORITY_SCHEMA":continuedDomain?"CONTINUE_DOMAIN_MIGRATED":"FRESH")
                .put("phaseElapsedNanos",System.nanoTime()-phaseStarted).put("setupElapsedNanos",System.nanoTime()-setupStarted);
        if(continuedDomain) receipt.put("domainMigration20","REUSED").put("domainValidation",domainValidated?"EXECUTED":"PENDING").put("domainReplay0","NOT_OBSERVED");
        if(continuedAuthoritySchema) receipt.put("authorityPrefix","REUSED_SCHEMA_ONLY")
                .put("authorityMigration2",phases.contains("AUTHORITY_MIGRATION")?"EXECUTED":"PENDING")
                .put("priorPendingIdentitySha256","59fb3c8253939116ac91388238963bec89b698f8a409b01cdd4aabaa86324781");
        if(continuedPublication){
            receipt.put("continuationProvenance","CONTINUATION_ONLY").put("authorityMigration2","REUSED")
                    .put("capacityProvisioning","REUSED").put("publicFixtureSeed","REUSED");
            if(name.equals("CAPACITY")||((continuedPublishedQueue||continuedAfterDrain)&&name.equals("PUBLICATION"))) receipt.put("status","REUSED");
            if(continuedPublishedQueue||continuedAfterDrain)receipt.put("localPublicationPhotography","RECONCILE_PUBLISHED_1_NO_CAS");
            if(continuedAfterDrain)receipt.put("expiredQueueDrain","REUSED_CONFIRMED_RUN6_READBACK");
        }
        phaseStarted=System.nanoTime();
        Files.writeString(output.resolve(name+".private.json"),receipt.toString());
        Files.setPosixFilePermissions(output.resolve(name+".private.json"),PosixFilePermissions.fromString("rw-------"));
        System.out.println("NEON_ASYNC_PHASE="+name+","+receipt.path("status").asText());
    }
    Map<String,List<String>> snapshot(){
        var tx=new TransactionTemplate(new DataSourceTransactionManager(owner));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        return tx.execute(s->{var rows=new TreeMap<String,List<String>>();
            observer.execute("set local time zone 'UTC'");observer.execute("set local statement_timeout='3s'");observer.execute("set local lock_timeout='1s'");
            for(String table:List.of("proposal","evaluation","target_manifest","preview_state","target_preview","preview_item_integrity","allocation",
                    "execution","item_receipt","admission","capacity_slot","capacity_installation","capacity_occupation","capacity_marker","operation_control","openapi_publication","namespace_binding"))
                rows.put(table,observer.queryForList("select to_jsonb(t)::text from praxis_bulk.praxis_bulk_"+table+" t order by to_jsonb(t)::text",String.class));
            rows.put("domain",observer.queryForList("select to_jsonb(t)::text from public.async_admission_domain t order by id",String.class));
            rows.put("grant",observer.queryForList("select to_jsonb(t)::text from public.async_admission_grant t order by subject",String.class));
            return rows;});
    }
    long count(String table,UUID execution){return observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_"+table+" where execution_id=?",Long.class,execution);}
    Map<String,List<String>> jobSnapshot(UUID execution){
        var tx=new TransactionTemplate(new DataSourceTransactionManager(owner));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        return tx.execute(s->{var rows=new TreeMap<String,List<String>>();
            observer.execute("set local time zone 'UTC'");observer.execute("set local statement_timeout='3s'");observer.execute("set local lock_timeout='1s'");
            UUID proposal=observer.queryForObject("select proposal_id from praxis_bulk.praxis_bulk_execution where execution_id=?",UUID.class,execution);
            for(String table:List.of("proposal","evaluation","target_manifest","preview_state","target_preview","preview_item_integrity"))
                rows.put(table,observer.queryForList("select to_jsonb(t)::text from praxis_bulk.praxis_bulk_"+table+" t where proposal_id=? order by to_jsonb(t)::text",String.class,proposal));
            for(String table:List.of("execution","item_receipt","admission","capacity_occupation"))
                rows.put(table,observer.queryForList("select to_jsonb(t)::text from praxis_bulk.praxis_bulk_"+table+" t where execution_id=? order by to_jsonb(t)::text",String.class,execution));
            rows.put("domain",observer.queryForList("select to_jsonb(t)::text from public.async_admission_domain t where id=3",String.class));
            return rows;});
    }
    Map<String,List<String>> authoritySnapshot(){
        var sql=new JdbcTemplate(authorityOwner);var tx=new TransactionTemplate(new DataSourceTransactionManager(authorityOwner));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        return tx.execute(s->{sql.execute("set local time zone 'UTC'");sql.execute("set local statement_timeout='3s'");sql.execute("set local lock_timeout='1s'");
            var rows=new TreeMap<String,List<String>>();
            for(String table:List.of("capacity_token","capacity_binding","binding_attestation","capacity_request","deployment_capacity","tenant_capacity","fairness_cursor"))
                rows.put(table,sql.queryForList("select to_jsonb(t)::text from praxis_bulk_capacity."+table+" t order by to_jsonb(t)::text",String.class));
            return rows;});
    }
    private static BulkCapacityAuthorityInfrastructure authority(DataSource ds,BulkCapacityAuthorityMigrator.Identity id,
            BulkCapacityAuthorityMigrator.RoleConfiguration roles,BulkCapacityAuthorityInfrastructure.Access access){
        return new BulkCapacityAuthorityInfrastructure(ds,new DataSourceTransactionManager(ds),id,access,roles);}
    static HikariDataSource ownerPool(PGSimpleDataSource ds){var p=new HikariDataSource();p.setDataSource(ds);p.setMaximumPoolSize(4);p.setMinimumIdle(1);return p;}
    private static HikariDataSource pool(PGSimpleDataSource ds){var p=new HikariDataSource();p.setDataSource(ds);p.setMaximumPoolSize(2);p.setMinimumIdle(1);return p;}
    private static String password(JsonNode inputs,String role){String p=inputs.path("rolePasswords").path(role).asText();if(p.isEmpty())throw new IllegalStateException("Credential absent");return p;}
    private static String required(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalStateException("Required assignment field absent: "+key);return v;}
    private static String decode(String s){return URLDecoder.decode(s,StandardCharsets.UTF_8);}
    private static PGSimpleDataSource source(URI uri,String db,String user,String password){
        var ds=new PGSimpleDataSource();ds.setServerNames(new String[]{uri.getHost()});ds.setDatabaseName(db);ds.setUser(user);ds.setPassword(password);
        ds.setSslMode("verify-full");ds.setProperty(org.postgresql.PGProperty.SSL_FACTORY,"org.postgresql.ssl.DefaultJavaSSLFactory");
        ds.setConnectTimeout(10);ds.setSocketTimeout(30);return ds;}
    @Override public void close() throws Exception {try{if(context!=null)context.close();}finally{try{if(client!=null)client.close();}finally{try{if(controlSource!=null)controlSource.close();}finally{try{if(runtimeSource!=null)runtimeSource.close();}finally{try{if(authorityOwner!=null)authorityOwner.close();}finally{if(owner!=null)owner.close();}}}}}}
}
