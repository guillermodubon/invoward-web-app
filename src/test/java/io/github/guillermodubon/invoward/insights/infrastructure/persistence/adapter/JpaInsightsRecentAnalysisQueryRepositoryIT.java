package io.github.guillermodubon.invoward.insights.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentQuery;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentView;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.port.InsightsRecentAnalysisQueryRepository;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "invoward.email.provider=disabled",
        "spring.ai.model.chat=none"
})
@Transactional
class JpaInsightsRecentAnalysisQueryRepositoryIT {

    private static final Instant CREATED_AT = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-02T12:00:00Z");

    @Autowired
    private InsightsRecentAnalysisQueryRepository queryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void recentPagesAllStatusesWithOwnerScopedCountAndStableIdTieBreak() throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        UUID guestSessionId = insertGuestSession();
        List<UUID> ownerAnalysisIds = new ArrayList<>();

        AnalysisStatus[] statuses = AnalysisStatus.values();
        for (int index = 1; index <= 58; index++) {
            AnalysisStatus status = statuses[(index - 1) % statuses.length];
            AnalysisReconciliationStatus reconciliationStatus = status == AnalysisStatus.COMPLETED
                    ? AnalysisReconciliationStatus.MATCHED : null;
            UUID id = analysisId(index);
            ownerAnalysisIds.add(id);
            insertAnalysis(id, ownerId, null, status, AnalysisReviewStatus.PENDING,
                    reconciliationStatus, CREATED_AT, status == AnalysisStatus.COMPLETED ? COMPLETED_AT : null, null);
        }

        insertAnalysis(analysisId(101), otherOwnerId, null, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.MATCHED,
                CREATED_AT, COMPLETED_AT, null);
        insertAnalysis(analysisId(102), null, guestSessionId, AnalysisStatus.FAILED,
                AnalysisReviewStatus.PENDING, null, CREATED_AT, null, null);

        InsightsPage<AnalysisHistoryItem> firstPage = queryRepository.pageRecent(
                ownerId, query(DashboardRecentView.RECENT, 0, 50));
        InsightsPage<AnalysisHistoryItem> secondPage = queryRepository.pageRecent(
                ownerId, query(DashboardRecentView.RECENT, 1, 50));
        InsightsPage<AnalysisHistoryItem> emptyPage = queryRepository.pageRecent(
                ownerId, query(DashboardRecentView.RECENT, 1_000, 50));

        assertEquals(50, firstPage.content().size());
        assertEquals(58, firstPage.totalElements());
        assertEquals(ownerAnalysisIds.subList(8, 58).reversed(), ids(firstPage));
        assertEquals(8, secondPage.content().size());
        assertEquals(58, secondPage.totalElements());
        assertEquals(ownerAnalysisIds.subList(0, 8).reversed(), ids(secondPage));
        assertTrue(emptyPage.content().isEmpty());
        assertEquals(58, emptyPage.totalElements());

        AnalysisHistoryItem projected = firstPage.content().getFirst();
        assertEquals(AnalysisStatus.CLASSIFYING, projected.status());
        assertEquals(AnalysisReviewStatus.PENDING, projected.reviewStatus());
        assertNull(projected.reconciliationStatus());
        assertEquals(CREATED_AT, projected.createdAt());
        assertFalse(ids(firstPage).contains(analysisId(101)));
        assertFalse(ids(firstPage).contains(analysisId(102)));
    }

    @Test
    void needsAttentionContainsOnlyPendingReviewRequiredCompletedRowsAndOrdersByCompletion() throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        UUID guestSessionId = insertGuestSession();
        Instant earlierCompletion = COMPLETED_AT.minusSeconds(60);

        insertAnalysis(analysisId(201), ownerId, null, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                CREATED_AT, earlierCompletion, null);
        insertAnalysis(analysisId(202), ownerId, null, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                CREATED_AT, COMPLETED_AT, null);
        insertAnalysis(analysisId(203), ownerId, null, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.REVIEWED, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                CREATED_AT, COMPLETED_AT, COMPLETED_AT);
        insertAnalysis(analysisId(204), ownerId, null, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.MATCHED,
                CREATED_AT, COMPLETED_AT, null);
        insertAnalysis(analysisId(205), ownerId, null, AnalysisStatus.FAILED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                CREATED_AT, null, null);
        insertAnalysis(analysisId(206), otherOwnerId, null, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                CREATED_AT, COMPLETED_AT, null);
        insertAnalysis(analysisId(207), null, guestSessionId, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                CREATED_AT, COMPLETED_AT, null);

        InsightsPage<AnalysisHistoryItem> page = queryRepository.pageRecent(
                ownerId, query(DashboardRecentView.NEEDS_ATTENTION, 0, 10));

        assertEquals(2, page.totalElements());
        assertEquals(List.of(analysisId(202), analysisId(201)), ids(page));
        assertTrue(page.content().stream().allMatch(item -> item.status() == AnalysisStatus.COMPLETED
                && item.reconciliationStatus() == AnalysisReconciliationStatus.REVIEW_REQUIRED
                && item.reviewStatus() == AnalysisReviewStatus.PENDING));
        assertEquals(COMPLETED_AT, page.content().getFirst().completedAt());
        assertEquals("INVOICE-202", page.content().getFirst().invoiceNumber());
    }

    @Test
    void emptyRegisteredAccountReturnsEmptyPageAndZeroOwnerScopedCount() throws Exception {
        UUID ownerId = insertUser();

        InsightsPage<AnalysisHistoryItem> page = queryRepository.pageRecent(
                ownerId, DashboardRecentQuery.defaults());

        assertTrue(page.content().isEmpty());
        assertEquals(0, page.totalElements());
        assertEquals(0, page.totalPages());
        assertFalse(page.hasNext());
        assertFalse(page.hasPrevious());
    }

    private UUID insertUser() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
    }

    private UUID insertGuestSession() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
    }

    private void insertAnalysis(
            UUID id,
            UUID userId,
            UUID guestSessionId,
            AnalysisStatus status,
            AnalysisReviewStatus reviewStatus,
            AnalysisReconciliationStatus reconciliationStatus,
            Instant createdAt,
            Instant completedAt,
            Instant reviewedAt) {
        Instant updatedAt = createdAt;
        if (completedAt != null && completedAt.isAfter(updatedAt)) {
            updatedAt = completedAt;
        }
        if (reviewedAt != null && reviewedAt.isAfter(updatedAt)) {
            updatedAt = reviewedAt;
        }
        Timestamp expiresAt = guestSessionId == null
                ? null
                : Timestamp.from(createdAt.plusSeconds(86_400));
        jdbcTemplate.update("""
                        INSERT INTO invoward.analyses
                            (id, user_id, guest_session_id, status, review_status, reconciliation_status,
                             supplier_name, invoice_number, currency, reference_total, invoiced_total, difference,
                             completed_at, expires_at, created_at, updated_at, reviewed_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'Example Supplier', ?, 'USD', 10.0000, 10.0000, 0.0000,
                                ?, ?, ?, ?, ?)
                        """,
                id, userId, guestSessionId, status.name(), reviewStatus.name(),
                reconciliationStatus == null ? null : reconciliationStatus.name(),
                "INVOICE-" + id.getLeastSignificantBits(),
                timestamp(completedAt), expiresAt, Timestamp.from(createdAt), Timestamp.from(updatedAt),
                timestamp(reviewedAt));
    }

    private static DashboardRecentQuery query(DashboardRecentView view, int page, int size) {
        return new DashboardRecentQuery(view,
                new io.github.guillermodubon.invoward.insights.application.model.InsightsPageRequest(page, size));
    }

    private static List<UUID> ids(InsightsPage<AnalysisHistoryItem> page) {
        return page.content().stream().map(AnalysisHistoryItem::id).toList();
    }

    private static UUID analysisId(long suffix) {
        return new UUID(0, suffix);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
