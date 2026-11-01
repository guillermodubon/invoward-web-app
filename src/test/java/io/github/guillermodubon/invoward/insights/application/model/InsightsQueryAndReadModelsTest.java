package io.github.guillermodubon.invoward.insights.application.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsQueryAndReadModelsTest {

    @Test
    void recentQueryUsesTypedViewAndContextSpecificPageBounds() {
        DashboardRecentQuery defaults = DashboardRecentQuery.defaults();

        assertEquals(DashboardRecentView.RECENT, defaults.view());
        assertEquals(new InsightsPageRequest(0, 10), defaults.pagination());
        assertEquals(DashboardRecentView.NEEDS_ATTENTION,
                DashboardRecentQuery.of("NEEDS_ATTENTION", 2, 50).view());
        assertThrows(IllegalArgumentException.class, () -> DashboardRecentQuery.of("UNKNOWN", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> DashboardRecentQuery.of("RECENT", 0, 51));
        assertThrows(IllegalArgumentException.class, () -> DashboardRecentQuery.of("RECENT", -1, 10));
        assertThrows(IllegalArgumentException.class, () -> InsightsPageRequest.of(1_001, 20, 20));
        assertThrows(IllegalArgumentException.class, () -> InsightsPageRequest.of(0, 101, 20));
        assertEquals(1_000, DashboardRecentQuery.of("RECENT", 1_000, 50).pagination().page());
    }

    @Test
    void supplierListQueryTrimsOptionalSearchAndValidatesUnicodeLength() {
        SupplierListQuery defaults = SupplierListQuery.of(null, null, "   ", null);
        assertEquals(null, defaults.search());
        assertEquals(SupplierListSort.ANALYSIS_COUNT_DESC, defaults.sort());
        assertEquals(new InsightsPageRequest(0, 20), defaults.pagination());

        SupplierListQuery query = SupplierListQuery.of(2, 15, "  Acme_%  ", "supplierName,asc");
        assertEquals("Acme_%", query.search());
        assertEquals(SupplierListSort.SUPPLIER_NAME_ASC, query.sort());
        assertEquals(2, query.pagination().page());
        assertEquals("😀".repeat(120), SupplierListQuery.of(0, 20, "😀".repeat(120), null).search());
        assertThrows(IllegalArgumentException.class,
                () -> SupplierListQuery.of(0, 20, "😀".repeat(121), null));
        assertThrows(IllegalArgumentException.class,
                () -> SupplierListQuery.of(0, 20, "acme", "supplierName,desc;delete"));
    }

    @Test
    void supplierAnalysisQueryKeepsStatusAndSortFiltersTyped() {
        SupplierAnalysisQuery defaults = SupplierAnalysisQuery.of(null, null, null, null, null);
        assertEquals(null, defaults.processingStatus());
        assertEquals(null, defaults.reviewStatus());
        assertEquals(SupplierHistorySort.CREATED_AT_DESC, defaults.sort());
        assertEquals(new InsightsPageRequest(0, 20), defaults.pagination());

        SupplierAnalysisQuery filtered = SupplierAnalysisQuery.of(
                AnalysisStatus.FAILED, AnalysisReviewStatus.PENDING, 3, 25, "completedAt,asc");
        assertEquals(AnalysisStatus.FAILED, filtered.processingStatus());
        assertEquals(AnalysisReviewStatus.PENDING, filtered.reviewStatus());
        assertEquals(SupplierHistorySort.COMPLETED_AT_ASC, filtered.sort());
        assertEquals(3, filtered.pagination().page());
        assertThrows(IllegalArgumentException.class,
                () -> SupplierAnalysisQuery.of(null, null, 0, 20, "userId,asc"));
        assertThrows(IllegalArgumentException.class,
                () -> SupplierAnalysisQuery.of(null, null, 1_001, 20, null));
        assertThrows(IllegalArgumentException.class,
                () -> SupplierAnalysisQuery.of(null, null, 0, 101, null));
    }

    @Test
    void dashboardSummaryPreservesTypedMoneyAndNullPercentageForEmptyDenominator() {
        InsightsPeriod period = new InsightsPeriod(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-01"));
        DashboardSummary empty = new DashboardSummary(
                period,
                new DashboardAggregateCounts(0, 0, 0, 0),
                null,
                0,
                List.of(),
                List.of(),
                new DashboardDataQualityCounts(0, 0, 0),
                List.of(),
                List.of());
        assertEquals(null, empty.matchedPercent());

        assertThrows(IllegalArgumentException.class, () -> new DashboardSummary(
                period, new DashboardAggregateCounts(0, 0, 0, 0), new BigDecimal("0.00"),
                0, List.of(), List.of(), new DashboardDataQualityCounts(0, 0, 0), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DashboardSummary(
                period, new DashboardAggregateCounts(1, 1, 0, 0), new BigDecimal("100.0"),
                0, List.of(), List.of(), new DashboardDataQualityCounts(0, 0, 0), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DashboardAggregateCounts(3, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DashboardAggregateCounts(1, 1, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new DashboardDataQualityCounts(-1, 0, 0));
    }

    @Test
    void supplierReadModelsDefendCountsCurrencyUniquenessAndListImmutability() {
        SupplierKey key = new SupplierKey("acme supplies");
        SupplierAggregateCounts counts = new SupplierAggregateCounts(3, 2, 1, 0, 0);
        ArrayList<CurrencyAmount> amounts = new ArrayList<>(List.of(
                new CurrencyAmount("USD", new BigDecimal("120.00"), 3)));
        SupplierListItem listItem = new SupplierListItem(
                key, "ACME Supplies", 3, 1, new BigDecimal("33.33"), amounts, List.of());
        amounts.clear();

        assertEquals(1, listItem.totalInvoicedByCurrency().size());
        assertThrows(UnsupportedOperationException.class, () -> listItem.totalInvoicedByCurrency().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierAggregateCounts(3, 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierAggregateCounts(2, 1, 1, 2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierListItem(key, "ACME", 0, 0, null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierListItem(key, "ACME", 1, 0, new BigDecimal("100.0"), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierCurrencyAmounts(key, List.of(
                        new CurrencyAmount("USD", BigDecimal.ONE, 1),
                        new CurrencyAmount("USD", BigDecimal.TEN, 1))));

        SupplierSummary summary = new SupplierSummary(
                key, "ACME Supplies", counts, new BigDecimal("33.33"),
                List.of(new CurrencyAmount("USD", new BigDecimal("120.00"), 3)), List.of(),
                List.of(new DiscrepancyTypeFrequency(DiscrepancyType.TOTAL_MISMATCH, 2)),
                List.of(historyItem()));
        assertEquals(key, summary.supplierKey());
        assertEquals(AnalysisStatus.COMPLETED, summary.recentAnalyses().getFirst().status());
        assertThrows(IllegalArgumentException.class, () -> new SupplierSummary(
                key, "ACME Supplies", counts, new BigDecimal("33.3"), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void readModelsExposeOnlySafeFieldsAndEnforceDashboardResultLimits() {
        AnalysisHistoryItem item = historyItem();
        InsightsPage<AnalysisHistoryItem> historyPage = new InsightsPage<>(List.of(item), 0, 10, 1);
        DashboardRecentPage recent = new DashboardRecentPage(DashboardRecentView.RECENT, historyPage);
        assertEquals(item, recent.page().content().getFirst());
        assertThrows(IllegalArgumentException.class, () -> new DashboardRecentPage(
                DashboardRecentView.RECENT, new InsightsPage<>(List.of(), 0, 51, 0)));

        Set<String> forbiddenNames = Set.of("userId", "registeredUserId", "guestSessionId", "note", "storageKey",
                "sha256", "signedUrl", "shareToken", "rawPayload");
        for (Class<?> model : List.of(DashboardSummary.class, DashboardRecentPage.class,
                SupplierListItem.class, SupplierSummary.class, SupplierAnalysisPage.class)) {
            List<String> componentNames = Arrays.stream(model.getRecordComponents())
                    .map(component -> component.getName())
                    .toList();
            assertTrue(componentNames.stream().noneMatch(forbiddenNames::contains),
                    model.getSimpleName() + " contains an internal/private field");
        }
    }

    private static AnalysisHistoryItem historyItem() {
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        return new AnalysisHistoryItem(
                UUID.randomUUID(), AnalysisStatus.COMPLETED, AnalysisReconciliationStatus.MATCHED,
                AnalysisReviewStatus.PENDING, null, "ACME Supplies", null, null, "INV-1", "USD",
                null, new BigDecimal("120.00"), BigDecimal.ZERO, created, created, created);
    }
}
