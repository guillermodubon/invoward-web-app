package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Builds evidence rows only from exact metadata on the confirmed source extractions. */
public final class DiscrepancyEvidenceBuilder {

    public List<DiscrepancyEvidence> build(
            LineReconciliationResult lineResult,
            DocumentReconciliationResult documentResult,
            List<LineItemMatch> matches,
            ConfirmedEvidenceSource referenceSource,
            ConfirmedEvidenceSource invoiceSource,
            Instant createdAt) {
        Objects.requireNonNull(lineResult, "lineResult must not be null");
        Objects.requireNonNull(documentResult, "documentResult must not be null");
        Objects.requireNonNull(matches, "matches must not be null");
        Objects.requireNonNull(referenceSource, "referenceSource must not be null");
        Objects.requireNonNull(invoiceSource, "invoiceSource must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        UUID analysisId = lineResult.analysisId();
        if (!analysisId.equals(documentResult.analysisId())) {
            throw new IllegalArgumentException("reconciliation results must belong to the same Analysis");
        }
        validateSource(referenceSource, analysisId, DocumentRole.REFERENCE);
        validateSource(invoiceSource, analysisId, DocumentRole.INVOICE);
        if (referenceSource.document().id().equals(invoiceSource.document().id())) {
            throw new IllegalArgumentException("reference and invoice must be different source documents");
        }
        Set<UUID> allSourceLineIds = new HashSet<>(referenceSource.lineItemIds());
        for (UUID invoiceLineId : invoiceSource.lineItemIds()) {
            if (!allSourceLineIds.add(invoiceLineId)) {
                throw new IllegalArgumentException("reference and invoice line identities must be distinct");
            }
        }

        Map<UUID, LineItemMatch> matchesById = indexMatches(matches, analysisId);
        Map<UUID, ReconciliationLine> linesById = indexLines(lineResult, matchesById,
                referenceSource, invoiceSource);
        List<Discrepancy> allDiscrepancies = combineDiscrepancies(lineResult, documentResult);
        validateDiscrepancies(allDiscrepancies, lineResult, documentResult, linesById, matchesById);

        List<DiscrepancyEvidence> evidence = new ArrayList<>();
        for (Discrepancy discrepancy : allDiscrepancies) {
            if (discrepancy.reconciliationLineId() == null) {
                addHeaderEvidence(evidence, discrepancy, referenceSource, EvidenceSide.REFERENCE, createdAt);
                addHeaderEvidence(evidence, discrepancy, invoiceSource, EvidenceSide.INVOICE, createdAt);
                continue;
            }

            ReconciliationLine line = linesById.get(discrepancy.reconciliationLineId());
            LineItemMatch match = matchesById.get(line.lineMatchId());
            switch (discrepancy.type()) {
                case MISSING_ITEM -> addLineEvidence(evidence, discrepancy, referenceSource,
                        match.referenceLineItemId(), EvidenceSide.REFERENCE, createdAt);
                case UNEXPECTED_ITEM -> addLineEvidence(evidence, discrepancy, invoiceSource,
                        match.invoiceLineItemId(), EvidenceSide.INVOICE, createdAt);
                default -> {
                    addLineEvidence(evidence, discrepancy, referenceSource,
                            match.referenceLineItemId(), EvidenceSide.REFERENCE, createdAt);
                    addLineEvidence(evidence, discrepancy, invoiceSource,
                            match.invoiceLineItemId(), EvidenceSide.INVOICE, createdAt);
                }
            }
        }
        return List.copyOf(evidence);
    }

    private static void validateSource(
            ConfirmedEvidenceSource source,
            UUID analysisId,
            DocumentRole expectedRole) {
        if (!analysisId.equals(source.document().analysisId())) {
            throw new IllegalArgumentException("evidence source must belong to the reconciled Analysis");
        }
        if (source.document().role() != expectedRole) {
            throw new IllegalArgumentException("evidence source role does not match its reconciliation side");
        }
    }

    private static Map<UUID, LineItemMatch> indexMatches(List<LineItemMatch> matches, UUID analysisId) {
        Map<UUID, LineItemMatch> indexed = new HashMap<>();
        for (LineItemMatch match : List.copyOf(matches)) {
            Objects.requireNonNull(match, "match must not be null");
            if (!analysisId.equals(match.analysisId()) || indexed.putIfAbsent(match.id(), match) != null) {
                throw new IllegalArgumentException("line matches must be unique and belong to the reconciled Analysis");
            }
            if (match.status() == LineMatchStatus.NEEDS_REVIEW) {
                throw new IllegalArgumentException("line matches must be confirmed before reconciliation evidence is built");
            }
        }
        return indexed;
    }

    private static Map<UUID, ReconciliationLine> indexLines(
            LineReconciliationResult lineResult,
            Map<UUID, LineItemMatch> matchesById,
            ConfirmedEvidenceSource referenceSource,
            ConfirmedEvidenceSource invoiceSource) {
        Map<UUID, ReconciliationLine> indexed = new HashMap<>();
        Set<UUID> coveredReference = new HashSet<>();
        Set<UUID> coveredInvoice = new HashSet<>();
        for (ReconciliationLine line : lineResult.lines()) {
            LineItemMatch match = matchesById.get(line.lineMatchId());
            if (match == null || indexed.putIfAbsent(line.id(), line) != null) {
                throw new IllegalArgumentException("reconciliation line must reference one unique persisted match");
            }
            requireSideLine(referenceSource, match.referenceLineItemId(), coveredReference, "reference");
            requireSideLine(invoiceSource, match.invoiceLineItemId(), coveredInvoice, "invoice");
            validateLineStatus(line, match);
        }
        if (indexed.size() != matchesById.size()
                || coveredReference.size() != referenceSource.lineItemIds().size()
                || coveredInvoice.size() != invoiceSource.lineItemIds().size()) {
            throw new IllegalArgumentException("reconciliation lines must cover confirmed source lines exactly once");
        }
        return indexed;
    }

    private static void requireSideLine(
            ConfirmedEvidenceSource source,
            UUID lineId,
            Set<UUID> covered,
            String side) {
        if (lineId == null) {
            return;
        }
        if (source.line(lineId) == null || !covered.add(lineId)) {
            throw new IllegalArgumentException("line match must reference one unique confirmed " + side + " line");
        }
    }

    private static void validateLineStatus(ReconciliationLine line, LineItemMatch match) {
        boolean valid = switch (line.status()) {
            case MATCHED, DIFFERENT -> match.status() == LineMatchStatus.MATCHED
                    && match.referenceLineItemId() != null && match.invoiceLineItemId() != null;
            case MISSING -> match.status() == LineMatchStatus.UNMATCHED_REFERENCE
                    && match.referenceLineItemId() != null && match.invoiceLineItemId() == null;
            case UNEXPECTED -> match.status() == LineMatchStatus.UNMATCHED_INVOICE
                    && match.referenceLineItemId() == null && match.invoiceLineItemId() != null;
        };
        if (!valid) {
            throw new IllegalArgumentException("reconciliation line status does not match its persisted line match");
        }
    }

    private static List<Discrepancy> combineDiscrepancies(
            LineReconciliationResult lineResult,
            DocumentReconciliationResult documentResult) {
        List<Discrepancy> combined = new ArrayList<>(lineResult.discrepancies().size()
                + documentResult.discrepancies().size());
        combined.addAll(lineResult.discrepancies());
        combined.addAll(documentResult.discrepancies());
        return List.copyOf(combined);
    }

    private static void validateDiscrepancies(
            List<Discrepancy> discrepancies,
            LineReconciliationResult lineResult,
            DocumentReconciliationResult documentResult,
            Map<UUID, ReconciliationLine> linesById,
            Map<UUID, LineItemMatch> matchesById) {
        Set<UUID> ids = new HashSet<>();
        for (Discrepancy discrepancy : discrepancies) {
            if (!ids.add(discrepancy.id())) {
                throw new IllegalArgumentException("discrepancy ids must be unique across reconciliation results");
            }
        }
        for (Discrepancy discrepancy : lineResult.discrepancies()) {
            if (discrepancy.reconciliationLineId() == null) {
                throw new IllegalArgumentException("line discrepancy must reference a reconciliation line");
            }
            ReconciliationLine line = linesById.get(discrepancy.reconciliationLineId());
            if (line == null) {
                throw new IllegalArgumentException("line discrepancy must reference a reconciled line");
            }
            validateLineDiscrepancyType(discrepancy.type(), line,
                    matchesById.get(line.lineMatchId()));
        }
        for (Discrepancy discrepancy : documentResult.discrepancies()) {
            if (discrepancy.reconciliationLineId() != null || !isHeaderDiscrepancy(discrepancy.type())) {
                throw new IllegalArgumentException("document discrepancy must be a supported header finding");
            }
        }
    }

    private static void validateLineDiscrepancyType(
            DiscrepancyType type,
            ReconciliationLine line,
            LineItemMatch match) {
        boolean valid = switch (type) {
            case MISSING_ITEM -> line.status() == ReconciliationLineStatus.MISSING
                    && match.status() == LineMatchStatus.UNMATCHED_REFERENCE;
            case UNEXPECTED_ITEM -> line.status() == ReconciliationLineStatus.UNEXPECTED
                    && match.status() == LineMatchStatus.UNMATCHED_INVOICE;
            case QUANTITY_MISMATCH, UNIT_PRICE_MISMATCH -> line.status() == ReconciliationLineStatus.DIFFERENT
                    && match.status() == LineMatchStatus.MATCHED;
            case DISCOUNT_MISMATCH, TAX_MISMATCH, TOTAL_MISMATCH ->
                    line.status() == ReconciliationLineStatus.DIFFERENT
                            && match.status() == LineMatchStatus.MATCHED;
            case SUBTOTAL_MISMATCH, CURRENCY_MISMATCH -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("discrepancy type does not match its reconciliation line");
        }
    }

    private static boolean isHeaderDiscrepancy(DiscrepancyType type) {
        return switch (type) {
            case CURRENCY_MISMATCH, SUBTOTAL_MISMATCH, DISCOUNT_MISMATCH, TAX_MISMATCH, TOTAL_MISMATCH -> true;
            case QUANTITY_MISMATCH, UNIT_PRICE_MISMATCH, MISSING_ITEM, UNEXPECTED_ITEM -> false;
        };
    }

    private static void addLineEvidence(
            List<DiscrepancyEvidence> evidence,
            Discrepancy discrepancy,
            ConfirmedEvidenceSource source,
            UUID lineItemId,
            EvidenceSide side,
            Instant createdAt) {
        if (lineItemId == null) {
            return;
        }
        ExtractedLineItem line = source.line(lineItemId);
        if (line == null) {
            throw new IllegalArgumentException("evidence line must belong to its confirmed source extraction");
        }
        if (line.pageNumber() == null) {
            return;
        }
        if (line.pageNumber() > source.document().pageCount()) {
            throw new IllegalArgumentException("evidence page exceeds the source document page count");
        }
        evidence.add(new DiscrepancyEvidence(
                UUID.randomUUID(), discrepancy.id(), source.document().id(), side,
                line.pageNumber(), line.sourceText(), line.boundingBox(), createdAt));
    }

    private static void addHeaderEvidence(
            List<DiscrepancyEvidence> evidence,
            Discrepancy discrepancy,
            ConfirmedEvidenceSource source,
            EvidenceSide side,
            Instant createdAt) {
        if (source.document().pageCount() == 1) {
            evidence.add(new DiscrepancyEvidence(
                    UUID.randomUUID(), discrepancy.id(), source.document().id(), side, 1, null, null, createdAt));
        }
    }
}
