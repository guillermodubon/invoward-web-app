package io.github.guillermodubon.invoward.reconciliation.api.model;

import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;

import java.math.BigDecimal;
import java.util.UUID;

/** Public line snapshot; evidence, owner and extraction metadata are deliberately omitted. */
public record ReconciliationLineResponse(
        UUID id,
        UUID lineMatchId,
        ReconciliationLineStatus status,
        Side reference,
        Side invoice,
        BigDecimal quantityDifference,
        BigDecimal unitPriceDifference,
        BigDecimal lineTotalDifference) {

    public static ReconciliationLineResponse from(ReconciliationLine line) {
        return new ReconciliationLineResponse(
                line.id(), line.lineMatchId(), line.status(),
                side(line.referenceDescription(), line.referenceItemCode(),
                        line.referenceQuantity(), line.referenceUnitPrice(), line.referenceLineTotal()),
                side(line.invoiceDescription(), line.invoiceItemCode(),
                        line.invoiceQuantity(), line.invoiceUnitPrice(), line.invoiceLineTotal()),
                line.quantityDifference(), line.unitPriceDifference(), line.lineTotalDifference());
    }

    private static Side side(
            String description,
            String itemCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {
        return description == null ? null : new Side(itemCode, description, quantity, unitPrice, lineTotal);
    }

    public record Side(
            String itemCode,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {
    }
}
