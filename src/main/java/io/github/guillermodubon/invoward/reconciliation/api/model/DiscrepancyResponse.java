package io.github.guillermodubon.invoward.reconciliation.api.model;

import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Public discrepancy state without ownership or persistence implementation details. */
public record DiscrepancyResponse(
        UUID id,
        UUID reconciliationLineId,
        DiscrepancyType type,
        String referenceValue,
        String invoiceValue,
        BigDecimal absoluteDifference,
        BigDecimal percentageDifference,
        String explanation,
        DiscrepancyResolutionStatus resolutionStatus,
        DiscrepancyResolutionReason resolutionReason,
        String resolutionNote,
        long version,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static DiscrepancyResponse from(Discrepancy discrepancy) {
        return new DiscrepancyResponse(
                discrepancy.id(), discrepancy.reconciliationLineId(), discrepancy.type(),
                discrepancy.referenceValue(), discrepancy.invoiceValue(), discrepancy.absoluteDifference(),
                discrepancy.percentageDifference(), discrepancy.explanation(), discrepancy.resolutionStatus(),
                discrepancy.resolutionReason(), discrepancy.resolutionNote(), discrepancy.version(),
                discrepancy.resolvedAt(), discrepancy.createdAt(), discrepancy.updatedAt());
    }
}
