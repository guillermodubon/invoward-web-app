package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Reads the current owner-scoped duplicate warning inputs without modifying reconciliation history. */
@Service
public class CurrentDuplicateInvoiceWarningReader {

    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final DuplicateInvoiceDetector duplicateInvoiceDetector;
    private final Clock clock;

    public CurrentDuplicateInvoiceWarningReader(
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            DuplicateInvoiceDetector duplicateInvoiceDetector,
            Clock clock) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
        this.duplicateInvoiceDetector = Objects.requireNonNull(duplicateInvoiceDetector);
        this.clock = Objects.requireNonNull(clock);
    }

    public DuplicateInvoiceWarning read(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Instant now = clock.instant();
        List<Document> documents = documentRepository.findByAnalysisId(analysis.id());
        if (documents.size() != 2 || documents.stream().anyMatch(document ->
                !analysis.id().equals(document.analysisId())
                        || !Objects.equals(analysis.expiresAt(), document.expiresAt())
                        || (document.expiresAt() != null && !document.expiresAt().isAfter(now)))) {
            throw new ReconciliationConflictException();
        }
        List<Document> invoices = documents.stream()
                .filter(document -> document.role() == DocumentRole.INVOICE)
                .toList();
        if (invoices.size() != 1) {
            throw new ReconciliationConflictException();
        }
        Document invoice = invoices.getFirst();
        PersistedExtraction extraction = extractedDocumentRepository.findByDocumentId(invoice.id())
                .filter(value -> invoice.id().equals(value.documentId()))
                .filter(value -> value.extraction().status() == ExtractionStatus.CONFIRMED
                        && value.extraction().confirmedAt() != null)
                .orElseThrow(ReconciliationConflictException::new);
        return duplicateInvoiceDetector.detect(analysis, invoice, extraction.extraction());
    }
}
