package org.praxisplatform.uischema.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Finite retained-history plan measurement, not a production load/SLO or T13 certification. */
class BulkDurableWorkerQueuePlanPostgresTest {
    private static final String SELECT = """
            select execution_id,created_at from praxis_bulk.praxis_bulk_execution
            where namespace_id=? and execution_mode='ASYNC' and status='QUEUED'
            """;
    private static final String POSITION = " and (created_at,execution_id) > (?,?)";
    private static final String ORDER = " order by created_at,execution_id limit 1";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void realSelectorPlansWithCanonicalTerminalHistoryExcludeStoppedExecutions() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("worker-history-query-plan")) {
            fixture.activate();
            var queue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            for (int i = 0; i < 32; i++) {
                var execution = fixture.enqueue(fixture.persist(1), "worker-history-" + i, queue);
                var stopped = fixture.kernel.requestCancel(fixture.context, execution.executionId());
                assertThat(stopped.status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
                assertThat(fixture.slot(queue)).containsEntry("current_execution_id", null);
            }
            assertThat(fixture.observer.queryForObject(
                    "select count(*) from praxis_bulk.praxis_bulk_execution where status='STOPPED'", Long.class))
                    .isEqualTo(32);
            var first = fixture.enqueue(fixture.persist(1), "worker-plan-live-first", queue);
            var otherQueue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var second = fixture.enqueue(fixture.persist(1), "worker-plan-live-second", otherQueue);
            assertThat(fixture.count("execution")).isEqualTo(34);
            // Official fixture OWNER updates statistics only, without rewriting protected rows or indexes.
            fixture.observer.execute("analyze praxis_bulk.praxis_bulk_execution");
            var firstHint = fixture.kernel.workerNextQueued(null).orElseThrow();
            var secondHint = fixture.kernel.workerNextQueued(firstHint).orElseThrow();
            assertThat(firstHint.executionId()).isEqualTo(first.executionId());
            assertThat(secondHint.executionId()).isEqualTo(second.executionId());
            assertThat(fixture.kernel.workerNextQueued(secondHint)).isEmpty();
            var initialPlan = explain(fixture, null);
            var positionedPlan = explain(fixture, firstHint);
            var exhaustedPlan = explain(fixture, secondHint);
            assertThat(initialPlan.path(0).path("Plan").path("Actual Rows").asInt()).isEqualTo(1);
            assertThat(positionedPlan.path(0).path("Plan").path("Actual Rows").asInt()).isEqualTo(1);
            assertThat(exhaustedPlan.path(0).path("Plan").path("Actual Rows").asInt()).isZero();
            assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution where status='QUEUED'",
                    Long.class)).isEqualTo(2);
            assertThat(fixture.count("item_receipt")).isZero();
            assertThat(fixture.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isZero();
            var controls = fixture.observer.queryForList("""
                    select status,owner_epoch,next_ordinal from praxis_bulk.praxis_bulk_execution
                     where execution_id in (?,?) order by created_at,execution_id
                    """, first.executionId(), second.executionId());
            assertThat(controls).hasSize(2).allSatisfy(value -> assertThat(value)
                    .containsEntry("status", "QUEUED").containsEntry("owner_epoch", 1L).containsEntry("next_ordinal", 0));
            var report = java.util.Map.of(
                    "scope", "finite-private-retained-history-not-production-SLO-or-T13",
                    "historyRows", 32, "liveQueuedRows", 2,
                    "postgresVersion", fixture.observer.queryForObject("select version()", String.class),
                    "initialQuery", SELECT + ORDER, "positionedQuery", SELECT + POSITION + ORDER,
                    "initialPlan", initialPlan, "positionedPlan", positionedPlan, "exhaustedPlan", exhaustedPlan,
                    "statisticsMaintenance", java.util.Map.of(
                            "method", "fixture OWNER ANALYZE after canonical seed; no row rewrite or index change",
                            "indexes", fixture.observer.queryForList("select indexname,indexdef from pg_indexes where schemaname='praxis_bulk' and tablename='praxis_bulk_execution' order by indexname"),
                            "runtimeBudget", "official withLifecycleRead independently writable verification; EXPLAIN statement tightened to worker 1s maximum; settings recorded; no timeout enlargement"));
            String directory = System.getProperty("praxis.bulk.proof.directory");
            if (directory != null)
                JSON.writeValue(java.nio.file.Path.of(directory, "worker-retained-history-explain.json").toFile(), report);
            fixture.observe("real-runtime-selector-plan-with-32-canonical-terminal-history-rows", first.executionId());
            fixture.kernel.requestCancel(fixture.context, first.executionId());
            fixture.kernel.requestCancel(fixture.context, second.executionId());
            assertThat(fixture.slot(queue)).containsEntry("occupancy_sequence", 33L).containsEntry("current_execution_id", null);
            assertThat(fixture.slot(otherQueue)).containsEntry("occupancy_sequence", 1L).containsEntry("current_execution_id", null);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(34);
            fixture.assertionsComplete();
        }
    }

    @Test
    void retainedHistoryGrowthSamplesKeepRightsAndWorkerBudgetsFixed() throws Exception {
        try (var fixture = new BulkCapacityOccupancyPostgresFixture("worker-history-growth")) {
            fixture.activate();
            var seedQueue = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var queueA = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var queueB = fixture.install(JdbcBulkCapacityIssuer.CapacityClass.QUEUE);
            var authority = fixture.authorityRows();
            var installations = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id");
            var controls = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_operation_control order by namespace_id,operation_id");
            var photo = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1");
            var samples = new java.util.ArrayList<java.util.Map<String, Object>>();
            int stoppedCount = 0;
            int seededCount = 0;
            int stage = 0;
            for (int level : new int[] {32, 128, 512}) {
                stage++;
                long seedStarted = System.nanoTime();
                int stageSeeded = 0;
                while (stoppedCount < level) {
                    var seed = fixture.enqueue(fixture.persist(1), "worker-growth-seed-" + seededCount, seedQueue);
                    assertThat(fixture.kernel.requestCancel(fixture.context, seed.executionId()).status())
                            .isEqualTo(BulkDurableExecutionStatus.STOPPED);
                    stoppedCount++;
                    seededCount++;
                    stageSeeded++;
                }
                long seedElapsedNanos = System.nanoTime() - seedStarted;
                assertThat(fixture.slot(seedQueue)).containsEntry("occupancy_sequence", (long) seededCount)
                        .containsEntry("current_execution_id", null);
                // Keep the default five-minute queue deadline; create fresh live jobs AFTER seeding.
                var first = fixture.enqueue(fixture.persist(1), "worker-growth-live-a-" + stage, queueA);
                var second = fixture.enqueue(fixture.persist(1), "worker-growth-live-b-" + stage, queueB);
                fixture.observer.execute("analyze praxis_bulk.praxis_bulk_execution");
                var slotsA = fixture.slot(queueA);
                var slotsB = fixture.slot(queueB);
                var allocationA = fixture.allocation(first.executionId());
                var allocationB = fixture.allocation(second.executionId());
                var liveBefore = fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_execution where execution_id in (?,?) order by created_at,execution_id",
                        first.executionId(), second.executionId());
                var firstHint = fixture.kernel.workerNextQueued(null).orElseThrow();
                var secondHint = fixture.kernel.workerNextQueued(firstHint).orElseThrow();
                assertThat(firstHint.executionId()).isEqualTo(first.executionId());
                assertThat(secondHint.executionId()).isEqualTo(second.executionId());
                assertThat(fixture.kernel.workerNextQueued(secondHint)).isEmpty();
                var plans = java.util.Map.of("initial", explain(fixture, null),
                        "positioned", explain(fixture, firstHint), "exhausted", explain(fixture, secondHint));
                assertThat(plans.get("initial").path(0).path("Plan").path("Actual Rows").asInt()).isEqualTo(1);
                assertThat(plans.get("positioned").path(0).path("Plan").path("Actual Rows").asInt()).isEqualTo(1);
                assertThat(plans.get("exhausted").path(0).path("Plan").path("Actual Rows").asInt()).isZero();
                var clockRows = fixture.observer.queryForList("""
                        select execution_id,clock_timestamp() as observed_at,deadline_at,
                            clock_timestamp()<deadline_at as before_deadline,status,owner_epoch,next_ordinal
                        from praxis_bulk.praxis_bulk_execution where execution_id in (?,?) order by created_at,execution_id
                        """, first.executionId(), second.executionId());
                assertThat(clockRows).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("before_deadline", true)
                        .containsEntry("status", "QUEUED").containsEntry("owner_epoch", 1L).containsEntry("next_ordinal", 0));
                assertThat(fixture.observer.queryForObject("select count(*) from praxis_bulk.praxis_bulk_execution where status='STOPPED'", Long.class))
                        .isEqualTo(level);
                assertThat(fixture.count("execution")).isEqualTo(level + 2);
                assertThat(fixture.count("item_receipt")).isZero();
                assertThat(fixture.observer.queryForObject("select sum(writes) from occupancy_domain_witness", Long.class)).isZero();
                assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_execution where execution_id in (?,?) order by created_at,execution_id",
                        first.executionId(), second.executionId())).isEqualTo(liveBefore);
                assertThat(fixture.slot(queueA)).isEqualTo(slotsA);
                assertThat(fixture.slot(queueB)).isEqualTo(slotsB);
                assertThat(fixture.allocation(first.executionId())).isEqualTo(allocationA);
                assertThat(fixture.allocation(second.executionId())).isEqualTo(allocationB);
                assertThat(fixture.authorityRows()).isEqualTo(authority);
                samples.add(java.util.Map.of("terminalRows", level, "liveQueuedRows", 2,
                        "canonicalSeedThisStage", stageSeeded, "seedElapsedNanos", seedElapsedNanos,
                        "firstExecution", first.executionId().toString(), "secondExecution", second.executionId().toString(),
                        "plans", plans, "postgresClockObservations", clockRows));
                assertThat(fixture.kernel.requestCancel(fixture.context, first.executionId()).status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
                assertThat(fixture.kernel.requestCancel(fixture.context, second.executionId()).status()).isEqualTo(BulkDurableExecutionStatus.STOPPED);
                stoppedCount += 2; // These live jobs become part of the next retained-history level.
            }
            assertThat(seededCount).isEqualTo(508);
            assertThat(stoppedCount).isEqualTo(514);
            assertThat(fixture.authorityRows()).isEqualTo(authority);
            assertThat(fixture.count("capacity_occupation")).isEqualTo(514);
            for (var queue : java.util.List.of(queueA, queueB))
                assertThat(fixture.slot(queue)).containsEntry("occupancy_sequence", 3L).containsEntry("current_execution_id", null);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_capacity_installation order by token_id"))
                    .isEqualTo(installations);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_operation_control order by namespace_id,operation_id"))
                    .isEqualTo(controls);
            assertThat(fixture.observer.queryForList("select * from praxis_bulk.praxis_bulk_openapi_publication order by 1"))
                    .isEqualTo(photo);
            String directory = System.getProperty("praxis.bulk.proof.directory");
            if (directory != null) JSON.writeValue(java.nio.file.Path.of(directory, "worker-retained-history-growth.json").toFile(),
                    java.util.Map.of("scope", "finite-cached-growth-not-SLO-T13-PG16-or-index-decision",
                            "samples", samples, "fixedQueueRights", 3, "activeRights", 0,
                            "postgresVersion", fixture.observer.queryForObject("select version()", String.class),
                            "indexes", fixture.observer.queryForList("select indexname,indexdef from pg_indexes where schemaname='praxis_bulk' and tablename='praxis_bulk_execution' order by indexname"),
                            "statisticsMaintenance", "owner ANALYZE each stage; no index/GUC/row rewrite",
                            "initialQuery", SELECT + ORDER, "positionedQuery", SELECT + POSITION + ORDER));
            fixture.assertionsComplete();
        }
    }

    private static JsonNode explain(BulkCapacityOccupancyPostgresFixture fixture,
            JdbcBulkDurableExecution.WorkerQueueHint after) {
        return fixture.runtime.withLifecycleRead(connection -> {
            // The lifecycle verifier permits 2s statements; the actual worker selector caps at 1s.
            java.util.Map<String, String> settings;
            try (var setup = connection.createStatement()) {
                setup.execute("select set_config('statement_timeout', case when current_setting('statement_timeout') = '0' or current_setting('statement_timeout')::interval > interval '1 second' then '1s' else current_setting('statement_timeout') end, true)");
                try (var actual = setup.executeQuery("select current_setting('statement_timeout'),current_setting('lock_timeout'),current_user,current_setting('transaction_read_only'),current_setting('statement_timeout')::interval <= interval '1 second'")) {
                    assertThat(actual.next()).isTrue();
                    assertThat(actual.getBoolean(5)).isTrue();
                    settings = java.util.Map.of("statementTimeout", actual.getString(1),
                            "lockTimeout", actual.getString(2), "runtimeRole", actual.getString(3),
                            "transactionReadOnly", actual.getString(4));
                }
            }
            String query = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + SELECT + (after == null ? "" : POSITION) + ORDER;
            try (var statement = connection.prepareStatement(query)) {
                statement.setString(1, fixture.context.namespaceId());
                if (after != null) {
                    statement.setObject(2, OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC));
                    statement.setObject(3, after.executionId());
                }
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    JsonNode plan;
                    try {
                        plan = JSON.readTree(rows.getString(1));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException invalidPlan) {
                        throw new java.sql.SQLException("PostgreSQL EXPLAIN did not return valid JSON", invalidPlan);
                    }
                    assertThat(rows.next()).isFalse();
                    assertThat(plan.isArray()).isTrue();
                    assertThat(plan.size()).isEqualTo(1);
                    assertThat(plan.path(0).path("Plan").path("Node Type").asText()).isEqualTo("Limit");
                    assertThat(plan.path(0).has("Execution Time")).isTrue();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) plan.get(0)).set("Test Runtime Settings", JSON.valueToTree(settings));
                    return plan;
                }
            }
        });
    }
}
