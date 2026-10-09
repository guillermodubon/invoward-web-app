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
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchesLockedException;
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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/** Validates and atomically confirms an owner-scoped persisted match set. */
@Service
public class ConfirmLineItemMatchesService {

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final LineItemMatchRepository lineItemMatchRepository;
    private final Clock clock;

    public ConfirmLineItemMatchesService(
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

    @Transactional
    public void confirm(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        boolean alreadyReconciling = analysis.status() == AnalysisStatus.RECONCILING;
        if (analysis.status() == AnalysisStatus.GENERATING_REPORT
                || analysis.status() == AnalysisStatus.COMPLETED) {
            throw new MatchesLockedException();
        }
        if (!alreadyReconciling && analysis.status() != AnalysisStatus.AWAITING_MATCH_REVIEW) {
            throw new AnalysisMatchingNotAllowedException();
        }

        AnalysisStatus requiredJobStage = alreadyReconciling
                ? AnalysisStatus.RECONCILING
                : AnalysisStatus.AWAITING_MATCH_REVIEW;
        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId)
                .filter(current -> current.analysisId().equals(analysisId)
                        && current.status() == AnalysisJobStatus.WAITING_FOR_USER
                        && current.currentStage() == requiredJobStage)
                .orElseThrow(AnalysisMatchingNotAllowedException::new);

        List<LineItemMatch> matches = lineItemMatchRepository.findByAnalysisIdForUpdate(analysisId);
        ConfirmedInput input = readConfirmedInput(analysis, now);
        requireCompleteCover(analysisId, input, matches);
        requireNoPendingReview(matches);
        if (alreadyReconciling) {
            return;
        }

        Instant transitionAt = latest(now, analysis.updatedAt(), job.updatedAt(), matches);
        stampUnreviewedMatches(matches, transitionAt);

        List<LineItemMatch> confirmedMatches = lineItemMatchRepository.findByAnalysisIdForUpdate(analysisId);
        requireCompleteCover(analysisId, input, confirmedMatches);
        requireNoPendingReview(confirmedMatches);

        analysisRepository.update(analysis.beginReconciliation(transitionAt));
        analysisJobRepository.update(job.waitForReconciliation(transitionAt));
    }

    private void stampUnreviewedMatches(List<LineItemMatch> matches, Instant reviewedAt) {
        for (LineItemMatch match : matches) {
            if (match.reviewedAt() != null) {
                continue;
            }
            LineItemMatch reviewed = new LineItemMatch(
                    match.id(), match.analysisId(), match.referenceLineItemId(), match.invoiceLineItemId(),
                    match.status(), match.method(), match.confidence(), match.version(),
                    reviewedAt, match.createdAt(), reviewedAt);
            lineItemMatchRepository.update(reviewed).orElseThrow(MatchConflictException::new);
        }
    }

    private ConfirmedInput readConfirmedInput(Analysis analysis, Instant now) {
        Map<DocumentRole, Document> documentsByRole = new EnumMap<>(DocumentRole.class);
        for (Document document : documentRepository.findByAnalysisId(analysis.id())) {
            if (!analysis.id().equals(document.analysisId())
                    || document.confirmedType() == null
                    || !Objects.equals(document.expiresAt(), analysis.expiresAt())
                    || (document.expiresAt() != null && !document.expiresAt().isAfter(now))
                    || documentsByRole.putIfAbsent(document.role(), document) != null) {
                throw new MatchingInputNotReadyException();
            }
        }
        Document reference = documentsByRole.get(DocumentRole.REFERENCE);
        Document invoice = documentsByRole.get(DocumentRole.INVOICE);
        if (documentsByRole.size() != 2 || reference == null || invoice == null) {
            throw new MatchingInputNotReadyException();
        }

        List<MatchableLineItem> referenceLines = confirmedLines(reference);
        List<MatchableLineItem> invoiceLines = confirmedLines(invoice);
        if (referenceLines.isEmpty() || invoiceLines.isEmpty()) {
            throw new MatchingInputNotReadyException();
        }
        return new ConfirmedInput(referenceLines, invoiceLines);
    }

    private List<MatchableLineItem> confirmedLines(Document document) {
        PersistedExtraction extraction = extractedDocumentRepository.findByDocumentId(document.id())
                .filter(value -> document.id().equals(value.documentId()))
                .filter(value -> value.extraction().status() == ExtractionStatus.CONFIRMED
                        && value.extraction().confirmedAt() != null)
                .orElseThrow(MatchingInputNotReadyException::new);
        if (extraction.lineItemIds().size() != extraction.extraction().lines().size()) {
            throw new MatchingInputNotReadyException();
        }
        return IntStream.range(0, extraction.extraction().lines().size())
                .mapToObj(index -> {
                    var line = extraction.extraction().lines().get(index);
                    return new MatchableLineItem(
                            extraction.lineItemIds().get(index), line.position(), line.itemCode(),
                            line.description(), line.normalizedDescription(), line.unit());
                })
                .toList();
    }

    private void requireCompleteCover(UUID analysisId, ConfirmedInput input, List<LineItemMatch> matches) {
        try {
            Set<UUID> matchIds = new HashSet<>();
            for (LineItemMatch match : matches) {
                if (!matchIds.add(match.id())) {
                    throw new IllegalArgumentException("match row identities must be unique");
                }
            }
            new MatchingPlan(analysisId, input.referenceLines(), input.invoiceLines(), matches);
        } catch (IllegalArgumentException invalidSet) {
            throw new MatchSetConflictException();
        }
    }

    private void requireNoPendingReview(List<LineItemMatch> matches) {
        if (matches.stream().anyMatch(match -> match.status() == LineMatchStatus.NEEDS_REVIEW)) {
            throw new MatchSetConflictException();
        }
    }

    private static Instant latest(Instant now, Instant analysisUpdatedAt, Instant jobUpdatedAt,
            List<LineItemMatch> matches) {
        Instant latest = now.isAfter(analysisUpdatedAt) ? now : analysisUpdatedAt;
        if (jobUpdatedAt.isAfter(latest)) {
            latest = jobUpdatedAt;
        }
        for (LineItemMatch match : matches) {
            if (match.updatedAt().isAfter(latest)) {
                latest = match.updatedAt();
            }
        }
        return latest;
    }

    private record ConfirmedInput(
            List<MatchableLineItem> referenceLines,
            List<MatchableLineItem> invoiceLines) {
        private ConfirmedInput {
            referenceLines = List.copyOf(referenceLines);
            invoiceLines = List.copyOf(invoiceLines);
        }
    }
}
