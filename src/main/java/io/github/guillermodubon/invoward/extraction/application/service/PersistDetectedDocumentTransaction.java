package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.exception.DocumentNotFoundException;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Stores a result only after a short owner-scoped lock/recheck transaction. */
@Service
public class PersistDetectedDocumentTransaction {

    private final AnalysisRepository analysisRepository;
    private final DocumentRepository documentRepository;
    private final Clock clock;

    public PersistDetectedDocumentTransaction(
            AnalysisRepository analysisRepository,
            DocumentRepository documentRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public Document persist(
            AnalysisOwner owner,
            Document document,
            DocumentType suggestion) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(suggestion, "suggestion must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(document.analysisId(), owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        if (analysis.status() != AnalysisStatus.UPLOADING
                && analysis.status() != AnalysisStatus.CLASSIFYING) {
            throw new DocumentClassificationConflictException();
        }
        Document current = documentRepository.findByIdAndAnalysisId(document.id(), analysis.id())
                .orElseThrow(DocumentNotFoundException::new);
        if (isExpired(current, now)) {
            throw new DocumentClassificationConflictException();
        }
        if (current.detectedType() != null) {
            return current;
        }
        if (analysis.status() != AnalysisStatus.UPLOADING) {
            throw new DocumentClassificationConflictException();
        }
        return documentRepository.updateTypes(current.withDetectedType(suggestion))
                .orElseThrow(DocumentNotFoundException::new);
    }

    private static boolean isExpired(Document document, Instant now) {
        return document.expiresAt() != null && !document.expiresAt().isAfter(now);
    }
}
