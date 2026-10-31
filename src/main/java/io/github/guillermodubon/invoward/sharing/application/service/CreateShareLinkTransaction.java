package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.sharing.application.exception.DuplicateShareTokenHashException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareExpiryUnavailableException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkLimitReachedException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkNotShareableException;
import io.github.guillermodubon.invoward.sharing.application.model.CreatedShareLink;
import io.github.guillermodubon.invoward.sharing.application.model.GeneratedShareToken;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkExpiryChoice;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Performs owner validation, readiness checks, active-cap enforcement and one atomic insert. */
public class CreateShareLinkTransaction {

    private static final String PUBLIC_REPORT_PATH = "/api/public/reports/";

    private final AnalysisRepository analysisRepository;
    private final GetReconciliationService reconciliationService;
    private final GeneratedReportRepository generatedReportRepository;
    private final ShareLinkRepository shareLinkRepository;
    private final ShareTokenGenerator tokenGenerator;
    private final URI publicBaseUrl;
    private final int maxActiveLinksPerAnalysis;
    private final Clock clock;

    public CreateShareLinkTransaction(
            AnalysisRepository analysisRepository,
            GetReconciliationService reconciliationService,
            GeneratedReportRepository generatedReportRepository,
            ShareLinkRepository shareLinkRepository,
            ShareTokenGenerator tokenGenerator,
            URI publicBaseUrl,
            int maxActiveLinksPerAnalysis,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository, "analysisRepository must not be null");
        this.reconciliationService = Objects.requireNonNull(
                reconciliationService, "reconciliationService must not be null");
        this.generatedReportRepository = Objects.requireNonNull(
                generatedReportRepository, "generatedReportRepository must not be null");
        this.shareLinkRepository = Objects.requireNonNull(shareLinkRepository, "shareLinkRepository must not be null");
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator must not be null");
        this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl must not be null");
        if (maxActiveLinksPerAnalysis < 1 || maxActiveLinksPerAnalysis > 10) {
            throw new IllegalArgumentException("maxActiveLinksPerAnalysis must be between 1 and 10");
        }
        this.maxActiveLinksPerAnalysis = maxActiveLinksPerAnalysis;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public CreatedShareLink create(UUID analysisId, AnalysisOwner owner, ShareLinkExpiryChoice expiresIn) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(expiresIn, "expiresIn must not be null");

        Instant lockLookupTime = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, lockLookupTime)
                .orElseThrow(AnalysisNotFoundException::new);
        Instant now = clock.instant();
        if (owner instanceof GuestSessionOwner guestOwner
                && (!guestOwner.expiresAt().isAfter(now) || !analysis.expiresAt().isAfter(now))) {
            throw new AnalysisNotFoundException();
        }
        if (analysis.status() != AnalysisStatus.COMPLETED || analysis.completedAt() == null) {
            throw new ShareLinkNotShareableException();
        }

        ReconciliationResult reconciliation = readCompleteReconciliation(analysisId, owner);
        if (!analysis.id().equals(reconciliation.analysisId())
                || reconciliation.analysisStatus() != AnalysisStatus.COMPLETED
                || analysis.reconciliationStatus() != reconciliation.reconciliationStatus()) {
            throw new ShareLinkNotShareableException();
        }

        GeneratedReport report = generatedReportRepository.findByAnalysisId(analysis.id())
                .orElseThrow(ShareLinkNotShareableException::new);
        if (!analysis.id().equals(report.analysisId())) {
            throw new ShareLinkNotShareableException();
        }
        if (report.expiresAt() != null && !report.expiresAt().isAfter(now)) {
            throw new ShareExpiryUnavailableException();
        }

        if (shareLinkRepository.countActiveByAnalysisId(analysis.id(), now) >= maxActiveLinksPerAnalysis) {
            throw new ShareLinkLimitReachedException();
        }

        Instant expiresAt;
        try {
            expiresAt = expiresIn.calculateExpiresAt(now, analysis.expiresAt(), report.expiresAt());
        } catch (DateTimeException | IllegalArgumentException exception) {
            throw new ShareExpiryUnavailableException();
        }

        GeneratedShareToken generatedToken = tokenGenerator.generate();
        if (shareLinkRepository.findByTokenHash(generatedToken.tokenHash()).isPresent()) {
            throw new DuplicateShareTokenHashException();
        }

        ShareLink candidate = new ShareLink(
                UUID.randomUUID(), analysis.id(), generatedToken.tokenHash(), now, expiresAt, null);
        ShareLink persisted = shareLinkRepository.create(candidate);
        String publicUrl = publicBaseUrl.toASCIIString() + PUBLIC_REPORT_PATH + generatedToken.rawToken();
        return new CreatedShareLink(
                persisted.id(), publicUrl, expiresIn, persisted.createdAt(), persisted.expiresAt(), persisted.revokedAt());
    }

    private ReconciliationResult readCompleteReconciliation(UUID analysisId, AnalysisOwner owner) {
        try {
            return reconciliationService.get(analysisId, owner);
        } catch (ReconciliationNotFoundException | ReconciliationConflictException exception) {
            throw new ShareLinkNotShareableException();
        }
    }
}
