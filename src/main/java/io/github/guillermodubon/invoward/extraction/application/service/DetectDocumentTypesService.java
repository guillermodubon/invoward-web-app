package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.exception.DocumentNotFoundException;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.model.DetectedDocumentType;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Synchronously detects the two uploaded document types without holding a database transaction. */
@Service
public class DetectDocumentTypesService {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final GetAnalysisService analysisService;
    private final DocumentRepository documentRepository;
    private final DocumentIntelligenceFileMaterializer fileMaterializer;
    private final DocumentIntelligence documentIntelligence;
    private final PersistDetectedDocumentTransaction persistTransaction;
    private final CompleteDocumentClassificationTransaction completeTransaction;
    private final Clock clock;

    public DetectDocumentTypesService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository,
            DocumentIntelligenceFileMaterializer fileMaterializer,
            DocumentIntelligence documentIntelligence,
            PersistDetectedDocumentTransaction persistTransaction,
            CompleteDocumentClassificationTransaction completeTransaction,
            Clock clock) {
        this.analysisService = Objects.requireNonNull(analysisService);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.fileMaterializer = Objects.requireNonNull(fileMaterializer);
        this.documentIntelligence = Objects.requireNonNull(documentIntelligence);
        this.persistTransaction = Objects.requireNonNull(persistTransaction);
        this.completeTransaction = Objects.requireNonNull(completeTransaction);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<DetectedDocumentType> detect(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Analysis analysis = analysisService.get(analysisId, owner);
        List<Document> documents = requiredDocuments(analysisId);
        if (analysis.status() == AnalysisStatus.CLASSIFYING) {
            if (documents.stream().anyMatch(document -> document.detectedType() == null)) {
                throw new DocumentClassificationConflictException();
            }
            return toResults(documents);
        }
        if (analysis.status() != AnalysisStatus.UPLOADING) {
            throw new DocumentClassificationConflictException();
        }
        requireNotExpired(documents, clock.instant());

        for (Document document : documents) {
            if (document.detectedType() != null) {
                continue;
            }
            Analysis latestAnalysis = analysisService.get(analysisId, owner);
            if (latestAnalysis.status() == AnalysisStatus.CLASSIFYING) {
                return completeTransaction.complete(owner, analysisId);
            }
            if (latestAnalysis.status() != AnalysisStatus.UPLOADING) {
                throw new DocumentClassificationConflictException();
            }

            Document current = documentRepository.findByIdAndAnalysisId(document.id(), analysisId)
                    .orElseThrow(DocumentNotFoundException::new);
            if (current.detectedType() != null) {
                continue;
            }
            requireNotExpired(List.of(current), clock.instant());
            var classification = fileMaterializer.withMaterializedFile(
                    current, documentIntelligence::classify);
            if (classification == null || classification.detectedType() == null) {
                throw new DocumentClassificationInvalidException();
            }
            persistTransaction.persist(owner, current, classification.detectedType());
        }

        return completeTransaction.complete(owner, analysisId);
    }

    private List<Document> requiredDocuments(UUID analysisId) {
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

    private static void requireNotExpired(List<Document> documents, Instant now) {
        if (documents.stream().anyMatch(document -> document.expiresAt() != null
                && !document.expiresAt().isAfter(now))) {
            throw new DocumentClassificationConflictException();
        }
    }

    private static List<DetectedDocumentType> toResults(List<Document> documents) {
        return documents.stream()
                .map(document -> {
                    if (document.detectedType() == null) {
                        throw new DocumentClassificationConflictException();
                    }
                    return new DetectedDocumentType(document.id(), document.role(), document.detectedType());
                })
                .toList();
    }
}
