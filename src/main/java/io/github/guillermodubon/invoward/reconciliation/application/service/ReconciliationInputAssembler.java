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
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisReconciliationNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputNotReadyException;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchingPlan;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationInput;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.ConfirmedEvidenceSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/** Authorizes an Analysis and assembles its complete, confirmed reconciliation input snapshot. */
@Service
@Transactional(readOnly = true)
public class ReconciliationInputAssembler {

    private final AnalysisRepository analysisRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final LineItemMatchRepository lineItemMatchRepository;
    private final Clock clock;

    public ReconciliationInputAssembler(
            AnalysisRepository analysisRepository,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            LineItemMatchRepository lineItemMatchRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.extractedDocumentRepository = Objects.requireNonNull(extractedDocumentRepository);
        this.lineItemMatchRepository = Objects.requireNonNull(lineItemMatchRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Loads downstream data only after the requested Analysis has been authorized for this owner. */
    public ReconciliationInput assemble(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        if (analysis.status() != AnalysisStatus.RECONCILING) {
            throw new AnalysisReconciliationNotAllowedException();
        }

        Documents documents = loadDocuments(analysis, now);
        PersistedExtraction referenceExtraction = loadConfirmedExtraction(documents.reference());
        PersistedExtraction invoiceExtraction = loadConfirmedExtraction(documents.invoice());
        Map<UUID, ExtractedLineItem> referenceLines = confirmedLines(referenceExtraction);
        Map<UUID, ExtractedLineItem> invoiceLines = confirmedLines(invoiceExtraction);
        requireNonEmptyLines(referenceLines, invoiceLines);
        requireDisjointLineIds(referenceLines, invoiceLines);

        List<LineItemMatch> matches = List.copyOf(lineItemMatchRepository.findByAnalysisId(analysis.id()));
        validateCompleteMatchCover(analysis.id(), referenceLines, invoiceLines, matches);

        return new ReconciliationInput(
                analysis,
                documents.reference(),
                documents.invoice(),
                referenceLines,
                invoiceLines,
                matches,
                analysis.priceTolerance(),
                ReconciliationInput.snapshot(matches),
                evidenceSource(documents.reference(), referenceExtraction),
                evidenceSource(documents.invoice(), invoiceExtraction));
    }

    private static ConfirmedEvidenceSource evidenceSource(Document document, PersistedExtraction extraction) {
        return new ConfirmedEvidenceSource(
                document, extraction.documentId(), extraction.extraction(), extraction.lineItemIds());
    }

    private Documents loadDocuments(Analysis analysis, Instant now) {
        Map<DocumentRole, Document> byRole = new EnumMap<>(DocumentRole.class);
        for (Document document : documentRepository.findByAnalysisId(analysis.id())) {
            if (!analysis.id().equals(document.analysisId())
                    || document.confirmedType() == null
                    || !Objects.equals(document.expiresAt(), analysis.expiresAt())
                    || (document.expiresAt() != null && !document.expiresAt().isAfter(now))
                    || byRole.putIfAbsent(document.role(), document) != null) {
                throw new ReconciliationInputNotReadyException();
            }
        }
        Document reference = byRole.get(DocumentRole.REFERENCE);
        Document invoice = byRole.get(DocumentRole.INVOICE);
        if (byRole.size() != 2 || reference == null || invoice == null) {
            throw new ReconciliationInputNotReadyException();
        }
        return new Documents(reference, invoice);
    }

    private PersistedExtraction loadConfirmedExtraction(Document document) {
        return extractedDocumentRepository.findByDocumentId(document.id())
                .filter(extraction -> document.id().equals(extraction.documentId()))
                .filter(ReconciliationInputAssembler::isConfirmed)
                .orElseThrow(ReconciliationInputNotReadyException::new);
    }

    private static boolean isConfirmed(PersistedExtraction extraction) {
        ExtractedDocument value = extraction.extraction();
        return value.status() == ExtractionStatus.CONFIRMED && value.confirmedAt() != null;
    }

    private static Map<UUID, ExtractedLineItem> confirmedLines(PersistedExtraction extraction) {
        List<ExtractedLineItem> lines = extraction.extraction().lines();
        List<UUID> lineIds = extraction.lineItemIds();
        if (lines.size() != lineIds.size() || new HashSet<>(lineIds).size() != lineIds.size()) {
            throw new ReconciliationInputNotReadyException();
        }
        Map<UUID, ExtractedLineItem> result = new LinkedHashMap<>();
        IntStream.range(0, lines.size()).forEach(index -> {
            UUID lineId = lineIds.get(index);
            ExtractedLineItem line = lines.get(index);
            if (line.position() != index || result.putIfAbsent(lineId, line) != null) {
                throw new ReconciliationInputNotReadyException();
            }
        });
        return result;
    }

    private static void requireNonEmptyLines(
            Map<UUID, ExtractedLineItem> referenceLines,
            Map<UUID, ExtractedLineItem> invoiceLines) {
        if (referenceLines.isEmpty() || invoiceLines.isEmpty()) {
            throw new ReconciliationInputNotReadyException();
        }
    }

    private static void requireDisjointLineIds(
            Map<UUID, ExtractedLineItem> referenceLines,
            Map<UUID, ExtractedLineItem> invoiceLines) {
        Set<UUID> ids = new HashSet<>(referenceLines.keySet());
        if (invoiceLines.keySet().stream().anyMatch(ids::contains)) {
            throw new ReconciliationInputNotReadyException();
        }
    }

    private static void validateCompleteMatchCover(
            UUID analysisId,
            Map<UUID, ExtractedLineItem> referenceLines,
            Map<UUID, ExtractedLineItem> invoiceLines,
            List<LineItemMatch> matches) {
        if (matches.stream().anyMatch(match -> match.status() == LineMatchStatus.NEEDS_REVIEW)) {
            throw new ReconciliationInputNotReadyException();
        }
        try {
            Set<UUID> matchIds = new HashSet<>();
            for (LineItemMatch match : matches) {
                if (!matchIds.add(match.id())) {
                    throw new IllegalArgumentException("match IDs must be unique");
                }
            }
            new MatchingPlan(
                    analysisId,
                    matchingLines(referenceLines),
                    matchingLines(invoiceLines),
                    matches);
        } catch (IllegalArgumentException invalidCover) {
            throw new ReconciliationInputNotReadyException();
        }
    }

    private static List<MatchableLineItem> matchingLines(Map<UUID, ExtractedLineItem> lines) {
        List<MatchableLineItem> result = new ArrayList<>(lines.size());
        lines.forEach((id, line) -> result.add(new MatchableLineItem(
                id,
                line.position(),
                line.itemCode(),
                line.description(),
                line.normalizedDescription(),
                line.unit())));
        return List.copyOf(result);
    }

    private record Documents(Document reference, Document invoice) {
    }
}
