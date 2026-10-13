package io.github.guillermodubon.invoward.reconciliation.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Header-level findings and signed Analysis difference derived from confirmed document data. */
public record DocumentReconciliationResult(
        UUID analysisId,
        boolean currenciesCompatible,
        BigDecimal analysisDifference,
        List<Discrepancy> discrepancies) {

    public DocumentReconciliationResult {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        discrepancies = List.copyOf(discrepancies);

        Set<UUID> discrepancyIds = new HashSet<>();
        Set<DiscrepancyType> discrepancyTypes = new HashSet<>();
        for (Discrepancy discrepancy : discrepancies) {
            if (!analysisId.equals(discrepancy.analysisId())
                    || !discrepancyIds.add(discrepancy.id())
                    || !discrepancyTypes.add(discrepancy.type())) {
                throw new IllegalArgumentException("document discrepancies must be unique and belong to its Analysis");
            }
            if (discrepancy.reconciliationLineId() != null) {
                throw new IllegalArgumentException("document-level discrepancy must not reference a reconciliation line");
            }
            if (discrepancy.resolutionStatus() != DiscrepancyResolutionStatus.OPEN || discrepancy.version() != 0) {
                throw new IllegalArgumentException("new document discrepancies must start OPEN at version zero");
            }
        }
        if (!currenciesCompatible
                && (analysisDifference != null || discrepancies.size() != 1
                        || discrepancies.getFirst().type() != DiscrepancyType.CURRENCY_MISMATCH)) {
            throw new IllegalArgumentException("currency mismatch must suppress monetary results and findings");
        }
    }

    @Override
    public String toString() {
        return "DocumentReconciliationResult[analysisId=" + analysisId
                + ", currenciesCompatible=" + currenciesCompatible
                + ", hasAnalysisDifference=" + (analysisDifference != null)
                + ", discrepancyCount=" + discrepancies.size() + "]";
    }
}
