package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.insights.application.exception.SupplierNotFoundException;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierQueryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ListSupplierAnalysesServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("914eb508-9809-49b8-907f-1142f1bf0d32");
    private static final SupplierKey SUPPLIER_KEY = new SupplierKey("canonical acme key");

    @Test
    void verifiesOwnedSupplierFirstThenReturnsTheRepositoryPageAndMetadata() {
        InsightsSupplierQueryRepository queryRepository = mock(InsightsSupplierQueryRepository.class);
        ListSupplierAnalysesService service = new ListSupplierAnalysesService(queryRepository);
        SupplierAnalysisQuery query = SupplierAnalysisQuery.of(
                null, AnalysisReviewStatus.PENDING, 1, 2, "createdAt,asc");
        AnalysisHistoryItem oldest = failedItem("00000000-0000-0000-0000-000000000001", "2026-10-01T00:00:00Z");
        AnalysisHistoryItem newer = completedItem("00000000-0000-0000-0000-000000000002", "2026-10-02T00:00:00Z");
        InsightsPage<AnalysisHistoryItem> repositoryPage =
                new InsightsPage<>(List.of(oldest, newer), 1, 2, 5);

        when(queryRepository.findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY))
                .thenReturn(Optional.of("ACME Supplies"));
        when(queryRepository.pageSupplierAnalyses(OWNER_ID, SUPPLIER_KEY, query)).thenReturn(repositoryPage);

        SupplierAnalysisPage result = service.list(OWNER_ID, SUPPLIER_KEY, query);

        assertEquals(SUPPLIER_KEY, result.supplierKey());
        assertEquals(repositoryPage, result.page());
        assertEquals(1, result.page().page());
        assertEquals(2, result.page().size());
        assertEquals(5, result.page().totalElements());
        assertEquals(List.of(oldest, newer), result.page().content());
        assertEquals(List.of(AnalysisStatus.FAILED, AnalysisStatus.COMPLETED),
                result.page().content().stream().map(AnalysisHistoryItem::status).toList());

        InOrder order = inOrder(queryRepository);
        order.verify(queryRepository).findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY);
        order.verify(queryRepository).pageSupplierAnalyses(OWNER_ID, SUPPLIER_KEY, query);
        verifyNoMoreInteractions(queryRepository);
    }

    @Test
    void unknownAndForeignSupplierBothReturnNotFoundBeforePageQuery() {
        InsightsSupplierQueryRepository queryRepository = mock(InsightsSupplierQueryRepository.class);
        ListSupplierAnalysesService service = new ListSupplierAnalysesService(queryRepository);
        SupplierKey unknownKey = new SupplierKey("unknown supplier");
        SupplierKey foreignKey = new SupplierKey("foreign owner's supplier");
        SupplierAnalysisQuery query = SupplierAnalysisQuery.of(null, null, null, null, null);
        when(queryRepository.findOwnedCompletedSupplierDisplayName(OWNER_ID, unknownKey))
                .thenReturn(Optional.empty());
        when(queryRepository.findOwnedCompletedSupplierDisplayName(OWNER_ID, foreignKey))
                .thenReturn(Optional.empty());

        assertThrows(SupplierNotFoundException.class, () -> service.list(OWNER_ID, unknownKey, query));
        assertThrows(SupplierNotFoundException.class, () -> service.list(OWNER_ID, foreignKey, query));

        verify(queryRepository).findOwnedCompletedSupplierDisplayName(OWNER_ID, unknownKey);
        verify(queryRepository).findOwnedCompletedSupplierDisplayName(OWNER_ID, foreignKey);
        verifyNoMoreInteractions(queryRepository);
    }

    @Test
    void existingSupplierWithNoRowsForLegalFiltersReturnsEmptyPageWithoutChangingMetadata() {
        InsightsSupplierQueryRepository queryRepository = mock(InsightsSupplierQueryRepository.class);
        ListSupplierAnalysesService service = new ListSupplierAnalysesService(queryRepository);
        SupplierAnalysisQuery query = SupplierAnalysisQuery.of(
                AnalysisStatus.CREATED, AnalysisReviewStatus.REVIEWED, 2, 15, "completedAt,desc");
        InsightsPage<AnalysisHistoryItem> emptyPage = new InsightsPage<>(List.of(), 2, 15, 0);
        when(queryRepository.findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY))
                .thenReturn(Optional.of("ACME Supplies"));
        when(queryRepository.pageSupplierAnalyses(OWNER_ID, SUPPLIER_KEY, query)).thenReturn(emptyPage);

        SupplierAnalysisPage result = service.list(OWNER_ID, SUPPLIER_KEY, query);

        assertTrue(result.page().content().isEmpty());
        assertEquals(2, result.page().page());
        assertEquals(15, result.page().size());
        assertEquals(0, result.page().totalElements());
        verify(queryRepository).findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY);
        verify(queryRepository).pageSupplierAnalyses(OWNER_ID, SUPPLIER_KEY, query);
        verifyNoMoreInteractions(queryRepository);
    }

    @Test
    void validatesOwnerKeyAndQueryBeforeAnyDatabaseAccess() {
        InsightsSupplierQueryRepository queryRepository = mock(InsightsSupplierQueryRepository.class);
        ListSupplierAnalysesService service = new ListSupplierAnalysesService(queryRepository);
        SupplierAnalysisQuery query = SupplierAnalysisQuery.of(null, null, null, null, null);

        assertThrows(NullPointerException.class, () -> service.list(null, SUPPLIER_KEY, query));
        assertThrows(NullPointerException.class, () -> service.list(OWNER_ID, null, query));
        assertThrows(NullPointerException.class, () -> service.list(OWNER_ID, SUPPLIER_KEY, null));

        verifyNoInteractions(queryRepository);
    }

    @Test
    void rejectsMissingRepositoryResultsInsteadOfReturningIncompleteData() {
        InsightsSupplierQueryRepository queryRepository = mock(InsightsSupplierQueryRepository.class);
        ListSupplierAnalysesService service = new ListSupplierAnalysesService(queryRepository);
        SupplierAnalysisQuery query = SupplierAnalysisQuery.of(null, null, null, null, null);
        when(queryRepository.findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY)).thenReturn(null);

        assertThrows(NullPointerException.class, () -> service.list(OWNER_ID, SUPPLIER_KEY, query));
        verify(queryRepository).findOwnedCompletedSupplierDisplayName(OWNER_ID, SUPPLIER_KEY);
        verifyNoMoreInteractions(queryRepository);
    }

    private static AnalysisHistoryItem failedItem(String id, String createdAtValue) {
        Instant createdAt = Instant.parse(createdAtValue);
        return new AnalysisHistoryItem(
                UUID.fromString(id),
                AnalysisStatus.FAILED,
                null,
                AnalysisReviewStatus.PENDING,
                null,
                "ACME Supplies",
                null,
                null,
                "INV-1",
                "USD",
                null,
                null,
                null,
                createdAt,
                createdAt,
                null);
    }

    private static AnalysisHistoryItem completedItem(String id, String completedAtValue) {
        Instant completedAt = Instant.parse(completedAtValue);
        return new AnalysisHistoryItem(
                UUID.fromString(id),
                AnalysisStatus.COMPLETED,
                AnalysisReconciliationStatus.MATCHED,
                AnalysisReviewStatus.PENDING,
                null,
                "ACME Supplies",
                "QUOTE",
                "Q-1",
                "INV-1",
                "USD",
                null,
                null,
                null,
                completedAt.minusSeconds(86_400),
                completedAt,
                completedAt);
    }
}
