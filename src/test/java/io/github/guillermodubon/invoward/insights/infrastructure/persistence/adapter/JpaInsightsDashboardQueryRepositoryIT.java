package io.github.guillermodubon.invoward.insights.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DashboardAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardDataQualityCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardTopSupplier;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPeriod;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKeyAggregate;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierCurrencyAmounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierFinancialDataQualityCounts;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierListQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierFinancialQueryRepository;
import io.github.guillermodubon.invoward.insights.application.service.ListSuppliersService;
import io.github.guillermodubon.invoward.insights.application.service.GetDashboardSummaryService;
import io.github.guillermodubon.invoward.insights.application.port.InsightsDashboardQueryRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
@Transactional
class JpaInsightsDashboardQueryRepositoryIT {

    private static final InsightsPeriod OCTOBER = new InsightsPeriod(
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-01"));
    private static final Instant OCTOBER_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant OCTOBER_END_MINUS_MICRO = Instant.parse("2026-10-31T23:59:59.999999Z");

    @Autowired
    private InsightsDashboardQueryRepository dashboardRepository;

    @Autowired
    private InsightsSupplierListQueryRepository supplierRepository;

    @Autowired
    private InsightsSupplierFinancialQueryRepository supplierFinancialRepository;

    @Autowired
    private ListSuppliersService listSuppliersService;

    @Autowired
    private GetDashboardSummaryService getDashboardSummaryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void ownerScopedAggregatesUseUtcWindowKeepCurrenciesSeparateAndDoNotMultiplyMoneyByDiscrepancies()
            throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        UUID guestSessionId = insertGuestSession();

        UUID matchedUsd = insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "ACME Supplies", "acme supplies", "USD", "100.0000", "0.0000",
                Instant.parse("2026-09-30T12:00:00Z"), OCTOBER_START);
        UUID reviewUsd = insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Acme Older Label", "acme supplies", "USD", "200.0000", "20.0000",
                Instant.parse("2026-10-02T12:00:00Z"), Instant.parse("2026-10-02T12:00:00Z"));
        UUID reviewEur = insertAnalysis(ownerId, null, "COMPLETED", "REVIEWED", "REVIEW_REQUIRED",
                "Beta Store", "beta", "EUR", "50.0000", "-5.0000",
                Instant.parse("2026-10-03T12:00:00Z"), Instant.parse("2026-10-03T12:00:00Z"));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Beta Store", "beta", "EUR", "70.0000", "0.0000",
                Instant.parse("2026-10-31T12:00:00Z"), OCTOBER_END_MINUS_MICRO);
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                null, null, null, null, null,
                Instant.parse("2026-10-04T12:00:00Z"), Instant.parse("2026-10-10T12:00:00Z"));
        UUID matchedWithinTolerance = insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                null, null, "USD", "50.0000", "0.2500",
                Instant.parse("2026-10-08T12:00:00Z"), Instant.parse("2026-10-09T12:00:00Z"));
        setReferenceTotalAndTolerance(matchedWithinTolerance, "49.7500", "1.0000");
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Beta Store", "beta", "EUR", "40.0000", "0.0000",
                Instant.parse("2026-10-11T12:00:00Z"), Instant.parse("2026-10-11T12:00:00Z"));

        insertAnalysis(ownerId, null, "FAILED", "PENDING", null,
                null, null, null, null, null,
                Instant.parse("2026-10-15T12:00:00Z"), null);
        insertAnalysis(ownerId, null, "FAILED", "PENDING", null,
                null, null, null, null, null,
                Instant.parse("2026-09-30T23:59:59Z"), null);
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", null,
                "Incomplete", "incomplete", "USD", "900.0000", null,
                Instant.parse("2026-10-05T12:00:00Z"), Instant.parse("2026-10-06T12:00:00Z"));
        insertAnalysis(ownerId, null, "EXTRACTING", "PENDING", null,
                "Processing", "processing", "USD", "800.0000", null,
                Instant.parse("2026-10-06T12:00:00Z"), Instant.parse("2026-10-07T12:00:00Z"));
        insertAnalysis(ownerId, null, "COMPLETED", "REVIEWED", "MATCHED",
                "ACME Latest Label", "acme supplies", "USD", "120.0000", "0.0000",
                Instant.parse("2026-10-30T12:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));

        UUID otherOwnerAnalysis = insertAnalysis(otherOwnerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Other ACME", "acme supplies", "USD", "900.0000", "700.0000",
                Instant.parse("2026-10-02T12:00:00Z"), Instant.parse("2026-10-03T12:00:00Z"));
        UUID guestAnalysis = insertAnalysis(null, guestSessionId, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Guest ACME", "acme supplies", "USD", "700.0000", "600.0000",
                Instant.parse("2026-10-02T12:00:00Z"), Instant.parse("2026-10-03T12:00:00Z"));

        insertDiscrepancy(reviewUsd, DiscrepancyType.UNIT_PRICE_MISMATCH, "RESOLVED");
        insertDiscrepancy(reviewUsd, DiscrepancyType.TAX_MISMATCH);
        insertDiscrepancy(reviewEur, DiscrepancyType.UNIT_PRICE_MISMATCH);
        insertDiscrepancy(otherOwnerAnalysis, DiscrepancyType.TOTAL_MISMATCH);
        insertDiscrepancy(guestAnalysis, DiscrepancyType.TOTAL_MISMATCH);

        assertEquals(new DashboardAggregateCounts(7, 3, 4, 3),
                dashboardRepository.fetchCompletedCounts(ownerId, OCTOBER));
        assertEquals(1, dashboardRepository.fetchFailedCountByCreationPeriod(ownerId, OCTOBER));
        assertEquals(List.of(
                        new CurrencyAmount("EUR", new BigDecimal("160.0000"), 3),
                        new CurrencyAmount("USD", new BigDecimal("350.0000"), 3)),
                dashboardRepository.fetchTotalsByCurrency(ownerId, OCTOBER));
        assertEquals(List.of(
                        new CurrencyAmount("EUR", new BigDecimal("5.0000"), 2),
                        new CurrencyAmount("USD", new BigDecimal("20.0000"), 1)),
                dashboardRepository.fetchPotentialDifferencesByCurrency(ownerId, OCTOBER));
        assertEquals(new DashboardDataQualityCounts(1, 1, 2),
                dashboardRepository.fetchMissingDataCounts(ownerId, OCTOBER));

        var summary = getDashboardSummaryService.get(
                ownerId, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));
        assertEquals(OCTOBER, summary.period());
        assertEquals(new DashboardAggregateCounts(7, 3, 4, 3), summary.counts());
        assertEquals(new BigDecimal("42.86"), summary.matchedPercent());
        assertEquals(1, summary.failedCount());
        assertEquals(List.of(
                        new CurrencyAmount("EUR", new BigDecimal("5.0000"), 2),
                        new CurrencyAmount("USD", new BigDecimal("20.0000"), 1)),
                summary.potentialDifferencesByCurrency());
        assertEquals(List.of(
                        new DiscrepancyTypeFrequency(DiscrepancyType.UNIT_PRICE_MISMATCH, 2),
                        new DiscrepancyTypeFrequency(DiscrepancyType.TAX_MISMATCH, 1)),
                summary.commonDiscrepancyTypes());

        List<DashboardTopSupplier> topSuppliers = dashboardRepository.fetchTopSuppliers(ownerId, OCTOBER, 5);
        assertEquals(List.of(new SupplierKey("beta"), new SupplierKey("acme supplies")),
                topSuppliers.stream().map(DashboardTopSupplier::supplierKey).toList());
        assertEquals("Beta Store", topSuppliers.getFirst().supplierName());
        assertEquals(List.of(3L, 2L), topSuppliers.stream().map(DashboardTopSupplier::completedAnalyses).toList());

        assertEquals(List.of(
                        new DiscrepancyTypeFrequency(DiscrepancyType.UNIT_PRICE_MISMATCH, 2),
                        new DiscrepancyTypeFrequency(DiscrepancyType.TAX_MISMATCH, 1)),
                dashboardRepository.fetchCommonDiscrepancyTypes(ownerId, OCTOBER, 5));
    }

    @Test
    void emptyAccountGetsZeroCountsEmptyBreakdownsAndNoFailedRows() throws Exception {
        UUID ownerId = insertUser();

        assertEquals(new DashboardAggregateCounts(0, 0, 0, 0),
                dashboardRepository.fetchCompletedCounts(ownerId, OCTOBER));
        assertEquals(0, dashboardRepository.fetchFailedCountByCreationPeriod(ownerId, OCTOBER));
        assertTrue(dashboardRepository.fetchTotalsByCurrency(ownerId, OCTOBER).isEmpty());
        assertTrue(dashboardRepository.fetchPotentialDifferencesByCurrency(ownerId, OCTOBER).isEmpty());
        assertTrue(dashboardRepository.fetchTopSuppliers(ownerId, OCTOBER, 5).isEmpty());
        assertTrue(dashboardRepository.fetchCommonDiscrepancyTypes(ownerId, OCTOBER, 5).isEmpty());
        assertEquals(new DashboardDataQualityCounts(0, 0, 0),
                dashboardRepository.fetchMissingDataCounts(ownerId, OCTOBER));
    }

    @Test
    void topSuppliersAndDiscrepancyTypesAreBoundedToFiveWithStableTies() throws Exception {
        UUID ownerId = insertUser();
        List<String> supplierKeys = new ArrayList<>();
        List<UUID> analysisIds = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            String key = "supplier-" + index;
            supplierKeys.add(key);
            analysisIds.add(insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                    "Supplier " + index, key, "USD", "1.0000", "0.0000",
                    OCTOBER_START, OCTOBER_START.plusSeconds(index)));
        }
        List<DiscrepancyType> insertedTypes = java.util.Arrays.stream(DiscrepancyType.values())
                .limit(6)
                .toList();
        for (int index = 0; index < insertedTypes.size(); index++) {
            insertDiscrepancy(analysisIds.get(index), insertedTypes.get(index));
        }

        List<DashboardTopSupplier> suppliers = dashboardRepository.fetchTopSuppliers(ownerId, OCTOBER, 5);
        assertEquals(5, suppliers.size());
        assertEquals(supplierKeys.subList(0, 5),
                suppliers.stream().map(row -> row.supplierKey().value()).toList());
        assertTrue(suppliers.stream().allMatch(row -> row.completedAnalyses() == 1));

        List<DiscrepancyTypeFrequency> types = dashboardRepository.fetchCommonDiscrepancyTypes(ownerId, OCTOBER, 5);
        List<DiscrepancyType> expectedTypes = insertedTypes.stream()
                .sorted(java.util.Comparator.comparing(DiscrepancyType::name))
                .limit(5)
                .toList();
        assertEquals(5, types.size());
        assertEquals(expectedTypes, types.stream().map(DiscrepancyTypeFrequency::type).toList());
        assertTrue(types.stream().allMatch(row -> row.count() == 1));
    }

    @Test
    void supplierDisplayUsesLatestOwnedNonblankNameAndFallsBackWithoutLeakingForeignOrBlankData()
            throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        String sharedKey = "acme/sons & co. (東京)";

        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Owner earlier label", sharedKey, "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(10));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                " \t ", sharedKey, "USD", "10.0000", "0.0000",
                OCTOBER_START.plusSeconds(20), OCTOBER_START.plusSeconds(20));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                null, "fallback-key", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(30));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Unassigned display", null, "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(40));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Blank key", "   ", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(50));

        insertAnalysis(otherOwnerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Foreign newest label", sharedKey, "USD", "900.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(60));
        insertAnalysis(otherOwnerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Foreign-only supplier", "foreign-only", "USD", "900.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(70));

        List<DashboardTopSupplier> suppliers = dashboardRepository.fetchTopSuppliers(ownerId, OCTOBER, 5);

        assertEquals(2, suppliers.size());
        assertEquals("Owner earlier label", suppliers.stream()
                .filter(row -> row.supplierKey().value().equals(sharedKey))
                .findFirst().orElseThrow().supplierName());
        assertEquals("fallback-key", suppliers.stream()
                .filter(row -> row.supplierKey().value().equals("fallback-key"))
                .findFirst().orElseThrow().supplierName());
        assertTrue(suppliers.stream().noneMatch(row -> row.supplierKey().value().equals("foreign-only")));
        assertTrue(suppliers.stream().noneMatch(row -> row.supplierKey().value().isBlank()));
    }

    @Test
    void supplierGroupsAreOwnerScopedAndIncludeOnlyCompletedReconciledCanonicalKeys() throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        UUID guestSessionId = insertGuestSession();

        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Acme Older Name", "acme", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(10));
        insertAnalysis(ownerId, null, "COMPLETED", "REVIEWED", "REVIEW_REQUIRED",
                "Acme Latest Name", "acme", "USD", "20.0000", "2.0000",
                OCTOBER_START.plusSeconds(1), OCTOBER_START.plusSeconds(20));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Beta Supplier", "beta", "EUR", "30.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(30));
        insertAnalysis(ownerId, null, "CREATED", "PENDING", null,
                "Draft Supplier", "draft", "USD", null, null,
                OCTOBER_START, null);
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Unassigned", null, "USD", "40.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(40));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Blank key", " \t ", "USD", "50.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(50));

        insertAnalysis(otherOwnerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Foreign Acme", "acme", "USD", "900.0000", "800.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(60));
        insertAnalysis(otherOwnerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Foreign Only", "foreign-only", "USD", "900.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(70));
        insertAnalysis(null, guestSessionId, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Guest Acme", "acme", "USD", "700.0000", "600.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(80));
        insertAnalysis(null, guestSessionId, "COMPLETED", "PENDING", "MATCHED",
                "Guest Only", "guest-only", "USD", "700.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(90));

        InsightsPage<SupplierKeyAggregate> page = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(null, null, null, null));

        assertEquals(2, page.totalElements());
        assertEquals(List.of(new SupplierKey("acme"), new SupplierKey("beta")),
                page.content().stream().map(SupplierKeyAggregate::supplierKey).toList());
        assertEquals("Acme Latest Name", page.content().getFirst().supplierName());
        assertEquals(2, page.content().getFirst().completedAnalyses());
        assertEquals(1, page.content().getFirst().analysesWithDifferences());
        assertEquals("Beta Supplier", page.content().get(1).supplierName());
        assertTrue(page.content().stream().noneMatch(row -> row.supplierKey().value().startsWith("guest")));
        assertTrue(page.content().stream().noneMatch(row -> row.supplierKey().value().equals("foreign-only")));
    }

    @Test
    void supplierGroupSearchTreatsLikeWildcardsAndEscapeCharacterLiterallyAndCountsAfterFiltering()
            throws Exception {
        UUID ownerId = insertUser();
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "ACME_100%!Store", "acme_100%!store", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(10));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "AcmeX100YStore", "acmex100ystore", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(20));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Other Supplier", "other", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(30));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Unique Display Vendor", "key-without-search-terms", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(40));

        InsightsPage<SupplierKeyAggregate> literalWildcards = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(0, 20, "_100%!", null));
        InsightsPage<SupplierKeyAggregate> literalEscapeCharacter = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(0, 20, "!", null));
        InsightsPage<SupplierKeyAggregate> latestDisplayNameSearch = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(0, 20, "UNIQUE DISPLAY", null));
        InsightsPage<SupplierKeyAggregate> unknown = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(0, 20, "no supplier", null));

        assertEquals(1, literalWildcards.totalElements());
        assertEquals("acme_100%!store", literalWildcards.content().getFirst().supplierKey().value());
        assertEquals(1, literalEscapeCharacter.totalElements());
        assertEquals("acme_100%!store", literalEscapeCharacter.content().getFirst().supplierKey().value());
        assertEquals(1, latestDisplayNameSearch.totalElements());
        assertEquals("key-without-search-terms", latestDisplayNameSearch.content().getFirst()
                .supplierKey().value());
        assertEquals(0, unknown.totalElements());
        assertTrue(unknown.content().isEmpty());
    }

    @Test
    void supplierGroupPagingAndWhitelistedSortsHaveStableKeyTieBreaksAndFilteredTotals() throws Exception {
        UUID ownerId = insertUser();
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Same Label", "zeta", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(10));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Same Label", "alpha", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(20));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Same Label", "alpha", "USD", "10.0000", "0.0000",
                OCTOBER_START.plusSeconds(1), OCTOBER_START.plusSeconds(25));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Beta Label", "beta", "USD", "10.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(30));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Same Label", "zeta", "USD", "10.0000", "1.0000",
                OCTOBER_START.plusSeconds(1), OCTOBER_START.plusSeconds(40));

        InsightsPage<SupplierKeyAggregate> defaultFirstPage = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(0, 2, null, null));
        InsightsPage<SupplierKeyAggregate> defaultSecondPage = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(1, 2, null, null));
        InsightsPage<SupplierKeyAggregate> ascendingCount = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(0, 20, null, "analysisCount,asc"));
        InsightsPage<SupplierKeyAggregate> ascendingName = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(0, 20, null, "supplierName,asc"));
        InsightsPage<SupplierKeyAggregate> descendingName = supplierRepository.pageSupplierKeys(
                ownerId, SupplierListQuery.of(0, 20, null, "supplierName,desc"));

        assertEquals(3, defaultFirstPage.totalElements());
        assertEquals(List.of("alpha", "zeta"), defaultFirstPage.content().stream()
                .map(row -> row.supplierKey().value()).toList());
        assertEquals(List.of("beta"), defaultSecondPage.content().stream()
                .map(row -> row.supplierKey().value()).toList());
        assertEquals(List.of("beta", "alpha", "zeta"), ascendingCount.content().stream()
                .map(row -> row.supplierKey().value()).toList());
        assertEquals(List.of("beta", "alpha", "zeta"), ascendingName.content().stream()
                .map(row -> row.supplierKey().value()).toList());
        assertEquals(List.of("alpha", "zeta", "beta"), descendingName.content().stream()
                .map(row -> row.supplierKey().value()).toList());
    }

    @Test
    void supplierFinancialAggregatesAreBatchedOwnerScopedCurrencySpecificAndUnaffectedByDiscrepancies()
            throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        UUID guestSessionId = insertGuestSession();
        String sharedKey = "shared-supplier";
        SupplierKey sharedSupplier = new SupplierKey(sharedKey);
        SupplierKey betaSupplier = new SupplierKey("beta-supplier");

        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                "Shared Supplier", sharedKey, "USD", "100.0000", "0.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(1));
        UUID reviewUsd = insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Shared Supplier", sharedKey, "USD", "200.0000", "-20.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(2));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Shared Supplier", sharedKey, "EUR", "50.0000", "5.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(3));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Shared Supplier", sharedKey, null, "75.0000", null,
                OCTOBER_START, OCTOBER_START.plusSeconds(4));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Shared Supplier", sharedKey, "USD", null, "-2.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(5));
        insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Beta Supplier", betaSupplier.value(), "USD", "8.0000", "-3.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(6));

        insertAnalysis(otherOwnerId, null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Foreign Shared Supplier", sharedKey, "USD", "900.0000", "800.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(7));
        insertAnalysis(null, guestSessionId, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Guest Shared Supplier", sharedKey, "USD", "700.0000", "600.0000",
                OCTOBER_START, OCTOBER_START.plusSeconds(8));

        insertDiscrepancy(reviewUsd, DiscrepancyType.UNIT_PRICE_MISMATCH);
        insertDiscrepancy(reviewUsd, DiscrepancyType.TAX_MISMATCH);
        insertDiscrepancy(reviewUsd, DiscrepancyType.DISCOUNT_MISMATCH);

        List<SupplierKey> pageKeys = supplierRepository.pageSupplierKeys(
                        ownerId, SupplierListQuery.of(0, 100, null, null))
                .content().stream()
                .map(SupplierKeyAggregate::supplierKey)
                .toList();

        assertEquals(List.of(sharedSupplier, betaSupplier), pageKeys);
        assertEquals(List.of(
                        new SupplierCurrencyAmounts(betaSupplier, List.of(
                                new CurrencyAmount("USD", new BigDecimal("8.0000"), 1))),
                        new SupplierCurrencyAmounts(sharedSupplier, List.of(
                                new CurrencyAmount("EUR", new BigDecimal("50.0000"), 1),
                                new CurrencyAmount("USD", new BigDecimal("300.0000"), 2)))),
                supplierFinancialRepository.fetchSupplierCurrencyAmounts(ownerId, pageKeys));
        assertEquals(List.of(
                        new SupplierCurrencyAmounts(betaSupplier, List.of(
                                new CurrencyAmount("USD", new BigDecimal("3.0000"), 1))),
                        new SupplierCurrencyAmounts(sharedSupplier, List.of(
                                new CurrencyAmount("EUR", new BigDecimal("5.0000"), 1),
                                new CurrencyAmount("USD", new BigDecimal("22.0000"), 2)))),
                supplierFinancialRepository.fetchSupplierPotentialDifferencesByCurrency(ownerId, pageKeys));
        assertEquals(List.of(
                        new SupplierFinancialDataQualityCounts(betaSupplier, 0, 0),
                        new SupplierFinancialDataQualityCounts(sharedSupplier, 1, 1)),
                supplierFinancialRepository.fetchSupplierMissingFinancialDataCounts(ownerId, pageKeys));
    }

    @Test
    void supplierFinancialBatchQueriesReturnEmptyForNoKeysAndRejectOversizedOrDuplicateBatches() throws Exception {
        UUID ownerId = insertUser();

        assertTrue(supplierFinancialRepository.fetchSupplierCurrencyAmounts(ownerId, List.of()).isEmpty());
        assertTrue(supplierFinancialRepository.fetchSupplierPotentialDifferencesByCurrency(ownerId, List.of())
                .isEmpty());
        assertTrue(supplierFinancialRepository.fetchSupplierMissingFinancialDataCounts(ownerId, List.of())
                .isEmpty());

        List<SupplierKey> oversized = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> new SupplierKey("supplier-" + index))
                .toList();
        InvalidDataAccessApiUsageException oversizedFailure = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> supplierFinancialRepository.fetchSupplierCurrencyAmounts(ownerId, oversized));
        assertInstanceOf(IllegalArgumentException.class, oversizedFailure.getCause());
        InvalidDataAccessApiUsageException duplicateFailure = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> supplierFinancialRepository.fetchSupplierPotentialDifferencesByCurrency(
                        ownerId, List.of(new SupplierKey("duplicate"), new SupplierKey("duplicate"))));
        assertInstanceOf(IllegalArgumentException.class, duplicateFailure.getCause());
    }

    @Test
    void supplierListingServicePagesMoreThanOneHundredGroupsAndEnrichesOnlyEachPage() throws Exception {
        UUID ownerId = insertUser();
        for (int index = 0; index < 101; index++) {
            String supplierKey = "supplier-%03d".formatted(index);
            insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                    "Supplier " + index, supplierKey, "USD", "1.0000", "0.0000",
                    OCTOBER_START, OCTOBER_START.plusSeconds(index));
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            var firstPage = listSuppliersService.list(
                    ownerId, SupplierListQuery.of(0, 100, null, null));
            long firstPageStatements = statistics.getPrepareStatementCount();

            statistics.clear();
            var secondPage = listSuppliersService.list(
                    ownerId, SupplierListQuery.of(1, 100, null, null));
            long secondPageStatements = statistics.getPrepareStatementCount();

            assertEquals(101, firstPage.page().totalElements());
            assertEquals(100, firstPage.page().content().size());
            assertEquals("supplier-000", firstPage.page().content().getFirst().supplierKey().value());
            assertEquals("supplier-099", firstPage.page().content().getLast().supplierKey().value());
            assertEquals(List.of(new CurrencyAmount("USD", new BigDecimal("1.0000"), 1)),
                    firstPage.page().content().getFirst().totalInvoicedByCurrency());
            assertEquals(101, secondPage.page().totalElements());
            assertEquals(1, secondPage.page().content().size());
            assertEquals("supplier-100", secondPage.page().content().getFirst().supplierKey().value());
            assertEquals(4, firstPageStatements,
                    "A 100-supplier page should use count + page + two bounded aggregate queries.");
            assertEquals(firstPageStatements, secondPageStatements,
                    "Supplier enrichment query count must not grow with the number of rows on the page.");
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }
    }

    @Test
    void ownerScopedSupplierQueryCanBeExplainedAgainstMigratedPostgresIndexes() throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        UUID guestSessionId = insertGuestSession();
        String supplierKey = "acme industrial";
        for (int index = 0; index < 40; index++) {
            Instant completedAt = OCTOBER_START.plusSeconds(index);
            insertAnalysis(ownerId, null, "COMPLETED", "PENDING", "MATCHED",
                    "Owned Supplier", supplierKey, "USD", "100.0000", "0.0000",
                    OCTOBER_START, completedAt);
            insertAnalysis(otherOwnerId, null, "COMPLETED", "PENDING", "MATCHED",
                    "Foreign Supplier", supplierKey, "USD", "900.0000", "0.0000",
                    OCTOBER_START, completedAt);
            insertAnalysis(null, guestSessionId, "COMPLETED", "PENDING", "MATCHED",
                    "Guest Supplier", supplierKey, "USD", "700.0000", "0.0000",
                    OCTOBER_START, completedAt);
        }
        jdbcTemplate.execute("ANALYZE invoward.analyses");
        List<String> plan = jdbcTemplate.query("""
                        EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                        SELECT a.supplier_key, a.currency, count(*), sum(a.invoiced_total)
                        FROM invoward.analyses a
                        WHERE a.user_id = ?
                          AND a.supplier_key = ?
                          AND a.status = 'COMPLETED'
                          AND a.reconciliation_status IN ('MATCHED', 'REVIEW_REQUIRED')
                          AND a.currency IS NOT NULL
                          AND a.invoiced_total IS NOT NULL
                        GROUP BY a.supplier_key, a.currency
                        """,
                statement -> {
                    statement.setObject(1, ownerId);
                    statement.setString(2, supplierKey);
                },
                (resultSet, rowNumber) -> resultSet.getString(1));
        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'invoward'
                  AND tablename = 'analyses'
                """, String.class);

        assertFalse(plan.isEmpty(), "PostgreSQL must produce a plan for the owner-scoped aggregate.");
        assertTrue(indexes.contains("analyses_user_supplier_key_created_idx"));
        assertTrue(indexes.contains("analyses_user_status_created_idx"));
    }

    @Test
    void dashboardAggregateLimitsRejectValuesOutsideTopFive() throws Exception {
        UUID ownerId = insertUser();
        InvalidDataAccessApiUsageException topSuppliersFailure = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> dashboardRepository.fetchTopSuppliers(ownerId, OCTOBER, 6));
        assertInstanceOf(IllegalArgumentException.class, topSuppliersFailure.getCause());
        InvalidDataAccessApiUsageException discrepancyFailure = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> dashboardRepository.fetchCommonDiscrepancyTypes(ownerId, OCTOBER, 0));
        assertInstanceOf(IllegalArgumentException.class, discrepancyFailure.getCause());
    }

    private UUID insertUser() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
    }

    private UUID insertGuestSession() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
    }

    private UUID insertAnalysis(
            UUID userId,
            UUID guestSessionId,
            String status,
            String reviewStatus,
            String reconciliationStatus,
            String supplierName,
            String supplierKey,
            String currency,
            String invoicedTotal,
            String difference,
            Instant createdAt,
            Instant completedAt) {
        UUID id = UUID.randomUUID();
        Instant updatedAt = completedAt == null || completedAt.isBefore(createdAt) ? createdAt : completedAt;
        Timestamp expiresAt = guestSessionId == null
                ? null
                : Timestamp.from(Instant.now().plusSeconds(86_400));
        jdbcTemplate.update("""
                        INSERT INTO invoward.analyses
                            (id, user_id, guest_session_id, status, review_status, reconciliation_status,
                             supplier_name, supplier_key, currency, invoiced_total, difference,
                             price_tolerance_percent, retryable, version, created_at, updated_at,
                             completed_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, FALSE, 0, ?, ?, ?, ?)
                        """,
                id, userId, guestSessionId, status, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, currency, decimal(invoicedTotal), decimal(difference),
                Timestamp.from(createdAt), Timestamp.from(updatedAt), timestamp(completedAt), expiresAt);
        return id;
    }

    private void setReferenceTotalAndTolerance(UUID analysisId, String referenceTotal, String tolerancePercent) {
        jdbcTemplate.update("""
                        UPDATE invoward.analyses
                        SET reference_total = ?, price_tolerance_percent = ?
                        WHERE id = ?
                        """,
                decimal(referenceTotal), decimal(tolerancePercent), analysisId);
    }

    private void insertDiscrepancy(UUID analysisId, DiscrepancyType type) throws Exception {
        jdbcTemplate.execute((ConnectionCallback<UUID>) connection -> DatabaseFixtures.insertDiscrepancy(
                connection, analysisId, null, type.name(), "OPEN", null, null, false));
    }

    private void insertDiscrepancy(UUID analysisId, DiscrepancyType type, String resolutionStatus)
            throws Exception {
        jdbcTemplate.execute((ConnectionCallback<UUID>) connection -> DatabaseFixtures.insertDiscrepancy(
                connection, analysisId, null, type.name(), resolutionStatus, "APPROVED_CHANGE", null, true));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
