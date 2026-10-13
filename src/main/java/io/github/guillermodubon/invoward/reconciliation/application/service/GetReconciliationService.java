package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.ReconciliationLineRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reads the persisted reconciliation after owner-scoped Analysis authorization. */
@Service
@Transactional(readOnly = true)
public class GetReconciliationService {

    private final AnalysisRepository analysisRepository;
    private final LineItemMatchRepository lineItemMatchRepository;
    private final ReconciliationLineRepository reconciliationLineRepository;
    private final DiscrepancyRepository discrepancyRepository;
    private final CurrentDuplicateInvoiceWarningReader duplicateInvoiceWarningReader;
    private final ReconciliationLinePositionReader linePositionReader;
    private final Clock clock;

    public GetReconciliationService(
            AnalysisRepository analysisRepository,
            LineItemMatchRepository lineItemMatchRepository,
            ReconciliationLineRepository reconciliationLineRepository,
            DiscrepancyRepository discrepancyRepository,
            CurrentDuplicateInvoiceWarningReader duplicateInvoiceWarningReader,
            ReconciliationLinePositionReader linePositionReader,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.lineItemMatchRepository = Objects.requireNonNull(lineItemMatchRepository);
        this.reconciliationLineRepository = Objects.requireNonNull(reconciliationLineRepository);
        this.discrepancyRepository = Objects.requireNonNull(discrepancyRepository);
        this.duplicateInvoiceWarningReader = Objects.requireNonNull(duplicateInvoiceWarningReader);
        this.linePositionReader = Objects.requireNonNull(linePositionReader);
        this.clock = Objects.requireNonNull(clock);
    }

    public ReconciliationResult get(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, clock.instant())
                .orElseThrow(AnalysisNotFoundException::new);
        long lineCount = reconciliationLineRepository.countByAnalysisId(analysis.id());
        long discrepancyCount = discrepancyRepository.countByAnalysisId(analysis.id());
        if (analysis.reconciliationStatus() == null && lineCount == 0 && discrepancyCount == 0) {
            throw new ReconciliationNotFoundException();
        }
        if (!isReconciledState(analysis.status())
                || analysis.reconciliationStatus() == null || lineCount == 0) {
            throw new ReconciliationConflictException();
        }

        List<LineItemMatch> matches = lineItemMatchRepository.findByAnalysisId(analysis.id());
        List<ReconciliationLine> lines = reconciliationLineRepository.findByAnalysisId(analysis.id());
        List<Discrepancy> discrepancies = discrepancyRepository.findByAnalysisId(analysis.id());
        Map<UUID, Integer> sourcePositions = linePositionReader.read(analysis);
        if (!isCompleteSnapshot(analysis, matches, lines, discrepancies, lineCount, discrepancyCount,
                sourcePositions)
                || (analysis.reconciliationStatus() == AnalysisReconciliationStatus.MATCHED
                        && !discrepancies.isEmpty())) {
            throw new ReconciliationConflictException();
        }

        DuplicateInvoiceWarning currentDuplicateWarning = duplicateInvoiceWarningReader.read(analysis);
        return ReconciliationResultAssembler.assemble(
                analysis, matches, lines, discrepancies, currentDuplicateWarning, sourcePositions);
    }

    private static boolean isReconciledState(AnalysisStatus status) {
        return status == AnalysisStatus.GENERATING_REPORT
                || status == AnalysisStatus.COMPLETED
                || status == AnalysisStatus.FAILED;
    }

    private static boolean isCompleteSnapshot(
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
        Set<UUID> lineMatchIds = new HashSet<>();
        Set<UUID> lineIds = new HashSet<>();
        for (ReconciliationLine line : lines) {
            if (!analysis.id().equals(line.analysisId())
                    || !lineIds.add(line.id())
                    || !lineMatchIds.add(line.lineMatchId())) {
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
        return matches.size() == matchIds.size()
                && lines.size() == lineCount
                && lines.size() == matches.size()
                && lines.size() == lineMatchIds.size()
                && matchIds.equals(lineMatchIds)
                && expectedLineIds.equals(sourcePositions.keySet())
                && discrepancies.size() == discrepancyCount
                && discrepancies.size() == discrepancyIds.size();
    }
}
