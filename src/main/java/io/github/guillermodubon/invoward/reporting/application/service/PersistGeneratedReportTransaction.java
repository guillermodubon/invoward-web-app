package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.reporting.application.exception.AnalysisReportNotAllowedException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportConflictException;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportPersistenceResult;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Atomically stores report metadata and completes the owning Analysis and job. */
@Service
public class PersistGeneratedReportTransaction {

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final GeneratedReportRepository generatedReportRepository;
    private final Clock clock;

    public PersistGeneratedReportTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            GeneratedReportRepository generatedReportRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.generatedReportRepository = Objects.requireNonNull(generatedReportRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Locks the owner-scoped Analysis before checking for a concurrent winner. Rendering and object
     * storage must finish before this short database transaction is called.
     */
    @Transactional
    public GeneratedReportPersistenceResult persist(AnalysisOwner owner, GeneratedReport candidate) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(candidate.analysisId(), owner, now)
                .orElseThrow(AnalysisNotFoundException::new);

        var existing = generatedReportRepository.findByAnalysisId(analysis.id());
        if (existing.isPresent()) {
            return new GeneratedReportPersistenceResult(existing.orElseThrow(), false);
        }

        if (analysis.status() == AnalysisStatus.COMPLETED) {
            throw new ReportConflictException();
        }
        if (analysis.status() != AnalysisStatus.GENERATING_REPORT) {
            throw new AnalysisReportNotAllowedException();
        }

        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id())
                .orElseThrow(ReportConflictException::new);
        if (job.status() != AnalysisJobStatus.WAITING_FOR_USER
                || job.currentStage() != AnalysisStatus.GENERATING_REPORT) {
            throw new ReportConflictException();
        }
        if (!Objects.equals(candidate.expiresAt(), analysis.expiresAt())
                || candidate.generatedAt().isBefore(analysis.updatedAt())
                || candidate.generatedAt().isBefore(job.updatedAt())) {
            throw new ReportConflictException();
        }

        GeneratedReport persisted = generatedReportRepository.create(candidate);
        analysisRepository.update(analysis.completeReportGeneration(persisted.generatedAt()));
        analysisJobRepository.update(job.completeReportGeneration(persisted.generatedAt()));
        return new GeneratedReportPersistenceResult(persisted, true);
    }
}
