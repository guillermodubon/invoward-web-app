package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentPage;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentQuery;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentView;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.port.InsightsRecentAnalysisQueryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ListDashboardRecentServiceTest {

    private static final UUID REGISTERED_USER_ID = UUID.fromString("bd301bf3-e154-4d5e-9c0c-860820ac24e3");

    @Test
    void returnsSafeHistoryProjectionAndPassesOnlyTheAuthenticatedOwnerAndTypedQuery() {
        InsightsRecentAnalysisQueryRepository repository = mock(InsightsRecentAnalysisQueryRepository.class);
        ListDashboardRecentService service = new ListDashboardRecentService(repository);
        DashboardRecentQuery query = DashboardRecentQuery.of("RECENT", 0, 10);
        AnalysisHistoryItem item = historyItem();
        InsightsPage<AnalysisHistoryItem> persistedPage = new InsightsPage<>(List.of(item), 0, 10, 1);
        when(repository.pageRecent(REGISTERED_USER_ID, query)).thenReturn(persistedPage);

        DashboardRecentPage result = service.list(REGISTERED_USER_ID, query);

        assertEquals(DashboardRecentView.RECENT, result.view());
        assertEquals(persistedPage, result.page());
        assertEquals(item, result.page().content().getFirst());
        assertTrue(Set.of(AnalysisHistoryItem.class.getRecordComponents()).stream()
                .map(component -> component.getName())
                .noneMatch(Set.of("userId", "registeredUserId", "guestSessionId", "note", "shareToken",
                        "storageKey", "sha256", "signedUrl")::contains));

        verify(repository).pageRecent(REGISTERED_USER_ID, query);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void passesNeedsAttentionAndMaximumPageBoundsWithoutLoadingAdditionalRows() {
        InsightsRecentAnalysisQueryRepository repository = mock(InsightsRecentAnalysisQueryRepository.class);
        ListDashboardRecentService service = new ListDashboardRecentService(repository);
        DashboardRecentQuery query = DashboardRecentQuery.of("NEEDS_ATTENTION", 1_000, 50);
        InsightsPage<AnalysisHistoryItem> emptyOutOfRangePage = new InsightsPage<>(List.of(), 1_000, 50, 58);
        when(repository.pageRecent(REGISTERED_USER_ID, query)).thenReturn(emptyOutOfRangePage);

        DashboardRecentPage result = service.list(REGISTERED_USER_ID, query);

        assertEquals(DashboardRecentView.NEEDS_ATTENTION, result.view());
        assertEquals(1_000, result.page().page());
        assertEquals(50, result.page().size());
        assertEquals(58, result.page().totalElements());
        assertTrue(result.page().content().isEmpty());
        verify(repository).pageRecent(REGISTERED_USER_ID, query);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsMissingRegisteredOwnerOrQueryBeforeAnyRead() {
        InsightsRecentAnalysisQueryRepository repository = mock(InsightsRecentAnalysisQueryRepository.class);
        ListDashboardRecentService service = new ListDashboardRecentService(repository);
        DashboardRecentQuery query = DashboardRecentQuery.defaults();

        assertThrows(NullPointerException.class, () -> service.list(null, query));
        assertThrows(NullPointerException.class, () -> service.list(REGISTERED_USER_ID, null));

        verifyNoInteractions(repository);
    }

    @Test
    void invalidViewAndPaginationAreRejectedBeforeQueryRepositoryCanBeCalled() {
        InsightsRecentAnalysisQueryRepository repository = mock(InsightsRecentAnalysisQueryRepository.class);
        ListDashboardRecentService service = new ListDashboardRecentService(repository);

        assertThrows(IllegalArgumentException.class, () -> service.list(
                REGISTERED_USER_ID, DashboardRecentQuery.of("UNKNOWN", 0, 10)));
        assertThrows(IllegalArgumentException.class, () -> service.list(
                REGISTERED_USER_ID, DashboardRecentQuery.of("RECENT", -1, 10)));
        assertThrows(IllegalArgumentException.class, () -> service.list(
                REGISTERED_USER_ID, DashboardRecentQuery.of("RECENT", 1_001, 10)));
        assertThrows(IllegalArgumentException.class, () -> service.list(
                REGISTERED_USER_ID, DashboardRecentQuery.of("RECENT", 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> service.list(
                REGISTERED_USER_ID, DashboardRecentQuery.of("RECENT", 0, 51)));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMissingRepositoryPageInsteadOfReturningAnIncompleteResponse() {
        InsightsRecentAnalysisQueryRepository repository = mock(InsightsRecentAnalysisQueryRepository.class);
        ListDashboardRecentService service = new ListDashboardRecentService(repository);
        DashboardRecentQuery query = DashboardRecentQuery.defaults();
        when(repository.pageRecent(REGISTERED_USER_ID, query)).thenReturn(null);

        assertThrows(NullPointerException.class, () -> service.list(REGISTERED_USER_ID, query));

        verify(repository).pageRecent(REGISTERED_USER_ID, query);
        verifyNoMoreInteractions(repository);
    }

    private static AnalysisHistoryItem historyItem() {
        Instant createdAt = Instant.parse("2026-10-20T12:00:00Z");
        Instant updatedAt = createdAt.plusSeconds(60);
        return new AnalysisHistoryItem(
                UUID.fromString("10564ad6-15f8-4ff4-b516-c2372782bfc3"),
                AnalysisStatus.COMPLETED,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisReviewStatus.PENDING,
                null,
                "ACME Supplies",
                "PURCHASE_ORDER",
                "PO-104",
                "INV-205",
                "USD",
                new BigDecimal("120.0000"),
                new BigDecimal("125.0000"),
                new BigDecimal("5.0000"),
                createdAt,
                updatedAt,
                updatedAt);
    }
}
