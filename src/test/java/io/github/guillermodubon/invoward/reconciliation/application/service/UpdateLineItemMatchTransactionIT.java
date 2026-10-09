package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchTargetUnavailableException;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualLineItemMatchUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualMatchAction;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class UpdateLineItemMatchTransactionIT {

    @Autowired
    private UpdateLineItemMatchTransaction transaction;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ExtractedDocumentRepository extractedDocumentRepository;

    @Autowired
    private LineItemMatchRepository lineItemMatchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void confirmsAiReviewAndPreservesMethodAndConfidenceWithOptimisticVersionIncrement() {
        Fixture fixture = createFixture(1, 1);
        LineItemMatch aiReview = match(fixture, fixture.referenceIds().getFirst(),
                fixture.invoiceIds().getFirst(), LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.AI,
                new BigDecimal("0.8123"));
        lineItemMatchRepository.create(aiReview);

        Instant before = clock.instant();
        List<LineItemMatch> updated = transaction.update(fixture.analysisId(), aiReview.id(), fixture.owner(),
                command(0, ManualMatchAction.CONFIRM));
        Instant after = clock.instant();

        LineItemMatch confirmed = find(updated, aiReview.id());
        assertEquals(LineMatchStatus.MATCHED, confirmed.status());
        assertEquals(LineMatchMethod.AI, confirmed.method());
        assertEquals(new BigDecimal("0.8123"), confirmed.confidence());
        assertEquals(1, confirmed.version());
        assertBetween(before, after, confirmed.reviewedAt());
        assertCompleteCover(fixture, updated);
    }

    @Test
    void matchWithConsumesUnmatchedInvoiceAndMakesDisplacedInvoiceUnmatched() {
        Fixture fixture = createFixture(2, 2);
        LineItemMatch target = match(fixture, fixture.referenceIds().get(0), fixture.invoiceIds().get(0),
                LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.AI, new BigDecimal("0.7000"));
        LineItemMatch unmatchedReference = match(fixture, fixture.referenceIds().get(1), null,
                LineMatchStatus.UNMATCHED_REFERENCE, LineMatchMethod.NONE, null);
        LineItemMatch unmatchedInvoice = match(fixture, null, fixture.invoiceIds().get(1),
                LineMatchStatus.UNMATCHED_INVOICE, LineMatchMethod.NONE, null);
        lineItemMatchRepository.createAll(List.of(target, unmatchedReference, unmatchedInvoice));

        List<LineItemMatch> updated = transaction.update(fixture.analysisId(), target.id(), fixture.owner(),
                new ManualLineItemMatchUpdate(0, ManualMatchAction.MATCH_WITH,
                        fixture.referenceIds().get(0), fixture.invoiceIds().get(1)));

        LineItemMatch manualPair = find(updated, target.id());
        assertEquals(LineMatchStatus.MATCHED, manualPair.status());
        assertEquals(LineMatchMethod.MANUAL, manualPair.method());
        assertNull(manualPair.confidence());
        assertEquals(1, manualPair.version());
        assertFalse(updated.stream().anyMatch(row -> row.id().equals(unmatchedInvoice.id())));
        assertTrue(updated.stream().anyMatch(row -> row.status() == LineMatchStatus.UNMATCHED_INVOICE
                && fixture.invoiceIds().get(0).equals(row.invoiceLineItemId())
                && row.method() == LineMatchMethod.MANUAL));
        assertTrue(updated.stream().anyMatch(row -> row.id().equals(unmatchedReference.id())));
        assertCompleteCover(fixture, updated);
    }

    @Test
    void matchWithAlsoSupportsReplacingTheReferenceSide() {
        Fixture fixture = createFixture(2, 2);
        LineItemMatch target = match(fixture, fixture.referenceIds().get(0), fixture.invoiceIds().get(0),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE);
        LineItemMatch unmatchedReference = match(fixture, fixture.referenceIds().get(1), null,
                LineMatchStatus.UNMATCHED_REFERENCE, LineMatchMethod.NONE, null);
        LineItemMatch unmatchedInvoice = match(fixture, null, fixture.invoiceIds().get(1),
                LineMatchStatus.UNMATCHED_INVOICE, LineMatchMethod.NONE, null);
        lineItemMatchRepository.createAll(List.of(target, unmatchedReference, unmatchedInvoice));

        List<LineItemMatch> updated = transaction.update(fixture.analysisId(), target.id(), fixture.owner(),
                new ManualLineItemMatchUpdate(0, ManualMatchAction.MATCH_WITH,
                        fixture.referenceIds().get(1), fixture.invoiceIds().get(0)));

        assertEquals(fixture.referenceIds().get(1), find(updated, target.id()).referenceLineItemId());
        assertEquals(fixture.invoiceIds().get(0), find(updated, target.id()).invoiceLineItemId());
        assertFalse(updated.stream().anyMatch(row -> row.id().equals(unmatchedReference.id())));
        assertTrue(updated.stream().anyMatch(row -> row.status() == LineMatchStatus.UNMATCHED_REFERENCE
                && fixture.referenceIds().get(0).equals(row.referenceLineItemId())
                && row.method() == LineMatchMethod.MANUAL));
        assertTrue(updated.stream().anyMatch(row -> row.id().equals(unmatchedInvoice.id())));
        assertCompleteCover(fixture, updated);
    }

    @Test
    void rejectsOccupiedAndForeignLinesAndStaleVersionsWithoutChangingRows() {
        Fixture fixture = createFixture(2, 2);
        Fixture otherAnalysis = createFixture(1, 1);
        LineItemMatch target = match(fixture, fixture.referenceIds().get(0), fixture.invoiceIds().get(0),
                LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.FUZZY, new BigDecimal("0.8000"));
        LineItemMatch occupied = match(fixture, fixture.referenceIds().get(1), fixture.invoiceIds().get(1),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE);
        lineItemMatchRepository.createAll(List.of(target, occupied));

        assertThrows(MatchTargetUnavailableException.class, () -> transaction.update(
                fixture.analysisId(), target.id(), fixture.owner(),
                new ManualLineItemMatchUpdate(0, ManualMatchAction.MATCH_WITH,
                        fixture.referenceIds().get(0), fixture.invoiceIds().get(1))));
        assertThrows(MatchTargetUnavailableException.class, () -> transaction.update(
                fixture.analysisId(), target.id(), fixture.owner(),
                new ManualLineItemMatchUpdate(0, ManualMatchAction.MATCH_WITH,
                        otherAnalysis.referenceIds().getFirst(), fixture.invoiceIds().get(0))));
        assertThrows(MatchConflictException.class, () -> transaction.update(
                fixture.analysisId(), target.id(), fixture.owner(),
                command(1, ManualMatchAction.CONFIRM)));

        List<LineItemMatch> unchanged = lineItemMatchRepository.findByAnalysisId(fixture.analysisId());
        assertEquals(2, unchanged.size());
        assertEquals(LineMatchStatus.NEEDS_REVIEW, find(unchanged, target.id()).status());
        assertEquals(0, find(unchanged, target.id()).version());
        assertEquals(LineMatchStatus.MATCHED, find(unchanged, occupied.id()).status());
        assertCompleteCover(fixture, unchanged);
    }

    @Test
    void noMatchSplitsPairIntoManualUnmatchedRowsAndPreservesCover() {
        Fixture fixture = createFixture(1, 1);
        LineItemMatch pair = match(fixture, fixture.referenceIds().getFirst(), fixture.invoiceIds().getFirst(),
                LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.FUZZY, new BigDecimal("0.7500"));
        lineItemMatchRepository.create(pair);

        List<LineItemMatch> updated = transaction.update(fixture.analysisId(), pair.id(), fixture.owner(),
                command(0, ManualMatchAction.NO_MATCH));

        LineItemMatch reference = find(updated, pair.id());
        assertEquals(LineMatchStatus.UNMATCHED_REFERENCE, reference.status());
        assertEquals(LineMatchMethod.MANUAL, reference.method());
        assertNull(reference.confidence());
        assertEquals(1, reference.version());
        LineItemMatch invoice = updated.stream()
                .filter(row -> row.status() == LineMatchStatus.UNMATCHED_INVOICE)
                .findFirst().orElseThrow();
        assertEquals(fixture.invoiceIds().getFirst(), invoice.invoiceLineItemId());
        assertEquals(LineMatchMethod.MANUAL, invoice.method());
        assertNull(invoice.confidence());
        assertNotNull(invoice.reviewedAt());
        assertCompleteCover(fixture, updated);
    }

    @Test
    void noMatchOnAlreadyUnmatchedLinePreservesItsMethodAndConfirmReviewsUnmatchedRows() {
        Fixture fixture = createFixture(2, 1);
        LineItemMatch pair = match(fixture, fixture.referenceIds().get(0), fixture.invoiceIds().getFirst(),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE);
        LineItemMatch unmatched = match(fixture, fixture.referenceIds().get(1), null,
                LineMatchStatus.UNMATCHED_REFERENCE, LineMatchMethod.NONE, null);
        lineItemMatchRepository.createAll(List.of(pair, unmatched));

        List<LineItemMatch> reviewed = transaction.update(fixture.analysisId(), unmatched.id(), fixture.owner(),
                command(0, ManualMatchAction.NO_MATCH));

        LineItemMatch reviewedUnmatched = find(reviewed, unmatched.id());
        assertEquals(LineMatchStatus.UNMATCHED_REFERENCE, reviewedUnmatched.status());
        assertEquals(LineMatchMethod.NONE, reviewedUnmatched.method());
        assertNotNull(reviewedUnmatched.reviewedAt());
        assertEquals(1, reviewedUnmatched.version());
        assertCompleteCover(fixture, reviewed);

        Instant before = clock.instant();
        List<LineItemMatch> confirmed = transaction.update(fixture.analysisId(), unmatched.id(), fixture.owner(),
                command(1, ManualMatchAction.CONFIRM));
        Instant after = clock.instant();
        LineItemMatch confirmedUnmatched = find(confirmed, unmatched.id());
        assertEquals(LineMatchStatus.UNMATCHED_REFERENCE, confirmedUnmatched.status());
        assertEquals(LineMatchMethod.NONE, confirmedUnmatched.method());
        assertEquals(2, confirmedUnmatched.version());
        assertBetween(before, after, confirmedUnmatched.reviewedAt());
        assertCompleteCover(fixture, confirmed);
    }

    @Test
    void authorizesAnalysisBeforeReadingMatchSetAndRejectsCorruptExistingCover() {
        Fixture fixture = createFixture(2, 1);
        assertThrows(AnalysisNotFoundException.class, () -> transaction.update(
                fixture.analysisId(), UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()),
                command(0, ManualMatchAction.CONFIRM)));

        LineItemMatch incomplete = match(fixture, fixture.referenceIds().getFirst(), fixture.invoiceIds().getFirst(),
                LineMatchStatus.MATCHED, LineMatchMethod.MANUAL, null);
        lineItemMatchRepository.create(incomplete);
        assertThrows(MatchSetConflictException.class, () -> transaction.update(
                fixture.analysisId(), incomplete.id(), fixture.owner(), command(0, ManualMatchAction.CONFIRM)));
    }

    private Fixture createFixture(int referenceCount, int invoiceCount) {
        Instant start = clock.instant().minusSeconds(120);
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), start));
        AnalysisJob job = analysisJobRepository.create(AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, start));

        Instant uploadingAt = start.plusSeconds(1);
        analysis = analysisRepository.update(analysis.transitionToUploading(uploadingAt));
        job = analysisJobRepository.update(job.awaitMoreUploads(uploadingAt));
        Instant classifyingAt = start.plusSeconds(2);
        analysis = analysisRepository.update(analysis.markClassifying(classifyingAt));
        job = analysisJobRepository.update(job.waitForUserAtClassification(classifyingAt));
        Instant extractionAt = start.plusSeconds(3);
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(extractionAt));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(extractionAt));
        Instant matchingAt = start.plusSeconds(4);
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-1", "I-1", "Test Vendor", "USD", BigDecimal.TEN, BigDecimal.TEN, matchingAt));
        job = analysisJobRepository.update(job.waitForMatching(matchingAt));
        Instant reviewAt = start.plusSeconds(5);
        analysisRepository.update(analysis.awaitMatchReview(reviewAt));
        analysisJobRepository.update(job.waitForMatchReview(reviewAt));

        Instant documentAt = start.plusSeconds(6);
        Document reference = createDocument(analysisId, DocumentRole.REFERENCE, DocumentType.QUOTE, documentAt);
        Document invoice = createDocument(analysisId, DocumentRole.INVOICE, DocumentType.INVOICE, documentAt);
        List<UUID> referenceIds = createConfirmedLines(reference, "reference", referenceCount, documentAt.plusSeconds(1));
        List<UUID> invoiceIds = createConfirmedLines(invoice, "invoice", invoiceCount, documentAt.plusSeconds(1));
        Analysis persisted = analysisRepository.findOwnedById(analysisId, owner, clock.instant()).orElseThrow();
        assertEquals(AnalysisStatus.AWAITING_MATCH_REVIEW, persisted.status());
        return new Fixture(analysisId, owner, referenceIds, invoiceIds);
    }

    private Document createDocument(UUID analysisId, DocumentRole role, DocumentType type, Instant createdAt) {
        return documentRepository.create(Document.createUploaded(
                        UUID.randomUUID(), analysisId, role, role + ".pdf", "application/pdf", 1024,
                        1, DatabaseFixtures.sha256(), "manual-match-test/" + UUID.randomUUID(), null, createdAt)
                .withDetectedType(type)
                .withConfirmedType(type));
    }

    private List<UUID> createConfirmedLines(Document document, String side, int count, Instant extractedAt) {
        List<ExtractedLineItem> lines = new ArrayList<>();
        for (int position = 0; position < count; position++) {
            lines.add(new ExtractedLineItem(
                    position, side + "-SKU-" + position, side + " item " + position,
                    BigDecimal.ONE, "each", BigDecimal.TEN,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                    1, null, null));
        }
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Test Vendor", null, null, "USD",
                null, null, null, null, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "matching-test-v1", "test-model", 1, extractedAt);
        Instant confirmedAt = extractedAt.plusSeconds(1);
        PersistedExtraction confirmed = extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return confirmed.lineItemIds();
    }

    private LineItemMatch match(
            Fixture fixture,
            UUID referenceId,
            UUID invoiceId,
            LineMatchStatus status,
            LineMatchMethod method,
            BigDecimal confidence) {
        Instant now = clock.instant().minusSeconds(10);
        return new LineItemMatch(UUID.randomUUID(), fixture.analysisId(), referenceId, invoiceId,
                status, method, confidence, 0, null, now, now);
    }

    private static ManualLineItemMatchUpdate command(long version, ManualMatchAction action) {
        return new ManualLineItemMatchUpdate(version, action, null, null);
    }

    private static LineItemMatch find(List<LineItemMatch> matches, UUID id) {
        return matches.stream().filter(match -> match.id().equals(id)).findFirst().orElseThrow();
    }

    private static void assertCompleteCover(Fixture fixture, List<LineItemMatch> matches) {
        List<UUID> references = matches.stream().map(LineItemMatch::referenceLineItemId)
                .filter(java.util.Objects::nonNull).toList();
        List<UUID> invoices = matches.stream().map(LineItemMatch::invoiceLineItemId)
                .filter(java.util.Objects::nonNull).toList();
        assertEquals(fixture.referenceIds().size(), references.size());
        assertEquals(fixture.invoiceIds().size(), invoices.size());
        assertEquals(fixture.referenceIds().stream().sorted().toList(), references.stream().sorted().toList());
        assertEquals(fixture.invoiceIds().stream().sorted().toList(), invoices.stream().sorted().toList());
    }

    private static void assertBetween(Instant before, Instant after, Instant actual) {
        assertNotNull(actual);
        assertFalse(actual.isBefore(before.truncatedTo(ChronoUnit.MICROS)));
        assertFalse(actual.isAfter(after.truncatedTo(ChronoUnit.MICROS)));
    }

    private record Fixture(UUID analysisId, AnalysisOwner owner, List<UUID> referenceIds, List<UUID> invoiceIds) {
    }
}
