package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
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
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchingResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchingPlan;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/** Orchestrates owner-authorized matching without holding database locks during computation or AI calls. */
@Service
public class RunLineItemMatchingService {

    private final AnalysisRepository analysisRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final LineItemMatchRepository lineItemMatchRepository;
    private final LineItemMatchingEngine matchingEngine;
    private final AmbiguousLineMatchingAssistant ambiguousLineMatchingAssistant;
    private final PersistLineItemMatchesTransaction persistenceTransaction;
    private final Clock clock;

    public RunLineItemMatchingService(
            AnalysisRepository analysisRepository,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            LineItemMatchRepository lineItemMatchRepository,
            LineItemMatchingEngine matchingEngine,
            AmbiguousLineMatchingAssistant ambiguousLineMatchingAssistant,
            PersistLineItemMatchesTransaction persistenceTransaction,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
        this.lineItemMatchRepository = Objects.requireNonNull(lineItemMatchRepository);
        this.matchingEngine = Objects.requireNonNull(matchingEngine);
        this.ambiguousLineMatchingAssistant = Objects.requireNonNull(ambiguousLineMatchingAssistant);
        this.persistenceTransaction = Objects.requireNonNull(persistenceTransaction);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Runs matching for an owner-visible Analysis and returns its complete persisted match set. */
    public List<LineItemMatch> run(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        if (analysis.status() != AnalysisStatus.MATCHING) {
            throw new AnalysisMatchingNotAllowedException();
        }

        ConfirmedInput input = loadConfirmedInput(analysis, now);
        List<LineItemMatch> existing = lineItemMatchRepository.findByAnalysisId(analysis.id());
        if (!existing.isEmpty()) {
            return requireCompleteExistingSet(analysis.id(), input, existing);
        }

        LineItemMatchingResult deterministicResult = matchingEngine.match(
                analysis.id(), input.referenceLines(), input.invoiceLines(), now, UUID::randomUUID);
        MatchingPlan proposedPlan = deterministicResult.ambiguousCandidates().isEmpty()
                ? deterministicResult.plan()
                : ambiguousLineMatchingAssistant.assist(deterministicResult);
        MatchingPlan completePlan = validateCompletePlan(analysis.id(), input, proposedPlan);

        // The transaction locks and reloads the Analysis, rechecks current input and any concurrent winner.
        return persistenceTransaction.persist(owner, completePlan);
    }

    private ConfirmedInput loadConfirmedInput(Analysis analysis, Instant now) {
        List<Document> documents = documentRepository.findByAnalysisId(analysis.id());
        if (documents.size() != 2) {
            throw new MatchingInputNotReadyException();
        }

        Document reference = null;
        Document invoice = null;
        for (Document document : documents) {
            if (!analysis.id().equals(document.analysisId())
                    || !isConfirmedAndActive(document, analysis, now)) {
                throw new MatchingInputNotReadyException();
            }
            if (document.role() == DocumentRole.REFERENCE) {
                if (reference != null) {
                    throw new MatchingInputNotReadyException();
                }
                reference = document;
            } else if (document.role() == DocumentRole.INVOICE) {
                if (invoice != null) {
                    throw new MatchingInputNotReadyException();
                }
                invoice = document;
            }
        }
        if (reference == null || invoice == null) {
            throw new MatchingInputNotReadyException();
        }

        PersistedExtraction referenceExtraction = confirmedExtraction(reference);
        PersistedExtraction invoiceExtraction = confirmedExtraction(invoice);
        List<MatchableLineItem> referenceLines = matchingLines(referenceExtraction);
        List<MatchableLineItem> invoiceLines = matchingLines(invoiceExtraction);
        if (referenceLines.isEmpty() || invoiceLines.isEmpty()) {
            throw new MatchingInputNotReadyException();
        }
        return new ConfirmedInput(referenceLines, invoiceLines);
    }

    private PersistedExtraction confirmedExtraction(Document document) {
        PersistedExtraction persisted = extractedDocumentRepository.findByDocumentId(document.id())
                .filter(extraction -> document.id().equals(extraction.documentId()))
                .filter(RunLineItemMatchingService::isConfirmed)
                .orElseThrow(MatchingInputNotReadyException::new);
        return persisted;
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
                .toList();
    }

    private static List<LineItemMatch> requireCompleteExistingSet(
            UUID analysisId,
            ConfirmedInput input,
            List<LineItemMatch> existing) {
        try {
            validateUniqueMatchIds(existing);
            new MatchingPlan(analysisId, input.referenceLines(), input.invoiceLines(), existing);
            return List.copyOf(existing);
        } catch (IllegalArgumentException invalidSet) {
            throw new MatchSetConflictException();
        }
    }

    private static MatchingPlan validateCompletePlan(
            UUID analysisId,
            ConfirmedInput input,
            MatchingPlan proposedPlan) {
        try {
            validateUniqueMatchIds(proposedPlan.matches());
            if (!analysisId.equals(proposedPlan.analysisId())
                    || !input.referenceLines().equals(proposedPlan.referenceLines())
                    || !input.invoiceLines().equals(proposedPlan.invoiceLines())) {
                throw new IllegalArgumentException("matching plan does not use the confirmed input");
            }
            return new MatchingPlan(
                    analysisId, input.referenceLines(), input.invoiceLines(), proposedPlan.matches());
        } catch (IllegalArgumentException invalidPlan) {
            throw new IllegalStateException("Matching engine did not produce a complete plan.", invalidPlan);
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

    private record ConfirmedInput(
            List<MatchableLineItem> referenceLines,
            List<MatchableLineItem> invoiceLines) {
    }
}
