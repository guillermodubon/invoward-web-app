package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
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
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkLimitReachedException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkNotShareableException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareExpiryUnavailableException;
import io.github.guillermodubon.invoward.sharing.application.model.CreatedShareLink;
import io.github.guillermodubon.invoward.sharing.application.model.GeneratedShareToken;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkExpiryChoice;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateShareLinkTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final RegisteredUserOwner REGISTERED_OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String TOKEN_HASH = "a".repeat(64);

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final GetReconciliationService reconciliationService = mock(GetReconciliationService.class);
    private final GeneratedReportRepository generatedReportRepository = mock(GeneratedReportRepository.class);
    private final ShareLinkRepository shareLinkRepository = mock(ShareLinkRepository.class);
    private final ShareTokenGenerator tokenGenerator = mock(ShareTokenGenerator.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private Analysis analysis;
    private GeneratedReport report;
    private CreateShareLinkTransaction transaction;

    @BeforeEach
    void setUp() {
        analysis = completedAnalysis(ANALYSIS_ID, REGISTERED_OWNER, null);
        report = report(ANALYSIS_ID, null);
        transaction = new CreateShareLinkTransaction(
                analysisRepository,
                reconciliationService,
                generatedReportRepository,
                shareLinkRepository,
                tokenGenerator,
                URI.create("https://app.example.test/base"),
                10,
                clock);

        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.of(analysis));
        when(reconciliationService.get(ANALYSIS_ID, REGISTERED_OWNER))
                .thenReturn(reconciliation(analysis));
        when(generatedReportRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(report));
        when(shareLinkRepository.countActiveByAnalysisId(ANALYSIS_ID, NOW)).thenReturn(0L);
        when(tokenGenerator.generate()).thenReturn(new GeneratedShareToken(RAW_TOKEN, TOKEN_HASH));
        when(shareLinkRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.empty());
        when(shareLinkRepository.create(any(ShareLink.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void authorizesParentThenChecksCompleteReconciliationAndReportBeforeIssuingOneTimeUrl() {
        CreatedShareLink created = transaction.create(ANALYSIS_ID, REGISTERED_OWNER, ShareLinkExpiryChoice.DAYS_7);

        assertEquals("https://app.example.test/base/api/public/reports/" + RAW_TOKEN, created.publicUrl());
        assertEquals(ShareLinkExpiryChoice.DAYS_7, created.expiresIn());
        assertEquals(NOW, created.createdAt());
        assertEquals(NOW.plusSeconds(7 * 24 * 60 * 60), created.expiresAt());
        assertEquals(null, created.revokedAt());
        assertFalse(created.toString().contains(RAW_TOKEN));

        var order = inOrder(analysisRepository, reconciliationService, generatedReportRepository,
                shareLinkRepository, tokenGenerator);
        order.verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW);
        order.verify(reconciliationService).get(ANALYSIS_ID, REGISTERED_OWNER);
        order.verify(generatedReportRepository).findByAnalysisId(ANALYSIS_ID);
        order.verify(shareLinkRepository).countActiveByAnalysisId(ANALYSIS_ID, NOW);
        order.verify(tokenGenerator).generate();
        order.verify(shareLinkRepository).findByTokenHash(TOKEN_HASH);

        org.mockito.ArgumentCaptor<ShareLink> linkCaptor = org.mockito.ArgumentCaptor.forClass(ShareLink.class);
        verify(shareLinkRepository).create(linkCaptor.capture());
        ShareLink persisted = linkCaptor.getValue();
        assertEquals(ANALYSIS_ID, persisted.analysisId());
        assertEquals(TOKEN_HASH, persisted.tokenHash());
        assertFalse(persisted.tokenHash().equals(RAW_TOKEN));
        assertEquals(NOW, persisted.createdAt());
        assertEquals(created.expiresAt(), persisted.expiresAt());
        verify(analysisRepository, never()).update(any());
    }

    @Test
    void missingOrForeignParentStopsBeforeReconciliationReportOrTokenAccess() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> transaction.create(ANALYSIS_ID, REGISTERED_OWNER, ShareLinkExpiryChoice.HOURS_24));

        verifyNoInteractions(reconciliationService, generatedReportRepository, shareLinkRepository, tokenGenerator);
    }

    @Test
    void nonCompletedAnalysisIsNotShareableAndDoesNotReadReconciliationOrReport() {
        Analysis incomplete = Analysis.create(ANALYSIS_ID, REGISTERED_OWNER, PriceTolerance.exactMatch(), NOW);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.of(incomplete));

        assertThrows(ShareLinkNotShareableException.class,
                () -> transaction.create(ANALYSIS_ID, REGISTERED_OWNER, ShareLinkExpiryChoice.HOURS_24));

        verifyNoInteractions(reconciliationService, generatedReportRepository, shareLinkRepository, tokenGenerator);
    }

    @Test
    void missingOrInconsistentPersistedReconciliationIsMappedToNotShareable() {
        when(reconciliationService.get(ANALYSIS_ID, REGISTERED_OWNER))
                .thenThrow(new ReconciliationNotFoundException());
        assertThrows(ShareLinkNotShareableException.class,
                () -> transaction.create(ANALYSIS_ID, REGISTERED_OWNER, ShareLinkExpiryChoice.HOURS_24));
        verifyNoInteractions(generatedReportRepository, shareLinkRepository, tokenGenerator);

        doThrow(new ReconciliationConflictException()).when(reconciliationService).get(ANALYSIS_ID, REGISTERED_OWNER);
        assertThrows(ShareLinkNotShareableException.class,
                () -> transaction.create(ANALYSIS_ID, REGISTERED_OWNER, ShareLinkExpiryChoice.HOURS_24));
        verifyNoInteractions(generatedReportRepository, shareLinkRepository, tokenGenerator);
    }

    @Test
    void missingOrMismatchedGeneratedReportStopsBeforeCapAndTokenGeneration() {
        when(generatedReportRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.empty());
        assertThrows(ShareLinkNotShareableException.class,
                () -> transaction.create(ANALYSIS_ID, REGISTERED_OWNER, ShareLinkExpiryChoice.HOURS_24));
        verify(shareLinkRepository, never()).countActiveByAnalysisId(any(), any());
        verifyNoInteractions(tokenGenerator);

        when(generatedReportRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(
                Optional.of(report(UUID.randomUUID(), null)));
        assertThrows(ShareLinkNotShareableException.class,
                () -> transaction.create(ANALYSIS_ID, REGISTERED_OWNER, ShareLinkExpiryChoice.HOURS_24));
        verify(shareLinkRepository, never()).countActiveByAnalysisId(any(), any());
        verifyNoInteractions(tokenGenerator);
    }

    @Test
    void activeCapIsCheckedBeforeGeneratingOrPersistingToken() {
        when(shareLinkRepository.countActiveByAnalysisId(ANALYSIS_ID, NOW)).thenReturn(10L);

        assertThrows(ShareLinkLimitReachedException.class,
                () -> transaction.create(ANALYSIS_ID, REGISTERED_OWNER, ShareLinkExpiryChoice.HOURS_24));

        verifyNoInteractions(tokenGenerator);
        verify(shareLinkRepository, never()).create(any());
    }

    @Test
    void guestLinkExpiryIsCappedByFixedAnalysisAndReportExpiry() {
        Instant guestExpiry = NOW.plusSeconds(3 * 24 * 60 * 60L);
        Instant reportExpiry = NOW.plusSeconds(2 * 24 * 60 * 60L);
        GuestSessionOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), guestExpiry);
        Analysis guestAnalysis = completedAnalysis(ANALYSIS_ID, guestOwner, guestExpiry);
        GeneratedReport guestReport = report(ANALYSIS_ID, reportExpiry);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, guestOwner, NOW))
                .thenReturn(Optional.of(guestAnalysis));
        when(reconciliationService.get(ANALYSIS_ID, guestOwner)).thenReturn(reconciliation(guestAnalysis));
        when(generatedReportRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(guestReport));
        when(shareLinkRepository.countActiveByAnalysisId(ANALYSIS_ID, NOW)).thenReturn(0L);

        CreatedShareLink created = transaction.create(ANALYSIS_ID, guestOwner, ShareLinkExpiryChoice.DAYS_30);

        assertEquals(reportExpiry, created.expiresAt());
    }

    @Test
    void applicableReportExpiryAlsoCapsRegisteredLinks() {
        Instant reportExpiry = NOW.plusSeconds(12 * 60 * 60L);
        when(generatedReportRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(Optional.of(report(ANALYSIS_ID, reportExpiry)));

        CreatedShareLink created = transaction.create(
                ANALYSIS_ID, REGISTERED_OWNER, ShareLinkExpiryChoice.HOURS_24);

        assertEquals(reportExpiry, created.expiresAt());
    }

    @Test
    void guestExpiryIsRecheckedAfterAnalysisLockAcquisition() {
        Instant expiresAt = NOW.plusSeconds(30);
        GuestSessionOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), expiresAt);
        Analysis guestAnalysis = completedAnalysis(ANALYSIS_ID, guestOwner, expiresAt);
        AtomicInteger reads = new AtomicInteger();
        Clock advancingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return reads.getAndIncrement() == 0 ? NOW : expiresAt;
            }
        };
        CreateShareLinkTransaction expiryAwareTransaction = new CreateShareLinkTransaction(
                analysisRepository, reconciliationService, generatedReportRepository, shareLinkRepository,
                tokenGenerator, URI.create("https://app.example.test"), 10, advancingClock);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, guestOwner, NOW))
                .thenReturn(Optional.of(guestAnalysis));

        assertThrows(AnalysisNotFoundException.class,
                () -> expiryAwareTransaction.create(ANALYSIS_ID, guestOwner, ShareLinkExpiryChoice.HOURS_24));

        verifyNoInteractions(reconciliationService, generatedReportRepository, shareLinkRepository, tokenGenerator);
    }

    @Test
    void expiredGuestReportIsRejectedWithoutIssuingToken() {
        Instant guestExpiry = NOW.plusSeconds(3600);
        GuestSessionOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), guestExpiry);
        Analysis guestAnalysis = completedAnalysis(ANALYSIS_ID, guestOwner, guestExpiry);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, guestOwner, NOW))
                .thenReturn(Optional.of(guestAnalysis));
        when(reconciliationService.get(ANALYSIS_ID, guestOwner)).thenReturn(reconciliation(guestAnalysis));
        when(generatedReportRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(Optional.of(report(ANALYSIS_ID, NOW.minusSeconds(1))));

        assertThrows(ShareExpiryUnavailableException.class,
                () -> transaction.create(ANALYSIS_ID, guestOwner, ShareLinkExpiryChoice.HOURS_24));
        verifyNoInteractions(tokenGenerator);
    }

    @Test
    void transactionBoundaryIsSeparateFromFacadeSoUrlIsReturnedOnlyAfterCommit() throws NoSuchMethodException {
        Transactional annotation = CreateShareLinkTransaction.class
                .getMethod("create", UUID.class, AnalysisOwner.class, ShareLinkExpiryChoice.class)
                .getAnnotation(Transactional.class);

        assertTrue(annotation != null);
        assertFalse(annotation.readOnly());
        assertFalse(CreateShareLinkService.class
                .getMethod("create", UUID.class, AnalysisOwner.class, ShareLinkExpiryChoice.class)
                .isAnnotationPresent(Transactional.class));
    }

    private static Analysis completedAnalysis(UUID id, AnalysisOwner owner, Instant expiresAt) {
        Instant createdAt = NOW.minusSeconds(60);
        Instant completedAt = NOW.minusSeconds(30);
        return new Analysis(
                id, owner, AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, null,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Example Supplier", "example supplier", "QUOTE", "REF-100", "INV-200", "USD",
                new BigDecimal("100.0000"), new BigDecimal("110.0000"), new BigDecimal("10.0000"),
                PriceTolerance.exactMatch(), false, null, null, 0, completedAt, expiresAt, createdAt, completedAt);
    }

    private static GeneratedReport report(UUID analysisId, Instant expiresAt) {
        return new GeneratedReport(UUID.randomUUID(), analysisId, "private/report.pdf", "application/pdf",
                100, "b".repeat(64), expiresAt, NOW.minusSeconds(20));
    }

    private static ReconciliationResult reconciliation(Analysis analysis) {
        return new ReconciliationResult(
                analysis.id(), AnalysisStatus.COMPLETED, analysis.reconciliationStatus(), analysis.priceTolerance(),
                analysis.referenceTotal(), analysis.invoicedTotal(), analysis.difference(),
                DuplicateInvoiceWarning.none(), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0),
                java.util.List.of(), java.util.List.of());
    }
}
