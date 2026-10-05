package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.model.DetectedDocumentType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Atomically advances Analysis and its waiting job after both persisted suggestions exist. */
@Service
public class CompleteDocumentClassificationTransaction {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DocumentRepository documentRepository;
    private final Clock clock;

    public CompleteDocumentClassificationTransaction(
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
    public List<DetectedDocumentType> complete(AnalysisOwner owner, java.util.UUID analysisId) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        List<Document> documents = orderedRequiredDocuments(analysisId);

        if (analysis.status() == AnalysisStatus.CLASSIFYING) {
            requireAllDetected(documents);
            return results(documents);
        }
        if (analysis.status() != AnalysisStatus.UPLOADING) {
            throw new DocumentClassificationConflictException();
        }
        requireAllDetected(documents);

        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId)
                .orElseThrow(DocumentClassificationConflictException::new);
        analysisRepository.update(analysis.markClassifying(now));
        analysisJobRepository.update(job.waitForUserAtClassification(now));
        return results(documents);
    }

    private List<Document> orderedRequiredDocuments(java.util.UUID analysisId) {
        List<Document> documents = documentRepository.findByAnalysisId(analysisId).stream()
                .sorted(ROLE_ORDER)
                .toList();
        if (documents.size() != 2
                || documents.get(0).role() != DocumentRole.REFERENCE
                || documents.get(1).role() != DocumentRole.INVOICE) {
            throw new DocumentsRequiredException();
        }
        return documents;
    }

    private static void requireAllDetected(List<Document> documents) {
        if (documents.stream().anyMatch(document -> document.detectedType() == null)) {
            throw new DocumentClassificationConflictException();
        }
    }

    private static List<DetectedDocumentType> results(List<Document> documents) {
        return documents.stream()
                .map(document -> new DetectedDocumentType(
                        document.id(), document.role(), document.detectedType()))
                .toList();
    }
}
