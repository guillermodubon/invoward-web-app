package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierCurrencyAmounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKeyAggregate;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListItem;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListQuery;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierFinancialQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierListQueryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ListSuppliersServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("2a2bb18f-eac4-42f4-9152-65aefdd00321");

    @Test
    void enrichesOnlyTheRequestedGroupPageWithOneBatchedCallPerFinancialProjection() {
        InsightsSupplierListQueryRepository listRepository = mock(InsightsSupplierListQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        ListSuppliersService service = new ListSuppliersService(
                listRepository, financialRepository, new InsightsMetricCalculator());
        SupplierListQuery query = SupplierListQuery.of(1, 2, " Acme ", "supplierName,asc");
        SupplierKey acme = new SupplierKey("acme");
        SupplierKey beta = new SupplierKey("beta");
        List<SupplierKey> keys = List.of(acme, beta);
        InsightsPage<SupplierKeyAggregate> groups = new InsightsPage<>(List.of(
                new SupplierKeyAggregate(acme, "ACME Supplies", 18, 6),
                new SupplierKeyAggregate(beta, "Beta Store", 3, 0)), 1, 2, 3);
        when(listRepository.pageSupplierKeys(OWNER_ID, query)).thenReturn(groups);
        when(financialRepository.fetchSupplierCurrencyAmounts(OWNER_ID, keys)).thenReturn(List.of(
                new SupplierCurrencyAmounts(acme, List.of(
                        amount("USD", "120.0000", 2), amount("EUR", "50.0000", 1))),
                new SupplierCurrencyAmounts(beta, List.of(amount("CAD", "9.0000", 1)))));
        when(financialRepository.fetchSupplierPotentialDifferencesByCurrency(OWNER_ID, keys)).thenReturn(List.of(
                new SupplierCurrencyAmounts(acme, List.of(amount("USD", "25.0000", 6)))));

        var result = service.list(OWNER_ID, query);

        assertEquals(1, result.page().page());
        assertEquals(2, result.page().size());
        assertEquals(3, result.page().totalElements());
        SupplierListItem acmeItem = result.page().content().getFirst();
        assertEquals(acme, acmeItem.supplierKey());
        assertEquals(new BigDecimal("33.33"), acmeItem.differenceRatePercent());
        assertEquals(List.of("EUR", "USD"), acmeItem.totalInvoicedByCurrency().stream()
                .map(CurrencyAmount::currency).toList());
        assertEquals(List.of("USD"), acmeItem.potentialDifferencesByCurrency().stream()
                .map(CurrencyAmount::currency).toList());
        SupplierListItem betaItem = result.page().content().get(1);
        assertEquals(new BigDecimal("0.00"), betaItem.differenceRatePercent());
        assertEquals(List.of(), betaItem.potentialDifferencesByCurrency());

        verify(listRepository).pageSupplierKeys(OWNER_ID, query);
        verify(financialRepository).fetchSupplierCurrencyAmounts(OWNER_ID, keys);
        verify(financialRepository).fetchSupplierPotentialDifferencesByCurrency(OWNER_ID, keys);
        verifyNoMoreInteractions(listRepository, financialRepository);
    }

    @Test
    void emptyPagePreservesMetadataAndSkipsBothFinancialQueries() {
        InsightsSupplierListQueryRepository listRepository = mock(InsightsSupplierListQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        ListSuppliersService service = new ListSuppliersService(
                listRepository, financialRepository, new InsightsMetricCalculator());
        SupplierListQuery query = SupplierListQuery.of(0, 20, null, null);
        when(listRepository.pageSupplierKeys(OWNER_ID, query))
                .thenReturn(new InsightsPage<>(List.of(), 0, 20, 0));

        var result = service.list(OWNER_ID, query);

        assertTrue(result.page().content().isEmpty());
        assertEquals(0, result.page().totalElements());
        verify(listRepository).pageSupplierKeys(OWNER_ID, query);
        verifyNoMoreInteractions(listRepository);
        verifyNoInteractions(financialRepository);
    }

    @Test
    void oneSupplierWithNoMonetaryContributionsHasZeroRateAndEmptyCurrencyBreakdowns() {
        InsightsSupplierListQueryRepository listRepository = mock(InsightsSupplierListQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        ListSuppliersService service = new ListSuppliersService(
                listRepository, financialRepository, new InsightsMetricCalculator());
        SupplierListQuery query = SupplierListQuery.of(null, null, null, null);
        SupplierKey key = new SupplierKey("single-supplier");
        List<SupplierKey> keys = List.of(key);
        when(listRepository.pageSupplierKeys(OWNER_ID, query)).thenReturn(new InsightsPage<>(List.of(
                new SupplierKeyAggregate(key, "Single Supplier", 4, 0)), 0, 20, 1));
        when(financialRepository.fetchSupplierCurrencyAmounts(OWNER_ID, keys)).thenReturn(List.of());
        when(financialRepository.fetchSupplierPotentialDifferencesByCurrency(OWNER_ID, keys))
                .thenReturn(List.of());

        var result = service.list(OWNER_ID, query);

        SupplierListItem item = result.page().content().getFirst();
        assertEquals(key, item.supplierKey());
        assertEquals(new BigDecimal("0.00"), item.differenceRatePercent());
        assertTrue(item.totalInvoicedByCurrency().isEmpty());
        assertTrue(item.potentialDifferencesByCurrency().isEmpty());
        verify(listRepository).pageSupplierKeys(OWNER_ID, query);
        verify(financialRepository).fetchSupplierCurrencyAmounts(OWNER_ID, keys);
        verify(financialRepository).fetchSupplierPotentialDifferencesByCurrency(OWNER_ID, keys);
        verifyNoMoreInteractions(listRepository, financialRepository);
    }

    @Test
    void rejectsMissingOwnerOrQueryBeforeReading() {
        InsightsSupplierListQueryRepository listRepository = mock(InsightsSupplierListQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        ListSuppliersService service = new ListSuppliersService(
                listRepository, financialRepository, new InsightsMetricCalculator());

        assertThrows(NullPointerException.class,
                () -> service.list(null, SupplierListQuery.of(null, null, null, null)));
        assertThrows(NullPointerException.class, () -> service.list(OWNER_ID, null));

        verifyNoInteractions(listRepository, financialRepository);
    }

    @Test
    void rejectsFinancialAggregatesForKeysOutsideThePage() {
        InsightsSupplierListQueryRepository listRepository = mock(InsightsSupplierListQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        ListSuppliersService service = new ListSuppliersService(
                listRepository, financialRepository, new InsightsMetricCalculator());
        SupplierListQuery query = SupplierListQuery.of(null, null, null, null);
        SupplierKey pageKey = new SupplierKey("owned-key");
        SupplierKey foreignKey = new SupplierKey("other-owner-key");
        List<SupplierKey> pageKeys = List.of(pageKey);
        when(listRepository.pageSupplierKeys(OWNER_ID, query)).thenReturn(new InsightsPage<>(List.of(
                new SupplierKeyAggregate(pageKey, "Owned supplier", 1, 0)), 0, 20, 1));
        when(financialRepository.fetchSupplierCurrencyAmounts(OWNER_ID, pageKeys)).thenReturn(List.of(
                new SupplierCurrencyAmounts(foreignKey, List.of(amount("USD", "99.0000", 1)))));

        assertThrows(IllegalStateException.class, () -> service.list(OWNER_ID, query));

        verify(listRepository).pageSupplierKeys(OWNER_ID, query);
        verify(financialRepository).fetchSupplierCurrencyAmounts(OWNER_ID, pageKeys);
        verifyNoMoreInteractions(listRepository, financialRepository);
    }

    @Test
    void rejectsMissingRepositoryPageInsteadOfReturningIncompleteResult() {
        InsightsSupplierListQueryRepository listRepository = mock(InsightsSupplierListQueryRepository.class);
        InsightsSupplierFinancialQueryRepository financialRepository =
                mock(InsightsSupplierFinancialQueryRepository.class);
        ListSuppliersService service = new ListSuppliersService(
                listRepository, financialRepository, new InsightsMetricCalculator());
        SupplierListQuery query = SupplierListQuery.of(null, null, null, null);
        when(listRepository.pageSupplierKeys(OWNER_ID, query)).thenReturn(null);

        assertThrows(NullPointerException.class, () -> service.list(OWNER_ID, query));

        verify(listRepository).pageSupplierKeys(OWNER_ID, query);
        verifyNoMoreInteractions(listRepository, financialRepository);
    }

    private static CurrencyAmount amount(String currency, String amount, long contributingAnalyses) {
        return new CurrencyAmount(currency, new BigDecimal(amount), contributingAnalyses);
    }
}
