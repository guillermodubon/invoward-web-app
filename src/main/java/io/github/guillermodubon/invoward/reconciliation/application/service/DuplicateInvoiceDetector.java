package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceCandidate;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceSignal;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.port.DuplicateInvoiceCandidateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Computes same-owner duplicate warnings from persisted invoice metadata and exact file hashes. */
@Service
public class DuplicateInvoiceDetector {

    private final DuplicateInvoiceCandidateRepository candidateRepository;
    private final Clock clock;

    public DuplicateInvoiceDetector(DuplicateInvoiceCandidateRepository candidateRepository, Clock clock) {
        this.candidateRepository = Objects.requireNonNull(candidateRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    public DuplicateInvoiceWarning detect(
            Analysis analysis, Document invoiceDocument, ExtractedDocument confirmedInvoice) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(invoiceDocument, "invoiceDocument must not be null");
        Objects.requireNonNull(confirmedInvoice, "confirmedInvoice must not be null");
        validateInvoiceInput(analysis, invoiceDocument, confirmedInvoice);

        Instant now = clock.instant();
        String normalizedInvoiceNumber = normalizeInvoiceNumber(analysis.invoiceNumber());
        List<DuplicateInvoiceCandidateRepository.CandidateRecord> records = candidateRepository.findCandidates(
                analysis.owner(), analysis.id(), invoiceDocument.sha256(), normalizedInvoiceNumber,
                now, DuplicateInvoiceWarning.MAX_CANDIDATES);
        Objects.requireNonNull(records, "candidateRepository result must not be null");

        List<DuplicateInvoiceCandidate> candidates = new ArrayList<>();
        Set<UUID> seenAnalysisIds = new HashSet<>();
        for (DuplicateInvoiceCandidateRepository.CandidateRecord record : records) {
            if (record == null || analysis.id().equals(record.analysisId())
                    || !seenAnalysisIds.add(record.analysisId())) {
                continue;
            }
            DuplicateInvoiceCandidate candidate = toWarningCandidate(
                    analysis, invoiceDocument, confirmedInvoice, normalizedInvoiceNumber, record);
            if (candidate != null) {
                candidates.add(candidate);
                if (candidates.size() == DuplicateInvoiceWarning.MAX_CANDIDATES) {
                    break;
                }
            }
        }
        return candidates.isEmpty()
                ? DuplicateInvoiceWarning.none()
                : new DuplicateInvoiceWarning(true, candidates);
    }

    static String normalizeInvoiceNumber(String invoiceNumber) {
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            return null;
        }
        String normalized = invoiceNumber.strip().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static DuplicateInvoiceCandidate toWarningCandidate(
            Analysis analysis,
            Document invoiceDocument,
            ExtractedDocument confirmedInvoice,
            String normalizedInvoiceNumber,
            DuplicateInvoiceCandidateRepository.CandidateRecord record) {
        boolean sameInvoiceHash = invoiceDocument.sha256().equals(record.invoiceFileSha256());
        String candidateInvoiceNumber = normalizeInvoiceNumber(record.invoiceNumber());
        boolean sameInvoiceNumber = normalizedInvoiceNumber != null
                && normalizedInvoiceNumber.equals(candidateInvoiceNumber);
        boolean sameSupplier = analysis.supplierKey() != null && record.supplierKey() != null
                && analysis.supplierKey().equals(record.supplierKey());
        boolean sameMetadata = sameInvoiceNumber && sameSupplier;
        if (!sameInvoiceHash && !sameMetadata) {
            return null;
        }

        List<DuplicateInvoiceSignal> signals = new ArrayList<>();
        if (sameInvoiceHash) {
            signals.add(DuplicateInvoiceSignal.EXACT_INVOICE_FILE_HASH);
        }
        if (sameInvoiceNumber) {
            signals.add(DuplicateInvoiceSignal.SAME_INVOICE_NUMBER);
        }
        if (sameSupplier) {
            signals.add(DuplicateInvoiceSignal.SAME_SUPPLIER);
        }
        if (sameAmount(analysis.invoicedTotal(), record.invoiceTotal())) {
            signals.add(DuplicateInvoiceSignal.SAME_TOTAL);
        }
        if (confirmedInvoice.documentDate() != null
                && confirmedInvoice.documentDate().equals(record.documentDate())) {
            signals.add(DuplicateInvoiceSignal.SAME_DOCUMENT_DATE);
        }
        return new DuplicateInvoiceCandidate(
                record.analysisId(), record.analyzedAt(), record.invoiceNumber(), signals);
    }

    private static boolean sameAmount(BigDecimal current, BigDecimal candidate) {
        return current != null && candidate != null && current.compareTo(candidate) == 0;
    }

    private static void validateInvoiceInput(
            Analysis analysis, Document invoiceDocument, ExtractedDocument confirmedInvoice) {
        if (!analysis.id().equals(invoiceDocument.analysisId())
                || invoiceDocument.role() != DocumentRole.INVOICE
                || confirmedInvoice.status() != ExtractionStatus.CONFIRMED) {
            throw new IllegalArgumentException("duplicate detection requires this Analysis's confirmed invoice");
        }
        if (!(analysis.owner() instanceof RegisteredUserOwner)
                && !(analysis.owner() instanceof GuestSessionOwner)) {
            throw new IllegalArgumentException("unsupported Analysis owner");
        }
    }
}
