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
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchTargetUnavailableException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchesLockedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchingInputNotReadyException;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualLineItemMatchUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualMatchAction;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchingPlan;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/** Applies one manual decision while holding the Analysis and its entire match set locked. */
@Service
public class UpdateLineItemMatchTransaction {

    private final AnalysisRepository analysisRepository;
    private final DocumentRepository documentRepository;
    private final ExtractedDocumentRepository extractedDocumentRepository;
    private final LineItemMatchRepository lineItemMatchRepository;
    private final Clock clock;

    public UpdateLineItemMatchTransaction(
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

    @Transactional
    public List<LineItemMatch> update(
            UUID analysisId,
            UUID matchId,
            AnalysisOwner owner,
            ManualLineItemMatchUpdate command) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(matchId, "matchId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(command, "command must not be null");

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        requireReviewState(analysis.status());

        List<LineItemMatch> lockedMatches = lineItemMatchRepository.findByAnalysisIdForUpdate(analysis.id());
        LineItemMatch target = lockedMatches.stream()
                .filter(match -> match.id().equals(matchId))
                .findFirst()
                .orElseThrow(MatchNotFoundException::new);
        if (target.version() != command.expectedVersion()) {
            throw new MatchConflictException();
        }

        ConfirmedInput input = readConfirmedInput(analysis, now);
        requireCompleteCover(analysis.id(), input, lockedMatches);

        switch (command.action()) {
            case CONFIRM -> confirm(target, now);
            case MATCH_WITH -> matchWith(target, command, lockedMatches, input, now);
            case NO_MATCH -> markNoMatch(target, now);
        }

        List<LineItemMatch> updatedMatches = lineItemMatchRepository.findByAnalysisIdForUpdate(analysis.id());
        requireCompleteCover(analysis.id(), input, updatedMatches);
        return updatedMatches;
    }

    private void requireReviewState(AnalysisStatus status) {
        if (status == AnalysisStatus.AWAITING_MATCH_REVIEW) {
            return;
        }
        if (status == AnalysisStatus.RECONCILING
                || status == AnalysisStatus.GENERATING_REPORT
                || status == AnalysisStatus.COMPLETED) {
            throw new MatchesLockedException();
        }
        throw new AnalysisMatchingNotAllowedException();
    }

    private void confirm(LineItemMatch target, Instant now) {
        if (target.status() == LineMatchStatus.NEEDS_REVIEW) {
            update(target, LineMatchStatus.MATCHED, target.referenceLineItemId(), target.invoiceLineItemId(),
                    target.method(), target.confidence(), now);
            return;
        }
        if (isUnmatched(target.status())) {
            update(target, target.status(), target.referenceLineItemId(), target.invoiceLineItemId(),
                    target.method(), target.confidence(), now);
            return;
        }
        throw new MatchConflictException();
    }

    private void matchWith(
            LineItemMatch target,
            ManualLineItemMatchUpdate command,
            List<LineItemMatch> matches,
            ConfirmedInput input,
            Instant now) {
        UUID referenceId = command.referenceLineItemId();
        UUID invoiceId = command.invoiceLineItemId();
        if (!input.referenceIds().contains(referenceId) || !input.invoiceIds().contains(invoiceId)) {
            throw new MatchTargetUnavailableException();
        }
        if (!Objects.equals(target.referenceLineItemId(), referenceId)
                && !Objects.equals(target.invoiceLineItemId(), invoiceId)) {
            throw new MatchTargetUnavailableException();
        }

        LineItemMatch referenceCounterpart = target.referenceLineItemId() != null
                && target.referenceLineItemId().equals(referenceId)
                ? null
                : findUnmatchedCounterpart(matches, referenceId, true);
        LineItemMatch invoiceCounterpart = target.invoiceLineItemId() != null
                && target.invoiceLineItemId().equals(invoiceId)
                ? null
                : findUnmatchedCounterpart(matches, invoiceId, false);
        if (referenceCounterpart != null && invoiceCounterpart != null) {
            throw new MatchTargetUnavailableException();
        }

        // Free the candidate line's unique index before assigning it to the target row.
        LineItemMatch consumed = referenceCounterpart != null ? referenceCounterpart : invoiceCounterpart;
        if (consumed != null && !lineItemMatchRepository.deleteByIdAndAnalysisId(consumed.id(), target.analysisId())) {
            throw new MatchConflictException();
        }

        UUID displacedReferenceId = target.referenceLineItemId() != null
                && !target.referenceLineItemId().equals(referenceId) ? target.referenceLineItemId() : null;
        UUID displacedInvoiceId = target.invoiceLineItemId() != null
                && !target.invoiceLineItemId().equals(invoiceId) ? target.invoiceLineItemId() : null;
        update(target, LineMatchStatus.MATCHED, referenceId, invoiceId,
                LineMatchMethod.MANUAL, null, now);
        if (displacedReferenceId != null) {
            createUnmatched(target.analysisId(), displacedReferenceId, true, now);
        }
        if (displacedInvoiceId != null) {
            createUnmatched(target.analysisId(), displacedInvoiceId, false, now);
        }
    }

    private LineItemMatch findUnmatchedCounterpart(List<LineItemMatch> matches, UUID lineId, boolean referenceSide) {
        LineItemMatch match = matches.stream()
                .filter(candidate -> referenceSide
                        ? lineId.equals(candidate.referenceLineItemId())
                        : lineId.equals(candidate.invoiceLineItemId()))
                .findFirst()
                .orElseThrow(MatchTargetUnavailableException::new);
        LineMatchStatus expectedStatus = referenceSide
                ? LineMatchStatus.UNMATCHED_REFERENCE
                : LineMatchStatus.UNMATCHED_INVOICE;
        if (match.status() != expectedStatus) {
            throw new MatchTargetUnavailableException();
        }
        return match;
    }

    private void markNoMatch(LineItemMatch target, Instant now) {
        if (target.status() == LineMatchStatus.MATCHED || target.status() == LineMatchStatus.NEEDS_REVIEW) {
            update(target, LineMatchStatus.UNMATCHED_REFERENCE, target.referenceLineItemId(), null,
                    LineMatchMethod.MANUAL, null, now);
            createUnmatched(target.analysisId(), target.invoiceLineItemId(), false, now);
            return;
        }
        update(target, target.status(), target.referenceLineItemId(), target.invoiceLineItemId(),
                target.method(), target.confidence(), now);
    }

    private LineItemMatch update(
            LineItemMatch current,
            LineMatchStatus status,
            UUID referenceId,
            UUID invoiceId,
            LineMatchMethod method,
            java.math.BigDecimal confidence,
            Instant now) {
        LineItemMatch replacement = new LineItemMatch(
                current.id(), current.analysisId(), referenceId, invoiceId, status, method, confidence,
                current.version(), now, current.createdAt(), now);
        return lineItemMatchRepository.update(replacement).orElseThrow(MatchConflictException::new);
    }

    private void createUnmatched(UUID analysisId, UUID lineId, boolean referenceSide, Instant now) {
        LineItemMatch unmatched = new LineItemMatch(
                UUID.randomUUID(), analysisId,
                referenceSide ? lineId : null,
                referenceSide ? null : lineId,
                referenceSide ? LineMatchStatus.UNMATCHED_REFERENCE : LineMatchStatus.UNMATCHED_INVOICE,
                LineMatchMethod.MANUAL, null, 0, now, now, now);
        lineItemMatchRepository.create(unmatched);
    }

    private ConfirmedInput readConfirmedInput(Analysis analysis, Instant now) {
        List<Document> documents = documentRepository.findByAnalysisId(analysis.id());
        Document reference = null;
        Document invoice = null;
        for (Document document : documents) {
            if (!analysis.id().equals(document.analysisId())
                    || document.confirmedType() == null
                    || !Objects.equals(document.expiresAt(), analysis.expiresAt())
                    || (document.expiresAt() != null && !document.expiresAt().isAfter(now))) {
                throw new MatchingInputNotReadyException();
            }
            if (document.role() == DocumentRole.REFERENCE && reference == null) {
                reference = document;
            } else if (document.role() == DocumentRole.INVOICE && invoice == null) {
                invoice = document;
            } else {
                throw new MatchingInputNotReadyException();
            }
        }
        if (documents.size() != 2 || reference == null || invoice == null) {
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

    private static boolean isUnmatched(LineMatchStatus status) {
        return status == LineMatchStatus.UNMATCHED_REFERENCE || status == LineMatchStatus.UNMATCHED_INVOICE;
    }

    private record ConfirmedInput(
            List<MatchableLineItem> referenceLines,
            List<MatchableLineItem> invoiceLines) {

        private ConfirmedInput {
            referenceLines = List.copyOf(referenceLines);
            invoiceLines = List.copyOf(invoiceLines);
        }

        private Set<UUID> referenceIds() {
            return referenceLines.stream().map(MatchableLineItem::id).collect(java.util.stream.Collectors.toSet());
        }

        private Set<UUID> invoiceIds() {
            return invoiceLines.stream().map(MatchableLineItem::id).collect(java.util.stream.Collectors.toSet());
        }
    }
}
