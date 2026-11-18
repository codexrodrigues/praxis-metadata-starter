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
        // FIN(1) violates minBucketCount=2 -> complementary suppression also suppresses smallest visible OPS(2)
        assertEquals(2, response1D.suppressedBucketCount());
        assertEquals(2, response1D.minBucketCount());
        assertEquals(1, response1D.buckets().size());

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
        // Strict min=3 suppresses PLATFORM(2), SRE(2), AUDIT(1), plus ENG's remaining CORE(3) via row-marginal anti-differentiation
        assertEquals(4, response2D.suppressedBucketCount());
        assertEquals(3, response2D.minBucketCount());
        assertEquals(0, response2D.buckets().size());

        // Without suppression, verify exact 2D boxplot quantiles on (ENG, CORE)
        GroupByStatsResponse unprotected2D = executor.executeGroupBy(
                entityManager,
                AllocationRecordEntity.class,
                null,
                List.of(
                        new ResolvedStatsDimension(request2D.dimensions().get(0), unprotectedDept),
                        new ResolvedStatsDimension(request2D.dimensions().get(1), teamDesc.withMinBucketCount(0))
                ),
                resolvedMetrics,
                request2D,
                null,
                10,
                100,
                0,
                100_000
        );
        assertFalse(unprotected2D.suppressed());
        GroupByBucket engCore = unprotected2D.buckets().stream()
                .filter(b -> "CORE".equals(b.coordinates().get("team")))
                .findFirst()
                .orElseThrow();
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
        // FIN(1) + complementary smallest visible OPS(2) => suppressedBucketCount=2, leaving only ENG(5)
        assertEquals(2, distResp.suppressedBucketCount());
        assertEquals(1, distResp.buckets().size());
        assertEquals("ENG", distResp.buckets().get(0).key());
        assertEquals(5L, distResp.buckets().get(0).count());
    }

    @Test
    void enforcesNumericCountLgpdSuppressionAcrossSqlGroupByPercentilesDistributionAndTimeSeries() {
        // Seed "LEGAL" with 4 rows >= minBucketCount=2, but only 1 non-null amount (numericCount=1 < 2)
        // Seed "BIG" with 5 non-null rows; "OPS" already has 2 rows (1 non-null amount -> also suppressed!)
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("DELETE FROM JpaStatsExecutorIntegrationTest$AllocationRecordEntity").executeUpdate();
            for (int i = 0; i < 4; i++) {
                persist("BIG", "T1", String.valueOf(100 * (i + 1)), "2026-03-01", null, "2026-03-01T12:00:00Z", null);
            }
            persist("SAFE", "T1", "150.00", "2026-03-02", null, "2026-03-02T12:00:00Z", null);
            persist("SAFE", "T1", "250.00", "2026-03-02", null, "2026-03-02T12:00:00Z", null);
            persist("SAFE", "T1", "350.00", "2026-03-02", null, "2026-03-02T12:00:00Z", null);
            // LEGAL has 3 rows (>=2) with ONLY 1 non-null amount -> must suppress numeric queries, keep COUNT queries!
            persist("LEGAL", "T1", "9999.00", "2026-03-03", null, "2026-03-03T12:00:00Z", null);
            persist("LEGAL", "T1", null, "2026-03-03", null, "2026-03-03T12:00:00Z", null);
            persist("LEGAL", "T1", null, "2026-03-03", null, "2026-03-03T12:00:00Z", null);
        });

        StatsFieldDescriptor deptDesc = StatsFieldDescriptor.categoricalGroupByBucket("department", "department");
        StatsFieldDescriptor amountDesc = StatsFieldDescriptor.numericMeasureField("amount", "amount");
        List<ResolvedStatsDimension> dims = List.of(new ResolvedStatsDimension(
                new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department"),
                deptDesc
        ));

        // 1. COUNT-only does NOT touch amount -> LEGAL(3) stays visible!
        GroupByStatsResponse countOnly = executor.executeGroupBy(
                entityManager,
                AllocationRecordEntity.class,
                null,
                dims,
                List.of(new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.COUNT, null, "total"), null)),
                new GroupByStatsRequest<>(new EmptyFilterDTO(), "department", new StatsMetricRequest(StatsMetric.COUNT, null, "total"), 10, StatsBucketOrder.KEY_ASC),
                null,
                10,
                100,
                2,
                100_000
        );
        assertFalse(countOnly.suppressed());
        assertEquals(3, countOnly.buckets().size());

        // 2. SQL SUM(amount) sees LEGAL numericCount=1 < 2 -> suppresses LEGAL AND complementary SAFE(3 < BIG(4))!
        StatsMetricRequest sumMetric = new StatsMetricRequest(StatsMetric.SUM, "amount", "sumAmount");
        GroupByStatsResponse sqlSum = executor.executeGroupBy(
                entityManager,
                AllocationRecordEntity.class,
                null,
                dims,
                List.of(new ResolvedStatsMetric(sumMetric, amountDesc)),
                new GroupByStatsRequest<>(new EmptyFilterDTO(), "department", sumMetric, 10, StatsBucketOrder.KEY_ASC, List.of(sumMetric)),
                null,
                10,
                100,
                2,
                100_000
        );
        assertTrue(sqlSum.suppressed());
        assertEquals(2, sqlSum.suppressedBucketCount());
        assertEquals(1, sqlSum.buckets().size());
        assertEquals("BIG", sqlSum.buckets().get(0).key());

        // 3. Percentile path (MEDIAN) sees LEGAL numericCount=1 < 2 -> suppresses LEGAL + complementary SAFE!
        StatsMetricRequest medMetric = new StatsMetricRequest(StatsMetric.MEDIAN, "amount", "medAmount");
        GroupByStatsResponse p50 = executor.executeGroupBy(
                entityManager,
                AllocationRecordEntity.class,
                null,
                dims,
                List.of(new ResolvedStatsMetric(medMetric, amountDesc)),
                new GroupByStatsRequest<>(new EmptyFilterDTO(), "department", medMetric, 10, StatsBucketOrder.KEY_ASC, List.of(medMetric)),
                null,
                10,
                100,
                2,
                100_000
        );
        assertTrue(p50.suppressed());
        assertEquals(2, p50.suppressedBucketCount());
        assertEquals(1, p50.buckets().size());
        assertEquals("BIG", p50.buckets().get(0).key());

        // 4. Distribution TERMS SQL MIN(amount) also suppresses LEGAL + SAFE!
        DistributionStatsResponse termsMin = executor.executeDistribution(
                entityManager,
                AllocationRecordEntity.class,
                null,
                StatsFieldDescriptor.categoricalTermsBucket("department", "department"),
                amountDesc,
                new DistributionStatsRequest<>(new EmptyFilterDTO(), "department", DistributionMode.TERMS, new StatsMetricRequest(StatsMetric.MIN, "amount"), null, null, 10, StatsBucketOrder.KEY_ASC),
                10,
                2,
                100_000
        );
        assertTrue(termsMin.suppressed());
        assertEquals(2, termsMin.suppressedBucketCount());
        assertEquals(1, termsMin.buckets().size());
        assertEquals("BIG", termsMin.buckets().get(0).key());

        // 5. Point-in-time TimeSeries with AVG(amount) and fillGaps=true:
        //    2026-03-01 (BIG=4, all non-null), 2026-03-02 (SAFE=3, all non-null),
        //    2026-03-03 (LEGAL=3 rows, ONLY 1 non-null -> suppressed!) + complementary partner 2026-03-02 (3 < 4)!
        TimeSeriesStatsResponse tsSuppressed = executor.executeTimeSeries(
                entityManager,
                AllocationRecordEntity.class,
                null,
                StatsFieldDescriptor.temporalTimeSeriesField("startAt", "startAt"),
                List.of(new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.AVG, "amount", "avg"), amountDesc)),
                new TimeSeriesStatsRequest<>(new EmptyFilterDTO(), "startAt", TimeSeriesGranularity.DAY, null, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 4), Boolean.TRUE, List.of(new StatsMetricRequest(StatsMetric.AVG, "amount", "avg")), "UTC"),
                10,
                366,
                2,
                100_000
        );
        assertTrue(tsSuppressed.suppressed());
        assertEquals(2, tsSuppressed.suppressedBucketCount());
        assertEquals(4, tsSuppressed.points().size());
        assertEquals(250.0, tsSuppressed.points().get(0).value().doubleValue(), 0.001); // 2026-03-01 visible
        org.junit.jupiter.api.Assertions.assertNull(tsSuppressed.points().get(1).value()); // 2026-03-02 complementary suppressed
        assertEquals(0L, tsSuppressed.points().get(1).count());
        org.junit.jupiter.api.Assertions.assertNull(tsSuppressed.points().get(2).value()); // 2026-03-03 primary suppressed (numericCount=1)
        assertEquals(0L, tsSuppressed.points().get(2).count());
        // Empty fillGap point 2026-03-04 returns null (not 0.0d) for AVG with numericCount == 0
        org.junit.jupiter.api.Assertions.assertNull(tsSuppressed.points().get(3).value());
        assertEquals(0L, tsSuppressed.points().get(3).count());
    }

    @Test
    void suppresses2DMarginalsIterativelyAndSupportsHistogramAutoStepAndIntervalClampingAndSampleGuardRail() {
        // 3x3 cross-tab where only (D1, T1)=1 violates minBucketCount=2, while T3 column remains untouched (visible)
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("DELETE FROM JpaStatsExecutorIntegrationTest$AllocationRecordEntity").executeUpdate();
            persistMany("D1", "T1", 1, "500.00", "2026-03-01", "2026-03-04");
            persistMany("D1", "T2", 5, "500.00", "2026-03-10", null);
            persistMany("D1", "T3", 6, "500.00", "2026-03-25", null);
            persistMany("D2", "T1", 7, "500.00", "2026-03-06", "2026-03-15");
            persistMany("D2", "T2", 8, "500.00", "2026-03-06", null);
            persistMany("D2", "T3", 9, "500.00", "2026-03-06", null);
            persistMany("D3", "T1", 4, "500.00", "2026-03-06", null);
            persistMany("D3", "T2", 10, "500.00", "2026-03-06", null);
            persistMany("D3", "T3", 11, "500.00", "2026-03-06", null);
        });

        StatsFieldDescriptor deptDesc = StatsFieldDescriptor.categoricalGroupByBucket("department", "department");
        StatsFieldDescriptor teamDesc = StatsFieldDescriptor.categoricalGroupByBucket("team", "team");
        GroupByStatsResponse matrix = executor.executeGroupBy(
                entityManager,
                AllocationRecordEntity.class,
                null,
                List.of(
                        new ResolvedStatsDimension(new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department"), deptDesc),
                        new ResolvedStatsDimension(new StatsDimensionRequest("team", StatsDimensionExtraction.NONE, "team"), teamDesc)
                ),
                List.of(new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.COUNT, null, "count"), null)),
                new GroupByStatsRequest<>(
                        new EmptyFilterDTO(),
                        null,
                        new StatsMetricRequest(StatsMetric.COUNT, null, "count"),
                        20,
                        StatsBucketOrder.KEY_ASC,
                        List.of(new StatsMetricRequest(StatsMetric.COUNT, null, "count")),
                        List.of(
                                new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department"),
                                new StatsDimensionRequest("team", StatsDimensionExtraction.NONE, "team")
                        ),
                        null, null, null, null, false, null
                ),
                null,
                20,
                100,
                2,
                100_000
        );
        assertTrue(matrix.suppressed());
        // Optimal 2x2 rectangular closure {(D1,T1), (D1,T2), (D3,T1), (D3,T2)} leaves 5 cells visible across D2 and T3
        assertEquals(4, matrix.suppressedBucketCount());
        assertEquals(5, matrix.buckets().size());
        for (GroupByBucket bucket : matrix.buckets()) {
            Object d = bucket.coordinates().get("department");
            Object t = bucket.coordinates().get("team");
            assertTrue("D2".equals(d) || "T3".equals(t));
        }

        // Auto-step HISTOGRAM when min == max ("500.00") uses clean step = 1
        DistributionStatsResponse equalHistogram = executor.executeDistribution(
                entityManager,
                AllocationRecordEntity.class,
                null,
                StatsFieldDescriptor.numericHistogramMeasureField("amount", "amount"),
                null,
                new DistributionStatsRequest<>(new EmptyFilterDTO(), "amount", DistributionMode.HISTOGRAM, new StatsMetricRequest(StatsMetric.COUNT, null), null, 5, 10, StatsBucketOrder.KEY_ASC),
                10,
                0,
                100_000
        );
        assertEquals(1, equalHistogram.buckets().size());
        assertEquals(new BigDecimal("500"), equalHistogram.buckets().get(0).from());
        assertEquals(new BigDecimal("501"), equalHistogram.buckets().get(0).to());

        // Exact interval intersection [effectiveFrom=2026-03-05, effectiveTo=2026-03-20] excludes:
        // D1/T1 (ended 2026-03-04 < 03-05, 1 row) and D1/T3 (starts 2026-03-25 > 03-20, 6 rows)
        // Total=61 -> active = 61 - 1 - 6 = 54
        TimeSeriesStatsResponse clampedMonth = executor.executeTimeSeries(
                entityManager,
                AllocationRecordEntity.class,
                null,
                StatsFieldDescriptor.temporalIntervalTimeSeriesField("contractDates", "startDate", "endDate"),
                List.of(new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.COUNT, null, "active"), null)),
                new TimeSeriesStatsRequest<>(new EmptyFilterDTO(), "contractDates", TimeSeriesGranularity.MONTH, null, LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 20), Boolean.FALSE, List.of(new StatsMetricRequest(StatsMetric.COUNT, null, "active")), "UTC"),
                10,
                366,
                0,
                100_000
        );
        assertEquals(1, clampedMonth.points().size());
        assertEquals(54L, clampedMonth.points().get(0).count());

        // Guard rail maxInMemorySampleRows=10 must fail fast cleanly with IllegalArgumentException on in-memory paths
        StatsFieldDescriptor amountDesc = StatsFieldDescriptor.numericMeasureField("amount", "amount");
        StatsMetricRequest p25Req = new StatsMetricRequest(StatsMetric.PERCENTILE_25, "amount", "p25");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> executor.executeGroupBy(
                        entityManager,
                        AllocationRecordEntity.class,
                        null,
                        List.of(new ResolvedStatsDimension(new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department"), deptDesc)),
                        List.of(new ResolvedStatsMetric(p25Req, amountDesc)),
                        new GroupByStatsRequest<>(new EmptyFilterDTO(), "department", p25Req, 10, StatsBucketOrder.KEY_ASC, List.of(p25Req)),
                        null,
                        10,
                        100,
                        0,
                        10
                )
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> executor.executeTimeSeries(
                        entityManager,
                        AllocationRecordEntity.class,
                        null,
                        StatsFieldDescriptor.temporalIntervalTimeSeriesField("contractDates", "startDate", "endDate"),
                        List.of(new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.COUNT, null, "active"), null)),
                        new TimeSeriesStatsRequest<>(new EmptyFilterDTO(), "contractDates", TimeSeriesGranularity.MONTH, null, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), Boolean.FALSE, List.of(new StatsMetricRequest(StatsMetric.COUNT, null, "active")), "UTC"),
                        10,
                        366,
                        0,
                        10
                )
        );
    }

    private void persistMany(String dept, String team, int count, String amount, String startDate, String endDate) {
        for (int i = 0; i < count; i++) {
            persist(dept, team, amount, startDate, endDate, "2026-03-06T12:00:00Z", null);
        }
    }
}
