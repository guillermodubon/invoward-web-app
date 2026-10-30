package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
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
import io.github.guillermodubon.invoward.sharing.application.exception.PublicSharedReportNotFoundException;
import io.github.guillermodubon.invoward.sharing.application.model.GeneratedShareToken;
import io.github.guillermodubon.invoward.sharing.application.model.PublicSharedReport;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class GetPublicSharedReportServiceIT {

    private final List<UUID> analysisIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @Autowired
    private GetPublicSharedReportService service;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private GeneratedReportRepository reportRepository;

    @Autowired
    private ShareLinkRepository shareLinkRepository;

    @Autowired
    private ShareTokenGenerator tokenGenerator;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private GetReconciliationService reconciliationService;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void cleanupFixtures() {
        analysisIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", id));
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.users WHERE id = ?", id));
        analysisIds.clear();
        userIds.clear();
    }

    @Test
    void readsActiveShareByIndexedHashAndReturnsWhitelistedPersistedProjection() {
        Analysis analysis = createCompletedAnalysis();
        GeneratedShareToken token = createLink(analysis, clock.instant().plusSeconds(3600), null);
        GeneratedReport report = new GeneratedReport(
                UUID.randomUUID(), analysis.id(), "users/private/analysis/report.pdf", "application/pdf",
                120, "c".repeat(64), null, analysis.completedAt());
        GeneratedReport persistedReport = reportRepository.create(report);
        when(reconciliationService.get(eq(analysis.id()), eq(analysis.owner())))
                .thenReturn(reconciliation(analysis));

        PublicSharedReport result = service.get(token.rawToken());

        assertEquals("Integration supplier", result.supplierName());
        assertEquals("INV-PUBLIC", result.invoiceNumber());
        assertEquals(persistedReport.generatedAt(), result.reportGeneratedAt());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, result.reconciliationStatus());
        assertEquals(0, result.summary().lineCount());
        assertTrue(result.toString().indexOf(analysis.owner().toString()) < 0);
        assertTrue(result.toString().indexOf(report.storageKey()) < 0);
        verify(reconciliationService).get(analysis.id(), analysis.owner());
    }

    @Test
    void revokedExpiredAndMissingReportCapabilitiesFailClosedWithoutReconciliationReads() {
        Analysis revokedAnalysis = createCompletedAnalysis();
        Instant now = clock.instant();
        GeneratedShareToken revokedToken = createLink(
                revokedAnalysis, now.plusSeconds(3600), now.minusSeconds(10));
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(revokedToken.rawToken()));

        Analysis expiredLinkAnalysis = createCompletedAnalysis();
        GeneratedShareToken expiredToken = createLink(
                expiredLinkAnalysis, now.minusSeconds(1), null);
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(expiredToken.rawToken()));

        Analysis withoutReport = createCompletedAnalysis();
        GeneratedShareToken noReportToken = createLink(withoutReport, now.plusSeconds(3600), null);
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(noReportToken.rawToken()));

        verify(reconciliationService, never()).get(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void malformedAndUnknownTokensHaveUniformNotFoundWithoutAnalysisLookup() {
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get("not-a-token"));
        GeneratedShareToken unknown = tokenGenerator.generate();
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(unknown.rawToken()));

        verify(reconciliationService, never()).get(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private Analysis createCompletedAnalysis() {
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        userIds.add(userId);
        Instant now = clock.instant();
        Instant createdAt = now.minusSeconds(120);
        Instant completedAt = now.minusSeconds(30);
        Analysis analysis = new Analysis(
                UUID.randomUUID(),
                new RegisteredUserOwner(userId),
                AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING,
                null,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Integration supplier",
                "integration supplier",
                "QUOTE",
                "REF-PUBLIC",
                "INV-PUBLIC",
                "USD",
                new BigDecimal("100.0000"),
                new BigDecimal("105.0000"),
                new BigDecimal("5.0000"),
                PriceTolerance.exactMatch(),
                false,
                null,
                null,
                0,
                completedAt,
                null,
                createdAt,
                completedAt);
        Analysis persisted = analysisRepository.create(analysis);
        analysisIds.add(persisted.id());
        return persisted;
    }

    private GeneratedShareToken createLink(Analysis analysis, Instant expiresAt, Instant revokedAt) {
        GeneratedShareToken token = tokenGenerator.generate();
        Instant createdAt = clock.instant().minusSeconds(20);
        shareLinkRepository.create(new ShareLink(
                UUID.randomUUID(), analysis.id(), token.tokenHash(), createdAt, expiresAt, revokedAt));
        return token;
    }

    private static ReconciliationResult reconciliation(Analysis analysis) {
        return new ReconciliationResult(
                analysis.id(),
                AnalysisStatus.COMPLETED,
                analysis.reconciliationStatus(),
                analysis.priceTolerance(),
                analysis.referenceTotal(),
                analysis.invoicedTotal(),
                analysis.difference(),
                DuplicateInvoiceWarning.none(),
                new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                List.of());
    }
}
