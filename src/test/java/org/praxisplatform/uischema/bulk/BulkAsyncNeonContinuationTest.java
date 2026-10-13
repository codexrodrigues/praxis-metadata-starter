package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class BulkAsyncNeonContinuationTest {
    @Test void freshIsExactAndRejectsContinuationBinding() {
        assertThat(BulkAsyncPublicAdmissionNeonFixture.startMode(null,false)).isEqualTo("FRESH");
        assertThat(BulkAsyncPublicAdmissionNeonFixture.startMode("FRESH",false)).isEqualTo("FRESH");
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.startMode("FRESH",true)).isInstanceOf(AssertionError.class);
    }
    @Test void continuationRequiresItsExplicitBinding() {
        assertThat(BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_DOMAIN_MIGRATED",true)).isEqualTo("CONTINUE_DOMAIN_MIGRATED");
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_DOMAIN_MIGRATED",false)).isInstanceOf(AssertionError.class);
    }
    @Test void schemaPrefixContinuationRequiresItsOwnExplicitBinding() {
        assertThat(BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_DOMAIN_AUTHORITY_SCHEMA",true)).isEqualTo("CONTINUE_DOMAIN_AUTHORITY_SCHEMA");
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_DOMAIN_AUTHORITY_SCHEMA",false)).isInstanceOf(AssertionError.class);
    }
    @Test void pendingIdentityIsAdoptedExactlyWithoutRegeneration() {
        var value=BulkAsyncPublicAdmissionNeonFixture.JSON.createObjectNode().put("status","PENDING_UNCERTAIN")
                .put("branch","br-polished-mud-adr67xib").put("deployment",BulkAsyncPublicAdmissionNeonFixture.DEP)
                .put("environment",BulkAsyncPublicAdmissionNeonFixture.ENV).put("authorityEpoch",1)
                .put("authorityId","396e4d41-94df-4b70-b5dc-93820ba482bf");
        var id=BulkAsyncPublicAdmissionNeonFixture.preservedAuthorityIdentity(value);
        assertThat(id.authorityId().toString()).isEqualTo(value.path("authorityId").asText());
        assertThat(BulkAsyncPublicAdmissionNeonFixture.preservedAuthorityIdentity(value)).isEqualTo(id);
        for(String key:new String[]{"status","branch","deployment","environment","authorityId"}){
            var changed=value.deepCopy().put(key,"INVALID");
            assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.preservedAuthorityIdentity(changed)).isInstanceOfAny(AssertionError.class,IllegalArgumentException.class);
        }
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.preservedAuthorityIdentity(value.deepCopy().put("authorityEpoch",2))).isInstanceOf(AssertionError.class);
    }
    @Test void publicationContinuationRequiresExplicitBindingAndExactArtifact() {
        assertThat(BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_FROM_PUBLICATION",true)).isEqualTo("CONTINUE_FROM_PUBLICATION");
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_FROM_PUBLICATION",false)).isInstanceOf(AssertionError.class);
        assertThat(BulkAsyncPublicAdmissionNeonFixture.PUBLICATION_JAR_SHA256).matches("[a-f0-9]{64}");
        assertThat(BulkAsyncPublicAdmissionNeonFixture.PUBLICATION_RUNTIME_FREEZE_SHA256).matches("[a-f0-9]{64}");
    }
    @Test void sameProvisionedCapacityIdentityIsReusedAndScopeDriftRejected() {
        var authority=new BulkCapacityAuthorityMigrator.Identity(BulkAsyncPublicAdmissionNeonFixture.DEP,BulkAsyncPublicAdmissionNeonFixture.ENV,
                java.util.UUID.fromString("396e4d41-94df-4b70-b5dc-93820ba482bf"),1);
        var value=BulkAsyncPublicAdmissionNeonFixture.JSON.createObjectNode().put("deploymentId",BulkAsyncPublicAdmissionNeonFixture.DEP)
                .put("tenantId",BulkAsyncPublicAdmissionNeonFixture.TENANT).put("environment",BulkAsyncPublicAdmissionNeonFixture.ENV)
                .put("bindingId","async-neon-binding").put("generation",1).put("authorityId",authority.authorityId().toString()).put("authorityEpoch",1)
                .put("databaseId","2531cdea-378d-4be8-9761-425274e020ea").put("attestationId","49597a9e-76c7-4074-8cf2-41db4e8ff05a");
        var binding=BulkAsyncPublicAdmissionNeonFixture.preservedCapacityBinding(value,authority);
        assertThat(BulkAsyncPublicAdmissionNeonFixture.preservedCapacityBinding(value,authority)).isEqualTo(binding);
        assertThat(binding.databaseId().toString()).isEqualTo(value.path("databaseId").asText());
        assertThat(binding.attestationId().toString()).isEqualTo(value.path("attestationId").asText());
        for(String key:new String[]{"deploymentId","tenantId","environment","bindingId","authorityId","databaseId","attestationId"})
            assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.preservedCapacityBinding(value.deepCopy().put(key,"INVALID"),authority)).isInstanceOfAny(AssertionError.class,IllegalArgumentException.class);
        for(String key:new String[]{"generation","authorityEpoch"})
            assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.preservedCapacityBinding(value.deepCopy().put(key,2),authority)).isInstanceOf(AssertionError.class);
    }
    @Test void publishedQueueContinuationRequiresBindingAndExactOriginalCustody() {
        assertThat(BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_PUBLISHED_AFTER_EXPIRED_QUEUE",true)).isEqualTo("CONTINUE_PUBLISHED_AFTER_EXPIRED_QUEUE");
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_PUBLISHED_AFTER_EXPIRED_QUEUE",false)).isInstanceOf(AssertionError.class);
        var value=BulkAsyncPublicAdmissionNeonFixture.JSON.createObjectNode().put("status","QUEUED").put("idempotencyKey","public-confirmation")
                .put("idempotencyKeyDigest","sha256:"+"a".repeat(64)).put("proposalId","2531cdea-378d-4be8-9761-425274e020ea").put("nextOrdinal",0).put("deadlineAt","2026-10-13T00:46:00Z").put("executionId","396e4d41-94df-4b70-b5dc-93820ba482bf");
        assertThat(BulkAsyncPublicAdmissionNeonFixture.expiredQueueIdentity(value).toString()).isEqualTo(value.path("executionId").asText());
        for(String key:new String[]{"status","idempotencyKey","idempotencyKeyDigest","proposalId","deadlineAt","executionId"})
            assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.expiredQueueIdentity(value.deepCopy().put(key,"INVALID"))).isInstanceOfAny(AssertionError.class,IllegalArgumentException.class,java.time.DateTimeException.class);
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.expiredQueueIdentity(value.deepCopy().put("nextOrdinal",1))).isInstanceOf(AssertionError.class);
    }
    @Test void expiredQueueDrainAllowsOnlyCanonicalTerminalFieldChanges() throws Exception {
        String id="396e4d41-94df-4b70-b5dc-93820ba482bf";
        var json=BulkAsyncPublicAdmissionNeonFixture.JSON;var before=new java.util.TreeMap<String,java.util.List<String>>();
        before.put("execution",java.util.List.of(json.createObjectNode().put("execution_id",id).put("proposal_id","preserved")
                .put("status","QUEUED").putNull("terminal_reason_code").putNull("terminal_at").put("updated_at","old").put("deadline_at","original").toString()));
        before.put("allocation",java.util.List.of(json.createObjectNode().put("allocation_id",id).put("execution_id",id).put("kind","EXECUTION_ASYNC")
                .put("state","QUEUED").putNull("released_at").putNull("release_reason").put("created_at","original").toString()));
        before.put("capacity_slot",java.util.List.of(json.createObjectNode().put("token_id","queue").put("capacity_class","QUEUE").put("current_execution_id",id).put("current_owner_epoch",1).put("occupancy_sequence",1).toString(),
                json.createObjectNode().put("token_id","active").put("capacity_class","ACTIVE").putNull("current_execution_id").putNull("current_owner_epoch").put("occupancy_sequence",0).toString()));
        before.put("capacity_occupation",java.util.List.of("{}"));before.put("domain",java.util.List.of("{\"writes\":0}"));
        var after=new java.util.TreeMap<>(before);
        var terminal=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(before.get("execution").getFirst());
        terminal.put("status","STOPPED").put("terminal_reason_code","DEADLINE_EXCEEDED").put("terminal_at","terminal").put("updated_at","terminal");after.put("execution",java.util.List.of(terminal.toString()));
        var released=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(before.get("allocation").getFirst());
        released.put("state","RELEASED").put("released_at","terminal").put("release_reason","TERMINAL_RECONCILED");after.put("allocation",java.util.List.of(released.toString()));
        var slot=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(before.get("capacity_slot").getFirst());slot.putNull("current_execution_id").putNull("current_owner_epoch");
        after.put("capacity_slot",java.util.List.of(slot.toString(),before.get("capacity_slot").get(1)));after.put("capacity_occupation",before.get("capacity_occupation"));
        var execution=java.util.UUID.fromString(id);BulkAsyncPublicAdmissionNeonFixture.assertDrainDelta(before,after,execution);
        for(String table:new String[]{"execution","allocation","capacity_slot"}){
            var drift=new java.util.TreeMap<>(after);var row=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(after.get(table).getFirst());
            row.put("unexpected_field","changed");var rows=new java.util.ArrayList<>(after.get(table));rows.set(0,row.toString());drift.put(table,rows);
            assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.assertDrainDelta(before,drift,execution)).isInstanceOf(AssertionError.class);
        }
        var badReason=new java.util.TreeMap<>(after);badReason.put("allocation",java.util.List.of(released.deepCopy().put("release_reason","OTHER").toString()));
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.assertDrainDelta(before,badReason,execution)).isInstanceOf(AssertionError.class);
        var activeDrift=new java.util.TreeMap<>(after);activeDrift.put("capacity_slot",java.util.List.of(slot.toString(),((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(after.get("capacity_slot").get(1))).put("occupancy_sequence",1).toString()));
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.assertDrainDelta(before,activeDrift,execution)).isInstanceOf(AssertionError.class);
    }
    @Test void alreadyDrainedModeRequiresExactBindingWithoutFallback() {
        assertThat(BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_PUBLISHED_AFTER_DRAIN",true)).isEqualTo("CONTINUE_PUBLISHED_AFTER_DRAIN");
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.startMode("CONTINUE_PUBLISHED_AFTER_DRAIN",false)).isInstanceOf(AssertionError.class);
        var value=BulkAsyncPublicAdmissionNeonFixture.JSON.createObjectNode().put("status","STOPPED").put("idempotencyKey","public-confirmation")
                .put("idempotencyKeyDigest","sha256:"+"a".repeat(64)).put("proposalId","2531cdea-378d-4be8-9761-425274e020ea").put("executionId","396e4d41-94df-4b70-b5dc-93820ba482bf").put("nextOrdinal",0).put("deadlineAt","2026-10-13T00:46:00Z");
        assertThat(BulkAsyncPublicAdmissionNeonFixture.queueCustodyIdentity(value,"STOPPED").toString()).isEqualTo(value.path("executionId").asText());
        assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.queueCustodyIdentity(value.deepCopy().put("status","QUEUED"),"STOPPED")).isInstanceOf(AssertionError.class);
    }
    @Test void historicalComparisonPreservesTypesDuplicatesAndOccupationRows() throws Exception {
        var json=BulkAsyncPublicAdmissionNeonFixture.JSON;var baseline=json.createObjectNode();var rows=baseline.putObject("async_admission_domain_20261012").putObject("rows");
        rows.putArray("public.async_admission_domain").addObject().put("row","{\"id\":1,\"writes\":0}");
        rows.putArray("praxis_bulk.praxis_bulk_capacity_occupation").addObject().put("row","{\"occupancy_sequence\":1}");
        var current=new java.util.TreeMap<String,java.util.List<String>>();current.put("domain",java.util.List.of("{\"writes\":0,\"id\":1}"));current.put("capacity_occupation",java.util.List.of("{\"occupancy_sequence\":1}"));
        BulkAsyncPublicAdmissionNeonFixture.assertHistoricalSnapshot(baseline,current);
        for(var changed:java.util.List.of(java.util.List.of("{\"occupancy_sequence\":2}"),java.util.List.of("{\"occupancy_sequence\":\"1\"}"),java.util.List.<String>of(),java.util.List.of("{\"occupancy_sequence\":1}","{\"occupancy_sequence\":1}"))){
            var drift=new java.util.TreeMap<>(current);drift.put("capacity_occupation",changed);
            assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.assertHistoricalSnapshot(baseline,drift)).isInstanceOf(AssertionError.class);
        }
    }
    @Test void unknownOrBlankModeCannotFallBack() {
        for(String mode:new String[]{"", "AUTO", "CONTINUE_READY", "fresh"})
            assertThatThrownBy(()->BulkAsyncPublicAdmissionNeonFixture.startMode(mode,false)).isInstanceOf(AssertionError.class);
    }
    @Test void ownersUseDistinctCloseablePoolsOverUnchangedSourcesWithoutConnecting() {
        var sourceA=new PGSimpleDataSource();var sourceB=new PGSimpleDataSource();
        var first=BulkAsyncPublicAdmissionNeonFixture.ownerPool(sourceA);
        var second=BulkAsyncPublicAdmissionNeonFixture.ownerPool(sourceB);
        try {
            assertThat(first).isNotSameAs(second);
            assertThat(first.getDataSource()).isSameAs(sourceA);assertThat(second.getDataSource()).isSameAs(sourceB);
            assertThat(first.getMaximumPoolSize()).isEqualTo(4);assertThat(second.getMaximumPoolSize()).isEqualTo(4);
            assertThat(first.getConnectionInitSql()).isNull();assertThat(second.getConnectionInitSql()).isNull();
        } finally {try{second.close();}finally{first.close();}}
        assertThat(first.isClosed()).isTrue();assertThat(second.isClosed()).isTrue();
    }
}
