package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
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
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisMatchingNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchingInputNotReadyException;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchingPlan;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/** Atomically persists one complete owner-scoped match set and advances the waiting workflow. */
@Service
public class PersistLineItemMatchesTransaction {

    private static final Comparator<Document> DOCUMENT_ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);
    private static final Comparator<MatchableLineItem> LINE_ORDER = Comparator
            .comparingInt(MatchableLineItem::position)
            .thenComparing(MatchableLineItem::id);

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final LineItemMatchRepository lineItemMatchRepository;
    private final Clock clock;

    public PersistLineItemMatchesTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            LineItemMatchRepository lineItemMatchRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
        this.lineItemMatchRepository = Objects.requireNonNull(lineItemMatchRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Rechecks the previously computed plan against locked, owner-scoped state before writing any rows.
     * Matching and AI work must be completed by the caller before entering this transaction.
     */
    @Transactional
    public List<LineItemMatch> persist(AnalysisOwner owner, MatchingPlan plan) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(plan, "plan must not be null");

        UUID analysisId = plan.analysisId();
        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);

        ConfirmedInput confirmedInput = readConfirmedInput(analysis, now);
        requireCurrentPlan(plan, confirmedInput);

        List<LineItemMatch> existing = lineItemMatchRepository.findByAnalysisIdForUpdate(analysisId);
        if (!existing.isEmpty()) {
            return requireCompleteExistingSet(plan, confirmedInput, existing);
        }
        if (analysis.status() != AnalysisStatus.MATCHING) {
            throw new AnalysisMatchingNotAllowedException();
        }

        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId)
                .filter(current -> current.status() == AnalysisJobStatus.WAITING_FOR_USER
                        && current.currentStage() == AnalysisStatus.MATCHING)
                .orElseThrow(AnalysisMatchingNotAllowedException::new);

        validatePlan(plan, confirmedInput);
        Instant transitionAt = latest(now, analysis.updatedAt(), job.updatedAt());
        List<LineItemMatch> persisted = lineItemMatchRepository.createAll(plan.matches());
        requirePersistedRows(plan.matches(), persisted);

        boolean fullyMatched = plan.matches().stream()
                .allMatch(match -> match.status() == LineMatchStatus.MATCHED);
        Analysis updatedAnalysis = fullyMatched
                ? analysis.beginReconciliation(transitionAt)
                : analysis.awaitMatchReview(transitionAt);
        AnalysisJob updatedJob = fullyMatched
                ? job.waitForReconciliation(transitionAt)
                : job.waitForMatchReview(transitionAt);

        analysisRepository.update(updatedAnalysis);
        analysisJobRepository.update(updatedJob);
        return persisted;
    }

    private ConfirmedInput readConfirmedInput(Analysis analysis, Instant now) {
        List<Document> documents = documentRepository.findByAnalysisId(analysis.id()).stream()
                .sorted(DOCUMENT_ROLE_ORDER)
                .toList();
        if (documents.size() != 2
                || documents.get(0).role() != DocumentRole.REFERENCE
                || documents.get(1).role() != DocumentRole.INVOICE) {
            throw new MatchingInputNotReadyException();
        }

        Document reference = documents.get(0);
        Document invoice = documents.get(1);
        if (!isConfirmedAndActive(reference, analysis, now)
                || !isConfirmedAndActive(invoice, analysis, now)) {
            throw new MatchingInputNotReadyException();
        }

        PersistedExtraction referenceExtraction = extractedDocumentRepository.findByDocumentId(reference.id())
                .filter(PersistLineItemMatchesTransaction::isConfirmed)
                .orElseThrow(MatchingInputNotReadyException::new);
        PersistedExtraction invoiceExtraction = extractedDocumentRepository.findByDocumentId(invoice.id())
                .filter(PersistLineItemMatchesTransaction::isConfirmed)
                .orElseThrow(MatchingInputNotReadyException::new);

        List<MatchableLineItem> referenceLines = matchingLines(referenceExtraction);
        List<MatchableLineItem> invoiceLines = matchingLines(invoiceExtraction);
        if (referenceLines.isEmpty() || invoiceLines.isEmpty()) {
            throw new MatchingInputNotReadyException();
        }
        return new ConfirmedInput(referenceLines, invoiceLines);
    }

    private static boolean isConfirmedAndActive(Document document, Analysis analysis, Instant now) {
        return document.confirmedType() != null
                && Objects.equals(document.expiresAt(), analysis.expiresAt())
                && (document.expiresAt() == null || document.expiresAt().isAfter(now));
    }

    private static boolean isConfirmed(PersistedExtraction extraction) {
        return extraction.extraction().status() == ExtractionStatus.CONFIRMED
                && extraction.extraction().confirmedAt() != null;
    }

    private static List<MatchableLineItem> matchingLines(PersistedExtraction extraction) {
        return IntStream.range(0, extraction.extraction().lines().size())
                .mapToObj(index -> {
                    var line = extraction.extraction().lines().get(index);
                    return new MatchableLineItem(
                            extraction.lineItemIds().get(index),
                            line.position(),
                            line.itemCode(),
                            line.description(),
                            line.normalizedDescription(),
                            line.unit());
                })
                .sorted(LINE_ORDER)
                .toList();
    }

    private static void requireCurrentPlan(MatchingPlan plan, ConfirmedInput input) {
        if (!ordered(plan.referenceLines()).equals(input.referenceLines())
                || !ordered(plan.invoiceLines()).equals(input.invoiceLines())) {
            throw new MatchingInputNotReadyException();
        }
    }

    private static List<LineItemMatch> requireCompleteExistingSet(
            MatchingPlan plan,
            ConfirmedInput input,
            List<LineItemMatch> existing) {
        try {
            validateUniqueMatchIds(existing);
            new MatchingPlan(plan.analysisId(), input.referenceLines(), input.invoiceLines(), existing);
            return existing;
        } catch (IllegalArgumentException invalidSet) {
            throw new MatchSetConflictException();
        }
    }

    private static void validatePlan(MatchingPlan plan, ConfirmedInput input) {
        try {
            validateUniqueMatchIds(plan.matches());
            new MatchingPlan(plan.analysisId(), input.referenceLines(), input.invoiceLines(), plan.matches());
        } catch (IllegalArgumentException invalidPlan) {
            throw new MatchSetConflictException();
        }
    }

    private static void validateUniqueMatchIds(List<LineItemMatch> matches) {
        Set<UUID> ids = new HashSet<>();
        for (LineItemMatch match : matches) {
            if (!ids.add(match.id())) {
                throw new IllegalArgumentException("match row identities must be unique");
            }
        }
    }

    private static void requirePersistedRows(List<LineItemMatch> requested, List<LineItemMatch> persisted) {
        if (persisted.size() != requested.size()
                || !matchIds(requested).equals(matchIds(persisted))) {
            throw new MatchSetConflictException();
        }
    }

    private static Set<UUID> matchIds(List<LineItemMatch> matches) {
        return matches.stream().map(LineItemMatch::id).collect(java.util.stream.Collectors.toSet());
    }

    private static List<MatchableLineItem> ordered(List<MatchableLineItem> lines) {
        return lines.stream().sorted(LINE_ORDER).toList();
    }

    private static Instant latest(Instant first, Instant second, Instant third) {
        return first.isAfter(second)
                ? (first.isAfter(third) ? first : third)
                : (second.isAfter(third) ? second : third);
    }

    private record ConfirmedInput(
            List<MatchableLineItem> referenceLines,
            List<MatchableLineItem> invoiceLines) {
    }
}
