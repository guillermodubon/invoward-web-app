package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryPage;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryQuery;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistorySort;
import io.github.guillermodubon.invoward.analysis.application.model.HistoryDiscrepancyType;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisHistoryQueryRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@Transactional
class AnalysisHistoryQueryMatrixIT {

    private static final Instant BASE_TIME = Instant.parse("2026-09-15T12:00:00Z");
    private static final String MATCHING_DISCREPANCY = "UNIT_PRICE_MISMATCH";

    @Autowired
    private AnalysisHistoryQueryRepository historyQueryRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void combinesEveryFilterWithOwnerScopeAndExistsWithoutDuplicatingAnalysisOrCount() throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        UUID guestSessionId = insertGuestSession();

        UUID matching = createRegisteredAnalysis(
                ownerId, BASE_TIME, "INV-needle-001", "Acme Systems", "REVIEW_REQUIRED",
                "COMPLETED", "REVIEWED", "USD", new BigDecimal("-15.0000"),
                MATCHING_DISCREPANCY, MATCHING_DISCREPANCY);

        createRegisteredAnalysis(ownerId, BASE_TIME, "INV-other-001", "Acme Systems", "REVIEW_REQUIRED",
                "COMPLETED", "REVIEWED", "USD", new BigDecimal("-15.0000"), MATCHING_DISCREPANCY);
        createRegisteredAnalysis(ownerId, BASE_TIME, "INV-other-002", "Acme Systems", "MATCHED",
                "COMPLETED", "REVIEWED", "USD", new BigDecimal("-15.0000"), MATCHING_DISCREPANCY);
        createRegisteredAnalysis(ownerId, BASE_TIME, "INV-other-003", "Acme Systems", "REVIEW_REQUIRED",
                "FAILED", "REVIEWED", "USD", new BigDecimal("-15.0000"), MATCHING_DISCREPANCY);
        createRegisteredAnalysis(ownerId, BASE_TIME, "INV-other-004", "Acme Systems", "REVIEW_REQUIRED",
                "COMPLETED", "PENDING", "USD", new BigDecimal("-15.0000"), MATCHING_DISCREPANCY);
        createRegisteredAnalysis(ownerId, BASE_TIME, "INV-other-005", "Other Supplier", "REVIEW_REQUIRED",
                "COMPLETED", "REVIEWED", "USD", new BigDecimal("-15.0000"), MATCHING_DISCREPANCY);
        createRegisteredAnalysis(ownerId, BASE_TIME, "INV-other-006", "Acme Systems", "REVIEW_REQUIRED",
                "COMPLETED", "REVIEWED", "EUR", new BigDecimal("-15.0000"), MATCHING_DISCREPANCY);
        createRegisteredAnalysis(ownerId, BASE_TIME.minusSeconds(86_400), "INV-other-007", "Acme Systems",
                "REVIEW_REQUIRED", "COMPLETED", "REVIEWED", "USD", new BigDecimal("-15.0000"),
                MATCHING_DISCREPANCY);
        createRegisteredAnalysis(ownerId, BASE_TIME, "INV-other-008", "Acme Systems", "REVIEW_REQUIRED",
                "COMPLETED", "REVIEWED", "USD", new BigDecimal("-9.9999"), MATCHING_DISCREPANCY);
        createRegisteredAnalysis(ownerId, BASE_TIME, "INV-other-009", "Acme Systems", "REVIEW_REQUIRED",
                "COMPLETED", "REVIEWED", "USD", new BigDecimal("-20.0001"), MATCHING_DISCREPANCY);
        createRegisteredAnalysis(ownerId, BASE_TIME, "INV-other-010", "Acme Systems", "REVIEW_REQUIRED",
                "COMPLETED", "REVIEWED", "USD", new BigDecimal("-15.0000"), "QUANTITY_MISMATCH");

        createRegisteredAnalysis(otherOwnerId, BASE_TIME, "INV-needle-other-owner", "Acme Systems",
                "REVIEW_REQUIRED", "COMPLETED", "REVIEWED", "USD", new BigDecimal("-15.0000"),
                MATCHING_DISCREPANCY);
        createGuestAnalysis(guestSessionId, "INV-needle-guest", "Acme Systems");

        AnalysisHistoryQuery query = AnalysisHistoryQuery.of(
                0,
                100,
                " needle ",
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.REVIEWED,
                " acme ",
                HistoryDiscrepancyType.UNIT_PRICE_MISMATCH,
                " usd ",
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 15),
                new BigDecimal("10.0000"),
                new BigDecimal("20.0000"),
                AnalysisHistorySort.CREATED_AT_DESC);

        AnalysisHistoryPage result = historyQueryRepository.findForRegisteredUser(ownerId, query);

        assertEquals(List.of(matching), result.content().stream().map(item -> item.id()).toList());
        assertEquals(1, result.totalElements());
        assertEquals(1, result.totalPages());
        assertEquals(new BigDecimal("-15.0000"), result.content().getFirst().difference());
    }

    @Test
    void pagedDiscrepancyProjectionUsesBoundedQueriesWithoutLoadingChildEntities() throws Exception {
        UUID ownerId = insertUser();
        List<UUID> matchingIds = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            matchingIds.add(createRegisteredAnalysis(
                    ownerId, BASE_TIME.plusSeconds(index), "INV-N+" + index, "Acme Systems", "REVIEW_REQUIRED",
                    "COMPLETED", "PENDING", "USD", new BigDecimal("1.0000"), MATCHING_DISCREPANCY));
        }

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        boolean statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            AnalysisHistoryPage result = historyQueryRepository.findForRegisteredUser(ownerId, queryByDiscrepancy());

            assertEquals(20, result.content().size());
            assertEquals(25, result.totalElements());
            assertEquals(2, result.totalPages());
            assertTrue(matchingIds.containsAll(result.content().stream().map(item -> item.id()).toList()));
            assertEquals(2, statistics.getQueryExecutionCount(), "The page and count should be the only queries.");
            assertEquals(2, statistics.getPrepareStatementCount(), "No per-Analysis child query should execute.");
            assertEquals(0, statistics.getEntityLoadCount(), "The read should use its scalar DTO projection.");
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }
    }

    private UUID createRegisteredAnalysis(
            UUID userId,
            Instant createdAt,
            String invoiceNumber,
            String supplierName,
            String reconciliationStatus,
            String processingStatus,
            String reviewStatus,
            String currency,
            BigDecimal difference,
            String... discrepancyTypes) {
        UUID analysisId = UUID.randomUUID();
        analysisRepository.create(Analysis.create(
                analysisId, new RegisteredUserOwner(userId), PriceTolerance.exactMatch(), createdAt));
        jdbcTemplate.update("""
                UPDATE invoward.analyses
                SET supplier_name = ?, invoice_number = ?, reference_number = ?,
                    reconciliation_status = ?, status = ?, review_status = ?, currency = ?, difference = ?
                WHERE id = ?
                """, supplierName, invoiceNumber, "REF-" + invoiceNumber, reconciliationStatus,
                processingStatus, reviewStatus, currency, difference, analysisId);
        for (String discrepancyType : discrepancyTypes) {
            insertDiscrepancy(analysisId, discrepancyType);
        }
        return analysisId;
    }

    private UUID createGuestAnalysis(UUID guestSessionId, String invoiceNumber, String supplierName) {
        Instant expiresAt = BASE_TIME.plusSeconds(86_400);
        UUID analysisId = UUID.randomUUID();
        analysisRepository.create(Analysis.create(
                analysisId, new GuestSessionOwner(guestSessionId, expiresAt), PriceTolerance.exactMatch(), BASE_TIME));
        jdbcTemplate.update("""
                UPDATE invoward.analyses
                SET supplier_name = ?, invoice_number = ?, reference_number = ?,
                    reconciliation_status = 'REVIEW_REQUIRED', status = 'COMPLETED',
                    review_status = 'REVIEWED', currency = 'USD', difference = -15.0000
                WHERE id = ?
                """, supplierName, invoiceNumber, "REF-" + invoiceNumber, analysisId);
        insertDiscrepancy(analysisId, MATCHING_DISCREPANCY);
        return analysisId;
    }

    private void insertDiscrepancy(UUID analysisId, String type) {
        jdbcTemplate.update("""
                INSERT INTO invoward.discrepancies
                    (id, analysis_id, type, explanation, resolution_status, created_at, updated_at)
                VALUES (?, ?, ?, 'History query matrix fixture', 'OPEN', ?, ?)
                """, UUID.randomUUID(), analysisId, type, Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
    }

    private UUID insertUser() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
    }

    private UUID insertGuestSession() throws Exception {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
    }

    private static AnalysisHistoryQuery queryByDiscrepancy() {
        return AnalysisHistoryQuery.of(
                0, 20, null, null, null, null, null, HistoryDiscrepancyType.UNIT_PRICE_MISMATCH,
                null, null, null, null, null, null);
    }
}
