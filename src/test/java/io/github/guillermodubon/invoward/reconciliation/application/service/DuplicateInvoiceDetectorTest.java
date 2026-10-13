package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceSignal;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.port.DuplicateInvoiceCandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuplicateInvoiceDetectorTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final UUID ANALYSIS_ID = uuid(1);
    private static final UUID DOCUMENT_ID = uuid(2);
    private static final String CURRENT_HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final LocalDate DOCUMENT_DATE = LocalDate.of(2026, 10, 1);

    private final DuplicateInvoiceCandidateRepository repository = mock(DuplicateInvoiceCandidateRepository.class);
    private final DuplicateInvoiceDetector detector = new DuplicateInvoiceDetector(
            repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(repository.findCandidates(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void exactFileHashIsAStrongWarningEvenWhenMetadataDoesNotMatch() {
        Analysis analysis = analysis(registeredOwner(), "INV-42", "Acme Corp", "100.0000");
        when(repository.findCandidates(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(candidate(uuid(10), "OTHER-9", "Different Supplier", CURRENT_HASH,
                        null, null, null)));

        DuplicateInvoiceWarning warning = detector.detect(
                analysis, invoice(analysis, CURRENT_HASH), confirmedInvoice("INV-42", "Acme Corp", "100.0000"));

        assertTrue(warning.possibleDuplicate());
        assertEquals(1, warning.candidates().size());
        assertEquals(List.of(DuplicateInvoiceSignal.EXACT_INVOICE_FILE_HASH), warning.candidates().getFirst().signals());
        assertFalse(warning.toString().contains(CURRENT_HASH));
        assertFalse(warning.toString().contains("different supplier"));
    }

    @Test
    void sameNormalizedInvoiceNumberAndSupplierWarnAndAddMatchingAmountAndDateSignals() {
        Analysis analysis = analysis(registeredOwner(), " INV-42 ", "Acme Corp", "100.0000");
        when(repository.findCandidates(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(candidate(uuid(11), "inv-42", "acme corp", OTHER_HASH,
                        new BigDecimal("100"), DOCUMENT_DATE, null)));

        DuplicateInvoiceWarning warning = detector.detect(
                analysis, invoice(analysis, CURRENT_HASH), confirmedInvoice("INV-42", "Acme Corp", "100.00"));

        assertEquals(List.of(
                DuplicateInvoiceSignal.SAME_INVOICE_NUMBER,
                DuplicateInvoiceSignal.SAME_SUPPLIER,
                DuplicateInvoiceSignal.SAME_TOTAL,
                DuplicateInvoiceSignal.SAME_DOCUMENT_DATE), warning.candidates().getFirst().signals());
        verify(repository).findCandidates(eq(analysis.owner()), eq(analysis.id()), eq(CURRENT_HASH),
                eq("INV-42"), eq(NOW), eq(DuplicateInvoiceWarning.MAX_CANDIDATES));
    }

    @Test
    void invoiceNumberWithoutSameSupplierDoesNotWarnEvenWhenAmountAndDateMatch() {
        Analysis analysis = analysis(registeredOwner(), "INV-42", "Acme Corp", "100.0000");
        when(repository.findCandidates(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(candidate(uuid(12), "inv-42", "other supplier", OTHER_HASH,
                        new BigDecimal("100.0000"), DOCUMENT_DATE, null)));

        DuplicateInvoiceWarning warning = detector.detect(
                analysis, invoice(analysis, CURRENT_HASH), confirmedInvoice("INV-42", "Acme Corp", "100.00"));

        assertFalse(warning.possibleDuplicate());
        assertTrue(warning.candidates().isEmpty());
    }

    @Test
    void amountOrDateAloneNeverProducesAWarning() {
        Analysis analysis = analysis(registeredOwner(), "INV-42", "Acme Corp", "100.0000");
        when(repository.findCandidates(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(candidate(uuid(13), "INV-43", "Acme Corp", OTHER_HASH,
                        new BigDecimal("100.0000"), DOCUMENT_DATE, null)));

        DuplicateInvoiceWarning warning = detector.detect(
                analysis, invoice(analysis, CURRENT_HASH), confirmedInvoice("INV-42", "Acme Corp", "100.00"));

        assertEquals(DuplicateInvoiceWarning.none(), warning);
    }

    @Test
    void aHashMatchCanWarnWithoutExposingSupplierKeyOrHash() {
        Analysis analysis = analysis(registeredOwner(), null, "Secret Supplier", null);
        String privateSupplierKey = analysis.supplierKey();
        when(repository.findCandidates(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(candidate(uuid(14), null, privateSupplierKey, CURRENT_HASH,
                        null, null, null)));

        DuplicateInvoiceWarning warning = detector.detect(
                analysis, invoice(analysis, CURRENT_HASH), confirmedInvoice(null, "Secret Supplier", null));

        assertTrue(warning.possibleDuplicate());
        assertFalse(warning.toString().contains(privateSupplierKey));
        assertFalse(warning.toString().contains(CURRENT_HASH));
        assertNotNull(warning.candidates().getFirst().analysisId());
    }

    @Test
    void excludesCurrentAnalysisDeduplicatesAndCapsCandidatesAtFive() {
        Analysis analysis = analysis(registeredOwner(), "INV-42", "Acme Corp", "100.00");
        List<DuplicateInvoiceCandidateRepository.CandidateRecord> records = new ArrayList<>();
        records.add(candidate(analysis.id(), "INV-42", analysis.supplierKey(), CURRENT_HASH, null, null, null));
        for (int index = 20; index >= 14; index--) {
            records.add(candidate(uuid(index), "INV-42", analysis.supplierKey(), CURRENT_HASH,
                    null, null, null));
        }
        records.add(records.get(1));
        when(repository.findCandidates(any(), any(), any(), any(), any(), anyInt())).thenReturn(records);

        DuplicateInvoiceWarning warning = detector.detect(
                analysis, invoice(analysis, CURRENT_HASH), confirmedInvoice("INV-42", "Acme Corp", "100.00"));

        assertEquals(DuplicateInvoiceWarning.MAX_CANDIDATES, warning.candidates().size());
        assertEquals(List.of(uuid(20), uuid(19), uuid(18), uuid(17), uuid(16)),
                warning.candidates().stream().map(candidate -> candidate.analysisId()).toList());
    }

    @Test
    void guestOwnerIsPassedToTheOwnerScopedPortWithoutLeakingItsId() {
        GuestSessionOwner owner = new GuestSessionOwner(uuid(80), NOW.plusSeconds(3600));
        Analysis analysis = analysis(owner, "INV-42", "Acme Corp", "100.00");

        detector.detect(analysis, invoice(analysis, CURRENT_HASH), confirmedInvoice("INV-42", "Acme Corp", "100.00"));

        verify(repository).findCandidates(eq(owner), eq(analysis.id()), eq(CURRENT_HASH),
                eq("INV-42"), eq(NOW), eq(DuplicateInvoiceWarning.MAX_CANDIDATES));
    }

    @Test
    void invoiceNumberNormalizationPreservesMeaningfulPunctuation() {
        assertEquals("INV-42", DuplicateInvoiceDetector.normalizeInvoiceNumber("  inv-42  "));
        assertFalse(DuplicateInvoiceDetector.normalizeInvoiceNumber("INV-42")
                .equals(DuplicateInvoiceDetector.normalizeInvoiceNumber("INV42")));
        assertEquals("INV-42", DuplicateInvoiceDetector.normalizeInvoiceNumber("inv-42"));
    }

    private static Analysis analysis(
            AnalysisOwner owner, String invoiceNumber, String supplier, String invoiceTotal) {
        Instant start = NOW.minusSeconds(10);
        return Analysis.create(ANALYSIS_ID, owner, PriceTolerance.exactMatch(), start)
                .transitionToUploading(start.plusSeconds(1))
                .markClassifying(start.plusSeconds(2))
                .awaitExtractionConfirmation(start.plusSeconds(3))
                .confirmExtraction("QUOTE", "Q-1", invoiceNumber, supplier, "USD",
                        null, invoiceTotal == null ? null : new BigDecimal(invoiceTotal), start.plusSeconds(4));
    }

    private static Document invoice(Analysis analysis, String hash) {
        return Document.createUploaded(DOCUMENT_ID, analysis.id(), DocumentRole.INVOICE,
                "invoice.pdf", "application/pdf", 100, 1, hash,
                "private/" + UUID.randomUUID(), analysis.expiresAt(), NOW);
    }

    private static ExtractedDocument confirmedInvoice(String number, String supplier, String total) {
        return new ExtractedDocument(ExtractionStatus.CONFIRMED, ExtractionSource.AI, NOW,
                supplier, number, DOCUMENT_DATE, "USD", null, null, null,
                total == null ? null : new BigDecimal(total), List.of());
    }

    private static DuplicateInvoiceCandidateRepository.CandidateRecord candidate(
            UUID id, String number, String supplierKey, String hash, BigDecimal total, LocalDate date, Instant analyzedAt) {
        return new DuplicateInvoiceCandidateRepository.CandidateRecord(
                id, analyzedAt == null ? NOW.minusSeconds(60) : analyzedAt, number, supplierKey, hash, total, date);
    }

    private static RegisteredUserOwner registeredOwner() {
        return new RegisteredUserOwner(uuid(90));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
