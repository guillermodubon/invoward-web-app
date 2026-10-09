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
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchesNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchSetView;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

/** Reads only persisted matching state after owner-scoped Analysis authorization. */
@Service
@Transactional(readOnly = true)
public class GetLineItemMatchesService {

    private static final Comparator<LineItemMatchSetView.MatchEntry> MATCH_ORDER = Comparator
            .comparingInt((LineItemMatchSetView.MatchEntry match) -> match.reference() == null ? 1 : 0)
            .thenComparingInt(match -> match.reference() != null
                    ? match.reference().position()
                    : match.invoice().position())
            .thenComparing(LineItemMatchSetView.MatchEntry::id);

    private final AnalysisRepository analysisRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final LineItemMatchRepository lineItemMatchRepository;
    private final Clock clock;

    public GetLineItemMatchesService(
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

    public LineItemMatchSetView get(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        List<LineItemMatch> persistedMatches = lineItemMatchRepository.findByAnalysisId(analysis.id());
        if (persistedMatches.isEmpty()) {
            throw new MatchesNotFoundException();
        }

        Map<UUID, LineItemMatchSetView.LineItem> referenceLines = new HashMap<>();
        Map<UUID, LineItemMatchSetView.LineItem> invoiceLines = new HashMap<>();
        loadConfirmedLines(analysis, now, referenceLines, invoiceLines);

        List<LineItemMatchSetView.MatchEntry> matches = persistedMatches.stream()
                .map(match -> projectMatch(analysis.id(), match, referenceLines, invoiceLines))
                .sorted(MATCH_ORDER)
                .toList();
        return new LineItemMatchSetView(
                analysis.id(), analysis.status(), analysis.status() == AnalysisStatus.AWAITING_MATCH_REVIEW, matches);
    }

    private void loadConfirmedLines(
            Analysis analysis,
            Instant now,
            Map<UUID, LineItemMatchSetView.LineItem> referenceLines,
            Map<UUID, LineItemMatchSetView.LineItem> invoiceLines) {
        EnumMap<DocumentRole, Document> documentsByRole = new EnumMap<>(DocumentRole.class);
        for (Document document : documentRepository.findByAnalysisId(analysis.id())) {
            if (!analysis.id().equals(document.analysisId())
                    || document.confirmedType() == null
                    || !Objects.equals(document.expiresAt(), analysis.expiresAt())
                    || (document.expiresAt() != null && !document.expiresAt().isAfter(now))
                    || documentsByRole.putIfAbsent(document.role(), document) != null) {
                throw new MatchSetConflictException();
            }
        }

        Document reference = documentsByRole.get(DocumentRole.REFERENCE);
        Document invoice = documentsByRole.get(DocumentRole.INVOICE);
        if (documentsByRole.size() != 2 || reference == null || invoice == null) {
            throw new MatchSetConflictException();
        }
        projectConfirmedExtraction(reference, referenceLines);
        projectConfirmedExtraction(invoice, invoiceLines);
    }

    private void projectConfirmedExtraction(
            Document document,
            Map<UUID, LineItemMatchSetView.LineItem> destination) {
        PersistedExtraction extraction = extractedDocumentRepository.findByDocumentId(document.id())
                .filter(persisted -> document.id().equals(persisted.documentId()))
                .filter(GetLineItemMatchesService::isConfirmed)
                .orElseThrow(MatchSetConflictException::new);

        IntStream.range(0, extraction.extraction().lines().size()).forEach(index -> {
            UUID lineId = extraction.lineItemIds().get(index);
            var line = extraction.extraction().lines().get(index);
            LineItemMatchSetView.LineItem projection = new LineItemMatchSetView.LineItem(
                    lineId,
                    line.position(),
                    line.itemCode(),
                    line.description(),
                    line.quantity(),
                    line.unit(),
                    line.unitPrice(),
                    line.lineTotal());
            if (destination.putIfAbsent(lineId, projection) != null) {
                throw new MatchSetConflictException();
            }
        });
    }

    private static LineItemMatchSetView.MatchEntry projectMatch(
            UUID analysisId,
            LineItemMatch match,
            Map<UUID, LineItemMatchSetView.LineItem> referenceLines,
            Map<UUID, LineItemMatchSetView.LineItem> invoiceLines) {
        if (!analysisId.equals(match.analysisId())) {
            throw new MatchSetConflictException();
        }

        LineItemMatchSetView.LineItem reference = match.referenceLineItemId() == null
                ? null
                : referenceLines.get(match.referenceLineItemId());
        LineItemMatchSetView.LineItem invoice = match.invoiceLineItemId() == null
                ? null
                : invoiceLines.get(match.invoiceLineItemId());
        if ((match.referenceLineItemId() != null && reference == null)
                || (match.invoiceLineItemId() != null && invoice == null)) {
            throw new MatchSetConflictException();
        }
        return new LineItemMatchSetView.MatchEntry(
                match.id(), match.status(), match.method(), match.confidence(), match.version(),
                match.reviewedAt(), reference, invoice);
    }

    private static boolean isConfirmed(PersistedExtraction extraction) {
        return extraction.extraction().status() == ExtractionStatus.CONFIRMED
                && extraction.extraction().confirmedAt() != null;
    }
}
