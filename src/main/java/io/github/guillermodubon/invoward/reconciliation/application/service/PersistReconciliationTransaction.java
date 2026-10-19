package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisReconciliationNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputChangedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputNotReadyException;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationPersistenceResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationPlan;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyEvidenceRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.ReconciliationLineRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.EvidenceSide;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Atomically persists a precomputed reconciliation snapshot and advances its workflow. */
@Service
public class PersistReconciliationTransaction {

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final LineItemMatchRepository lineItemMatchRepository;
    private final ReconciliationLineRepository reconciliationLineRepository;
    private final DiscrepancyRepository discrepancyRepository;
    private final DiscrepancyEvidenceRepository discrepancyEvidenceRepository;
    private final AnalysisProcessingSignalPublisher processingSignalPublisher;
    private final Clock clock;

    public PersistReconciliationTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            LineItemMatchRepository lineItemMatchRepository,
            ReconciliationLineRepository reconciliationLineRepository,
            DiscrepancyRepository discrepancyRepository,
            DiscrepancyEvidenceRepository discrepancyEvidenceRepository,
            AnalysisProcessingSignalPublisher processingSignalPublisher,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.lineItemMatchRepository = Objects.requireNonNull(lineItemMatchRepository);
        this.reconciliationLineRepository = Objects.requireNonNull(reconciliationLineRepository);
        this.discrepancyRepository = Objects.requireNonNull(discrepancyRepository);
        this.discrepancyEvidenceRepository = Objects.requireNonNull(discrepancyEvidenceRepository);
        this.processingSignalPublisher = Objects.requireNonNull(processingSignalPublisher);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Rechecks ownership, workflow and captured match revisions before persisting. Financial
     * calculation and duplicate detection must already be complete before this transaction begins.
     */
    @Transactional
    public ReconciliationPersistenceResult persist(
            AnalysisOwner owner,
            ReconciliationInput input,
            ReconciliationPlan plan,
            AnalysisReconciliationStatus reconciliationStatus) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(reconciliationStatus, "reconciliationStatus must not be null");
        if (!input.analysis().id().equals(plan.analysisId())) {
            throw new ReconciliationInputNotReadyException();
        }
        if (reconciliationStatus == AnalysisReconciliationStatus.MATCHED && !plan.discrepancies().isEmpty()) {
            throw new ReconciliationInputNotReadyException();
        }

        UUID analysisId = plan.analysisId();
        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        if (!analysis.owner().equals(input.analysis().owner())
                || !analysis.priceTolerance().equals(input.priceTolerance())) {
            throw new ReconciliationInputChangedException();
        }

        List<LineItemMatch> lockedMatches = lineItemMatchRepository.findByAnalysisIdForUpdate(analysisId);
        verifyMatchSnapshot(input, lockedMatches);
        if (lockedMatches.isEmpty() || lockedMatches.stream()
                .anyMatch(match -> match.status() == LineMatchStatus.NEEDS_REVIEW)) {
            throw new ReconciliationInputNotReadyException();
        }
        verifyPlan(input, plan, lockedMatches);

        AnalysisJob job = analysisJobRepository.findJobByAnalysisIdForUpdate(analysisId)
                .orElseThrow(AnalysisReconciliationNotAllowedException::new);
        long existingLineCount = reconciliationLineRepository.countByAnalysisId(analysisId);
        long existingDiscrepancyCount = discrepancyRepository.countByAnalysisId(analysisId);

        if (isAfterReconciliation(analysis.status())) {
            return existingWinner(analysis, job, lockedMatches, existingLineCount, existingDiscrepancyCount);
        }
        if (analysis.status() != AnalysisStatus.RECONCILING) {
            throw new AnalysisReconciliationNotAllowedException();
        }
        if (job.status() != AnalysisJobStatus.RUNNING
                || job.currentStage() != AnalysisStatus.RECONCILING) {
            throw new AnalysisReconciliationNotAllowedException();
        }
        if (existingLineCount != 0 || existingDiscrepancyCount != 0) {
            throw new ReconciliationConflictException();
        }

        validateEvidenceSources(input, plan.evidence());
        Instant transitionAt = latest(now, analysis.updatedAt(), job.updatedAt());

        List<ReconciliationLine> lines = requirePersistedLines(
                plan.lines(), reconciliationLineRepository.createAll(plan.lines()), lockedMatches);
        List<Discrepancy> discrepancies = requirePersistedDiscrepancies(
                plan.discrepancies(), discrepancyRepository.createAll(plan.discrepancies()));
        requirePersistedEvidence(plan.evidence(), discrepancyEvidenceRepository.createAll(plan.evidence()));

        Analysis updatedAnalysis = analysisRepository.update(
                analysis.completeReconciliation(reconciliationStatus, plan.analysisDifference(), transitionAt));
        analysisJobRepository.update(job.queueReportGeneration(transitionAt));
        processingSignalPublisher.request(analysisId);
        return new ReconciliationPersistenceResult(true, updatedAnalysis, lines, discrepancies);
    }

    private ReconciliationPersistenceResult existingWinner(
            Analysis analysis,
            AnalysisJob job,
            List<LineItemMatch> matches,
            long existingLineCount,
            long existingDiscrepancyCount) {
        if (!isPersistedResultState(analysis, job)
                || analysis.reconciliationStatus() == null
                || existingLineCount != matches.size()
                || existingLineCount == 0) {
            throw new ReconciliationConflictException();
        }
        List<ReconciliationLine> lines = reconciliationLineRepository.findByAnalysisId(analysis.id());
        List<Discrepancy> discrepancies = discrepancyRepository.findByAnalysisId(analysis.id());
        if (lines.size() != existingLineCount || discrepancies.size() != existingDiscrepancyCount
                || !lineMatchIds(lines).equals(matchIds(matches))) {
            throw new ReconciliationConflictException();
        }
        return new ReconciliationPersistenceResult(false, analysis, lines, discrepancies);
    }

    private static boolean isAfterReconciliation(AnalysisStatus status) {
        return status == AnalysisStatus.GENERATING_REPORT
                || status == AnalysisStatus.COMPLETED
                || status == AnalysisStatus.FAILED;
    }

    private static boolean isPersistedResultState(Analysis analysis, AnalysisJob job) {
        if (!analysis.id().equals(job.analysisId())) {
            return false;
        }
        return switch (analysis.status()) {
            case GENERATING_REPORT -> job.currentStage() == AnalysisStatus.GENERATING_REPORT
                    && (job.status() == AnalysisJobStatus.QUEUED
                    || job.status() == AnalysisJobStatus.RUNNING
                    || job.status() == AnalysisJobStatus.FAILED
                    || job.status() == AnalysisJobStatus.WAITING_FOR_USER);
            case COMPLETED -> job.status() == AnalysisJobStatus.COMPLETED
                    && job.currentStage() == AnalysisStatus.COMPLETED;
            case FAILED -> job.status() == AnalysisJobStatus.FAILED
                    && job.currentStage() == AnalysisStatus.GENERATING_REPORT;
            default -> false;
        };
    }

    private static void verifyMatchSnapshot(ReconciliationInput input, List<LineItemMatch> matches) {
        Set<ReconciliationInput.MatchSnapshot> expected = new HashSet<>(input.matchSnapshots());
        Set<ReconciliationInput.MatchSnapshot> current = new HashSet<>(ReconciliationInput.snapshot(matches));
        if (expected.size() != input.matchSnapshots().size()
                || current.size() != matches.size()
                || !expected.equals(current)) {
            throw new ReconciliationInputChangedException();
        }
    }

    private static void verifyPlan(
            ReconciliationInput input,
            ReconciliationPlan plan,
            List<LineItemMatch> matches) {
        if (!input.analysis().id().equals(plan.analysisId())
                || !lineMatchIds(plan.lines()).equals(matchIds(matches))
                || plan.lines().size() != matches.size()) {
            throw new ReconciliationInputNotReadyException();
        }
    }

    private static void validateEvidenceSources(
            ReconciliationInput input, List<DiscrepancyEvidence> evidence) {
        for (DiscrepancyEvidence item : evidence) {
            boolean sourceMatches = item.side() == EvidenceSide.REFERENCE
                    ? item.documentId().equals(input.referenceDocument().id())
                    : item.documentId().equals(input.invoiceDocument().id());
            if (!sourceMatches) {
                throw new ReconciliationInputNotReadyException();
            }
        }
    }

    private static List<ReconciliationLine> requirePersistedLines(
            List<ReconciliationLine> requested,
            List<ReconciliationLine> persisted,
            List<LineItemMatch> matches) {
        if (persisted.size() != requested.size()
                || !lineMatchIds(persisted).equals(matchIds(matches))) {
            throw new ReconciliationConflictException();
        }
        return persisted;
    }

    private static List<Discrepancy> requirePersistedDiscrepancies(
            List<Discrepancy> requested, List<Discrepancy> persisted) {
        if (persisted.size() != requested.size()
                || !ids(requested, Discrepancy::id).equals(ids(persisted, Discrepancy::id))) {
            throw new ReconciliationConflictException();
        }
        return persisted;
    }

    private static List<DiscrepancyEvidence> requirePersistedEvidence(
            List<DiscrepancyEvidence> requested, List<DiscrepancyEvidence> persisted) {
        if (persisted.size() != requested.size()
                || !ids(requested, DiscrepancyEvidence::id).equals(ids(persisted, DiscrepancyEvidence::id))) {
            throw new ReconciliationConflictException();
        }
        return persisted;
    }

    private static Set<UUID> lineMatchIds(List<ReconciliationLine> lines) {
        return lines.stream().map(ReconciliationLine::lineMatchId).collect(Collectors.toSet());
    }

    private static Set<UUID> matchIds(List<LineItemMatch> matches) {
        return matches.stream().map(LineItemMatch::id).collect(Collectors.toSet());
    }

    private static <T> Set<UUID> ids(List<T> values, java.util.function.Function<T, UUID> id) {
        return values.stream().map(id).collect(Collectors.toSet());
    }

    private static Instant latest(Instant first, Instant second, Instant third) {
        if (first.isAfter(second)) {
            return first.isAfter(third) ? first : third;
        }
        return second.isAfter(third) ? second : third;
    }
}
