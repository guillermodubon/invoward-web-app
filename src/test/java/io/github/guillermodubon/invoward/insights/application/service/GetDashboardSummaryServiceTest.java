package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DashboardAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardDataQualityCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardSummary;
import io.github.guillermodubon.invoward.insights.application.model.DashboardTopSupplier;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPeriod;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.port.InsightsDashboardQueryRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GetDashboardSummaryServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("920e3dc0-f75a-498d-b61c-d6df3b06f88c");
    private static final InsightsPeriod NOVEMBER = new InsightsPeriod(
            LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-01"));

    @Test
    void composesDefaultUtcMonthSummaryForTheAuthorizedOwnerAndSortsItsArrays() {
        InsightsDashboardQueryRepository repository = mock(InsightsDashboardQueryRepository.class);
        GetDashboardSummaryService service = service(repository);
        DashboardAggregateCounts counts = new DashboardAggregateCounts(3, 2, 1, 1);
        List<CurrencyAmount> totals = List.of(
                amount("USD", "300.0000", 2), amount("EUR", "50.0000", 1));
        List<CurrencyAmount> differences = List.of(
                amount("USD", "20.0000", 1), amount("EUR", "5.0000", 1));
        List<DashboardTopSupplier> suppliers = List.of(
                supplier("beta", 1), supplier("alpha", 2));
        List<DiscrepancyTypeFrequency> types = List.of(
                new DiscrepancyTypeFrequency(DiscrepancyType.TAX_MISMATCH, 1),
                new DiscrepancyTypeFrequency(DiscrepancyType.UNIT_PRICE_MISMATCH, 3));
        DashboardDataQualityCounts dataQualityCounts = new DashboardDataQualityCounts(0, 0, 0);
        stubRepository(repository, NOVEMBER, counts, 2, totals, differences, suppliers, types, dataQualityCounts);

        DashboardSummary summary = service.get(OWNER_ID, null, null);

        assertEquals(NOVEMBER, summary.period());
        assertEquals(counts, summary.counts());
        assertEquals(new BigDecimal("66.67"), summary.matchedPercent());
        assertEquals(2, summary.failedCount());
        assertEquals(List.of("EUR", "USD"),
                summary.totalInvoicedByCurrency().stream().map(CurrencyAmount::currency).toList());
        assertEquals(List.of("EUR", "USD"),
                summary.potentialDifferencesByCurrency().stream().map(CurrencyAmount::currency).toList());
        assertEquals(List.of("alpha", "beta"),
                summary.topSuppliers().stream().map(row -> row.supplierKey().value()).toList());
        assertEquals(List.of(DiscrepancyType.UNIT_PRICE_MISMATCH, DiscrepancyType.TAX_MISMATCH),
                summary.commonDiscrepancyTypes().stream().map(DiscrepancyTypeFrequency::type).toList());
        assertEquals(dataQualityCounts, summary.dataQualityCounts());

        verifyAllRepositoryCalls(repository, NOVEMBER);
    }

    @Test
    void customInclusiveDateRangeProducesHalfOpenPeriodAndEmptyAccountHasNullMatchedPercent() {
        InsightsDashboardQueryRepository repository = mock(InsightsDashboardQueryRepository.class);
        GetDashboardSummaryService service = service(repository);
        InsightsPeriod customPeriod = new InsightsPeriod(
                LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-03"));
        stubRepository(repository, customPeriod, new DashboardAggregateCounts(0, 0, 0, 0), 0,
                List.of(), List.of(), List.of(), List.of(), new DashboardDataQualityCounts(0, 0, 0));

        DashboardSummary summary = service.get(
                OWNER_ID, LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-02"));

        assertEquals(customPeriod, summary.period());
        assertNull(summary.matchedPercent());
        assertEquals(0, summary.counts().completedAnalyses());
        assertEquals(0, summary.failedCount());
        assertEquals(List.of(), summary.totalInvoicedByCurrency());
        assertEquals(List.of(), summary.potentialDifferencesByCurrency());
        assertEquals(List.of(), summary.topSuppliers());
        assertEquals(List.of(), summary.commonDiscrepancyTypes());

        verifyAllRepositoryCalls(repository, customPeriod);
    }

    @Test
    void rejectsMissingOwnerOrIncompleteDateRangeBeforeQuerying() {
        InsightsDashboardQueryRepository repository = mock(InsightsDashboardQueryRepository.class);
        GetDashboardSummaryService service = service(repository);

        assertThrows(NullPointerException.class, () -> service.get(null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.get(OWNER_ID, LocalDate.parse("2026-10-01"), null));
        verify(repository, never()).fetchCompletedCounts(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void validatesAllSuppliedDateRangeRulesBeforeCallingQueries() {
        InsightsDashboardQueryRepository repository = mock(InsightsDashboardQueryRepository.class);
        GetDashboardSummaryService service = service(repository);

        assertThrows(IllegalArgumentException.class, () -> service.get(
                OWNER_ID, LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-02")));
        assertThrows(IllegalArgumentException.class, () -> service.get(
                OWNER_ID, LocalDate.parse("2024-01-01"), LocalDate.parse("2025-01-01")));
        verifyNoMoreInteractions(repository);
    }

    private static GetDashboardSummaryService service(InsightsDashboardQueryRepository repository) {
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:30:00Z"), ZoneOffset.ofHours(-8));
        return new GetDashboardSummaryService(
                repository, new InsightsPeriodFactory(clock), new InsightsMetricCalculator());
    }

    private static void stubRepository(
            InsightsDashboardQueryRepository repository,
            InsightsPeriod period,
            DashboardAggregateCounts counts,
            long failedCount,
            List<CurrencyAmount> totals,
            List<CurrencyAmount> differences,
            List<DashboardTopSupplier> suppliers,
            List<DiscrepancyTypeFrequency> types,
            DashboardDataQualityCounts dataQualityCounts) {
        when(repository.fetchCompletedCounts(OWNER_ID, period)).thenReturn(counts);
        when(repository.fetchFailedCountByCreationPeriod(OWNER_ID, period)).thenReturn(failedCount);
        when(repository.fetchTotalsByCurrency(OWNER_ID, period)).thenReturn(totals);
        when(repository.fetchPotentialDifferencesByCurrency(OWNER_ID, period)).thenReturn(differences);
        when(repository.fetchTopSuppliers(OWNER_ID, period, 5)).thenReturn(suppliers);
        when(repository.fetchCommonDiscrepancyTypes(OWNER_ID, period, 5)).thenReturn(types);
        when(repository.fetchMissingDataCounts(OWNER_ID, period)).thenReturn(dataQualityCounts);
    }

    private static void verifyAllRepositoryCalls(
            InsightsDashboardQueryRepository repository, InsightsPeriod period) {
        verify(repository).fetchCompletedCounts(OWNER_ID, period);
        verify(repository).fetchFailedCountByCreationPeriod(OWNER_ID, period);
        verify(repository).fetchTotalsByCurrency(OWNER_ID, period);
        verify(repository).fetchPotentialDifferencesByCurrency(OWNER_ID, period);
        verify(repository).fetchTopSuppliers(OWNER_ID, period, 5);
        verify(repository).fetchCommonDiscrepancyTypes(OWNER_ID, period, 5);
        verify(repository).fetchMissingDataCounts(OWNER_ID, period);
        verifyNoMoreInteractions(repository);
    }

    private static CurrencyAmount amount(String currency, String value, long count) {
        return new CurrencyAmount(currency, new BigDecimal(value), count);
    }

    private static DashboardTopSupplier supplier(String key, long count) {
        String name = key.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + key.substring(1);
        return new DashboardTopSupplier(new SupplierKey(key), name, count);
    }
}
