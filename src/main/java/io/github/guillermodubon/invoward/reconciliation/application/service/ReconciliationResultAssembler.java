package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Builds the same stable, provider-neutral representation for reconcile and read use cases. */
final class ReconciliationResultAssembler {

    private ReconciliationResultAssembler() {
    }

    static ReconciliationResult assemble(
            Analysis analysis,
            List<LineItemMatch> matches,
            List<ReconciliationLine> sourceLines,
            List<Discrepancy> sourceDiscrepancies,
            DuplicateInvoiceWarning duplicateInvoice,
            Map<UUID, Integer> sourcePositions) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Map<UUID, LineMatchOrder> matchOrders = matchOrders(matches, sourcePositions);
        List<ReconciliationLine> lines = sourceLines.stream()
                .sorted(lineOrder(matchOrders))
                .toList();
        Map<UUID, Integer> lineOrderById = new HashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            lineOrderById.put(lines.get(index).id(), index);
        }
        List<Discrepancy> discrepancies = sourceDiscrepancies.stream()
                .sorted(discrepancyOrder(lineOrderById))
                .toList();
        ReconciliationSummary summary = new ReconciliationSummary(
                lines.size(), countLines(lines, ReconciliationLineStatus.MATCHED),
                countLines(lines, ReconciliationLineStatus.DIFFERENT),
                countLines(lines, ReconciliationLineStatus.MISSING),
                countLines(lines, ReconciliationLineStatus.UNEXPECTED),
                countDiscrepancies(discrepancies, DiscrepancyResolutionStatus.OPEN),
                countDiscrepancies(discrepancies, DiscrepancyResolutionStatus.ACCEPTED),
                countDiscrepancies(discrepancies, DiscrepancyResolutionStatus.RESOLVED));
        return new ReconciliationResult(
                analysis.id(), analysis.status(), analysis.reconciliationStatus(), analysis.priceTolerance(),
                analysis.referenceTotal(), analysis.invoicedTotal(), analysis.difference(),
                duplicateInvoice, summary, lines, discrepancies);
    }

    static Map<UUID, Integer> positionsFrom(ReconciliationInput input) {
        Objects.requireNonNull(input, "input must not be null");
        Map<UUID, Integer> positions = new HashMap<>();
        input.referenceLines().forEach((id, line) -> addPosition(positions, id, line.position()));
        input.invoiceLines().forEach((id, line) -> addPosition(positions, id, line.position()));
        return Map.copyOf(positions);
    }

    private static Map<UUID, LineMatchOrder> matchOrders(
            List<LineItemMatch> matches, Map<UUID, Integer> sourcePositions) {
        Objects.requireNonNull(matches, "matches must not be null");
        Objects.requireNonNull(sourcePositions, "sourcePositions must not be null");
        Map<UUID, LineMatchOrder> orders = new HashMap<>();
        for (LineItemMatch match : matches) {
            Integer referencePosition = positionOf(match.referenceLineItemId(), sourcePositions);
            Integer invoicePosition = positionOf(match.invoiceLineItemId(), sourcePositions);
            LineMatchOrder order = new LineMatchOrder(match.id(), referencePosition, invoicePosition);
            if (orders.putIfAbsent(match.id(), order) != null) {
                throw new ReconciliationConflictException();
            }
        }
        return Map.copyOf(orders);
    }

    private static Integer positionOf(UUID lineItemId, Map<UUID, Integer> sourcePositions) {
        if (lineItemId == null) {
            return null;
        }
        Integer position = sourcePositions.get(lineItemId);
        if (position == null) {
            throw new ReconciliationConflictException();
        }
        return position;
    }

    private static Comparator<ReconciliationLine> lineOrder(Map<UUID, LineMatchOrder> orders) {
        Comparator<Integer> nullablePosition = Comparator.nullsLast(Comparator.naturalOrder());
        return Comparator.comparing(
                        (ReconciliationLine line) -> requireOrder(line, orders).referencePosition(), nullablePosition)
                .thenComparing(line -> requireOrder(line, orders).invoicePosition(), nullablePosition)
                .thenComparing(ReconciliationLine::lineMatchId)
                .thenComparing(ReconciliationLine::id);
    }

    private static LineMatchOrder requireOrder(
            ReconciliationLine line, Map<UUID, LineMatchOrder> orders) {
        LineMatchOrder order = orders.get(line.lineMatchId());
        if (order == null) {
            throw new ReconciliationConflictException();
        }
        return order;
    }

    private static Comparator<Discrepancy> discrepancyOrder(Map<UUID, Integer> lineOrderById) {
        return Comparator.comparingInt((Discrepancy discrepancy) -> discrepancy.reconciliationLineId() == null
                        ? Integer.MAX_VALUE
                        : requireLineOrder(discrepancy, lineOrderById))
                .thenComparing(discrepancy -> discrepancy.type().ordinal())
                .thenComparing(Discrepancy::id);
    }

    private static int requireLineOrder(Discrepancy discrepancy, Map<UUID, Integer> lineOrderById) {
        Integer order = lineOrderById.get(discrepancy.reconciliationLineId());
        if (order == null) {
            throw new ReconciliationConflictException();
        }
        return order;
    }

    private static void addPosition(Map<UUID, Integer> positions, UUID lineId, int position) {
        if (positions.putIfAbsent(lineId, position) != null) {
            throw new ReconciliationConflictException();
        }
    }

    private record LineMatchOrder(UUID matchId, Integer referencePosition, Integer invoicePosition) {
    }

    private static long countLines(List<ReconciliationLine> lines, ReconciliationLineStatus status) {
        return lines.stream().filter(line -> line.status() == status).count();
    }

    private static long countDiscrepancies(List<Discrepancy> discrepancies, DiscrepancyResolutionStatus status) {
        return discrepancies.stream().filter(item -> item.resolutionStatus() == status).count();
    }
}
