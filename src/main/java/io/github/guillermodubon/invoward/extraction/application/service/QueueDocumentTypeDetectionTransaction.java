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
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Validates and queues the owner-scoped classification stage without invoking document intelligence. */
@Service
public class QueueDocumentTypeDetectionTransaction {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DocumentRepository documentRepository;
    private final Clock clock;

    public QueueDocumentTypeDetectionTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            DocumentRepository documentRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public AnalysisStatusSnapshot queue(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId)
                .orElseThrow(DocumentClassificationConflictException::new);
        List<Document> documents = requiredDocuments(analysis, now);

        if (isActiveClassification(analysis, job)) {
            return snapshot(analysis, job);
        }
        if (isClassificationWaitingForUser(analysis, job)) {
            if (allTypesDetected(documents)) {
                return snapshot(analysis, job);
            }
            AnalysisJob queuedJob = analysisJobRepository.update(job.queueLegacyMachineStage(now));
            return snapshot(analysis, queuedJob);
        }
        if (hasPassedClassification(analysis, job) && allTypesDetected(documents)) {
            return snapshot(analysis, job);
        }
        throw new DocumentClassificationConflictException();
    }

    private List<Document> requiredDocuments(Analysis analysis, Instant now) {
        List<Document> documents = documentRepository.findByAnalysisId(analysis.id()).stream()
                .sorted(ROLE_ORDER)
                .toList();
        if (documents.size() != 2
                || documents.get(0).role() != DocumentRole.REFERENCE
                || documents.get(1).role() != DocumentRole.INVOICE) {
            throw new DocumentsRequiredException();
        }
        if (documents.stream().anyMatch(document -> !analysis.id().equals(document.analysisId())
                || !Objects.equals(analysis.expiresAt(), document.expiresAt())
                || (document.expiresAt() != null && !document.expiresAt().isAfter(now)))) {
            throw new DocumentClassificationConflictException();
        }
        return documents;
    }

    private static boolean isActiveClassification(Analysis analysis, AnalysisJob job) {
        return analysis.status() == AnalysisStatus.CLASSIFYING
                && job.currentStage() == AnalysisStatus.CLASSIFYING
                && (job.status() == AnalysisJobStatus.QUEUED || job.status() == AnalysisJobStatus.RUNNING);
    }

    private static boolean isClassificationWaitingForUser(Analysis analysis, AnalysisJob job) {
        return analysis.status() == AnalysisStatus.CLASSIFYING
                && job.status() == AnalysisJobStatus.WAITING_FOR_USER
                && job.currentStage() == AnalysisStatus.CLASSIFYING;
    }

    private static boolean hasPassedClassification(Analysis analysis, AnalysisJob job) {
        return switch (analysis.status()) {
            case EXTRACTING, MATCHING -> isQueuedOrRunningAt(job, analysis.status());
            case AWAITING_CONFIRMATION, AWAITING_MATCH_REVIEW ->
                    job.status() == AnalysisJobStatus.WAITING_FOR_USER
                            && job.currentStage() == analysis.status();
            case RECONCILING, GENERATING_REPORT -> job.currentStage() == analysis.status()
                    && (job.status() == AnalysisJobStatus.QUEUED
                            || job.status() == AnalysisJobStatus.RUNNING
                            || job.status() == AnalysisJobStatus.WAITING_FOR_USER);
            case COMPLETED -> job.status() == AnalysisJobStatus.COMPLETED
                    && job.currentStage() == AnalysisStatus.COMPLETED;
            default -> false;
        };
    }

    private static boolean isQueuedOrRunningAt(AnalysisJob job, AnalysisStatus stage) {
        return job.currentStage() == stage
                && (job.status() == AnalysisJobStatus.QUEUED || job.status() == AnalysisJobStatus.RUNNING);
    }

    private static boolean allTypesDetected(List<Document> documents) {
        return documents.stream().allMatch(document -> document.detectedType() != null);
    }

    private static AnalysisStatusSnapshot snapshot(Analysis analysis, AnalysisJob job) {
        return new AnalysisStatusSnapshot(
                analysis.id(), analysis.status(), job.status(), job.currentStage(),
                analysis.retryable(), analysis.failureCode(), analysis.failureUserMessage(), analysis.updatedAt());
    }
}
