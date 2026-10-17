package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.service.GetExtractionService;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.exception.AnalysisReportNotAllowedException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportConflictException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportInputNotReadyException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.model.GenerateReportResult;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportPersistenceResult;
import io.github.guillermodubon.invoward.reporting.application.model.PresignedReportDownload;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDownload;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.application.port.ReportArtifactGenerator;
import io.github.guillermodubon.invoward.reporting.application.port.ReportStorage;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Coordinates immutable PDF generation without holding a database transaction during rendering or storage. */
public class GenerateReportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerateReportService.class);
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final GetAnalysisService analysisService;
    private final GeneratedReportRepository generatedReportRepository;
    private final GetReconciliationService reconciliationService;
    private final GetExtractionService extractionService;
    private final ReportArtifactGenerator artifactGenerator;
    private final ReportStorage reportStorage;
    private final PersistGeneratedReportTransaction persistenceTransaction;
    private final ReportStorageKeyGenerator storageKeyGenerator;
    private final ReportDownloadPolicy downloadPolicy;
    private final Duration downloadUrlTtl;
    private final Clock clock;

    public GenerateReportService(
            GetAnalysisService analysisService,
            GeneratedReportRepository generatedReportRepository,
            GetReconciliationService reconciliationService,
            GetExtractionService extractionService,
            ReportArtifactGenerator artifactGenerator,
            ReportStorage reportStorage,
            PersistGeneratedReportTransaction persistenceTransaction,
            ReportStorageKeyGenerator storageKeyGenerator,
            ReportDownloadPolicy downloadPolicy,
            Duration downloadUrlTtl,
            Clock clock) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService must not be null");
        this.generatedReportRepository = Objects.requireNonNull(
                generatedReportRepository, "generatedReportRepository must not be null");
        this.reconciliationService = Objects.requireNonNull(
                reconciliationService, "reconciliationService must not be null");
        this.extractionService = Objects.requireNonNull(extractionService, "extractionService must not be null");
        this.artifactGenerator = Objects.requireNonNull(artifactGenerator, "artifactGenerator must not be null");
        this.reportStorage = Objects.requireNonNull(reportStorage, "reportStorage must not be null");
        this.persistenceTransaction = Objects.requireNonNull(
                persistenceTransaction, "persistenceTransaction must not be null");
        this.storageKeyGenerator = Objects.requireNonNull(
                storageKeyGenerator, "storageKeyGenerator must not be null");
        this.downloadPolicy = Objects.requireNonNull(downloadPolicy, "downloadPolicy must not be null");
        this.downloadUrlTtl = Objects.requireNonNull(downloadUrlTtl, "downloadUrlTtl must not be null");
        if (downloadUrlTtl.isNegative() || downloadUrlTtl.isZero()) {
            throw new IllegalArgumentException("downloadUrlTtl must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Generates and persists a PDF once, or returns the existing immutable report for this owner. */
    public GenerateReportResult generate(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Analysis analysis = analysisService.get(analysisId, owner);
        Optional<GeneratedReport> existing = generatedReportRepository.findByAnalysisId(analysis.id());
        if (existing.isPresent()) {
            return download(analysis, existing.orElseThrow(), false);
        }
        requireReportGenerationState(analysis);

        ReconciliationResult reconciliation = loadCompleteReconciliation(analysisId, owner);
        ExtractionReview extraction = loadConfirmedExtraction(analysisId, owner);
        Instant generatedAt = clock.instant();
        ReportSnapshot snapshot = ReportSnapshotAssembler.assemble(
                analysis,
                reconciliation,
                extraction.invoice().extraction().documentDate(),
                extraction.reference().extraction().documentDate(),
                generatedAt);

        GeneratedReportPersistenceResult persisted;
        try (GeneratedReportArtifact artifact = artifactGenerator.generate(snapshot)) {
            String storageKey = storageKeyGenerator.generate(analysis);
            GeneratedReport candidate = new GeneratedReport(
                    UUID.randomUUID(),
                    analysis.id(),
                    storageKey,
                    PDF_CONTENT_TYPE,
                    artifact.sizeBytes(),
                    artifact.sha256(),
                    downloadPolicy.reportExpiresAt(analysis),
                    generatedAt);

            reportStorage.store(storageKey, artifact);
            try {
                persisted = persistenceTransaction.persist(owner, candidate);
            } catch (RuntimeException persistenceFailure) {
                compensateStoredObject(storageKey, analysis.id(), persistenceFailure);
                throw persistenceFailure;
            }

            if (!persisted.createdByThisCall()) {
                deleteConcurrentLoserObject(storageKey, analysis.id());
            }
        }
        return download(analysis, persisted.report(), persisted.createdByThisCall());
    }

    private static void requireReportGenerationState(Analysis analysis) {
        if (analysis.status() == AnalysisStatus.COMPLETED) {
            throw new ReportConflictException();
        }
        if (analysis.status() != AnalysisStatus.GENERATING_REPORT) {
            throw new AnalysisReportNotAllowedException();
        }
    }

    private ReconciliationResult loadCompleteReconciliation(UUID analysisId, AnalysisOwner owner) {
        try {
            return reconciliationService.get(analysisId, owner);
        } catch (ReconciliationNotFoundException | ReconciliationConflictException exception) {
            throw new ReportInputNotReadyException(exception);
        }
    }

    private ExtractionReview loadConfirmedExtraction(UUID analysisId, AnalysisOwner owner) {
        ExtractionReview review;
        try {
            review = extractionService.get(analysisId, owner);
        } catch (ExtractionNotFoundException | ExtractionConflictException exception) {
            throw new ReportInputNotReadyException(exception);
        }
        if (review.reference().extraction().status() != ExtractionStatus.CONFIRMED
                || review.invoice().extraction().status() != ExtractionStatus.CONFIRMED) {
            throw new ReportInputNotReadyException();
        }
        return review;
    }

    private GenerateReportResult download(Analysis analysis, GeneratedReport report, boolean created) {
        if (!analysis.id().equals(report.analysisId())
                || !Objects.equals(downloadPolicy.reportExpiresAt(analysis), report.expiresAt())) {
            throw new ReportConflictException();
        }
        Optional<Duration> ttl = downloadPolicy.signedUrlTtl(
                analysis, report, downloadUrlTtl, clock.instant());
        if (ttl.isEmpty()) {
            throw new AnalysisNotFoundException();
        }
        PresignedReportDownload presigned = reportStorage.createPresignedDownload(
                report.storageKey(), downloadPolicy.attachmentFilename(analysis.id()), ttl.orElseThrow());
        return new GenerateReportResult(ReportDownload.from(report, presigned), created);
    }

    private void compensateStoredObject(String storageKey, UUID analysisId, RuntimeException originalFailure) {
        try {
            reportStorage.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            logCleanupFailure(analysisId, cleanupFailure);
        }
    }

    private void deleteConcurrentLoserObject(String storageKey, UUID analysisId) {
        try {
            reportStorage.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            // The winner remains authoritative; log only a safe category, never the object key or URL.
            logCleanupFailure(analysisId, cleanupFailure);
        }
    }

    private static void logCleanupFailure(UUID analysisId, RuntimeException exception) {
        String failure = exception instanceof ReportStorageException storageException
                ? storageException.failure().name()
                : ReportStorageException.Failure.UNKNOWN.name();
        LOGGER.warn("operation=report_storage_cleanup result=failure analysisId={} failure={}",
                analysisId, failure);
    }
}
