package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reads confirmed source-line positions used only to order persisted reconciliation snapshots. */
@Service
@Transactional(readOnly = true)
public class ReconciliationLinePositionReader {

    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final Clock clock;

    public ReconciliationLinePositionReader(
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            Clock clock) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Returns source line IDs mapped to their confirmed extraction positions.
     * Financial snapshot values are deliberately not read or reconstructed here.
     */
    public Map<UUID, Integer> read(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Instant now = clock.instant();
        Map<DocumentRole, Document> documentsByRole = new EnumMap<>(DocumentRole.class);
        List<Document> documents = documentRepository.findByAnalysisId(analysis.id());
        for (Document document : documents) {
            if (!analysis.id().equals(document.analysisId())
                    || document.confirmedType() == null
                    || !Objects.equals(analysis.expiresAt(), document.expiresAt())
                    || (document.expiresAt() != null && !document.expiresAt().isAfter(now))
                    || documentsByRole.putIfAbsent(document.role(), document) != null) {
                throw new ReconciliationConflictException();
            }
        }
        if (documents.size() != 2
                || !documentsByRole.containsKey(DocumentRole.REFERENCE)
                || !documentsByRole.containsKey(DocumentRole.INVOICE)) {
            throw new ReconciliationConflictException();
        }

        Map<UUID, Integer> positionsByLineId = new HashMap<>();
        for (DocumentRole role : List.of(DocumentRole.REFERENCE, DocumentRole.INVOICE)) {
            Document document = documentsByRole.get(role);
            PersistedExtraction extraction = extractedDocumentRepository.findByDocumentId(document.id())
                    .filter(value -> document.id().equals(value.documentId()))
                    .filter(value -> value.extraction().status() == ExtractionStatus.CONFIRMED
                            && value.extraction().confirmedAt() != null)
                    .orElseThrow(ReconciliationConflictException::new);
            if (extraction.lineItemIds().isEmpty()
                    || extraction.lineItemIds().size() != extraction.extraction().lines().size()) {
                throw new ReconciliationConflictException();
            }
            for (int index = 0; index < extraction.lineItemIds().size(); index++) {
                UUID lineItemId = extraction.lineItemIds().get(index);
                if (extraction.extraction().lines().get(index).position() != index
                        || positionsByLineId.putIfAbsent(lineItemId, index) != null) {
                    throw new ReconciliationConflictException();
                }
            }
        }
        return Map.copyOf(positionsByLineId);
    }
}
