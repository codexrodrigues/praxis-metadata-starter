package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Input/custody, closed diagnostics and completion checks; actual provisioning needs a packaged Neon JVM. */
class BulkCapacityProvisioningMainTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    @BeforeEach void privateDirectory() throws Exception {
        directory = directory.toRealPath(); // macOS /var is a symlink; production rejects symlink ancestors.
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
    @Test void everyCommandParsesOnlyItsExactExistingProtocolIntent() throws Exception {
        for (var operation : BulkCapacityProvisioningMain.Operation.values()) {
            var c = BulkCapacityProvisioningMain.readConfiguration(operation, input(operation.name(), configuration(operation)));
            assertThat(c.operation()).isEqualTo(operation);
            assertThat(c.intent().toString()).doesNotContain("secret-password", "jdbc:", "password", "username");
            assertThat(c.toString()).isEqualTo("Capacity provisioning configuration [private]");
            assertThat(c.identity().authorityId()).isEqualTo(UUID.fromString(ID));
        }
    }
    @Test void rejectsUnknownFieldsDuplicateKeysAndTrailingJson() throws Exception {
        var op = BulkCapacityProvisioningMain.Operation.REQUEST;
        ObjectNode root = configuration(op);root.put("verified", true);
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op, input("extra", root))).isInstanceOf(IllegalArgumentException.class);
        String normal = configuration(op).toString();
        for (String invalid : new String[]{normal.substring(0, normal.length()-1)+",\"request\":{}}", normal+" {}"}) {
            Path p = input("duplicate-"+UUID.randomUUID(), invalid);
            assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op, p)).isInstanceOf(Exception.class);
        }
    }
    @Test void rejectsIdentityEpochRoleAndRequestSubstitutionWithoutConnecting() throws Exception {
        var op = BulkCapacityProvisioningMain.Operation.INSTALL;
        for (String field : new String[]{"authorityId", "authorityEpoch", "deploymentId", "environment", "generation"}) {
            ObjectNode root = configuration(op);ObjectNode b=(ObjectNode)root.get("binding");
            if (field.equals("authorityEpoch") || field.equals("generation")) b.put(field, 0); else b.put(field, "other");
            assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op, input(field, root))).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode wrongRole=configuration(op);((ObjectNode)wrongRole.get("reader")).put("username", "authority_owner");
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op, input("role", wrongRole))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode request=configuration(BulkCapacityProvisioningMain.Operation.REQUEST);
        ((ObjectNode)request.get("request")).put("requestedCount", 3);
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(BulkCapacityProvisioningMain.Operation.REQUEST,
                input("quantity", request))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsWeakTlsCredentialUrlOverridesAndUnboundedTransport() throws Exception {
        var op=BulkCapacityProvisioningMain.Operation.REQUEST;
        for(String query:new String[]{"sslmode=require", "sslmode=verify-full&user=other", "sslmode=verify-full&password=bad",
                "sslmode=verify-full&socketTimeout=0", "sslmode=verify-full&connectTimeout=21",
                "sslmode=verify-full&sslmode=require", "sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory",
                "sslmode=verify-full&sslhostnameverifier=custom.PermissiveVerifier"}) {
            ObjectNode root=configuration(op);((ObjectNode)root.get("allocator")).put("url","jdbc:postgresql://invalid.example/authority?"+query);
            assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op,input("tls-"+UUID.randomUUID(),root))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void preservesSmallerNativeTransportTimeouts() throws Exception {
        var op=BulkCapacityProvisioningMain.Operation.REQUEST;ObjectNode root=configuration(op);
        ((ObjectNode)root.get("allocator")).put("url","jdbc:postgresql://invalid.example/authority?sslmode=verify-full&connectTimeout=1&socketTimeout=2");
        ((ObjectNode)root.get("allocator")).put("password", " secret bytes ");
        var parsed=BulkCapacityProvisioningMain.readConfiguration(op,input("lower",root));
        assertThat(parsed.root().get("allocator").get("password").textValue()).isEqualTo(" secret bytes ");
        String bounded=BulkCapacityLocalAdministrationMain.boundedOwnerUrl(parsed.root().get("allocator").get("url").asText(),10);
        assertThat(bounded).contains("connectTimeout=1", "socketTimeout=2", "sslmode=verify-full");
    }
    @Test void deniesNoncanonicalUuidTypesMissingFieldsAndInvalidClass() throws Exception {
        var op=BulkCapacityProvisioningMain.Operation.INSTALL;
        for (Object value : new Object[]{"1-1-1-1-1", 7, "", "not-a-token"}) {
            ObjectNode root=configuration(op);root.set("tokenId", JSON.valueToTree(value));
            assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op,input("uuid-"+UUID.randomUUID(),root))).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode missing=configuration(op);missing.remove("reader");
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op,input("missing",missing))).isInstanceOf(IllegalArgumentException.class);
        var allocate=configuration(BulkCapacityProvisioningMain.Operation.ALLOCATE_NEXT);allocate.put("capacityClass","queue");
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(BulkCapacityProvisioningMain.Operation.ALLOCATE_NEXT,
                input("class",allocate))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsPublicFilesSymlinksOversizeAndExistingReceipt() throws Exception {
        var op=BulkCapacityProvisioningMain.Operation.ENROLL;Path p=input("permissions",configuration(op));
        Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op,p)).isInstanceOf(IllegalArgumentException.class);
        Path normal=input("normal",configuration(op));Path link=directory.resolve("link");Files.createSymbolicLink(link,normal);
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op,link)).isInstanceOf(IllegalArgumentException.class);
        Path large=input("large"," ".repeat(32769));
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.readConfiguration(op,large)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.privatePath(normal,false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pendingReceiptIsExclusiveDurableAndContainsOnlyGovernedIntent() throws Exception {
        var c=BulkCapacityProvisioningMain.readConfiguration(BulkCapacityProvisioningMain.Operation.INSTALL,
                input("config",configuration(BulkCapacityProvisioningMain.Operation.INSTALL)));
        ObjectNode pending=JSON.createObjectNode().put("status","PENDING_UNCONFIRMED");pending.set("intent",c.intent());
        Path receipt=directory.resolve("receipt");BulkCapacityProvisioningMain.createReceipt(receipt,pending);
        byte[] before=Files.readAllBytes(receipt);
        assertThat(Files.getPosixFilePermissions(receipt)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> BulkCapacityProvisioningMain.createReceipt(receipt,JSON.createObjectNode())).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(receipt)).isEqualTo(before);
        assertThat(Files.readString(receipt)).doesNotContain("secret-password","jdbc:","password");
        pending.put("status","UNCONFIRMED");BulkCapacityProvisioningMain.replaceReceipt(receipt,pending);
        assertThat(JSON.readTree(receipt.toFile()).get("status").asText()).isEqualTo("UNCONFIRMED");
        try(var files=Files.list(directory)){assertThat(files.map(x->x.getFileName().toString()).toList()).containsExactlyInAnyOrder("config","receipt");}
    }
    @Test void invalidInvocationNeverPublishesInputOrCreatesReceipt() throws Exception {
        Path p=input("secret", "{\"password\":\"secret-password\"}");Path receipt=directory.resolve("receipt");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        int code=BulkCapacityProvisioningMain.run(new String[]{"REQUEST",p.toString(),receipt.toString()},new PrintStream(bytes));
        assertThat(code).isEqualTo(64);assertThat(Files.exists(receipt)).isFalse();
        JsonNodeResult.assertDenied(bytes.toString());
        assertThat(bytes.toString()).doesNotContain("secret-password",p.toString(),"password","Exception");
    }
    @Test void ambientTransactionAndReceiptCollisionDenyBeforeDatasourceAcquisition() throws Exception {
        Path p=input("valid",configuration(BulkCapacityProvisioningMain.Operation.REQUEST));Path receipt=directory.resolve("receipt");
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(BulkCapacityProvisioningMain.run(new String[]{"REQUEST",p.toString(),receipt.toString()},new PrintStream(new ByteArrayOutputStream()))).isEqualTo(64);
            assertThat(Files.exists(receipt)).isFalse();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        Files.writeString(receipt,"existing");Files.setPosixFilePermissions(receipt,PosixFilePermissions.fromString("rw-------"));
        assertThat(BulkCapacityProvisioningMain.run(new String[]{"REQUEST",p.toString(),receipt.toString()},new PrintStream(new ByteArrayOutputStream()))).isEqualTo(64);
        assertThat(Files.readString(receipt)).isEqualTo("existing");
    }
    @Test void issueReadbackMustMatchCompleteRequestButDoesNotInventMissingTokens() {
        UUID id=UUID.fromString(ID);var request=new JdbcBulkCapacityIssuer.Request(id,"dep","tenant","binding",JdbcBulkCapacityIssuer.CapacityClass.ACTIVE,1);
        var issue=new JdbcBulkCapacityIssuer.Issue(id,"dep","tenant","binding",JdbcBulkCapacityIssuer.CapacityClass.ACTIVE,1,0,"digest",java.util.List.of());
        assertThatCode(()->BulkCapacityProvisioningMain.requireRequest(issue,request)).doesNotThrowAnyException();
        var other=new JdbcBulkCapacityIssuer.Request(id,"dep","other","binding",JdbcBulkCapacityIssuer.CapacityClass.ACTIVE,1);
        assertThatThrownBy(()->BulkCapacityProvisioningMain.requireRequest(issue,other)).isInstanceOf(IllegalStateException.class);
    }
    @Test void lateCanonicalReturnRemainsUnconfirmedWithRealMonotonicBudgetAndDurableIntent() throws Exception {
        Path receipt = directory.resolve("late-receipt");
        var evidence = JSON.createObjectNode().put("status", "PENDING_UNCONFIRMED");
        evidence.putObject("intent").put("operation", "REGISTER_ATTESTATION");
        BulkCapacityProvisioningMain.createReceipt(receipt, evidence);
        var output = new ByteArrayOutputStream();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        int exit = BulkCapacityProvisioningMain.completeCommand(BulkCapacityProvisioningMain.Operation.REGISTER_ATTESTATION,
                receipt, evidence, System.nanoTime()-1, new PrintStream(output), () -> {
                    calls.incrementAndGet(); return JSON.createObjectNode();
                });
        assertThat(exit).isEqualTo(2); assertThat(calls.get()).isEqualTo(1);
        var saved = JSON.readTree(receipt.toFile());
        assertThat(saved.path("status").asText()).isEqualTo("UNCONFIRMED");
        assertThat(saved.has("result")).isFalse(); assertThat(saved.path("intent").size()).isEqualTo(1);
        assertThat(saved.path("diagnostic").path("phase").asText()).isEqualTo("FINAL_BUDGET");
        assertThat(saved.path("diagnostic").path("category").asText()).isEqualTo("BUDGET_EXPIRED");
        assertThat(saved.path("diagnostic").path("sqlState").isNull()).isTrue();
        assertThat(JSON.readTree(output.toByteArray()).size()).isEqualTo(4);
        assertThat(JSON.readTree(output.toByteArray()).path("result").asText()).isEqualTo("UNCONFIRMED");
    }

    @Test void commandFailurePublishesOnlyClosedPrivateDiagnosticAndNoSuccessfulResult() throws Exception {
        Path receipt = directory.resolve("failure-receipt");
        var evidence = JSON.createObjectNode().put("status", "PENDING_UNCONFIRMED");
        evidence.putObject("intent").put("operation", "REGISTER_ATTESTATION");
        BulkCapacityProvisioningMain.createReceipt(receipt, evidence);
        var output = new ByteArrayOutputStream();
        var primary = new IllegalStateException("jdbc:secret-password/private/path",
                new java.sql.SQLException("secret-password", "57014"));
        int exit = BulkCapacityProvisioningMain.completeCommand(BulkCapacityProvisioningMain.Operation.REGISTER_ATTESTATION,
                receipt, evidence, System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos(), new PrintStream(output),
                () -> { throw primary; });
        assertThat(exit).isEqualTo(2);
        var saved = JSON.readTree(receipt.toFile());
        assertThat(saved.path("diagnostic")).isEqualTo(JSON.readTree(
                "{\"phase\":\"COMMAND\",\"category\":\"SQL_ERROR\",\"sqlState\":\"57014\"}"));
        assertThat(saved.has("result")).isFalse();
        assertThat(saved.toString()+output).doesNotContain("secret-password", "jdbc:", "/private/path", "IllegalStateException", "SQLException");
        assertThat(JSON.readTree(output.toByteArray()).size()).isEqualTo(4);
        assertThat(primary.getCause()).isInstanceOf(java.sql.SQLException.class);
    }

    @Test void diagnosticRejectsArbitrarySqlStateAndTerminatesCauseCycles() {
        var invalid = new java.sql.SQLException("private-message", "secret-password");
        var wrapper = new IllegalStateException("private-message", invalid);
        invalid.initCause(wrapper);
        var diagnostic = BulkCapacityProvisioningMain.diagnostic(BulkCapacityProvisioningMain.CompletionPhase.COMMAND, wrapper);
        assertThat(diagnostic.size()).isEqualTo(3);
        assertThat(diagnostic.path("category").asText()).isEqualTo("SQL_ERROR");
        assertThat(diagnostic.path("sqlState").isNull()).isTrue();
        assertThat(diagnostic.toString()).doesNotContain("private-message", "secret-password");
        var timeout = BulkCapacityProvisioningMain.diagnostic(BulkCapacityProvisioningMain.CompletionPhase.COMMAND,
                new IllegalStateException("secret", new java.sql.SQLTimeoutException("secret", "57014")));
        assertThat(timeout.path("category").asText()).isEqualTo("TIMEOUT");
        assertThat(timeout.path("sqlState").asText()).isEqualTo("57014");
    }

    private Path input(String name,Object value) throws Exception {
        Path p=directory.resolve(name);Files.writeString(p,value instanceof String s?s:JSON.writeValueAsString(value));
        Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));return p;
    }
    private ObjectNode configuration(BulkCapacityProvisioningMain.Operation operation) {
        ObjectNode root=JSON.createObjectNode();var authority=root.putObject("authority");
        authority.put("deploymentId","dep").put("environment","env").put("authorityId",ID).put("authorityEpoch",1);
        authority.putObject("roles").put("ownerLogin","authority_owner").put("provisionerLogin","provisioner")
                .put("allocatorLogin","allocator").put("readerLogin","reader");
        if (java.util.Set.of(BulkCapacityProvisioningMain.Operation.REQUEST,BulkCapacityProvisioningMain.Operation.READ_ISSUE,BulkCapacityProvisioningMain.Operation.ALLOCATE_NEXT).contains(operation)) {
            root.set("allocator",credential("allocator"));root.set("reader",credential("reader"));
            if(operation==BulkCapacityProvisioningMain.Operation.ALLOCATE_NEXT)root.put("capacityClass","ACTIVE");
            else root.putObject("request").put("requestId",ID).put("tenantId","tenant").put("bindingId","binding").put("capacityClass","ACTIVE").put("requestedCount",1);
        } else {
            root.set("provisioner",credential("provisioner"));
            root.putObject("binding").put("deploymentId","dep").put("tenantId","tenant").put("environment","env")
                    .put("bindingId","binding").put("generation",1).put("databaseId",ID).put("attestationId",ID).put("authorityId",ID).put("authorityEpoch",1);
            if(operation!=BulkCapacityProvisioningMain.Operation.ENROLL){
                root.set("reader",credential("reader"));var local=root.putObject("operational").put("namespace","ns");
                local.set("owner",credential("local_owner"));local.set("runtime",credential("runtime"));
                var roles=local.putObject("roles").put("expectedSchemaOwnerRole","local_owner");
                roles.putArray("runtimeGranteeRoles").add("runtime");roles.putArray("retentionExecutorMembers");roles.putArray("controlPlaneGranteeRoles");
            }
            if(operation==BulkCapacityProvisioningMain.Operation.INSTALL)root.put("tokenId",ID);
        }
        return root;
    }
    private ObjectNode credential(String role) {
        return JSON.createObjectNode().put("url","jdbc:postgresql://invalid.example/test?sslmode=verify-full")
                .put("username",role).put("password","secret-password");
    }
    private static class JsonNodeResult {
        static void assertDenied(String output) throws Exception {
            var value=JSON.readTree(output);assertThat(value.size()).isEqualTo(4);
            assertThat(value.get("result").asText()).isEqualTo("DENIED");assertThat(value.get("code").asText()).isEqualTo("INVALID_INPUT");
        }
    }
}
