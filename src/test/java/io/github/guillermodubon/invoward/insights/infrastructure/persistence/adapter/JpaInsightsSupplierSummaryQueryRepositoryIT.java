package io.github.guillermodubon.invoward.insights.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierFinancialQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierSummaryQueryRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
@Transactional
class JpaInsightsSupplierSummaryQueryRepositoryIT {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final String SHARED_KEY = "canonical shared supplier";

    @Autowired
    private InsightsSupplierSummaryQueryRepository summaryRepository;

    @Autowired
    private InsightsSupplierFinancialQueryRepository financialRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void supplierExistenceCountsMoneyIssuesAndRecentRowsRemainOwnerScopedAndDeterministic() throws Exception {
        UUID ownerId = insertUser();
        UUID otherOwnerId = insertUser();
        UUID guestSessionId = insertGuestSession();
        SupplierKey key = new SupplierKey(SHARED_KEY);

        UUID oct1 = insertAnalysis(ownerId, null, "00000000-0000-0000-0000-000000000011",
                "COMPLETED", "MATCHED", "Old label", "USD", null, null, 1);
        UUID oct2 = insertAnalysis(ownerId, null, "00000000-0000-0000-0000-000000000012",
                "COMPLETED", "REVIEW_REQUIRED", "Older label", "USD", "100.0000", "-12.5000", 2);
        UUID oct3a = insertAnalysis(ownerId, null, "00000000-0000-0000-0000-000000000013",
                "COMPLETED", "REVIEW_REQUIRED", "Newest label", null, null, null, 3);
        UUID oct3b = insertAnalysis(ownerId, null, "00000000-0000-0000-0000-000000000014",
                "COMPLETED", "REVIEW_REQUIRED", "Newest label", "USD", "150.0000", null, 3);
        insertAnalysis(ownerId, null, "00000000-0000-0000-0000-000000000015",
                "COMPLETED", "MATCHED", " \t ", "USD", null, null, 4);
        insertAnalysis(ownerId, null, "00000000-0000-0000-0000-000000000016",
                "COMPLETED", "MATCHED", null, "USD", null, null, 5);

        UUID foreignAnalysis = insertAnalysis(otherOwnerId, null, "00000000-0000-0000-0000-000000000021",
                "COMPLETED", "REVIEW_REQUIRED", "Foreign newest", "USD", "900.0000", "800.0000", 6);
        insertAnalysis(null, guestSessionId, "00000000-0000-0000-0000-000000000031",
                "COMPLETED", "REVIEW_REQUIRED", "Guest label", "USD", "700.0000", "600.0000", 7);

        insertDiscrepancy(oct2, DiscrepancyType.UNIT_PRICE_MISMATCH, "OPEN", null, null, false);
        insertDiscrepancy(oct2, DiscrepancyType.UNIT_PRICE_MISMATCH, "ACCEPTED", "APPROVED_CHANGE", null, true);
        insertDiscrepancy(oct2, DiscrepancyType.TAX_MISMATCH, "RESOLVED", "OTHER", "Reviewed", true);
        insertDiscrepancy(oct3b, DiscrepancyType.UNIT_PRICE_MISMATCH, "RESOLVED", "APPROVED_CHANGE", null, true);
        insertDiscrepancy(foreignAnalysis, DiscrepancyType.TOTAL_MISMATCH,
                "OPEN", null, null, false);

        assertEquals(Optional.of("Newest label"),
                summaryRepository.findOwnedCompletedSupplierDisplayName(ownerId, key));
        assertEquals(Optional.of("Foreign newest"),
                summaryRepository.findOwnedCompletedSupplierDisplayName(otherOwnerId, key));
        assertEquals(Optional.empty(), summaryRepository.findOwnedCompletedSupplierDisplayName(
                insertUser(), key));
        assertEquals(Optional.empty(), summaryRepository.findOwnedCompletedSupplierDisplayName(
                ownerId, new SupplierKey("unknown supplier")));

        assertEquals(new SupplierAggregateCounts(6, 3, 3, 2, 1),
                summaryRepository.fetchSupplierCompletedCounts(ownerId, key));
        assertEquals(List.of(new CurrencyAmount("USD", new BigDecimal("250.0000"), 2)),
                financialRepository.fetchSupplierCurrencyAmounts(ownerId, List.of(key))
                        .getFirst().amounts());
        assertEquals(List.of(new CurrencyAmount("USD", new BigDecimal("12.5000"), 1)),
                financialRepository.fetchSupplierPotentialDifferencesByCurrency(ownerId, List.of(key))
                        .getFirst().amounts());
        assertEquals(List.of(
                        new DiscrepancyTypeFrequency(DiscrepancyType.UNIT_PRICE_MISMATCH, 3),
                        new DiscrepancyTypeFrequency(DiscrepancyType.TAX_MISMATCH, 1)),
                summaryRepository.fetchSupplierCommonIssues(ownerId, key, 5));

        List<AnalysisHistoryItem> recent = summaryRepository.fetchSupplierRecent(ownerId, key, 5);
        assertEquals(List.of(
                        "00000000-0000-0000-0000-000000000016",
                        "00000000-0000-0000-0000-000000000015",
                        "00000000-0000-0000-0000-000000000014",
                        "00000000-0000-0000-0000-000000000013",
                        "00000000-0000-0000-0000-000000000012"),
                recent.stream().map(item -> item.id().toString()).toList());
        assertTrue(recent.stream().allMatch(item -> item.status().name().equals("COMPLETED")));

        InvalidDataAccessApiUsageException invalidIssueLimit = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> summaryRepository.fetchSupplierCommonIssues(ownerId, key, 6));
        assertTrue(invalidIssueLimit.getCause() instanceof IllegalArgumentException);
        InvalidDataAccessApiUsageException invalidRecentLimit = assertThrows(
                InvalidDataAccessApiUsageException.class,
                () -> summaryRepository.fetchSupplierRecent(ownerId, key, 0));
        assertTrue(invalidRecentLimit.getCause() instanceof IllegalArgumentException);
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
            String idValue,
            String status,
            String reconciliationStatus,
            String supplierName,
            String currency,
            String invoicedTotal,
            String difference,
            long completedSeconds) {
        UUID id = UUID.fromString(idValue);
        Instant createdAt = START;
        Instant completedAt = START.plusSeconds(completedSeconds);
        Timestamp expiresAt = guestSessionId == null ? null : Timestamp.from(Instant.now().plusSeconds(86_400));
        jdbcTemplate.update("""
                        INSERT INTO invoward.analyses
                            (id, user_id, guest_session_id, status, review_status, reconciliation_status,
                             supplier_name, supplier_key, currency, invoiced_total, difference,
                             price_tolerance_percent, retryable, version, created_at, updated_at,
                             completed_at, expires_at)
                        VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 0, FALSE, 0, ?, ?, ?, ?)
                        """,
                id, userId, guestSessionId, status, reconciliationStatus, supplierName, SHARED_KEY, currency,
                decimal(invoicedTotal), decimal(difference), Timestamp.from(createdAt),
                Timestamp.from(completedAt), Timestamp.from(completedAt), expiresAt);
        return id;
    }

    private void insertDiscrepancy(
            UUID analysisId,
            DiscrepancyType type,
            String status,
            String reason,
            String note,
            boolean resolvedAt) throws Exception {
        jdbcTemplate.execute((ConnectionCallback<UUID>) connection -> DatabaseFixtures.insertDiscrepancy(
                connection, analysisId, null, type.name(), status, reason, note, resolvedAt));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
