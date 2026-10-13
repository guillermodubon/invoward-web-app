package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisReconciliationNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputNotReadyException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationPersistenceResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationPlan;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.ReconciliationLineRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidenceBuilder;
import io.github.guillermodubon.invoward.reconciliation.domain.DocumentReconciliationEngine;
import io.github.guillermodubon.invoward.reconciliation.domain.DocumentReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.LineReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationEngine;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Orchestrates owner-authorized reconciliation and delegates writes to one atomic transaction. */
@Service
public class ReconcileAnalysisService {

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final LineItemMatchRepository lineItemMatchRepository;
    private final ReconciliationLineRepository reconciliationLineRepository;
    private final DiscrepancyRepository discrepancyRepository;
    private final ReconciliationInputAssembler inputAssembler;
    private final DuplicateInvoiceDetector duplicateInvoiceDetector;
    private final CurrentDuplicateInvoiceWarningReader duplicateInvoiceWarningReader;
    private final ReconciliationLinePositionReader linePositionReader;
    private final PersistReconciliationTransaction persistenceTransaction;
    private final ReconciliationEngine lineEngine = new ReconciliationEngine();
    private final DocumentReconciliationEngine documentEngine = new DocumentReconciliationEngine();
    private final DiscrepancyEvidenceBuilder evidenceBuilder = new DiscrepancyEvidenceBuilder();
    private final Clock clock;

    public ReconcileAnalysisService(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            LineItemMatchRepository lineItemMatchRepository,
            ReconciliationLineRepository reconciliationLineRepository,
            DiscrepancyRepository discrepancyRepository,
            ReconciliationInputAssembler inputAssembler,
            DuplicateInvoiceDetector duplicateInvoiceDetector,
            CurrentDuplicateInvoiceWarningReader duplicateInvoiceWarningReader,
            ReconciliationLinePositionReader linePositionReader,
            PersistReconciliationTransaction persistenceTransaction,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.analysisJobRepository = Objects.requireNonNull(analysisJobRepository);
        this.lineItemMatchRepository = Objects.requireNonNull(lineItemMatchRepository);
        this.reconciliationLineRepository = Objects.requireNonNull(reconciliationLineRepository);
        this.discrepancyRepository = Objects.requireNonNull(discrepancyRepository);
        this.inputAssembler = Objects.requireNonNull(inputAssembler);
        this.duplicateInvoiceDetector = Objects.requireNonNull(duplicateInvoiceDetector);
        this.duplicateInvoiceWarningReader = Objects.requireNonNull(duplicateInvoiceWarningReader);
        this.linePositionReader = Objects.requireNonNull(linePositionReader);
        this.persistenceTransaction = Objects.requireNonNull(persistenceTransaction);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Reconciles one owner-visible Analysis synchronously and returns its persisted snapshot. */
    public ReconciliationResult reconcile(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Instant authorizedAt = clock.instant();
        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, authorizedAt)
                .orElseThrow(AnalysisNotFoundException::new);
        long existingLineCount = reconciliationLineRepository.countByAnalysisId(analysisId);
        long existingDiscrepancyCount = discrepancyRepository.countByAnalysisId(analysisId);

        if (analysis.status() == AnalysisStatus.GENERATING_REPORT) {
            return loadExistingResult(analysis, existingLineCount, existingDiscrepancyCount);
        }
        if (analysis.status() != AnalysisStatus.RECONCILING) {
            if (hasPersistedRows(existingLineCount, existingDiscrepancyCount)) {
                throw new ReconciliationConflictException();
            }
            throw new AnalysisReconciliationNotAllowedException();
        }
        if (hasPersistedRows(existingLineCount, existingDiscrepancyCount)) {
            throw new ReconciliationConflictException();
        }
        requireActiveJob(analysisId);

        ReconciliationInput input;
        try {
            input = inputAssembler.assemble(analysisId, owner);
        } catch (AnalysisReconciliationNotAllowedException changedDuringAssembly) {
            ReconciliationResult concurrentResult = completedResultAfterRace(analysisId, owner);
            if (concurrentResult != null) {
                return concurrentResult;
            }
            throw changedDuringAssembly;
        }
        Instant calculatedAt = clock.instant();
        DocumentReconciliationResult documentResult = documentEngine.reconcile(
                analysisId,
                input.referenceSource().extraction(),
                input.invoiceSource().extraction(),
                input.priceTolerance(),
                calculatedAt);
        LineReconciliationResult lineResult = lineEngine.reconcile(
                analysisId,
                input.matches(),
                input.referenceLines(),
                input.invoiceLines(),
                input.priceTolerance(),
                documentResult.currenciesCompatible(),
                calculatedAt);
        List<Discrepancy> discrepancies = combineDiscrepancies(lineResult, documentResult);
        var evidence = evidenceBuilder.build(
                lineResult,
                documentResult,
                input.matches(),
                input.referenceSource(),
                input.invoiceSource(),
                calculatedAt);
        DuplicateInvoiceWarning duplicateInvoice = duplicateInvoiceDetector.detect(
                input.analysis(), input.invoiceDocument(), input.invoiceSource().extraction());
        AnalysisReconciliationStatus reconciliationStatus = discrepancies.isEmpty()
                && !duplicateInvoice.possibleDuplicate()
                ? AnalysisReconciliationStatus.MATCHED
                : AnalysisReconciliationStatus.REVIEW_REQUIRED;

        ReconciliationPlan plan = new ReconciliationPlan(
                analysisId, lineResult.lines(), discrepancies, evidence, documentResult.analysisDifference());
        ReconciliationPersistenceResult persisted = persistenceTransaction.persist(
                owner, input, plan, reconciliationStatus);
        return ReconciliationResultAssembler.assemble(
                persisted.analysis(), input.matches(), persisted.lines(), persisted.discrepancies(),
                duplicateInvoice, ReconciliationResultAssembler.positionsFrom(input));
    }

    private void requireActiveJob(UUID analysisId) {
        AnalysisJob job = analysisJobRepository.findByAnalysisId(analysisId)
                .orElseThrow(ReconciliationInputNotReadyException::new);
        if (!analysisId.equals(job.analysisId())
                || job.status() != AnalysisJobStatus.WAITING_FOR_USER
                || job.currentStage() != AnalysisStatus.RECONCILING) {
            throw new AnalysisReconciliationNotAllowedException();
        }
    }

    private ReconciliationResult completedResultAfterRace(UUID analysisId, AnalysisOwner owner) {
        Analysis latest = analysisRepository.findOwnedById(analysisId, owner, clock.instant()).orElse(null);
        if (latest == null || latest.status() != AnalysisStatus.GENERATING_REPORT) {
            return null;
        }
        long lineCount = reconciliationLineRepository.countByAnalysisId(analysisId);
        long discrepancyCount = discrepancyRepository.countByAnalysisId(analysisId);
        return loadExistingResult(latest, lineCount, discrepancyCount);
    }

    private ReconciliationResult loadExistingResult(
            Analysis analysis, long lineCount, long discrepancyCount) {
        if (analysis.reconciliationStatus() == null || lineCount == 0) {
            throw new ReconciliationConflictException();
        }
        AnalysisJob job = analysisJobRepository.findByAnalysisId(analysis.id())
                .orElseThrow(ReconciliationConflictException::new);
        if (!analysis.id().equals(job.analysisId())
                || job.status() != AnalysisJobStatus.WAITING_FOR_USER
                || job.currentStage() != AnalysisStatus.GENERATING_REPORT) {
            throw new ReconciliationConflictException();
        }

        List<LineItemMatch> matches = lineItemMatchRepository.findByAnalysisId(analysis.id());
        List<ReconciliationLine> lines = reconciliationLineRepository.findByAnalysisId(analysis.id());
        List<Discrepancy> discrepancies = discrepancyRepository.findByAnalysisId(analysis.id());
        var sourcePositions = linePositionReader.read(analysis);
        if (!isCompletePersistedResult(
                analysis, matches, lines, discrepancies, lineCount, discrepancyCount, sourcePositions)) {
            throw new ReconciliationConflictException();
        }

        DuplicateInvoiceWarning duplicateInvoice = duplicateInvoiceWarningReader.read(analysis);
        if (analysis.reconciliationStatus() == AnalysisReconciliationStatus.MATCHED && !discrepancies.isEmpty()) {
            throw new ReconciliationConflictException();
        }
        return ReconciliationResultAssembler.assemble(
                analysis, matches, lines, discrepancies, duplicateInvoice, sourcePositions);
    }

    private static boolean isCompletePersistedResult(
            Analysis analysis,
            List<LineItemMatch> matches,
            List<ReconciliationLine> lines,
            List<Discrepancy> discrepancies,
            long lineCount,
            long discrepancyCount,
            Map<UUID, Integer> sourcePositions) {
        if (matches.isEmpty() || matches.stream().anyMatch(match ->
                !analysis.id().equals(match.analysisId()) || match.status() == LineMatchStatus.NEEDS_REVIEW)) {
            return false;
        }
        Set<UUID> matchIds = new HashSet<>();
        Set<UUID> referenceLineIds = new HashSet<>();
        Set<UUID> invoiceLineIds = new HashSet<>();
        for (LineItemMatch match : matches) {
            if (!matchIds.add(match.id())
                    || (match.referenceLineItemId() != null
                            && !referenceLineIds.add(match.referenceLineItemId()))
                    || (match.invoiceLineItemId() != null
                            && !invoiceLineIds.add(match.invoiceLineItemId()))) {
                return false;
            }
        }
        Set<UUID> expectedLineIds = new HashSet<>(referenceLineIds);
        if (invoiceLineIds.stream().anyMatch(expectedLineIds::contains)) {
            return false;
        }
        expectedLineIds.addAll(invoiceLineIds);
        Set<UUID> reconciledMatchIds = new HashSet<>();
        Set<UUID> lineIds = new HashSet<>();
        for (ReconciliationLine line : lines) {
            if (!analysis.id().equals(line.analysisId())
                    || !lineIds.add(line.id())
                    || !reconciledMatchIds.add(line.lineMatchId())) {
                return false;
            }
        }
        Set<UUID> discrepancyIds = new HashSet<>();
        for (Discrepancy discrepancy : discrepancies) {
            if (!analysis.id().equals(discrepancy.analysisId())
                    || !discrepancyIds.add(discrepancy.id())
                    || (discrepancy.reconciliationLineId() != null
                            && !lineIds.contains(discrepancy.reconciliationLineId()))) {
                return false;
            }
        }
        return expectedLineIds.equals(sourcePositions.keySet())
                && matches.size() == matchIds.size()
                && lines.size() == lineCount
                && lines.size() == matches.size()
                && lines.size() == reconciledMatchIds.size()
                && matchIds.equals(reconciledMatchIds)
                && discrepancies.size() == discrepancyCount
                && discrepancies.size() == discrepancyIds.size();
    }

    private static List<Discrepancy> combineDiscrepancies(
            LineReconciliationResult lineResult, DocumentReconciliationResult documentResult) {
        List<Discrepancy> combined = new ArrayList<>(
                lineResult.discrepancies().size() + documentResult.discrepancies().size());
        combined.addAll(lineResult.discrepancies());
        combined.addAll(documentResult.discrepancies());
        return List.copyOf(combined);
    }

    private static boolean hasPersistedRows(long lineCount, long discrepancyCount) {
        return lineCount != 0 || discrepancyCount != 0;
    }

}
