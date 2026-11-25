package org.praxisplatform.uischema.stats.service.jpa;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.praxisplatform.uischema.stats.RollingWindowUnit;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.praxisplatform.uischema.stats.dto.RollingWindowRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesPoint;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.stats.service.ResolvedStatsMetric;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies PostgreSQL SQL pushdown ({@code date_trunc}) for time-series stats,
 * timezone boundary alignment, LGPD suppression with {@code fillGaps}, SDC partition guard,
 * and {@link RollingWindowRequest} resolution.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JpaStatsTimeSeriesPostgresPushdownTest {

    private final JpaStatsQueryExecutor executor = new JpaStatsQueryExecutor();

    private EmbeddedPostgres postgres;
    private HikariDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean emfBean;
    private EntityManagerFactory emf;
    private EntityManager entityManager;
    private TransactionTemplate tx;

    @BeforeAll
    void startPostgresAndSeed() throws Exception {
        postgres = EmbeddedPostgres.builder()
                .setCleanDataDirectory(true)
                .setRegisterShutdownHook(false)
                .start();

        HikariDataSource pool = new HikariDataSource();
        pool.setDataSource(postgres.getPostgresDatabase());
        pool.setMaximumPoolSize(4);
        pool.setMinimumIdle(1);
        dataSource = pool;

        HibernateJpaVendorAdapter vendor = new HibernateJpaVendorAdapter();
        vendor.setDatabase(Database.POSTGRESQL);
        vendor.setShowSql(false);

        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setJpaVendorAdapter(vendor);
        bean.setPackagesToScan();
        bean.setPersistenceUnitPostProcessors(pui -> pui.addManagedClassName(PushdownSampleEntity.class.getName()));
        bean.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.archive.autodetection", "none",
                "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"
        ));
        bean.afterPropertiesSet();

        emfBean = bean;
        emf = bean.getObject();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(emf);
        tx = new TransactionTemplate(new JpaTransactionManager(emf));

        seedDataset();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        try {
            if (emfBean != null) {
                emfBean.destroy();
            }
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
            if (postgres != null) {
                postgres.close();
            }
        }
    }

    private void seedDataset() {
        tx.executeWithoutResult(status -> {
            // Cohort "main-metrics": 24 rows across 2026-01..2026-03 (> maxInMemorySampleRows=5)
            // Includes Instant crossing UTC month boundary (2026-03-01T01:30:00Z == 2026-02-28 22:30 in America/Sao_Paulo).
            for (int i = 0; i < 10; i++) {
                LocalDate day = LocalDate.of(2026, 1, 5 + (i % 2));
                persist(
                        "main-metrics",
                        i % 2 == 0 ? "CAT_A" : "CAT_B",
                        day,
                        day.atTime(10, i),
                        day.atTime(14, 0).toInstant(ZoneOffset.UTC),
                        BigDecimal.valueOf(100 + (i * 10L))
                );
            }
            // Boundary row: 2026-03-01T01:30:00Z -> 2026-02-28T22:30:00-03:00 in America/Sao_Paulo
            persist(
                    "main-metrics",
                    "CAT_C",
                    LocalDate.of(2026, 2, 28),
                    LocalDateTime.of(2026, 2, 28, 22, 30),
                    Instant.parse("2026-03-01T01:30:00Z"),
                    BigDecimal.valueOf(250)
            );
            for (int i = 0; i < 7; i++) {
                LocalDate day = LocalDate.of(2026, 2, 10 + i);
                persist(
                        "main-metrics",
                        "CAT_D",
                        day,
                        day.atTime(12, 0),
                        day.atTime(15, 0).toInstant(ZoneOffset.UTC),
                        BigDecimal.valueOf(50)
                );
            }
            for (int i = 0; i < 6; i++) {
                LocalDate day = LocalDate.of(2026, 3, 12 + i);
                persist(
                        "main-metrics",
                        i < 3 ? "CAT_E" : "CAT_F",
                        day,
                        day.atTime(9, 0),
                        day.atTime(18, 0).toInstant(ZoneOffset.UTC),
                        BigDecimal.valueOf(200)
                );
            }

            // Cohort "sdc-fill-visible-pool": Jan=10, Feb=0 (gap), Mar=2, Apr=4 (sum=6 >= 2 + 5 - 1)
            for (int i = 0; i < 10; i++) {
                persist("sdc-fill-visible-pool", "P1", LocalDate.of(2026, 1, 10 + i), null, null, BigDecimal.TEN);
            }
            for (int i = 0; i < 2; i++) {
                persist("sdc-fill-visible-pool", "P2", LocalDate.of(2026, 3, 4 + i), null, null, BigDecimal.ONE);
            }
            for (int i = 0; i < 4; i++) {
                persist("sdc-fill-visible-pool", "P3", LocalDate.of(2026, 4, 4 + i), null, null, BigDecimal.ONE);
            }

            // Cohort "sdc-fill-hidden-pool": Jan=10, Mar=2, Apr=3 (sum=5 < 2 + 5 - 1 -> hides pool/total)
            for (int i = 0; i < 10; i++) {
                persist("sdc-fill-hidden-pool", "H1", LocalDate.of(2026, 1, 10 + i), null, null, BigDecimal.TEN);
            }
            for (int i = 0; i < 2; i++) {
                persist("sdc-fill-hidden-pool", "H2", LocalDate.of(2026, 3, 4 + i), null, null, BigDecimal.ONE);
            }
            for (int i = 0; i < 3; i++) {
                persist("sdc-fill-hidden-pool", "H3", LocalDate.of(2026, 4, 4 + i), null, null, BigDecimal.ONE);
            }

            // Cohort "rolling-36": relative to today so test remains deterministic across calendar dates
            LocalDate recentTwoMonthsAgo = LocalDate.now(ZoneOffset.UTC).minusMonths(2).withDayOfMonth(10);
            LocalDate recentOneMonthAgo = LocalDate.now(ZoneOffset.UTC).minusMonths(1).withDayOfMonth(12);
            for (int i = 0; i < 6; i++) {
                persist("rolling-36", "R1", recentTwoMonthsAgo, recentTwoMonthsAgo.atTime(10, i),
                        recentTwoMonthsAgo.atTime(12, i).toInstant(ZoneOffset.UTC), BigDecimal.valueOf(10));
                persist("rolling-36", "R2", recentOneMonthAgo, recentOneMonthAgo.atTime(10, i),
                        recentOneMonthAgo.atTime(12, i).toInstant(ZoneOffset.UTC), BigDecimal.valueOf(20));
            }
        });
    }

    private void persist(
            String cohort,
            String category,
            LocalDate eventDate,
            LocalDateTime eventDateTime,
            Instant eventInstant,
            BigDecimal amount
    ) {
        PushdownSampleEntity entity = new PushdownSampleEntity();
        entity.setCohort(cohort);
        entity.setCategory(category);
        entity.setEventDate(eventDate);
        entity.setEventDateTime(eventDateTime);
        entity.setEventInstant(eventInstant);
        entity.setAmount(amount);
        entityManager.persist(entity);
    }

    private static Specification<PushdownSampleEntity> byCohort(String cohort) {
        return (root, query, cb) -> cb.equal(root.get("cohort"), cohort);
    }

    @Test
    void executesSqlPushdownAcrossTemporalTypesGranularitiesAndMetricsAboveSampleLimit() {
        StatsFieldDescriptor amountDesc = StatsFieldDescriptor.metricField(
                "amount",
                "amount",
                Set.of(StatsMetric.SUM, StatsMetric.AVG, StatsMetric.MIN, StatsMetric.MAX)
        );
        StatsFieldDescriptor categoryDistinctDesc = StatsFieldDescriptor.distinctCountField("category", "category");

        List<ResolvedStatsMetric> allMetrics = List.of(
                new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.SUM, "amount", "sumAmount"), amountDesc),
                new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.AVG, "amount", "avgAmount"), amountDesc),
                new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.MIN, "amount", "minAmount"), amountDesc),
                new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.MAX, "amount", "maxAmount"), amountDesc),
                new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.DISTINCT_COUNT, "category", "distinctCats"), categoryDistinctDesc),
                new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.COUNT, null, "rowCount"), null)
        );

        // maxInMemorySampleRows = 5 is intentionally lower than the 24 matching rows to prove SQL pushdown!
        TimeSeriesStatsResponse instantMonthly = executor.executeTimeSeries(
                entityManager,
                PushdownSampleEntity.class,
                byCohort("main-metrics"),
                StatsFieldDescriptor.timeSeriesField("eventInstant", "eventInstant"),
                allMetrics,
                new TimeSeriesStatsRequest<>(
                        new EmptyFilter(),
                        "eventInstant",
                        TimeSeriesGranularity.MONTH,
                        null,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 3, 31),
                        false,
                        List.of(
                                new StatsMetricRequest(StatsMetric.SUM, "amount", "sumAmount"),
                                new StatsMetricRequest(StatsMetric.AVG, "amount", "avgAmount"),
                                new StatsMetricRequest(StatsMetric.MIN, "amount", "minAmount"),
                                new StatsMetricRequest(StatsMetric.MAX, "amount", "maxAmount"),
                                new StatsMetricRequest(StatsMetric.DISTINCT_COUNT, "category", "distinctCats"),
                                new StatsMetricRequest(StatsMetric.COUNT, null, "rowCount")
                        ),
                        "America/Sao_Paulo",
                        null
                ),
                120,
                365,
                0,
                5
        );

        assertEquals(3, instantMonthly.points().size());
        TimeSeriesPoint jan = instantMonthly.points().get(0);
        TimeSeriesPoint feb = instantMonthly.points().get(1);
        TimeSeriesPoint mar = instantMonthly.points().get(2);

        // Jan: 10 rows (100..190 => sum 1450, avg 145, min 100, max 190, 2 distinct categories)
        assertEquals(LocalDate.of(2026, 1, 1), jan.start());
        assertEquals(10L, jan.count());
        assertEquals(1450d, jan.values().get("sumAmount").doubleValue(), 0.001);
        assertEquals(145d, jan.values().get("avgAmount").doubleValue(), 0.001);
        assertEquals(100d, jan.values().get("minAmount").doubleValue(), 0.001);
        assertEquals(190d, jan.values().get("maxAmount").doubleValue(), 0.001);
        assertEquals(2L, jan.values().get("distinctCats").longValue());

        // Feb: 7 rows + 2026-03-01T01:30:00Z (22:30-03:00 on Feb 28) = 8 rows, sum = 7*50 + 250 = 600
        assertEquals(LocalDate.of(2026, 2, 1), feb.start());
        assertEquals(8L, feb.count());
        assertEquals(600d, feb.values().get("sumAmount").doubleValue(), 0.001);
        assertEquals(2L, feb.values().get("distinctCats").longValue());

        // Mar: 6 rows (18:00 UTC is 15:00-03:00), sum = 1200
        assertEquals(LocalDate.of(2026, 3, 1), mar.start());
        assertEquals(6L, mar.count());
        assertEquals(1200d, mar.values().get("sumAmount").doubleValue(), 0.001);

        // LocalDate + WEEK granularity with maxInMemorySampleRows = 5
        TimeSeriesStatsResponse localDateWeekly = executor.executeTimeSeries(
                entityManager,
                PushdownSampleEntity.class,
                byCohort("main-metrics"),
                StatsFieldDescriptor.timeSeriesField("eventDate", "eventDate"),
                List.of(new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.COUNT, null), null)),
                new TimeSeriesStatsRequest<>(
                        new EmptyFilter(),
                        "eventDate",
                        TimeSeriesGranularity.WEEK,
                        new StatsMetricRequest(StatsMetric.COUNT, null),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 3, 31),
                        false,
                        null,
                        "UTC"
                ),
                120,
                365,
                0,
                5
        );
        assertEquals(24L, localDateWeekly.points().stream().mapToLong(TimeSeriesPoint::countOrZero).sum());

        // LocalDateTime + DAY granularity with maxInMemorySampleRows = 5
        TimeSeriesStatsResponse localDateTimeDaily = executor.executeTimeSeries(
                entityManager,
                PushdownSampleEntity.class,
                byCohort("main-metrics"),
                StatsFieldDescriptor.timeSeriesField("eventDateTime", "eventDateTime"),
                List.of(new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.COUNT, null), null)),
                new TimeSeriesStatsRequest<>(
                        new EmptyFilter(),
                        "eventDateTime",
                        TimeSeriesGranularity.DAY,
                        new StatsMetricRequest(StatsMetric.COUNT, null),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 3, 31),
                        false,
                        null,
                        "UTC"
                ),
                120,
                365,
                0,
                5
        );
        assertEquals(24L, localDateTimeDaily.points().stream().mapToLong(TimeSeriesPoint::countOrZero).sum());
    }

    @Test
    void emitsSuppressedFillGapsSlotsEnforcesSdcPartitionGuardAndResolvesRollingWindow() {
        List<ResolvedStatsMetric> countOnly = List.of(
                new ResolvedStatsMetric(new StatsMetricRequest(StatsMetric.COUNT, null), null)
        );

        // Visible pool case: Jan=10, Feb=0(gap), Mar=2, Apr=4 (sum=6 >= 2 + 5 - 1)
        TimeSeriesStatsResponse visiblePool = executor.executeTimeSeries(
                entityManager,
                PushdownSampleEntity.class,
                byCohort("sdc-fill-visible-pool"),
                StatsFieldDescriptor.timeSeriesField("eventDate", "eventDate"),
                countOnly,
                new TimeSeriesStatsRequest<>(
                        new EmptyFilter(),
                        "eventDate",
                        TimeSeriesGranularity.MONTH,
                        new StatsMetricRequest(StatsMetric.COUNT, null),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 4, 30),
                        true,
                        null,
                        "UTC"
                ),
                120,
                365,
                5,
                5
        );

        assertEquals(Boolean.TRUE, visiblePool.suppressed());
        assertEquals(2, visiblePool.suppressedBucketCount());
        assertEquals(5, visiblePool.minBucketCount());
        assertEquals(16L, visiblePool.populationTotalCount());
        assertEquals(6L, visiblePool.suppressedPoolCount());
        assertEquals(4, visiblePool.points().size());

        TimeSeriesPoint jan = visiblePool.points().get(0);
        TimeSeriesPoint febGap = visiblePool.points().get(1);
        TimeSeriesPoint marSuppressed = visiblePool.points().get(2);
        TimeSeriesPoint aprSuppressed = visiblePool.points().get(3);

        assertEquals(10L, jan.count());
        assertNull(jan.suppressed());

        assertEquals(0L, febGap.count());
        assertNull(febGap.suppressed());

        assertNull(marSuppressed.count());
        assertNull(marSuppressed.value());
        assertEquals(0L, marSuppressed.countOrZero());
        assertEquals(Boolean.TRUE, marSuppressed.suppressed());

        assertNull(aprSuppressed.count());
        assertNull(aprSuppressed.value());
        assertEquals(0L, aprSuppressed.countOrZero());
        assertEquals(Boolean.TRUE, aprSuppressed.suppressed());

        // Hidden pool case under SDC guard: Jan=10, Mar=2, Apr=3 (sum=5 < 2 + 5 - 1 = 6)
        TimeSeriesStatsResponse hiddenPool = executor.executeTimeSeries(
                entityManager,
                PushdownSampleEntity.class,
                byCohort("sdc-fill-hidden-pool"),
                StatsFieldDescriptor.timeSeriesField("eventDate", "eventDate"),
                countOnly,
                new TimeSeriesStatsRequest<>(
                        new EmptyFilter(),
                        "eventDate",
                        TimeSeriesGranularity.MONTH,
                        new StatsMetricRequest(StatsMetric.COUNT, null),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 4, 30),
                        false,
                        null,
                        "UTC"
                ),
                120,
                365,
                5,
                5
        );

        assertEquals(2, hiddenPool.suppressedBucketCount());
        assertNull(hiddenPool.populationTotalCount());
        assertNull(hiddenPool.suppressedPoolCount());

        // RollingWindowRequest(MONTHS, 36)
        TimeSeriesStatsResponse rolling = executor.executeTimeSeries(
                entityManager,
                PushdownSampleEntity.class,
                byCohort("rolling-36"),
                StatsFieldDescriptor.timeSeriesField("eventInstant", "eventInstant"),
                countOnly,
                new TimeSeriesStatsRequest<>(
                        new EmptyFilter(),
                        "eventInstant",
                        TimeSeriesGranularity.MONTH,
                        new StatsMetricRequest(StatsMetric.COUNT, null),
                        null,
                        null,
                        true,
                        null,
                        "America/Sao_Paulo",
                        new RollingWindowRequest(RollingWindowUnit.MONTHS, 36)
                ),
                120,
                365,
                5,
                5
        );

        assertFalse(rolling.points().isEmpty());
        assertNotNull(rolling.points().get(0).start());
        assertNotNull(rolling.points().get(rolling.points().size() - 1).end());
        long totalRollingRows = rolling.points().stream().mapToLong(TimeSeriesPoint::countOrZero).sum();
        assertEquals(12L, totalRollingRows);
        assertTrue(rolling.points().size() >= 36);
    }

    static class EmptyFilter implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO {
    }

    @Entity
    @Table(name = "ts_pushdown_sample")
    static class PushdownSampleEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private String cohort;

        @Column(nullable = false)
        private String category;

        private LocalDate eventDate;
        private LocalDateTime eventDateTime;
        private Instant eventInstant;

        @Column(precision = 14, scale = 2)
        private BigDecimal amount;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getCohort() { return cohort; }
        public void setCohort(String cohort) { this.cohort = cohort; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public LocalDate getEventDate() { return eventDate; }
        public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
        public LocalDateTime getEventDateTime() { return eventDateTime; }
        public void setEventDateTime(LocalDateTime eventDateTime) { this.eventDateTime = eventDateTime; }
        public Instant getEventInstant() { return eventInstant; }
        public void setEventInstant(Instant eventInstant) { this.eventInstant = eventInstant; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}
