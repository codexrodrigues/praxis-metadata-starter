package org.praxisplatform.uischema.stats.service.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsDimensionExtraction;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsDimensionRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.stats.service.ResolvedStatsDimension;
import org.praxisplatform.uischema.stats.service.ResolvedStatsMetric;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = JpaStatsExecutorIntegrationTest.TestJpaConfig.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jpastatsexecutorit;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        }
)
class JpaStatsExecutorIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = AllocationRecordEntity.class)
    static class TestJpaConfig {
    }

    @Entity
    @Table(name = "it_allocations")
    static class AllocationRecordEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String department;
        private String team;
        private BigDecimal amount;
        private LocalDate startDate;
        private LocalDate endDate;
        private Instant startAt;
        private Instant endAt;

        AllocationRecordEntity() {
        }

        AllocationRecordEntity(
                String department,
                String team,
                BigDecimal amount,
                LocalDate startDate,
                LocalDate endDate,
                Instant startAt,
                Instant endAt
        ) {
            this.department = department;
            this.team = team;
            this.amount = amount;
            this.startDate = startDate;
            this.endDate = endDate;
            this.startAt = startAt;
            this.endAt = endAt;
        }
    }

    static class EmptyFilterDTO implements GenericFilterDTO {
    }

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final JpaStatsQueryExecutor executor = new JpaStatsQueryExecutor();

    @BeforeEach
    void seedData() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("DELETE FROM JpaStatsExecutorIntegrationTest$AllocationRecordEntity").executeUpdate();

            // ENG (5 records: 100, 200, 300, 400, 500 -> min=100, p25=200, median=300, p75=400, max=500)
            persist("ENG", "CORE", "100.00", "2026-03-01", "2026-03-10", "2026-03-02T01:30:00Z", "2026-03-02T02:30:00Z");
            persist("ENG", "CORE", "200.00", "2026-03-01", null, "2026-03-01T15:00:00Z", null);
            persist("ENG", "CORE", "300.00", "2026-03-05", "2026-03-08", "2026-03-02T12:00:00Z", "2026-03-02T18:00:00Z");
            persist("ENG", "PLATFORM", "400.00", "2026-02-20", "2026-03-04", "2026-03-03T10:00:00Z", "2026-03-04T10:00:00Z");
            persist("ENG", "PLATFORM", "500.00", "2026-03-10", null, "2026-03-02T04:00:00Z", "2026-03-03T04:00:00Z");

            // OPS (2 records, nullable amount on 1 row to verify AVG denominator uses numericCount)
            persist("OPS", "SRE", "900.00", "2026-03-02", "2026-03-02", "2026-03-02T01:00:00Z", "2026-03-03T01:00:00Z");
            persist("OPS", "SRE", null, "2026-03-02", null, "2026-03-04T12:00:00Z", null);

            // FIN (1 singleton -> suppressed under threshold >= 2)
            persist("FIN", "AUDIT", "700.00", "2026-03-06", "2026-03-07", "2026-03-06T12:00:00Z", "2026-03-07T12:00:00Z");
        });
    }

    private void persist(
            String dept,
            String team,
            String amount,
            String startDate,
            String endDate,
            String startAt,
            String endAt
    ) {
        entityManager.persist(new AllocationRecordEntity(
                dept,
                team,
                amount == null ? null : new BigDecimal(amount),
                LocalDate.parse(startDate),
                endDate == null ? null : LocalDate.parse(endDate),
                Instant.parse(startAt),
                endAt == null ? null : Instant.parse(endAt)
        ));
    }

    @Test
    void computesExactBoxPlotPercentilesAcross1DAnd2DAndSuppressesSmallCells() {
        StatsFieldDescriptor deptDesc = StatsFieldDescriptor.categoricalGroupByBucket("department", "department");
        StatsFieldDescriptor teamDesc = StatsFieldDescriptor.categoricalGroupByBucket("team", "team");
        StatsFieldDescriptor amountDesc = StatsFieldDescriptor.numericMeasureField("amount", "amount");

        List<StatsMetricRequest> metricRequests = List.of(
                new StatsMetricRequest(StatsMetric.MIN, "amount", "min"),
                new StatsMetricRequest(StatsMetric.PERCENTILE_25, "amount", "p25"),
                new StatsMetricRequest(StatsMetric.MEDIAN, "amount", "median"),
                new StatsMetricRequest(StatsMetric.PERCENTILE_75, "amount", "p75"),
                new StatsMetricRequest(StatsMetric.MAX, "amount", "max"),
                new StatsMetricRequest(StatsMetric.COUNT, null, "count")
        );
        List<ResolvedStatsMetric> resolvedMetrics = List.of(
                new ResolvedStatsMetric(metricRequests.get(0), amountDesc),
                new ResolvedStatsMetric(metricRequests.get(1), amountDesc),
                new ResolvedStatsMetric(metricRequests.get(2), amountDesc),
                new ResolvedStatsMetric(metricRequests.get(3), amountDesc),
                new ResolvedStatsMetric(metricRequests.get(4), amountDesc),
                new ResolvedStatsMetric(metricRequests.get(5), null)
        );

        GroupByStatsRequest<EmptyFilterDTO> request1D = new GroupByStatsRequest<>(
                new EmptyFilterDTO(),
                "department",
                null,
                10,
                StatsBucketOrder.KEY_ASC,
                metricRequests
        );
        GroupByStatsResponse response1D = executor.executeGroupBy(
                entityManager,
                AllocationRecordEntity.class,
                null,
                List.of(new ResolvedStatsDimension(
                        new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department"),
                        deptDesc
                )),
                resolvedMetrics,
                request1D,
                null,
                10,
                100,
                2
        );

        assertTrue(response1D.suppressed());
        assertEquals(1, response1D.suppressedBucketCount());
        assertEquals(2, response1D.minBucketCount());
        assertEquals(2, response1D.buckets().size());

        GroupByBucket eng = response1D.buckets().get(0);
        assertEquals("ENG", eng.key());
        assertEquals(100.0, eng.values().get("min").doubleValue(), 0.001);
        assertEquals(200.0, eng.values().get("p25").doubleValue(), 0.001);
        assertEquals(300.0, eng.values().get("median").doubleValue(), 0.001);
        assertEquals(400.0, eng.values().get("p75").doubleValue(), 0.001);
        assertEquals(500.0, eng.values().get("max").doubleValue(), 0.001);
        assertEquals(5L, eng.values().get("count").longValue());

        // Strictest override on second dimension wins even when first dimension explicitly sets 0 opt-out
        StatsFieldDescriptor unprotectedDept = deptDesc.withMinBucketCount(0);
        StatsFieldDescriptor strictTeam = teamDesc.withMinBucketCount(3);
        GroupByStatsRequest<EmptyFilterDTO> request2D = new GroupByStatsRequest<>(
                new EmptyFilterDTO(),
                null,
                metricRequests.get(0),
                10,
                StatsBucketOrder.VALUE_ASC,
                metricRequests,
                List.of(
                        new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department"),
                        new StatsDimensionRequest("team", StatsDimensionExtraction.NONE, "team")
                ),
                null,
                null,
                null,
                null,
                true,
                null
        );
        GroupByStatsResponse response2D = executor.executeGroupBy(
                entityManager,
                AllocationRecordEntity.class,
                null,
                List.of(
                        new ResolvedStatsDimension(request2D.dimensions().get(0), unprotectedDept),
                        new ResolvedStatsDimension(request2D.dimensions().get(1), strictTeam)
                ),
                resolvedMetrics,
                request2D,
                null,
                10,
                100,
                0
        );

        assertTrue(response2D.suppressed());
        assertEquals(3, response2D.suppressedBucketCount()); // PLATFORM(2), SRE(2), AUDIT(1) all < 3
        assertEquals(3, response2D.minBucketCount());
        assertEquals(1, response2D.buckets().size());
        GroupByBucket engCore = response2D.buckets().get(0);
        assertEquals("CORE", engCore.coordinates().get("team"));
        assertEquals(3L, engCore.count());
        assertEquals(150.0, engCore.values().get("p25").doubleValue(), 0.001);
        assertEquals(250.0, engCore.values().get("p75").doubleValue(), 0.001);

        // Opt-out (minBucketCount = 0 on field) allows viewing buckets even when global default = 3
        GroupByStatsResponse optOutResponse = executor.executeGroupBy(
                entityManager,
                AllocationRecordEntity.class,
                null,
                List.of(new ResolvedStatsDimension(
                        new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department"),
                        unprotectedDept
                )),
                resolvedMetrics,
                request1D,
                null,
                10,
                100,
                3
        );
        assertFalse(optOutResponse.suppressed());
        assertEquals(3, optOutResponse.buckets().size());
    }

    @Test
    void intervalTimeSeriesRespectsTimezoneMidnightShiftsAndNullableNumericAvg() {
        StatsFieldDescriptor instantInterval = StatsFieldDescriptor.temporalIntervalTimeSeriesField(
                "activeAt",
                "startAt",
                "endAt"
        );
        StatsFieldDescriptor amountDesc = StatsFieldDescriptor.numericMeasureField("amount", "amount");

        List<StatsMetricRequest> metrics = List.of(
                new StatsMetricRequest(StatsMetric.COUNT, null, "active"),
                new StatsMetricRequest(StatsMetric.AVG, "amount", "avgAmount")
        );
        List<ResolvedStatsMetric> resolvedMetrics = List.of(
                new ResolvedStatsMetric(metrics.get(0), null),
                new ResolvedStatsMetric(metrics.get(1), amountDesc)
        );

        // Record 1: 2026-03-02T01:30Z..2026-03-02T02:30Z -> ends 2026-03-01 23:30 in Sao Paulo (NOT Mar 2!),
        // whereas in UTC both start and end are on Mar 2.
        // Record 6: 2026-03-02T01:00Z..2026-03-03T01:00Z -> starts 2026-03-01 22:00 in Sao Paulo.
        // Record 2: 2026-03-01T15:00Z..open -> active all days.
        TimeSeriesStatsRequest<EmptyFilterDTO> saoPauloReq = new TimeSeriesStatsRequest<>(
                new EmptyFilterDTO(),
                "activeAt",
                TimeSeriesGranularity.DAY,
                null,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 2),
                Boolean.TRUE,
                metrics,
                "America/Sao_Paulo"
        );
        TimeSeriesStatsResponse saoPauloResp = executor.executeTimeSeries(
                entityManager,
                AllocationRecordEntity.class,
                null,
                instantInterval,
                resolvedMetrics,
                saoPauloReq,
                10
        );
        assertEquals(2, saoPauloResp.points().size());
        // Sao Paulo 2026-03-01: Rec1(100), Rec2(200), Rec6(900) => count=3
        assertEquals(3L, saoPauloResp.points().get(0).count());
        assertEquals(400.0, saoPauloResp.points().get(0).values().get("avgAmount").doubleValue(), 0.01);
        // Sao Paulo 2026-03-02: Rec2(200), Rec3(300), Rec5(500), Rec6(900) => count=4 (Rec1 already ended!)
        assertEquals(4L, saoPauloResp.points().get(1).count());

        TimeSeriesStatsRequest<EmptyFilterDTO> utcReq = new TimeSeriesStatsRequest<>(
                new EmptyFilterDTO(),
                "activeAt",
                TimeSeriesGranularity.DAY,
                null,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 2),
                Boolean.TRUE,
                metrics,
                "UTC"
        );
        TimeSeriesStatsResponse utcResp = executor.executeTimeSeries(
                entityManager,
                AllocationRecordEntity.class,
                null,
                instantInterval,
                resolvedMetrics,
                utcReq,
                10
        );
        // UTC 2026-03-01: only Rec2(200)
        assertEquals(1L, utcResp.points().get(0).count());
        // UTC 2026-03-02: Rec1, Rec2, Rec3, Rec5, Rec6 => count=5
        assertEquals(5L, utcResp.points().get(1).count());

        // LocalDate interval with nullable amount on OPS(null) + OPS(900) + 4 other non-null rows:
        // Verify AVG on 2026-03-02 divides ONLY by non-null numericCount (5 non-null values among 6 active rows!)
        StatsFieldDescriptor dateInterval = StatsFieldDescriptor.temporalIntervalTimeSeriesField(
                "contractDates",
                "startDate",
                "endDate"
        );
        TimeSeriesStatsRequest<EmptyFilterDTO> dateAvgReq = new TimeSeriesStatsRequest<>(
                new EmptyFilterDTO(),
                "contractDates",
                TimeSeriesGranularity.DAY,
                null,
                LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 2),
                Boolean.FALSE,
                metrics,
                "UTC"
        );
        TimeSeriesStatsResponse dateAvgResp = executor.executeTimeSeries(
                entityManager,
                AllocationRecordEntity.class,
                null,
                dateInterval,
                resolvedMetrics,
                dateAvgReq,
                10
        );
        // Active on 2026-03-02: Rec1(100), Rec2(200), Rec4(400), OPS1(900), OPS2(null) => count=5, numericSum=1600/4=400.0
        assertEquals(1, dateAvgResp.points().size());
        assertEquals(5L, dateAvgResp.points().get(0).count());
        assertEquals(400.0, dateAvgResp.points().get(0).values().get("avgAmount").doubleValue(), 0.01);

        // SQL TERMS distribution suppression with VALUE_ASC + limit=1 must skip singleton FIN(1) and return OPS(2)
        DistributionStatsRequest<EmptyFilterDTO> distReq = new DistributionStatsRequest<>(
                new EmptyFilterDTO(),
                "department",
                DistributionMode.TERMS,
                new StatsMetricRequest(StatsMetric.COUNT, null),
                null,
                null,
                1,
                StatsBucketOrder.VALUE_ASC
        );
        DistributionStatsResponse distResp = executor.executeDistribution(
                entityManager,
                AllocationRecordEntity.class,
                null,
                StatsFieldDescriptor.categoricalTermsBucket("department", "department"),
                null,
                distReq,
                10,
                2
        );
        assertTrue(distResp.suppressed());
        assertEquals(1, distResp.suppressedBucketCount());
        assertEquals(1, distResp.buckets().size());
        assertEquals("OPS", distResp.buckets().get(0).key());
        assertEquals(2L, distResp.buckets().get(0).count());
    }
}
