package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.insights.application.exception.SupplierNotFoundException;
import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierCurrencyAmounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierFinancialQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierSummaryQueryRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GetSupplierSummaryServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("281b108f-1f15-4e5e-ae64-1135dfc80bc5");
    private static final SupplierKey SUPPLIER_KEY = new SupplierKey("canonical acme key");

    @Test
    void checksOwnedSupplierBeforeReadingAndReturnsTheComposedSummaryWithStableArrays() {
        InsightsSupplierSummaryQueryRepository summaryRepository =
                mock(InsightsSupplierSummaryQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        GetSupplierSummaryService service = new GetSupplierSummaryService(
                summaryRepository, financialRepository, new InsightsMetricCalculator());
        List<SupplierKey> keys = List.of(SUPPLIER_KEY);
        SupplierAggregateCounts counts = new SupplierAggregateCounts(18, 12, 6, 2, 1);
        List<CurrencyAmount> totalAmounts = List.of(
                amount("USD", "1200.0000", 2), amount("EUR", "50.0000", 1));
        List<CurrencyAmount> potentialAmounts = List.of(
                amount("USD", "25.0000", 2), amount("CAD", "5.0000", 1));
        List<DiscrepancyTypeFrequency> issues = List.of(
                new DiscrepancyTypeFrequency(DiscrepancyType.TAX_MISMATCH, 1),
                new DiscrepancyTypeFrequency(DiscrepancyType.UNEXPECTED_ITEM, 2),
                new DiscrepancyTypeFrequency(DiscrepancyType.UNIT_PRICE_MISMATCH, 4));
        AnalysisHistoryItem older = historyItem(
                "00000000-0000-0000-0000-000000000001", "2026-10-02T00:00:00Z");
        AnalysisHistoryItem newer = historyItem(
                "00000000-0000-0000-0000-000000000002", "2026-10-03T00:00:00Z");

        when(summaryRepository.findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY))
                .thenReturn(Optional.of("ACME Supplies"));
        when(summaryRepository.fetchSupplierCompletedCounts(OWNER_ID, SUPPLIER_KEY)).thenReturn(counts);
        when(financialRepository.fetchSupplierCurrencyAmounts(OWNER_ID, keys)).thenReturn(List.of(
                new SupplierCurrencyAmounts(SUPPLIER_KEY, totalAmounts)));
        when(financialRepository.fetchSupplierPotentialDifferencesByCurrency(OWNER_ID, keys)).thenReturn(List.of(
                new SupplierCurrencyAmounts(SUPPLIER_KEY, potentialAmounts)));
        when(summaryRepository.fetchSupplierCommonIssues(OWNER_ID, SUPPLIER_KEY, 5)).thenReturn(issues);
        when(summaryRepository.fetchSupplierRecent(OWNER_ID, SUPPLIER_KEY, 5)).thenReturn(List.of(older, newer));

        var result = service.get(OWNER_ID, SUPPLIER_KEY);

        assertEquals(SUPPLIER_KEY, result.supplierKey());
        assertEquals("ACME Supplies", result.supplierName());
        assertEquals(counts, result.counts());
        assertEquals(2, result.counts().differenceAmountUnavailableCount());
        assertEquals(1, result.counts().missingCurrencyCount());
        assertEquals(new BigDecimal("33.33"), result.differenceRatePercent());
        assertEquals(List.of("EUR", "USD"), result.totalInvoicedByCurrency().stream()
                .map(CurrencyAmount::currency).toList());
        assertEquals(List.of("CAD", "USD"), result.potentialDifferencesByCurrency().stream()
                .map(CurrencyAmount::currency).toList());
        assertEquals(List.of(DiscrepancyType.UNIT_PRICE_MISMATCH,
                        DiscrepancyType.UNEXPECTED_ITEM, DiscrepancyType.TAX_MISMATCH),
                result.commonDiscrepancyTypes().stream().map(DiscrepancyTypeFrequency::type).toList());
        assertEquals(List.of(newer.id(), older.id()),
                result.recentAnalyses().stream().map(AnalysisHistoryItem::id).toList());

        InOrder inOrder = inOrder(summaryRepository, financialRepository);
        inOrder.verify(summaryRepository).findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY);
        inOrder.verify(summaryRepository).fetchSupplierCompletedCounts(OWNER_ID, SUPPLIER_KEY);
        inOrder.verify(financialRepository).fetchSupplierCurrencyAmounts(OWNER_ID, keys);
        inOrder.verify(financialRepository).fetchSupplierPotentialDifferencesByCurrency(OWNER_ID, keys);
        inOrder.verify(summaryRepository).fetchSupplierCommonIssues(OWNER_ID, SUPPLIER_KEY, 5);
        inOrder.verify(summaryRepository).fetchSupplierRecent(OWNER_ID, SUPPLIER_KEY, 5);
        verifyNoMoreInteractions(summaryRepository, financialRepository);
    }

    @Test
    void unknownOrForeignSupplierUsesSameNotFoundAndStopsBeforeAnyAggregates() {
        InsightsSupplierSummaryQueryRepository summaryRepository =
                mock(InsightsSupplierSummaryQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        GetSupplierSummaryService service = new GetSupplierSummaryService(
                summaryRepository, financialRepository, new InsightsMetricCalculator());
        SupplierKey unknownKey = new SupplierKey("unknown key");
        SupplierKey foreignKey = new SupplierKey("foreign key");
        when(summaryRepository.findOwnedCompletedSupplierDisplayName(OWNER_ID, unknownKey))
                .thenReturn(Optional.empty());
        when(summaryRepository.findOwnedCompletedSupplierDisplayName(OWNER_ID, foreignKey))
                .thenReturn(Optional.empty());

        assertThrows(SupplierNotFoundException.class, () -> service.get(OWNER_ID, unknownKey));
        assertThrows(SupplierNotFoundException.class, () -> service.get(OWNER_ID, foreignKey));

        verify(summaryRepository).findOwnedCompletedSupplierDisplayName(OWNER_ID, unknownKey);
        verify(summaryRepository).findOwnedCompletedSupplierDisplayName(OWNER_ID, foreignKey);
        verifyNoMoreInteractions(summaryRepository);
        verifyNoInteractions(financialRepository);
    }

    @Test
    void rejectsMissingOwnerOrSupplierKeyBeforeRepositoryAccess() {
        InsightsSupplierSummaryQueryRepository summaryRepository =
                mock(InsightsSupplierSummaryQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        GetSupplierSummaryService service = new GetSupplierSummaryService(
                summaryRepository, financialRepository, new InsightsMetricCalculator());

        assertThrows(NullPointerException.class, () -> service.get(null, SUPPLIER_KEY));
        assertThrows(NullPointerException.class, () -> service.get(OWNER_ID, null));

        verifyNoInteractions(summaryRepository, financialRepository);
    }

    @Test
    void emptyCurrencyAggregatesBecomeEmptyBreakdowns() {
        InsightsSupplierSummaryQueryRepository summaryRepository =
                mock(InsightsSupplierSummaryQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        GetSupplierSummaryService service = new GetSupplierSummaryService(
                summaryRepository, financialRepository, new InsightsMetricCalculator());
        when(summaryRepository.findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY))
                .thenReturn(Optional.of(SUPPLIER_KEY.value()));
        when(summaryRepository.fetchSupplierCompletedCounts(OWNER_ID, SUPPLIER_KEY))
                .thenReturn(new SupplierAggregateCounts(1, 1, 0, 0, 0));
        when(financialRepository.fetchSupplierCurrencyAmounts(OWNER_ID, List.of(SUPPLIER_KEY)))
                .thenReturn(List.of());
        when(financialRepository.fetchSupplierPotentialDifferencesByCurrency(OWNER_ID, List.of(SUPPLIER_KEY)))
                .thenReturn(List.of());
        when(summaryRepository.fetchSupplierCommonIssues(OWNER_ID, SUPPLIER_KEY, 5)).thenReturn(List.of());
        when(summaryRepository.fetchSupplierRecent(OWNER_ID, SUPPLIER_KEY, 5)).thenReturn(List.of());

        var result = service.get(OWNER_ID, SUPPLIER_KEY);

        assertEquals(List.of(), result.totalInvoicedByCurrency());
        assertEquals(List.of(), result.potentialDifferencesByCurrency());
        assertEquals(List.of(), result.commonDiscrepancyTypes());
        assertEquals(List.of(), result.recentAnalyses());
        verify(summaryRepository).findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY);
        verify(summaryRepository).fetchSupplierCompletedCounts(OWNER_ID, SUPPLIER_KEY);
        verify(financialRepository).fetchSupplierCurrencyAmounts(OWNER_ID, List.of(SUPPLIER_KEY));
        verify(financialRepository).fetchSupplierPotentialDifferencesByCurrency(OWNER_ID, List.of(SUPPLIER_KEY));
        verify(summaryRepository).fetchSupplierCommonIssues(OWNER_ID, SUPPLIER_KEY, 5);
        verify(summaryRepository).fetchSupplierRecent(OWNER_ID, SUPPLIER_KEY, 5);
        verifyNoMoreInteractions(summaryRepository, financialRepository);
    }

    private static CurrencyAmount amount(String currency, String value, long contributingAnalyses) {
        return new CurrencyAmount(currency, new BigDecimal(value), contributingAnalyses);
    }

    private static AnalysisHistoryItem historyItem(String id, String completedAt) {
        Instant createdAt = Instant.parse("2026-10-01T00:00:00Z");
        Instant completed = Instant.parse(completedAt);
        return new AnalysisHistoryItem(
                UUID.fromString(id),
                AnalysisStatus.COMPLETED,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisReviewStatus.PENDING,
                null,
                "ACME Supplies",
                "QUOTE",
                "Q-42",
                "INV-42",
                "USD",
                new BigDecimal("120.0000"),
                new BigDecimal("125.0000"),
                new BigDecimal("5.0000"),
                createdAt,
                completed,
                completed);
    }
}
