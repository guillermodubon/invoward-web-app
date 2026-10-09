package io.github.guillermodubon.invoward.analysis.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
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

    /** Marks both upload slots as classified and waits for human confirmation. */
    public Analysis markClassifying(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.UPLOADING) {
            throw new IllegalStateException("Analysis can be classified only after uploads");
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        return new Analysis(
                id, owner, AnalysisStatus.CLASSIFYING, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance, retryable, failureCode,
                failureUserMessage, version, completedAt, expiresAt, createdAt, now);
    }

    /** Moves a fully extracted Analysis to human review without performing reconciliation. */
    public Analysis awaitExtractionConfirmation(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.CLASSIFYING) {
            throw new IllegalStateException("Analysis can await extraction confirmation only after classification");
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        return new Analysis(
                id, owner, AnalysisStatus.AWAITING_CONFIRMATION, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance, false, null, null,
                version, null, expiresAt, createdAt, now);
    }

    /** Copies the human-confirmed extraction summary and advances the workflow to matching. */
    public Analysis confirmExtraction(
            String confirmedReferenceType,
            String confirmedReferenceNumber,
            String confirmedInvoiceNumber,
            String confirmedSupplierName,
            String confirmedCurrency,
            BigDecimal confirmedReferenceTotal,
            BigDecimal confirmedInvoiceTotal,
            Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.AWAITING_CONFIRMATION) {
            throw new IllegalStateException("Analysis can start matching only after extraction confirmation");
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        if (confirmedReferenceType == null || confirmedReferenceType.isBlank()) {
            throw new IllegalArgumentException("confirmedReferenceType must not be blank");
        }
        requireNonnegative(confirmedReferenceTotal, "referenceTotal");
        requireNonnegative(confirmedInvoiceTotal, "invoicedTotal");

        return new Analysis(
                id,
                owner,
                AnalysisStatus.MATCHING,
                reviewStatus,
                null,
                confirmedSupplierName,
                supplierKey(confirmedSupplierName),
                confirmedReferenceType,
                confirmedReferenceNumber,
                confirmedInvoiceNumber,
                confirmedCurrency,
                confirmedReferenceTotal,
                confirmedInvoiceTotal,
                null,
                priceTolerance,
                false,
                null,
                null,
                version,
                null,
                expiresAt,
                createdAt,
                now);
    }

    /** Keeps matching results available for user review without starting reconciliation. */
    public Analysis awaitMatchReview(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.MATCHING) {
            throw new IllegalStateException("Analysis can await match review only while matching");
        }
        requireTransitionTime(now);
        return withStatus(AnalysisStatus.AWAITING_MATCH_REVIEW, now);
    }

    /** Advances a fully matched Analysis, or a user-confirmed match set, to reconciliation. */
    public Analysis beginReconciliation(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.MATCHING && status != AnalysisStatus.AWAITING_MATCH_REVIEW) {
            throw new IllegalStateException("Analysis can begin reconciliation only after matching review");
        }
        requireTransitionTime(now);
        return withStatus(AnalysisStatus.RECONCILING, now);
    }

    private void requireTransitionTime(Instant now) {
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
    }

    private Analysis withStatus(AnalysisStatus nextStatus, Instant now) {
        return new Analysis(
                id, owner, nextStatus, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance,
                retryable, failureCode, failureUserMessage, version, completedAt, expiresAt, createdAt, now);
    }

    private static String supplierKey(String supplierName) {
        if (supplierName == null || supplierName.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(supplierName, Normalizer.Form.NFKC);
        StringBuilder collapsed = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = collapsed.length() > 0;
            } else {
                if (pendingSpace) {
                    collapsed.append(' ');
                    pendingSpace = false;
                }
                collapsed.appendCodePoint(codePoint);
            }
        }
        if (collapsed.isEmpty()) {
            return null;
        }
        String key = collapsed.toString().toLowerCase(Locale.ROOT);
        if (key.codePointCount(0, key.length()) > 240) {
            throw new IllegalArgumentException("supplierKey must not exceed 240 code points");
        }
        return key;
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
