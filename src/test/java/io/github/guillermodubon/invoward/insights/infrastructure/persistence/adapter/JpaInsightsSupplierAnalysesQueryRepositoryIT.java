package io.github.guillermodubon.invoward.insights.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierQueryRepository;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "invoward.email.provider=disabled",
        "spring.ai.model.chat=none"
})
@Transactional
class JpaInsightsSupplierAnalysesQueryRepositoryIT {

    private static final String SUPPLIER_KEY = "acme industrial";
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-02T12:00:00Z");

    @Autowired
    private InsightsSupplierQueryRepository queryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void supplierPagesScopeByRegisteredOwnerAndExactKeyAndKeepStableOrderingAndCounts() throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        UUID guestSessionId = insertGuestSession();
        List<UUID> expectedIds = new ArrayList<>();

        AnalysisStatus[] statuses = AnalysisStatus.values();
        for (int index = 1; index <= 105; index++) {
            AnalysisStatus status = statuses[(index - 1) % statuses.length];
            AnalysisReviewStatus reviewStatus = status == AnalysisStatus.COMPLETED && index % 2 == 0
                    ? AnalysisReviewStatus.REVIEWED
                    : AnalysisReviewStatus.PENDING;
            UUID id = analysisId(1_000 + index);
            expectedIds.add(id);
            insertAnalysis(id, ownerId, null, SUPPLIER_KEY, status, reviewStatus,
                    status == AnalysisStatus.COMPLETED ? AnalysisReconciliationStatus.MATCHED : null,
                    CREATED_AT, status == AnalysisStatus.COMPLETED ? COMPLETED_AT : null,
                    reviewStatus == AnalysisReviewStatus.REVIEWED ? COMPLETED_AT : null);
        }

        insertAnalysis(analysisId(1_200), ownerId, null, "another supplier", AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.MATCHED,
                CREATED_AT, COMPLETED_AT, null);
        insertAnalysis(analysisId(1_201), otherOwnerId, null, SUPPLIER_KEY, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.MATCHED,
                CREATED_AT, COMPLETED_AT, null);
        insertAnalysis(analysisId(1_202), null, guestSessionId, SUPPLIER_KEY, AnalysisStatus.FAILED,
                AnalysisReviewStatus.PENDING, null, CREATED_AT, null, null);

        InsightsPage<AnalysisHistoryItem> firstPage = queryRepository.pageSupplierAnalyses(
                ownerId, new SupplierKey(SUPPLIER_KEY), query(null, null, 0, 100, "createdAt,desc"));
        InsightsPage<AnalysisHistoryItem> secondPage = queryRepository.pageSupplierAnalyses(
                ownerId, new SupplierKey(SUPPLIER_KEY), query(null, null, 1, 100, "createdAt,desc"));
        InsightsPage<AnalysisHistoryItem> maxPage = queryRepository.pageSupplierAnalyses(
                ownerId, new SupplierKey(SUPPLIER_KEY), query(null, null, 1_000, 100, "createdAt,desc"));

        assertEquals(100, firstPage.content().size());
        assertEquals(105, firstPage.totalElements());
        assertEquals(expectedIds.reversed().subList(0, 100), ids(firstPage));
        assertEquals(5, secondPage.content().size());
        assertEquals(105, secondPage.totalElements());
        assertEquals(expectedIds.reversed().subList(100, 105), ids(secondPage));
        assertTrue(maxPage.content().isEmpty());
        assertEquals(105, maxPage.totalElements());
        assertFalse(ids(firstPage).contains(analysisId(1_201)));
        assertFalse(ids(firstPage).contains(analysisId(1_202)));
        assertTrue(firstPage.content().stream().allMatch(item -> item.supplierName().equals("Example Supplier")));
        Set<AnalysisStatus> statusSet = new HashSet<>();
        firstPage.content().forEach(item -> statusSet.add(item.status()));
        secondPage.content().forEach(item -> statusSet.add(item.status()));
        assertEquals(EnumSet.allOf(AnalysisStatus.class), statusSet);
    }

    @Test
    void supplierPageAppliesTypedProcessingAndReviewFiltersInTheDatabase() throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();

        insertAnalysis(analysisId(2_001), ownerId, null, SUPPLIER_KEY, AnalysisStatus.FAILED,
                AnalysisReviewStatus.PENDING, null, CREATED_AT, null, null);
        insertAnalysis(analysisId(2_002), ownerId, null, SUPPLIER_KEY, AnalysisStatus.FAILED,
                AnalysisReviewStatus.REVIEWED, null, CREATED_AT, null, COMPLETED_AT);
        insertAnalysis(analysisId(2_003), ownerId, null, SUPPLIER_KEY, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.REVIEWED, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                CREATED_AT, COMPLETED_AT, COMPLETED_AT);
        insertAnalysis(analysisId(2_004), ownerId, null, SUPPLIER_KEY, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.MATCHED,
                CREATED_AT, COMPLETED_AT, null);
        insertAnalysis(analysisId(2_005), otherOwnerId, null, SUPPLIER_KEY, AnalysisStatus.FAILED,
                AnalysisReviewStatus.PENDING, null, CREATED_AT, null, null);

        InsightsPage<AnalysisHistoryItem> filtered = queryRepository.pageSupplierAnalyses(
                ownerId, new SupplierKey(SUPPLIER_KEY),
                query(AnalysisStatus.FAILED, AnalysisReviewStatus.PENDING, 0, 20, null));
        InsightsPage<AnalysisHistoryItem> legalNoMatch = queryRepository.pageSupplierAnalyses(
                ownerId, new SupplierKey(SUPPLIER_KEY),
                query(AnalysisStatus.CREATED, AnalysisReviewStatus.REVIEWED, 0, 20, null));

        assertEquals(List.of(analysisId(2_001)), ids(filtered));
        assertEquals(1, filtered.totalElements());
        assertTrue(legalNoMatch.content().isEmpty());
        assertEquals(0, legalNoMatch.totalElements());
        assertEquals(0, legalNoMatch.totalPages());
    }

    @Test
    void completedAtOrderingKeepsNullsLastAndUsesIdAsStableTieBreak() throws Exception {
        UUID ownerId = insertUser();
        Instant earlier = COMPLETED_AT.minusSeconds(3_600);
        Instant sameCompletion = COMPLETED_AT;

        insertAnalysis(analysisId(3_001), ownerId, null, SUPPLIER_KEY, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.MATCHED,
                CREATED_AT, earlier, null);
        insertAnalysis(analysisId(3_002), ownerId, null, SUPPLIER_KEY, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.MATCHED,
                CREATED_AT, sameCompletion, null);
        insertAnalysis(analysisId(3_003), ownerId, null, SUPPLIER_KEY, AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, AnalysisReconciliationStatus.MATCHED,
                CREATED_AT, sameCompletion, null);
        insertAnalysis(analysisId(3_004), ownerId, null, SUPPLIER_KEY, AnalysisStatus.FAILED,
                AnalysisReviewStatus.PENDING, null, CREATED_AT, null, null);

        InsightsPage<AnalysisHistoryItem> descending = queryRepository.pageSupplierAnalyses(
                ownerId, new SupplierKey(SUPPLIER_KEY), query(null, null, 0, 20, "completedAt,desc"));
        InsightsPage<AnalysisHistoryItem> ascending = queryRepository.pageSupplierAnalyses(
                ownerId, new SupplierKey(SUPPLIER_KEY), query(null, null, 0, 20, "completedAt,asc"));

        assertEquals(List.of(analysisId(3_003), analysisId(3_002), analysisId(3_001), analysisId(3_004)),
                ids(descending));
        assertEquals(List.of(analysisId(3_001), analysisId(3_003), analysisId(3_002), analysisId(3_004)),
                ids(ascending));
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
            String supplierKey,
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
                             supplier_name, supplier_key, invoice_number, currency, reference_total,
                             invoiced_total, difference, completed_at, expires_at, created_at, updated_at,
                             reviewed_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'Example Supplier', ?, ?, 'USD', 10.0000, 10.0000,
                                0.0000, ?, ?, ?, ?, ?)
                        """,
                id, userId, guestSessionId, status.name(), reviewStatus.name(),
                reconciliationStatus == null ? null : reconciliationStatus.name(), supplierKey,
                "INV-" + id.getLeastSignificantBits(), timestamp(completedAt), expiresAt,
                Timestamp.from(createdAt), Timestamp.from(updatedAt), timestamp(reviewedAt));
    }

    private static SupplierAnalysisQuery query(
            AnalysisStatus processingStatus,
            AnalysisReviewStatus reviewStatus,
            Integer page,
            Integer size,
            String sort) {
        return SupplierAnalysisQuery.of(processingStatus, reviewStatus, page, size, sort);
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
