package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkLimitReachedException;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkExpiryChoice;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkPage;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "invoward.email.provider=disabled",
        "invoward.sharing.max-active-links-per-analysis=10"
})
class CreateShareLinkAtomicCapIT {

    private final List<UUID> analysisIds = new java.util.ArrayList<>();
    private final List<UUID> userIds = new java.util.ArrayList<>();

    @Autowired
    private CreateShareLinkService service;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private GeneratedReportRepository generatedReportRepository;

    @Autowired
    private ShareLinkRepository shareLinkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @MockitoBean
    private GetReconciliationService reconciliationService;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void cleanFixtures() {
        analysisIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", id));
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.users WHERE id = ?", id));
        analysisIds.clear();
        userIds.clear();
    }

    @Test
    void concurrentCreationsForOneAnalysisCannotExceedTheActiveCap() throws Exception {
        int concurrentRequests = 12;
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        userIds.add(userId);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        Instant createdAt = clock.instant().minusSeconds(90);
        Instant completedAt = createdAt.plusSeconds(60);
        Analysis analysis = analysisRepository.create(
                completedAnalysis(UUID.randomUUID(), owner, createdAt, completedAt));
        analysisIds.add(analysis.id());
        Analysis completedAnalysis = analysis;
        generatedReportRepository.create(new GeneratedReport(
                UUID.randomUUID(), completedAnalysis.id(), "users/" + userId + "/analyses/" + completedAnalysis.id()
                        + "/reports/atomic-cap.pdf",
                "application/pdf", 128, "c".repeat(64), null, completedAt));
        when(reconciliationService.get(eq(completedAnalysis.id()), eq(owner)))
                .thenReturn(reconciliation(completedAnalysis));

        CountDownLatch ready = new CountDownLatch(concurrentRequests);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        try {
            List<Future<Boolean>> attempts = new java.util.ArrayList<>();
            for (int index = 0; index < concurrentRequests; index++) {
                attempts.add(executor.submit(() -> createAfterConcurrentStart(
                        completedAnalysis.id(), owner, ready, start)));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "all requests should reach the start barrier");
            start.countDown();
            int createdCount = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(60, TimeUnit.SECONDS)) {
                    createdCount++;
                }
            }

            assertEquals(10, createdCount, "exactly the configured maximum should be created");
            assertEquals(10L, shareLinkRepository.countActiveByAnalysisId(completedAnalysis.id(), clock.instant()));
            ShareLinkPage persisted = shareLinkRepository.findPageByAnalysisId(completedAnalysis.id(), 0, 10);
            assertEquals(10, persisted.content().size());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private boolean createAfterConcurrentStart(
            UUID analysisId, AnalysisOwner owner, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("share-link creation test start signal timed out");
        }
        try {
            service.create(analysisId, owner, ShareLinkExpiryChoice.HOURS_24);
            return true;
        } catch (ShareLinkLimitReachedException expected) {
            return false;
        }
    }

    private static Analysis completedAnalysis(
            UUID analysisId, AnalysisOwner owner, Instant createdAt, Instant completedAt) {
        return new Analysis(
                analysisId, owner, AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, null,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Atomic cap supplier", "atomic cap supplier", "QUOTE", "REF-CAP", "INV-CAP", "USD",
                new BigDecimal("100.0000"), new BigDecimal("110.0000"), new BigDecimal("10.0000"),
                PriceTolerance.exactMatch(), false, null, null, 0,
                completedAt, null, createdAt, completedAt);
    }

    private static ReconciliationResult reconciliation(Analysis analysis) {
        return new ReconciliationResult(
                analysis.id(), AnalysisStatus.COMPLETED, analysis.reconciliationStatus(), analysis.priceTolerance(),
                analysis.referenceTotal(), analysis.invoicedTotal(), analysis.difference(),
                DuplicateInvoiceWarning.none(), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of());
    }
}
