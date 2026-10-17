package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportConflictException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportNotFoundException;
import io.github.guillermodubon.invoward.reporting.application.model.PresignedReportDownload;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDownload;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.application.port.ReportStorage;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads an authorized persisted report and creates a short-lived private download link. */
public class GetGeneratedReportService {

    private final GetAnalysisService analysisService;
    private final GeneratedReportRepository generatedReportRepository;
    private final ReportStorage reportStorage;
    private final ReportDownloadPolicy downloadPolicy;
    private final Duration downloadUrlTtl;
    private final Clock clock;

    public GetGeneratedReportService(
            GetAnalysisService analysisService,
            GeneratedReportRepository generatedReportRepository,
            ReportStorage reportStorage,
            ReportDownloadPolicy downloadPolicy,
            Duration downloadUrlTtl,
            Clock clock) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService must not be null");
        this.generatedReportRepository = Objects.requireNonNull(
                generatedReportRepository, "generatedReportRepository must not be null");
        this.reportStorage = Objects.requireNonNull(reportStorage, "reportStorage must not be null");
        this.downloadPolicy = Objects.requireNonNull(downloadPolicy, "downloadPolicy must not be null");
        this.downloadUrlTtl = Objects.requireNonNull(downloadUrlTtl, "downloadUrlTtl must not be null");
        if (downloadUrlTtl.isNegative() || downloadUrlTtl.isZero()) {
            throw new IllegalArgumentException("downloadUrlTtl must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Returns persisted metadata and a newly signed URL, without generating or mutating a report. */
    public ReportDownload get(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        // Owner-scoped Analysis lookup must precede report persistence or storage access.
        Analysis analysis = analysisService.get(analysisId, owner);
        GeneratedReport report = generatedReportRepository.findByAnalysisId(analysis.id())
                .orElseThrow(ReportNotFoundException::new);
        if (!analysis.id().equals(report.analysisId())) {
            throw new ReportConflictException();
        }
        if (!Objects.equals(downloadPolicy.reportExpiresAt(analysis), report.expiresAt())) {
            throw new ReportConflictException();
        }

        Optional<Duration> ttl = downloadPolicy.signedUrlTtl(
                analysis, report, downloadUrlTtl, clock.instant());
        if (ttl.isEmpty()) {
            // Guest report retention is identical to its Analysis lifetime; keep expired owners indistinguishable.
            throw new AnalysisNotFoundException();
        }

        PresignedReportDownload presigned = reportStorage.createPresignedDownload(
                report.storageKey(), downloadPolicy.attachmentFilename(analysis.id()), ttl.orElseThrow());
        return ReportDownload.from(report, presigned);
    }
}
