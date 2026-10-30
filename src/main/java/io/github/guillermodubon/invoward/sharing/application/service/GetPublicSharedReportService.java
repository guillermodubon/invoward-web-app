package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
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
import io.github.guillermodubon.invoward.sharing.application.exception.PublicSharedReportNotFoundException;
import io.github.guillermodubon.invoward.sharing.application.model.GeneratedShareToken;
import io.github.guillermodubon.invoward.sharing.application.model.PublicSharedReport;
import io.github.guillermodubon.invoward.sharing.application.port.ShareAuthorizedAnalysisReader;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authorizes a public share capability and reads only its persisted, whitelisted report projection. */
@Transactional(readOnly = true)
public class GetPublicSharedReportService {

    private final ShareTokenGenerator tokenGenerator;
    private final ShareLinkRepository shareLinkRepository;
    private final ShareAuthorizedAnalysisReader analysisReader;
    private final GeneratedReportRepository generatedReportRepository;
    private final GetReconciliationService reconciliationService;
    private final PublicSharedReportAssembler assembler;
    private final Clock clock;

    public GetPublicSharedReportService(
            ShareTokenGenerator tokenGenerator,
            ShareLinkRepository shareLinkRepository,
            ShareAuthorizedAnalysisReader analysisReader,
            GeneratedReportRepository generatedReportRepository,
            GetReconciliationService reconciliationService,
            PublicSharedReportAssembler assembler,
            Clock clock) {
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator must not be null");
        this.shareLinkRepository = Objects.requireNonNull(shareLinkRepository, "shareLinkRepository must not be null");
        this.analysisReader = Objects.requireNonNull(analysisReader, "analysisReader must not be null");
        this.generatedReportRepository = Objects.requireNonNull(
                generatedReportRepository, "generatedReportRepository must not be null");
        this.reconciliationService = Objects.requireNonNull(
                reconciliationService, "reconciliationService must not be null");
        this.assembler = Objects.requireNonNull(assembler, "assembler must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public PublicSharedReport get(String rawToken) {
        if (!GeneratedShareToken.isCanonicalRawToken(rawToken)) {
            throw notFound();
        }

        String tokenHash = tokenGenerator.hash(rawToken);
        ShareLink authorizedLink = shareLinkRepository.findByTokenHash(tokenHash)
                .filter(link -> link.isActive(clock.instant()))
                .orElseThrow(GetPublicSharedReportService::notFound);

        Analysis analysis = analysisReader.findById(authorizedLink.analysisId())
                .orElseThrow(GetPublicSharedReportService::notFound);
        Instant parentCheckTime = clock.instant();
        if (!isAvailableAnalysis(analysis, parentCheckTime)) {
            throw notFound();
        }

        GeneratedReport report = generatedReportRepository.findByAnalysisId(analysis.id())
                .filter(candidate -> analysis.id().equals(candidate.analysisId()))
                .filter(candidate -> isAvailable(candidate.expiresAt(), parentCheckTime))
                .orElseThrow(GetPublicSharedReportService::notFound);

        ReconciliationResult reconciliation = readCompleteReconciliation(analysis.id(), analysis.owner());
        if (!analysis.id().equals(reconciliation.analysisId())
                || reconciliation.analysisStatus() != AnalysisStatus.COMPLETED
                || analysis.reconciliationStatus() != reconciliation.reconciliationStatus()) {
            throw notFound();
        }

        PublicSharedReport publicReport;
        try {
            publicReport = assembler.assemble(analysis, reconciliation, report.generatedAt());
        } catch (IllegalArgumentException exception) {
            // A corrupt or mismatched persisted snapshot is indistinguishable from an unavailable share.
            throw notFound();
        }

        ShareLink currentLink = shareLinkRepository.findByTokenHash(tokenHash)
                .orElseThrow(GetPublicSharedReportService::notFound);
        Instant finalCheckTime = clock.instant();
        if (!currentLink.id().equals(authorizedLink.id())
                || !currentLink.analysisId().equals(analysis.id())
                || !currentLink.isActive(finalCheckTime)
                || !isAvailableAnalysis(analysis, finalCheckTime)
                || !isAvailable(report.expiresAt(), finalCheckTime)) {
            throw notFound();
        }

        return publicReport;
    }

    private ReconciliationResult readCompleteReconciliation(
            UUID analysisId, AnalysisOwner owner) {
        try {
            return reconciliationService.get(analysisId, owner);
        } catch (AnalysisNotFoundException | ReconciliationNotFoundException | ReconciliationConflictException e) {
            throw notFound();
        } catch (IllegalArgumentException exception) {
            // Invalid aggregate assembly indicates that the persisted reconciliation is inconsistent.
            throw notFound();
        }
    }

    private static boolean isAvailableAnalysis(Analysis analysis, Instant now) {
        if (analysis.status() != AnalysisStatus.COMPLETED || !isAvailable(analysis.expiresAt(), now)) {
            return false;
        }
        return !(analysis.owner() instanceof GuestSessionOwner guestOwner)
                || guestOwner.expiresAt().isAfter(now);
    }

    private static boolean isAvailable(Instant expiresAt, Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    private static PublicSharedReportNotFoundException notFound() {
        return new PublicSharedReportNotFoundException();
    }
}
