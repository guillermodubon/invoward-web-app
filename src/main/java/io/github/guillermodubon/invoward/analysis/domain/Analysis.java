package io.github.guillermodubon.invoward.analysis.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Framework-independent Analysis aggregate and its initial-state factory. */
public record Analysis(
        UUID id,
        AnalysisOwner owner,
        AnalysisStatus status,
        AnalysisReviewStatus reviewStatus,
        AnalysisReconciliationStatus reconciliationStatus,
        String supplierName,
        String supplierKey,
        String referenceType,
        String referenceNumber,
        String invoiceNumber,
        String currency,
        BigDecimal referenceTotal,
        BigDecimal invoicedTotal,
        BigDecimal difference,
        PriceTolerance priceTolerance,
        boolean retryable,
        String failureCode,
        String failureUserMessage,
        long version,
        Instant completedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public Analysis {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(reviewStatus, "reviewStatus must not be null");
        Objects.requireNonNull(priceTolerance, "priceTolerance must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        requireNonnegative(referenceTotal, "referenceTotal");
        requireNonnegative(invoicedTotal, "invoicedTotal");
        requireNonnegative(difference, "difference");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (completedAt != null && (completedAt.isBefore(createdAt) || completedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("completedAt must be between createdAt and updatedAt");
        }
        validateOwnerExpiry(owner, expiresAt, createdAt);
    }

    /** Builds the empty workspace state; the supplied instant comes from the application Clock. */
    public static Analysis create(UUID id, AnalysisOwner owner, PriceTolerance priceTolerance, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        Instant analysisExpiry = owner instanceof GuestSessionOwner guestOwner
                ? guestOwner.expiresAt()
                : null;
        return new Analysis(
                id,
                owner,
                AnalysisStatus.CREATED,
                AnalysisReviewStatus.PENDING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                priceTolerance,
                false,
                null,
                null,
                0,
                null,
                analysisExpiry,
                now,
                now);
    }

    /** Marks an Analysis as receiving documents while uploads are still being assembled. */
    public Analysis transitionToUploading(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.CREATED && status != AnalysisStatus.UPLOADING) {
            throw new IllegalStateException("Analysis cannot accept uploads in its current state");
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        return new Analysis(
                id,
                owner,
                AnalysisStatus.UPLOADING,
                reviewStatus,
                reconciliationStatus,
                supplierName,
                supplierKey,
                referenceType,
                referenceNumber,
                invoiceNumber,
                currency,
                referenceTotal,
                invoicedTotal,
                difference,
                priceTolerance,
                retryable,
                failureCode,
                failureUserMessage,
                version,
                completedAt,
                expiresAt,
                createdAt,
                now);
    }

    private static void validateOwnerExpiry(AnalysisOwner owner, Instant expiresAt, Instant createdAt) {
        if (owner instanceof GuestSessionOwner guestOwner) {
            Objects.requireNonNull(expiresAt, "guest Analysis expiresAt must not be null");
            if (!expiresAt.equals(guestOwner.expiresAt())) {
                throw new IllegalArgumentException("guest Analysis expiry must match its owner expiry");
            }
            if (!expiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException("guest Analysis expiry must be after createdAt");
            }
            return;
        }
        if (expiresAt != null) {
            throw new IllegalArgumentException("registered Analysis must not expire");
        }
    }

    private static void requireNonnegative(BigDecimal amount, String fieldName) {
        if (amount != null && amount.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
    }

    @Override
    public String toString() {
        return "Analysis[id=" + id + ", status=" + status + ", reviewStatus=" + reviewStatus + "]";
    }
}
