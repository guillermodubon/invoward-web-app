package io.github.guillermodubon.invoward.reconciliation.application.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Privacy-safe, same-owner candidate details suitable for a reconciliation response. */
public record DuplicateInvoiceCandidate(
        UUID analysisId,
        Instant analyzedAt,
        String invoiceNumber,
        List<DuplicateInvoiceSignal> signals) {

    public DuplicateInvoiceCandidate {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(analyzedAt, "analyzedAt must not be null");
        signals = List.copyOf(Objects.requireNonNull(signals, "signals must not be null"));
        if (signals.isEmpty() || new HashSet<>(signals).size() != signals.size()) {
            throw new IllegalArgumentException("signals must contain unique values");
        }
    }
}
