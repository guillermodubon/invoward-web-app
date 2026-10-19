package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.exception.AnalysisExtractionNotAllowedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Atomically confirms document types and queues the extraction worker stage. */
@Service
public class QueueExtractionTransaction {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DocumentRepository documentRepository;
    private final ConfirmExtractionTypesTransaction confirmTypesTransaction;
    private final Clock clock;

    public QueueExtractionTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            DocumentRepository documentRepository,
            ConfirmExtractionTypesTransaction confirmTypesTransaction,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.confirmTypesTransaction = Objects.requireNonNull(confirmTypesTransaction);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public AnalysisStatusSnapshot queue(
            UUID analysisId,
            AnalysisOwner owner,
            ExtractionRequestTypes requestedTypes) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(requestedTypes, "requestedTypes must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId)
                .orElseThrow(ExtractionConflictException::new);

        if (isMatchingQueuedRequest(analysis, job)) {
            requireSameConfirmedTypes(analysisId, requestedTypes, now);
            return snapshot(analysis, job);
        }

        if (analysis.status() != AnalysisStatus.CLASSIFYING) {
            throw new AnalysisExtractionNotAllowedException();
        }
        if (job.status() != AnalysisJobStatus.WAITING_FOR_USER
                || job.currentStage() != AnalysisStatus.CLASSIFYING) {
            throw new ExtractionConflictException();
        }

        // Reuse the existing owner/state-locked type validation and update transaction.
        // It joins this transaction, so type confirmation rolls back with the queue transition.
        confirmTypesTransaction.confirm(analysisId, owner, requestedTypes);

        Analysis updatedAnalysis = analysisRepository.update(analysis.beginExtraction(now));
        AnalysisJob queuedJob = analysisJobRepository.update(job.queueExtraction(now));
        return snapshot(updatedAnalysis, queuedJob);
    }

    private boolean isMatchingQueuedRequest(Analysis analysis, AnalysisJob job) {
        return analysis.status() == AnalysisStatus.EXTRACTING
                && job.status() == AnalysisJobStatus.QUEUED
                && job.currentStage() == AnalysisStatus.EXTRACTING;
    }

    private void requireSameConfirmedTypes(
            UUID analysisId,
            ExtractionRequestTypes requestedTypes,
            Instant now) {
        List<Document> documents = documentRepository.findByAnalysisId(analysisId).stream()
                .sorted(ROLE_ORDER)
                .toList();
        if (documents.size() != 2
                || documents.get(0).role() != DocumentRole.REFERENCE
                || documents.get(1).role() != DocumentRole.INVOICE
                || documents.stream().anyMatch(document -> document.expiresAt() != null
                        && !document.expiresAt().isAfter(now))
                || documents.get(0).confirmedType() != requestedTypes.referenceType()
                || documents.get(1).confirmedType() != requestedTypes.invoiceType()) {
            throw new ExtractionConflictException();
        }
    }

    private static AnalysisStatusSnapshot snapshot(Analysis analysis, AnalysisJob job) {
        return new AnalysisStatusSnapshot(
                analysis.id(),
                analysis.status(),
                job.status(),
                job.currentStage(),
                analysis.retryable(),
                analysis.failureCode(),
                analysis.failureUserMessage(),
                analysis.updatedAt());
    }
}
