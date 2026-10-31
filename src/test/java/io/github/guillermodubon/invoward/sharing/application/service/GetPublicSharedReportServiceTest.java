package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.sharing.application.exception.PublicSharedReportNotFoundException;
import io.github.guillermodubon.invoward.sharing.application.model.PublicSharedReport;
import io.github.guillermodubon.invoward.sharing.application.port.ShareAuthorizedAnalysisReader;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetPublicSharedReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID LINK_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private final ShareTokenGenerator tokenGenerator = mock(ShareTokenGenerator.class);
    private final ShareLinkRepository shareLinkRepository = mock(ShareLinkRepository.class);
    private final ShareAuthorizedAnalysisReader analysisReader = mock(ShareAuthorizedAnalysisReader.class);
    private final GeneratedReportRepository reportRepository = mock(GeneratedReportRepository.class);
    private final GetReconciliationService reconciliationService = mock(GetReconciliationService.class);
    private final PublicSharedReportAssembler assembler = new PublicSharedReportAssembler();
    private final GetPublicSharedReportService service = new GetPublicSharedReportService(
            tokenGenerator,
            shareLinkRepository,
            analysisReader,
            reportRepository,
            reconciliationService,
            assembler,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void configureTokenHash() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
    }

    @Test
    void rejectsMalformedTokenBeforeHashingOrAnyRepositoryAccess() {
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get("malformed"));

        verifyNoInteractions(tokenGenerator, shareLinkRepository, analysisReader, reportRepository,
                reconciliationService);
    }

    @Test
    void rejectsUnknownRevokedAndExpiredLinksBeforeReadingParentData() {
        when(shareLinkRepository.findByTokenHash(TOKEN_HASH))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(link(NOW.plusSeconds(60), NOW.minusSeconds(1))))
                .thenReturn(Optional.of(link(NOW, null)));

        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));

        verify(shareLinkRepository, org.mockito.Mockito.times(3)).findByTokenHash(TOKEN_HASH);
        verifyNoInteractions(analysisReader, reportRepository, reconciliationService);
    }

    @Test
    void missingAnalysisIsUniformNotFoundAndPreventsReportOrReconciliationReads() {
        when(shareLinkRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(activeLink()));
        when(analysisReader.findById(ANALYSIS_ID)).thenReturn(Optional.empty());

        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));

        verifyNoInteractions(reportRepository, reconciliationService);
    }

    @Test
    void rejectsIncompleteOrExpiredAnalysisBeforeReadingReport() {
        when(shareLinkRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(activeLink()));
        when(analysisReader.findById(ANALYSIS_ID)).thenReturn(Optional.of(
                analysis(AnalysisStatus.GENERATING_REPORT, new RegisteredUserOwner(USER_ID), null)));
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));

        GuestSessionOwner expiredOwner = new GuestSessionOwner(UUID.randomUUID(), NOW.minusSeconds(1));
        when(analysisReader.findById(ANALYSIS_ID)).thenReturn(Optional.of(
                analysis(AnalysisStatus.COMPLETED, expiredOwner, expiredOwner.expiresAt())));
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));

        verifyNoInteractions(reportRepository, reconciliationService);
    }

    @Test
    void rejectsMissingOrExpiredReportBeforeReadingReconciliation() {
        Analysis completed = completedRegisteredAnalysis();
        when(shareLinkRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(activeLink()));
        when(analysisReader.findById(ANALYSIS_ID)).thenReturn(Optional.of(completed));
        when(reportRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(report(NOW)));

        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));

        verifyNoInteractions(reconciliationService);
    }

    @Test
    void delegatesCompletePersistedReconciliationAndReturnsOnlyPublicProjection() {
        Analysis completed = completedRegisteredAnalysis();
        GeneratedReport report = report(null);
        when(shareLinkRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(activeLink()));
        when(analysisReader.findById(ANALYSIS_ID)).thenReturn(Optional.of(completed));
        when(reportRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(report));
        when(reconciliationService.get(ANALYSIS_ID, completed.owner())).thenReturn(reconciliation(completed));

        PublicSharedReport result = service.get(RAW_TOKEN);

        assertEquals(report.generatedAt(), result.reportGeneratedAt());
        assertEquals("Private supplier", result.supplierName());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, result.reconciliationStatus());
        assertEquals(0, result.summary().lineCount());
        assertFalse(result.toString().contains(USER_ID.toString()));
        assertFalse(result.toString().contains(report.storageKey()));

        var ordered = inOrder(shareLinkRepository, analysisReader, reportRepository, reconciliationService);
        ordered.verify(shareLinkRepository).findByTokenHash(TOKEN_HASH);
        ordered.verify(analysisReader).findById(ANALYSIS_ID);
        ordered.verify(reportRepository).findByAnalysisId(ANALYSIS_ID);
        ordered.verify(reconciliationService).get(ANALYSIS_ID, completed.owner());
        ordered.verify(shareLinkRepository).findByTokenHash(TOKEN_HASH);
        verify(shareLinkRepository, never()).create(any());
        verify(shareLinkRepository, never()).update(any());
        verify(reportRepository, never()).create(any());
    }

    @Test
    void rechecksLinkImmediatelyBeforeReturningAndRejectsConcurrentRevocation() {
        Analysis completed = completedRegisteredAnalysis();
        when(shareLinkRepository.findByTokenHash(TOKEN_HASH))
                .thenReturn(Optional.of(activeLink()))
                .thenReturn(Optional.of(link(NOW.plusSeconds(60), NOW.minusSeconds(1))));
        when(analysisReader.findById(ANALYSIS_ID)).thenReturn(Optional.of(completed));
        when(reportRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(report(null)));
        when(reconciliationService.get(ANALYSIS_ID, completed.owner())).thenReturn(reconciliation(completed));

        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));
    }

    @Test
    void mapsUnavailableOrInconsistentPersistedReconciliationToUniformNotFound() {
        Analysis completed = completedRegisteredAnalysis();
        when(shareLinkRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(activeLink()));
        when(analysisReader.findById(ANALYSIS_ID)).thenReturn(Optional.of(completed));
        when(reportRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(report(null)));
        when(reconciliationService.get(ANALYSIS_ID, completed.owner()))
                .thenThrow(new ReconciliationNotFoundException())
                .thenThrow(new ReconciliationConflictException());

        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));
        assertThrows(PublicSharedReportNotFoundException.class, () -> service.get(RAW_TOKEN));
    }

    @Test
    void readOperationIsTransactionalReadOnly() throws NoSuchMethodException {
        Transactional transactional = GetPublicSharedReportService.class.getAnnotation(Transactional.class);
        assertTrue(transactional.readOnly());
    }

    private static ShareLink activeLink() {
        return link(NOW.plusSeconds(3600), null);
    }

    private static ShareLink link(Instant expiresAt, Instant revokedAt) {
        return new ShareLink(LINK_ID, ANALYSIS_ID, TOKEN_HASH, NOW.minusSeconds(60), expiresAt, revokedAt);
    }

    private static Analysis completedRegisteredAnalysis() {
        return analysis(AnalysisStatus.COMPLETED, new RegisteredUserOwner(USER_ID), null);
    }

    private static Analysis analysis(AnalysisStatus status, AnalysisOwner owner,
            Instant expiresAt) {
        Instant createdAt = NOW.minusSeconds(120);
        Instant completedAt = status == AnalysisStatus.COMPLETED ? NOW.minusSeconds(30) : null;
        return new Analysis(
                ANALYSIS_ID,
                owner,
                status,
                AnalysisReviewStatus.PENDING,
                null,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Private supplier",
                "private supplier key",
                "QUOTE",
                "REF-1",
                "INV-1",
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
                expiresAt,
                createdAt,
                completedAt == null ? NOW.minusSeconds(30) : completedAt);
    }

    private static GeneratedReport report(Instant expiresAt) {
        return new GeneratedReport(UUID.randomUUID(), ANALYSIS_ID, "private/report-key.pdf", "application/pdf",
                123, "b".repeat(64), expiresAt, NOW.minusSeconds(20));
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
                java.util.List.of(),
                java.util.List.of());
    }
}
