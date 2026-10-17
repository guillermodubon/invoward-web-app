package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportConflictException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportNotFoundException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.model.PresignedReportDownload;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDownload;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.application.port.ReportStorage;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetGeneratedReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

    private final UUID analysisId = UUID.randomUUID();
    private final AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
    private final GetAnalysisService analysisService = mock(GetAnalysisService.class);
    private final GeneratedReportRepository generatedReportRepository = mock(GeneratedReportRepository.class);
    private final ReportStorage reportStorage = mock(ReportStorage.class);
    private final ReportDownloadPolicy downloadPolicy = new ReportDownloadPolicy();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private Analysis analysis;
    private GeneratedReport report;
    private GetGeneratedReportService service;

    @BeforeEach
    void setUp() {
        analysis = Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), NOW.minusSeconds(30));
        report = report(analysisId, null);
        service = new GetGeneratedReportService(
                analysisService, generatedReportRepository, reportStorage, downloadPolicy, DOWNLOAD_TTL, clock);

        when(analysisService.get(analysisId, owner)).thenReturn(analysis);
        when(generatedReportRepository.findByAnalysisId(analysisId)).thenReturn(Optional.of(report));
        when(reportStorage.createPresignedDownload(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(call -> new PresignedReportDownload(
                        URI.create("https://private-storage.invalid/signed"), NOW.plus(call.getArgument(2))));
    }

    @Test
    void authorizedOwnerReadsPersistedMetadataAndReceivesFreshShortLivedUrl() {
        ReportDownload result = service.get(analysisId, owner);

        assertEquals(report.id(), result.id());
        assertEquals("application/pdf", result.contentType());
        assertEquals(report.sizeBytes(), result.sizeBytes());
        assertEquals(report.generatedAt(), result.generatedAt());
        assertEquals(URI.create("https://private-storage.invalid/signed"), result.downloadUrl());
        assertEquals(NOW.plus(DOWNLOAD_TTL), result.downloadUrlExpiresAt());
        assertFalse(result.toString().contains(report.storageKey()));
        assertFalse(result.toString().contains(report.sha256()));

        InOrder order = inOrder(analysisService, generatedReportRepository, reportStorage);
        order.verify(analysisService).get(analysisId, owner);
        order.verify(generatedReportRepository).findByAnalysisId(analysisId);
        order.verify(reportStorage).createPresignedDownload(
                report.storageKey(), "invoward-report-" + analysisId + ".pdf", DOWNLOAD_TTL);
        verify(generatedReportRepository, never()).create(any());
        verify(generatedReportRepository, never()).existsByAnalysisId(any());
    }

    @Test
    void guestDownloadTtlIsCappedAtTheFixedAnalysisExpiry() {
        Instant expiry = NOW.plusSeconds(75);
        AnalysisOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), expiry);
        Analysis guestAnalysis = Analysis.create(
                analysisId, guestOwner, PriceTolerance.exactMatch(), NOW.minusSeconds(30));
        GeneratedReport guestReport = report(analysisId, expiry);
        when(analysisService.get(analysisId, guestOwner)).thenReturn(guestAnalysis);
        when(generatedReportRepository.findByAnalysisId(analysisId)).thenReturn(Optional.of(guestReport));
        when(reportStorage.createPresignedDownload(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(call -> new PresignedReportDownload(
                        URI.create("https://private-storage.invalid/guest"), NOW.plus(call.getArgument(2))));

        ReportDownload result = service.get(analysisId, guestOwner);

        verify(reportStorage).createPresignedDownload(
                guestReport.storageKey(), "invoward-report-" + analysisId + ".pdf", Duration.ofSeconds(74));
        assertEquals(expiry, result.expiresAt());
        assertEquals(expiry.minusSeconds(1), result.downloadUrlExpiresAt());
    }

    @Test
    void ownerAuthorizationHappensBeforeReportOrStorageAccess() {
        when(analysisService.get(analysisId, owner)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class, () -> service.get(analysisId, owner));

        verifyNoInteractions(generatedReportRepository, reportStorage);
    }

    @Test
    void missingPersistedReportReturnsSafeNotFoundWithoutStorageAccess() {
        when(generatedReportRepository.findByAnalysisId(analysisId)).thenReturn(Optional.empty());

        ReportNotFoundException exception = assertThrows(
                ReportNotFoundException.class, () -> service.get(analysisId, owner));

        assertEquals("Report was not found.", exception.getMessage());
        verifyNoInteractions(reportStorage);
    }

    @Test
    void expiredGuestIsHiddenByOwnerAuthorizationBeforeReportLookup() {
        AnalysisNotFoundException notFound = new AnalysisNotFoundException();
        when(analysisService.get(analysisId, owner)).thenThrow(notFound);

        assertThrows(AnalysisNotFoundException.class, () -> service.get(analysisId, owner));

        verifyNoInteractions(generatedReportRepository, reportStorage);
    }

    @Test
    void reportThatExpiresWithGuestAnalysisIsNotPresignedAfterExpiry() {
        Instant expiry = NOW;
        AnalysisOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), expiry);
        Analysis guestAnalysis = Analysis.create(
                analysisId, guestOwner, PriceTolerance.exactMatch(), NOW.minusSeconds(30));
        when(analysisService.get(analysisId, guestOwner)).thenReturn(guestAnalysis);
        when(generatedReportRepository.findByAnalysisId(analysisId)).thenReturn(Optional.of(report(analysisId, expiry)));

        assertThrows(AnalysisNotFoundException.class, () -> service.get(analysisId, guestOwner));

        verify(reportStorage, never()).createPresignedDownload(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void inconsistentReportAssociationReturnsConflictWithoutPresigning() {
        when(generatedReportRepository.findByAnalysisId(analysisId))
                .thenReturn(Optional.of(report(UUID.randomUUID(), null)));

        assertThrows(ReportConflictException.class, () -> service.get(analysisId, owner));

        verifyNoInteractions(reportStorage);
    }

    @Test
    void reportExpiryMismatchReturnsSafeConflictWithoutPresigning() {
        AnalysisOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), NOW.plusSeconds(120));
        Analysis guestAnalysis = Analysis.create(
                analysisId, guestOwner, PriceTolerance.exactMatch(), NOW.minusSeconds(30));
        when(analysisService.get(analysisId, guestOwner)).thenReturn(guestAnalysis);
        when(generatedReportRepository.findByAnalysisId(analysisId)).thenReturn(Optional.of(report(analysisId, null)));

        assertThrows(ReportConflictException.class, () -> service.get(analysisId, guestOwner));

        verifyNoInteractions(reportStorage);
    }

    @Test
    void presignFailureIsPropagatedAsProviderNeutralStorageFailure() {
        ReportStorageException failure = new ReportStorageException(ReportStorageException.Failure.UNAVAILABLE);
        org.mockito.Mockito.doThrow(failure)
                .when(reportStorage).createPresignedDownload(anyString(), anyString(), any(Duration.class));

        assertThrows(ReportStorageException.class, () -> service.get(analysisId, owner));

        verify(generatedReportRepository).findByAnalysisId(analysisId);
        verify(generatedReportRepository, never()).create(any());
        verify(reportStorage, never()).store(anyString(), any());
        verify(reportStorage, never()).delete(anyString());
    }

    @Test
    void constructorRejectsNonPositiveConfiguredDownloadTtl() {
        assertThrows(IllegalArgumentException.class, () -> new GetGeneratedReportService(
                analysisService, generatedReportRepository, reportStorage, downloadPolicy, Duration.ZERO, clock));
        assertThrows(IllegalArgumentException.class, () -> new GetGeneratedReportService(
                analysisService, generatedReportRepository, reportStorage, downloadPolicy,
                Duration.ofSeconds(-1), clock));
        assertTrue(DOWNLOAD_TTL.isPositive());
    }

    private static GeneratedReport report(UUID analysisId, Instant expiresAt) {
        return new GeneratedReport(UUID.randomUUID(), analysisId, "internal/report-key.pdf",
                "application/pdf", 512, "c".repeat(64), expiresAt, NOW.minusSeconds(20));
    }
}
