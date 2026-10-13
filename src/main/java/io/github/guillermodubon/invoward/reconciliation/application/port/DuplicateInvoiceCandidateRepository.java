package io.github.guillermodubon.invoward.reconciliation.application.port;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owner-scoped candidate search port; sensitive matching data stays inside the application boundary. */
public interface DuplicateInvoiceCandidateRepository {

    List<CandidateRecord> findCandidates(
            AnalysisOwner owner,
            UUID currentAnalysisId,
            String invoiceFileSha256,
            String normalizedInvoiceNumber,
            Instant now,
            int limit);

    /** Internal search data used to evaluate signals; never expose this record from an API. */
    record CandidateRecord(
            UUID analysisId,
            Instant analyzedAt,
            String invoiceNumber,
            String supplierKey,
            String invoiceFileSha256,
            BigDecimal invoiceTotal,
            LocalDate documentDate) {

        public CandidateRecord {
            Objects.requireNonNull(analysisId, "analysisId must not be null");
            Objects.requireNonNull(analyzedAt, "analyzedAt must not be null");
            Objects.requireNonNull(invoiceFileSha256, "invoiceFileSha256 must not be null");
        }

        @Override
        public String toString() {
            return "CandidateRecord[analysisId=" + analysisId + ", analyzedAt=" + analyzedAt
                    + ", invoiceNumberPresent=" + (invoiceNumber != null)
                    + ", invoiceTotalPresent=" + (invoiceTotal != null)
                    + ", documentDatePresent=" + (documentDate != null) + "]";
        }
    }
}
