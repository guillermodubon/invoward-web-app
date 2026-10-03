package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentSizeLimitExceededException;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentsLockedException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentRoleAlreadyExistsException;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Persists validated document metadata and upload state atomically in PostgreSQL. */
public class UploadDocumentTransaction {

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DocumentRepository documentRepository;
    private final Clock clock;
    private final long maxCombinedSizeBytes;

    public UploadDocumentTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            DocumentRepository documentRepository,
            Clock clock,
            long maxCombinedSizeBytes) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.clock = Objects.requireNonNull(clock);
        if (maxCombinedSizeBytes <= 0) {
            throw new IllegalArgumentException("maxCombinedSizeBytes must be positive");
        }
        this.maxCombinedSizeBytes = maxCombinedSizeBytes;
    }

    @Transactional
    public Document persist(AnalysisOwner owner, Document document) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(document, "document must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(
                        document.analysisId(), owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        validateUploadState(analysis);
        validateDocumentExpiry(analysis, document);
        validateRoleAvailable(document);
        validateCombinedSize(document);

        Document persistedDocument = documentRepository.create(document);
        analysisRepository.update(analysis.transitionToUploading(now));

        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysis.id())
                .orElseThrow(() -> new IllegalStateException("Analysis job is missing"));
        analysisJobRepository.update(job.awaitMoreUploads(now));

        return persistedDocument;
    }

    private static void validateUploadState(Analysis analysis) {
        if (analysis.status() != AnalysisStatus.CREATED
                && analysis.status() != AnalysisStatus.UPLOADING) {
            throw new AnalysisDocumentsLockedException();
        }
    }

    private static void validateDocumentExpiry(Analysis analysis, Document document) {
        if (!Objects.equals(analysis.expiresAt(), document.expiresAt())) {
            throw new IllegalArgumentException("Document expiry must match its Analysis expiry");
        }
    }

    private void validateRoleAvailable(Document document) {
        if (documentRepository.existsByAnalysisIdAndRole(document.analysisId(), document.role())) {
            throw new DocumentRoleAlreadyExistsException();
        }
    }

    private void validateCombinedSize(Document document) {
        long existingBytes = documentRepository.sumSizeBytesByAnalysisId(document.analysisId());
        if (existingBytes > maxCombinedSizeBytes
                || document.sizeBytes() > maxCombinedSizeBytes - existingBytes) {
            throw new AnalysisDocumentSizeLimitExceededException();
        }
    }
}
