package io.github.guillermodubon.invoward.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Framework-independent persisted match state and its database-equivalent invariants. */
public record LineItemMatch(
        UUID id,
        UUID analysisId,
        UUID referenceLineItemId,
        UUID invoiceLineItemId,
        LineMatchStatus status,
        LineMatchMethod method,
        BigDecimal confidence,
        long version,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt) {

    private static final BigDecimal ONE = BigDecimal.ONE;

    public LineItemMatch {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        validateShape(status, referenceLineItemId, invoiceLineItemId);
        validateConfidence(confidence);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static void validateShape(LineMatchStatus status, UUID referenceId, UUID invoiceId) {
        boolean valid = switch (status) {
            case MATCHED, NEEDS_REVIEW -> referenceId != null && invoiceId != null;
            case UNMATCHED_REFERENCE -> referenceId != null && invoiceId == null;
            case UNMATCHED_INVOICE -> referenceId == null && invoiceId != null;
        };
        if (!valid) {
            throw new IllegalArgumentException("line item identities do not match match status");
        }
    }

    private static void validateConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return;
        }
        if (confidence.signum() < 0 || confidence.compareTo(ONE) > 0 || confidence.scale() > 4) {
            throw new IllegalArgumentException("confidence must be between 0 and 1 with at most 4 decimal places");
        }
    }
}
