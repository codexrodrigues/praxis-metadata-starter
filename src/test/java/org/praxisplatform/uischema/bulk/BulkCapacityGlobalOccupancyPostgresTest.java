package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Serial, quiescent composition of real local occupancy under one global authority.
 * These are protected storage conformance controls, not public ASYNC composition or
 * a distributed atomic snapshot. This harness does not launch runtime subprocesses.
 */
class BulkCapacityGlobalOccupancyPostgresTest {
    private static final List<String> TOKEN_IDENTITY = List.of("token_id", "request_id", "deployment_id",
            "tenant_id", "environment", "binding_id", "binding_generation", "capacity_class", "token_ordinal",
            "payload_digest", "token_state", "database_id", "attestation_id", "authority_id", "authority_epoch");

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void fourTenantsOccupyEightActiveAndEightyQueueSlotsAndRejectAFifthBinding() throws Exception {
        try (var scope = new BulkCapacityOccupancyPostgresFixture.SharedScope();
                var proof = new GlobalProof()) {
            var locals = new ArrayList<BulkCapacityOccupancyPostgresFixture>();
            var markerBefore = new ArrayList<Map<String, Object>>();
            var controlBefore = new ArrayList<Map<String, Object>>();
            var namespacesBefore = new ArrayList<List<Map<String, Object>>>();
            var namespaces = new HashSet<String>();
            var tenants = new HashSet<String>();
            var databases = new HashSet<UUID>();
            var attestations = new HashSet<UUID>();
            var bindings = new HashSet<String>();
            for (int ordinal = 1; ordinal <= 5; ordinal++) {
                var local = scope.local("global-occupancy-local-" + ordinal, ordinal);
                locals.add(local);
                local.activate();
                var marker = local.observer.queryForMap("select * from praxis_bulk.praxis_bulk_capacity_marker");
                assertMarker(local, marker, scope);
                markerBefore.add(marker);
                controlBefore.add(local.controlRow());
                namespacesBefore.add(local.observer.queryForList("select * from "
                        + "praxis_bulk.praxis_bulk_namespace_binding order by namespace_id"));
                assertThat(namespacesBefore.getLast()).hasSize(1);
                assertThat(namespacesBefore.getLast().getFirst()).containsEntry("namespace_id", local.context.namespaceId())
                        .containsEntry("deployment_id", local.expected.deploymentId());
                assertThat(namespaces.add(local.context.namespaceId())).isTrue();
                assertThat(tenants.add(local.tenant)).isTrue();
                assertThat(databases.add(local.expected.databaseId())).isTrue();
                assertThat(attestations.add(local.expected.attestationId())).isTrue();
                assertThat(bindings.add(local.expected.bindingId())).isTrue();
                assertThat(local.runtimeSql.queryForObject("select current_user", String.class))
                        .isEqualTo("bulk_runtime_test");
                BulkExecutionMigrator.validate(local.ownerSource, BulkPostgresTestSupport.testRoleConfiguration());
            }
            assertThat(scope.authorityObserver.queryForObject("select count(*) from "
                    + "praxis_bulk_capacity.authority_identity", Long.class)).isEqualTo(1);
            assertThat(scope.authorityObserver.queryForObject("select count(*) from "
                    + "praxis_bulk_capacity.binding_attestation", Long.class)).isEqualTo(5);
            var identityBefore = scope.authorityObserver.queryForList("select * from "
                    + "praxis_bulk_capacity.authority_identity order by deployment_id");
            var attestationsBefore = scope.authorityObserver.queryForList("select * from "
                    + "praxis_bulk_capacity.binding_attestation order by attestation_id");
            proof.phase("FIVE_DISTINCT_AUTHENTICATED_MARKERS", 0, 0, 0);

            var requests = new HashSet<UUID>();
            var active = new LinkedHashMap<String, List<UUID>>();
            var queued = new LinkedHashMap<String, List<UUID>>();
            for (var local : locals.subList(0, 4)) {
                for (var capacityClass : JdbcBulkCapacityIssuer.CapacityClass.values()) {
                    int count = capacityClass == JdbcBulkCapacityIssuer.CapacityClass.ACTIVE ? 2 : 20;
                    UUID requestId = UUID.randomUUID();
                    assertThat(requests.add(requestId)).isTrue();
                    scope.issuer.requestCapacity(new JdbcBulkCapacityIssuer.Request(requestId,
                            local.expected.deploymentId(), local.tenant, local.expected.bindingId(), capacityClass, count));
                }
                active.put(local.tenant, new ArrayList<>());
                queued.put(local.tenant, new ArrayList<>());
            }
            // The fifth demand is deliberately absent until the first four tenants own all rights.
            issueAndInstall(scope, locals, JdbcBulkCapacityIssuer.CapacityClass.ACTIVE, 8, active);
            issueAndInstall(scope, locals, JdbcBulkCapacityIssuer.CapacityClass.QUEUE, 80, queued);
            for (var local : locals.subList(0, 4)) {
                assertThat(active.get(local.tenant)).hasSize(2);
                assertThat(queued.get(local.tenant)).hasSize(20);
            }
            var issuedBeforeOccupancy = authorityTokens(scope);
            assertThat(issuedBeforeOccupancy).hasSize(88);
            var authorityRows = new LinkedHashMap<UUID, Map<String, Object>>();
            for (var row : issuedBeforeOccupancy)
                assertThat(authorityRows.put((UUID) row.get("token_id"), row)).isNull();
            var installationsBefore = new ArrayList<List<Map<String, Object>>>();
            for (var local : locals)
                installationsBefore.add(local.observer.queryForList("select * from "
                        + "praxis_bulk.praxis_bulk_capacity_installation order by token_id"));
            proof.phase("EIGHT_ACTIVE_AND_EIGHTY_QUEUE_RIGHTS_INSTALLED", 0, 0, 0);

            var executionIds = new HashSet<UUID>();
            var proposalIds = new HashSet<UUID>();
            var reusedQueues = new HashSet<UUID>();
            for (var local : locals.subList(0, 4)) {
                for (int ordinal = 0; ordinal < 2; ordinal++) {
                    UUID queue = queued.get(local.tenant).get(ordinal);
                    assertThat(reusedQueues.add(queue)).isTrue();
                    var input = local.persist();
                    assertThat(proposalIds.add(input.proposal().id())).isTrue();
                    var enqueued = local.enqueue(input, "global-active-" + ordinal, queue);
                    assertThat(executionIds.add(enqueued.executionId())).isTrue();
                    assertThat(enqueued.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
                    var claimed = local.kernel.claim(local.context, enqueued.executionId(),
                            "global-worker-" + ordinal, active.get(local.tenant).get(ordinal)).orElseThrow();
                    assertThat(claimed.executionId()).isEqualTo(enqueued.executionId());
                    assertThat(claimed.status()).isEqualTo(BulkDurableExecutionStatus.RUNNING);
                    assertThat(claimed.control().epoch()).isEqualTo(2);
                    assertThat(local.slot(queue)).containsEntry("current_execution_id", null)
                            .containsEntry("current_owner_epoch", null).containsEntry("occupancy_sequence", 1L);
                }
            }
            proof.phase("EIGHT_RUNNING_AND_INITIAL_QUEUE_SLOTS_RELEASED", 8, 0, 16);
            for (var local : locals.subList(0, 4)) {
                for (int ordinal = 0; ordinal < 20; ordinal++) {
                    // PENDING subject quota is ten: consume each proposal before persisting another.
                    var input = local.persist();
                    assertThat(proposalIds.add(input.proposal().id())).isTrue();
                    var reservation = local.enqueue(input, "global-queued-" + ordinal, queued.get(local.tenant).get(ordinal));
                    assertThat(executionIds.add(reservation.executionId())).isTrue();
                    assertThat(reservation.status()).isEqualTo(BulkDurableExecutionStatus.QUEUED);
                    assertThat(reservation.control().epoch()).isEqualTo(1);
                }
            }
            assertThat(executionIds).hasSize(88);
            assertThat(proposalIds).hasSize(88);
            assertFullOccupancy(locals, authorityRows, reusedQueues, proof);
            assertStableBindingsAndRights(scope, locals, markerBefore, controlBefore,
                    issuedBeforeOccupancy, installationsBefore, namespacesBefore, identityBefore, attestationsBefore);
            proof.phase("EIGHT_RUNNING_AND_EIGHTY_QUEUED_SIMULTANEOUSLY", 8, 80, 96);

            var fifth = locals.get(4);
            for (var capacityClass : JdbcBulkCapacityIssuer.CapacityClass.values()) {
                UUID requestId = UUID.randomUUID();
                assertThat(requests.add(requestId)).isTrue();
                scope.issuer.requestCapacity(new JdbcBulkCapacityIssuer.Request(requestId,
                        fifth.expected.deploymentId(), fifth.tenant, fifth.expected.bindingId(), capacityClass,
                        capacityClass == JdbcBulkCapacityIssuer.CapacityClass.ACTIVE ? 2 : 20));
                assertThat(scope.issuer.allocateNext(capacityClass)).isEmpty();
                var issue = scope.issuer.findIssue(requestId).orElseThrow();
                assertThat(issue.requestedCount()).isEqualTo(
                        capacityClass == JdbcBulkCapacityIssuer.CapacityClass.ACTIVE ? 2 : 20);
                assertThat(issue.issuedCount()).isZero();
                assertThat(issue.tokenIds()).isEmpty();
            }
            assertThat(requests).hasSize(10);
            UUID foreignQueue = queued.get(locals.getFirst().tenant).getFirst();
            assertThat(scope.reader.readIssuedToken(foreignQueue).orElseThrow().tenantId())
                    .isEqualTo(locals.getFirst().tenant);
            assertThatThrownBy(() -> fifth.installation.install(foreignQueue))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("Issued capacity token differs from trusted binding");
            var fifthInput = fifth.persist();
            var fifthPending = fifth.observer.queryForList("select * from praxis_bulk.praxis_bulk_allocation "
                    + "where proposal_id=? order by allocation_id", fifthInput.proposal().id());
            assertThat(fifthPending).hasSize(1);
            assertThat(fifthPending.getFirst()).containsEntry("kind", "PROPOSAL_PENDING").containsEntry("state", "PENDING");
            assertThatThrownBy(() -> fifth.enqueue(fifthInput, "global-fifth-denied", foreignQueue))
                    .isInstanceOfSatisfying(BulkDurableExecutionException.class,
                            error -> assertThat(error.reason()).isEqualTo(BulkDurableExecutionException.Reason.NOT_EXECUTABLE));
            assertThat(fifth.observer.queryForList("select * from praxis_bulk.praxis_bulk_allocation "
                    + "where proposal_id=? order by allocation_id", fifthInput.proposal().id())).isEqualTo(fifthPending);
            for (String table : List.of("execution", "capacity_occupation", "item_receipt", "admission"))
                assertThat(fifth.count(table)).isZero();
            assertThat(fifth.observer.queryForObject("select count(*) from "
                    + "praxis_bulk.praxis_bulk_capacity_installation", Long.class)).isZero();
            assertThat(fifth.observer.queryForObject("select count(*) from "
                    + "praxis_bulk.praxis_bulk_capacity_slot", Long.class)).isZero();
            assertNoDomainOrReceiptMutation(fifth);
            assertFullOccupancy(locals, authorityRows, reusedQueues, proof);
            assertStableBindingsAndRights(scope, locals, markerBefore, controlBefore,
                    issuedBeforeOccupancy, installationsBefore, namespacesBefore, identityBefore, attestationsBefore);
            proof.phase("FIFTH_BINDING_DENIED_WITHOUT_EXTRA_OCCUPANCY", 8, 80, 96);
            for (var local : locals) local.assertionsComplete();
            proof.assertionsComplete = true;
        }
    }

    private static void issueAndInstall(BulkCapacityOccupancyPostgresFixture.SharedScope scope,
            List<BulkCapacityOccupancyPostgresFixture> locals, JdbcBulkCapacityIssuer.CapacityClass capacityClass,
            int count, Map<String, List<UUID>> tokens) {
        for (int ordinal = 0; ordinal < count; ordinal++) {
            var token = scope.issuer.allocateNext(capacityClass).orElseThrow();
            var local = locals.stream().filter(candidate -> candidate.tenant.equals(token.tenantId())).findFirst().orElseThrow();
            var authenticated = scope.reader.readIssuedToken(token.tokenId()).orElseThrow();
            assertThat(authenticated.capacityClass()).isEqualTo(capacityClass);
            assertThat(authenticated.bindingId()).isEqualTo(local.expected.bindingId());
            assertThat(local.installation.install(authenticated.tokenId())).isTrue();
            assertThat(local.slot(authenticated.tokenId())).containsEntry("occupancy_sequence", 0L)
                    .containsEntry("current_execution_id", null).containsEntry("current_owner_epoch", null);
            tokens.get(local.tenant).add(authenticated.tokenId());
        }
    }

    private static List<Map<String, Object>> authorityTokens(BulkCapacityOccupancyPostgresFixture.SharedScope scope) {
        return scope.authorityObserver.queryForList("""
                select t.token_id,t.request_id,t.deployment_id,t.tenant_id,a.environment,t.binding_id,
                       t.binding_generation,t.capacity_class,t.token_ordinal,r.payload_digest,t.state as token_state,
                       a.database_id,a.attestation_id,a.authority_id,a.authority_epoch
                from praxis_bulk_capacity.capacity_token t
                join praxis_bulk_capacity.capacity_request r on r.request_id=t.request_id
                join praxis_bulk_capacity.binding_attestation a
                  on a.binding_id=t.binding_id and a.deployment_id=t.deployment_id
                 and a.tenant_id=t.tenant_id and a.binding_generation=t.binding_generation
                order by t.token_id
                """);
    }

    private static void assertMarker(BulkCapacityOccupancyPostgresFixture local, Map<String, Object> row,
            BulkCapacityOccupancyPostgresFixture.SharedScope scope) {
        var expected = local.expected;
        assertThat(row).containsEntry("database_id", expected.databaseId()).containsEntry("attestation_id", expected.attestationId())
                .containsEntry("deployment_id", expected.deploymentId()).containsEntry("tenant_id", local.tenant)
                .containsEntry("environment", expected.environment()).containsEntry("binding_id", expected.bindingId())
                .containsEntry("binding_generation", expected.generation()).containsEntry("authority_id", scope.identity.authorityId())
                .containsEntry("authority_epoch", scope.identity.expectedAuthorityEpoch()).containsEntry("state", "ACTIVE");
    }

    private static void assertFullOccupancy(List<BulkCapacityOccupancyPostgresFixture> locals,
            Map<UUID, Map<String, Object>> authorityRows, Set<UUID> reusedQueues, GlobalProof proof) {
        var installedIds = new HashSet<UUID>();
        var executionIds = new HashSet<UUID>();
        var allocationIds = new HashSet<UUID>();
        long running = 0, queued = 0, histories = 0;
        proof.localCounts.removeAll();
        for (int ordinal = 0; ordinal < 4; ordinal++) {
            var local = locals.get(ordinal);
            var installations = local.observer.queryForList("""
                    select i.*,s.occupancy_sequence,s.current_execution_id,s.current_owner_epoch
                    from praxis_bulk.praxis_bulk_capacity_installation i
                    join praxis_bulk.praxis_bulk_capacity_slot s on s.token_id=i.token_id and s.capacity_class=i.capacity_class
                    order by i.token_id
                    """);
            assertThat(installations).hasSize(22);
            var executions = local.observer.queryForList("select * from praxis_bulk.praxis_bulk_execution order by execution_id");
            var allocations = local.observer.queryForList("select * from praxis_bulk.praxis_bulk_allocation "
                    + "where execution_id is not null order by execution_id");
            var proposals = local.observer.queryForList("select * from praxis_bulk.praxis_bulk_proposal order by proposal_id");
            var pendingAllocations = local.observer.queryForList("select * from praxis_bulk.praxis_bulk_allocation "
                    + "where kind='PROPOSAL_PENDING' order by proposal_id");
            var history = local.observer.queryForList("select * from praxis_bulk.praxis_bulk_capacity_occupation "
                    + "order by token_id,occupancy_sequence");
            assertThat(executions).hasSize(22);
            assertThat(allocations).hasSize(22);
            assertThat(pendingAllocations).hasSize(22);
            assertThat(proposals).hasSize(22);
            assertThat(history).hasSize(24);
            var byExecution = new LinkedHashMap<UUID, Map<String, Object>>();
            for (var execution : executions) {
                UUID id = (UUID) execution.get("execution_id");
                assertThat(executionIds.add(id)).isTrue();
                assertThat(byExecution.put(id, execution)).isNull();
                assertThat(execution).containsEntry("execution_mode", "ASYNC").containsEntry("next_ordinal", 0)
                        .containsEntry("namespace_id", local.context.namespaceId()).containsEntry("subject_id", local.context.subjectId())
                        .containsEntry("resource_key", local.context.resourceKey()).containsEntry("operation_id", local.context.operationRef().operationId())
                        .containsEntry("control_generation", local.control.generation())
                        .containsEntry("control_descriptor_fingerprint", local.control.descriptorFingerprint())
                        .containsEntry("structural_revision", local.control.structuralRevision()).containsEntry("active_attempt_id", null);
                boolean active = "RUNNING".equals(execution.get("status"));
                assertThat(execution.get("status")).isIn("RUNNING", "QUEUED");
                assertThat(execution.get("owner_epoch")).isEqualTo(active ? 2L : 1L);
                var allocation = allocations.stream().filter(row -> id.equals(row.get("execution_id"))).toList();
                assertThat(allocation).hasSize(1);
                assertThat(allocation.getFirst()).containsEntry("kind", "EXECUTION_ASYNC")
                        .containsEntry("state", active ? "ACTIVE" : "QUEUED")
                        .containsEntry("allocation_id", id).containsEntry("proposal_id", null)
                        .containsEntry("namespace_id", local.context.namespaceId())
                        .containsEntry("deployment_id", local.expected.deploymentId());
                var proposal = proposals.stream()
                        .filter(row -> execution.get("proposal_id").equals(row.get("proposal_id"))).toList();
                assertThat(proposal).hasSize(1);
                assertThat(proposal.getFirst()).containsEntry("namespace_id", local.context.namespaceId())
                        .containsEntry("subject_id", local.context.subjectId()).containsEntry("resource_key", local.context.resourceKey())
                        .containsEntry("operation_id", local.context.operationRef().operationId()).containsEntry("execution_mode", "ASYNC")
                        .containsEntry("fingerprint", execution.get("input_fingerprint"));
                var decoded = BulkSnapshotStorageCodec.decode((byte[]) proposal.getFirst().get("payload"),
                        (String) proposal.getFirst().get("fingerprint"));
                assertThat(decoded.context()).isEqualTo(local.context);
                assertThat(decoded.fingerprint()).isEqualTo(execution.get("input_fingerprint"));
                assertThat(decoded.intent().path("executionMode").asText()).isEqualTo("ASYNC");
                var pendingAllocation = pendingAllocations.stream()
                        .filter(row -> execution.get("proposal_id").equals(row.get("proposal_id"))).toList();
                assertThat(pendingAllocation).hasSize(1);
                assertThat(pendingAllocation.getFirst()).containsEntry("kind", "PROPOSAL_PENDING")
                        .containsEntry("state", "CONSUMED").containsEntry("execution_id", null);
                // V19 transfers the consumed proposal's exact quota scope to an execution-owned allocation.
                for (String column : List.of("namespace_id", "deployment_id", "subject_scope_digest_version",
                        "subject_scope_digest", "authorization_scope_digest_version", "authorization_scope_digest"))
                    assertThat(allocation.getFirst().get(column)).as("canonical allocation transfer: %s", column)
                            .isEqualTo(pendingAllocation.getFirst().get(column));
                assertThat(allocationIds.add((UUID) allocation.getFirst().get("allocation_id"))).isTrue();
                var executionHistory = history.stream().filter(row -> id.equals(row.get("execution_id"))).toList();
                assertThat(executionHistory).hasSize(active ? 2 : 1);
                var queueHistory = executionHistory.stream()
                        .filter(row -> execution.get("queue_token_id").equals(row.get("token_id"))).toList();
                assertThat(queueHistory).hasSize(1);
                assertThat(queueHistory.getFirst()).containsEntry("owner_epoch", 1L)
                        .containsEntry("occupancy_sequence", !active && reusedQueues.contains(execution.get("queue_token_id")) ? 2L : 1L);
                if (active) {
                    assertThat(executionHistory.stream().filter(row -> execution.get("active_token_id").equals(row.get("token_id"))).toList())
                            .hasSize(1).allSatisfy(row -> assertThat(row).containsEntry("owner_epoch", 2L));
                }
            }
            int localRunning = 0, localQueued = 0;
            for (var installed : installations) {
                UUID token = (UUID) installed.get("token_id");
                assertThat(installedIds.add(token)).isTrue();
                var authoritative = authorityRows.get(token);
                assertThat(authoritative).isNotNull();
                for (String column : TOKEN_IDENTITY) assertThat(installed.get(column)).as("full issued binding: %s", column)
                        .isEqualTo(authoritative.get(column));
                assertThat(installed).containsEntry("database_id", local.expected.databaseId())
                        .containsEntry("attestation_id", local.expected.attestationId()).containsEntry("tenant_id", local.tenant);
                UUID executionId = (UUID) installed.get("current_execution_id");
                assertThat(executionId).isNotNull();
                var execution = byExecution.get(executionId);
                assertThat(execution).isNotNull();
                boolean active = "ACTIVE".equals(installed.get("capacity_class"));
                assertThat(execution.get(active ? "active_token_id" : "queue_token_id")).isEqualTo(token);
                assertThat(execution.get("status")).isEqualTo(active ? "RUNNING" : "QUEUED");
                assertThat(installed.get("current_owner_epoch")).isEqualTo(active ? 2L : 1L);
                assertThat(installed.get("occupancy_sequence")).isEqualTo(reusedQueues.contains(token) ? 2L : 1L);
                var currentHistory = history.stream().filter(row -> token.equals(row.get("token_id"))
                        && installed.get("occupancy_sequence").equals(row.get("occupancy_sequence"))).toList();
                assertThat(currentHistory).hasSize(1);
                assertThat(currentHistory.getFirst()).containsEntry("execution_id", executionId)
                        .containsEntry("owner_epoch", installed.get("current_owner_epoch"));
                if (active) localRunning++; else localQueued++;
            }
            assertThat(localRunning).isEqualTo(2);
            assertThat(localQueued).isEqualTo(20);
            assertNoDomainOrReceiptMutation(local);
            running += localRunning;
            queued += localQueued;
            histories += history.size();
            proof.localCounts.addObject().put("logicalDatabase", "capacity_local_" + (ordinal + 1))
                    .put("running", localRunning).put("queued", localQueued).put("historyRows", history.size());
        }
        assertThat(installedIds).containsExactlyInAnyOrderElementsOf(authorityRows.keySet());
        assertThat(executionIds).hasSize(88);
        assertThat(allocationIds).hasSize(88);
        assertThat(running).isEqualTo(8);
        assertThat(queued).isEqualTo(80);
        assertThat(histories).isEqualTo(96);
        assertThat(reusedQueues).hasSize(8);
    }

    private static void assertNoDomainOrReceiptMutation(BulkCapacityOccupancyPostgresFixture local) {
        assertThat(local.count("item_receipt")).isZero();
        assertThat(local.count("admission")).isZero();
        assertThat(local.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isZero();
        assertThat(local.observer.queryForObject("select count(*) from occupancy_domain_witness "
                + "where last_pid is not null or last_xid is not null", Long.class)).isZero();
    }

    private static void assertStableBindingsAndRights(BulkCapacityOccupancyPostgresFixture.SharedScope scope,
            List<BulkCapacityOccupancyPostgresFixture> locals, List<Map<String, Object>> markers,
            List<Map<String, Object>> controls, List<Map<String, Object>> authorityRows,
            List<List<Map<String, Object>>> installations, List<List<Map<String, Object>>> namespaces,
            List<Map<String, Object>> identity, List<Map<String, Object>> attestations) {
        assertThat(authorityTokens(scope)).isEqualTo(authorityRows);
        assertThat(scope.authorityObserver.queryForList("select * from praxis_bulk_capacity.authority_identity "
                + "order by deployment_id")).isEqualTo(identity);
        assertThat(scope.authorityObserver.queryForList("select * from praxis_bulk_capacity.binding_attestation "
                + "order by attestation_id")).isEqualTo(attestations);
        for (int ordinal = 0; ordinal < locals.size(); ordinal++) {
            var local = locals.get(ordinal);
            assertThat(local.observer.queryForMap("select * from praxis_bulk.praxis_bulk_capacity_marker")).isEqualTo(markers.get(ordinal));
            assertThat(local.controlRow()).isEqualTo(controls.get(ordinal));
            assertThat(local.observer.queryForList("select * from praxis_bulk.praxis_bulk_namespace_binding "
                    + "order by namespace_id")).isEqualTo(namespaces.get(ordinal));
            assertThat(local.observer.queryForList("select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id"))
                    .isEqualTo(installations.get(ordinal));
        }
    }

    /** Sanitized phase observations; all protected tuple/UUID comparisons remain in assertions. */
    private static final class GlobalProof implements AutoCloseable {
        private final ObjectNode manifest = JsonNodeFactory.instance.objectNode()
                .put("caseId", "global-four-tenants-eight-active-eighty-queue")
                .put("logicalAuthorityDatabase", "capacity_global").put("harnessPid", ProcessHandle.current().pid())
                .put("subprocesses", 0).put("processExit", "NOT_APPLICABLE_IN_PROCESS_JUNIT")
                .put("barriersUsed", false).put("observationModel", "SERIAL_QUIESCENT_LOCAL_READ_COMPOSITION");
        private final com.fasterxml.jackson.databind.node.ArrayNode phases = manifest.putArray("phases");
        private final com.fasterxml.jackson.databind.node.ArrayNode localCounts = manifest.putArray("localCounts");
        private boolean assertionsComplete;

        void phase(String phase, int running, int queued, int historyRows) {
            if (!phase.matches("[A-Z][A-Z0-9_]{0,80}") || running < 0 || queued < 0 || historyRows < 0)
                throw new IllegalArgumentException("Invalid global proof phase");
            phases.addObject().put("phase", phase).put("running", running).put("queued", queued).put("historyRows", historyRows);
        }

        @Override public void close() throws Exception {
            manifest.put("caseOutcome", assertionsComplete ? "ASSERTIONS_COMPLETE" : "INCOMPLETE_OR_FAILED");
            String configured = System.getProperty("praxis.bulk.proof.directory");
            Path directory = configured == null ? Files.createTempDirectory("praxis-global-occupancy-proof-") : Path.of(configured);
            Files.createDirectories(directory);
            Path file = directory.resolve("global-four-tenants-eight-active-eighty-queue.json");
            Files.writeString(file, manifest.toPrettyString(), StandardCharsets.UTF_8);
            if (configured == null) System.out.println("Global occupancy proof manifest: " + file.toAbsolutePath());
        }
    }
}
