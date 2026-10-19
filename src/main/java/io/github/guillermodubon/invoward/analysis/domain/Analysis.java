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

    /** Enters the classification state after both required upload slots are present. */
    public Analysis markClassifying(Instant now) {
        return beginClassification(now);
    }

    /** Enters the classification machine stage after all required uploads are present. */
    public Analysis beginClassification(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.UPLOADING) {
            throw new IllegalStateException("Analysis can be classified only after uploads");
        }
        requireTransitionTime(now);
        return new Analysis(
                id, owner, AnalysisStatus.CLASSIFYING, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance, false, null,
                null, version, null, expiresAt, createdAt, now);
    }

    /** Enters extraction after the user has confirmed the detected document types. */
    public Analysis beginExtraction(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.CLASSIFYING) {
            throw new IllegalStateException("Analysis can begin extraction only after classification");
        }
        requireTransitionTime(now);
        return new Analysis(
                id, owner, AnalysisStatus.EXTRACTING, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance, false, null,
                null, version, null, expiresAt, createdAt, now);
    }

    /** Moves a fully extracted Analysis to human review without performing reconciliation. */
    public Analysis awaitExtractionConfirmation(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.CLASSIFYING && status != AnalysisStatus.EXTRACTING) {
            throw new IllegalStateException("Analysis can await extraction confirmation only after extraction");
        }
        requireTransitionTime(now);
        return new Analysis(
                id, owner, AnalysisStatus.AWAITING_CONFIRMATION, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance, false, null, null,
                version, null, expiresAt, createdAt, now);
    }

    /** Marks the current machine stage failed with safe, preclassified error data. */
    public Analysis failProcessing(
            AnalysisStatus expectedStage,
            String safeCode,
            String safeUserMessage,
            boolean effectiveRetryable,
            Instant now) {
        Objects.requireNonNull(expectedStage, "expectedStage must not be null");
        Objects.requireNonNull(now, "now must not be null");
        requireMachineStage(expectedStage);
        if (status != expectedStage) {
            throw new IllegalStateException("Analysis is no longer at the failed machine stage");
        }
        requireSafeCode(safeCode, "safeCode");
        requireSafeMessage(safeUserMessage, "safeUserMessage");
        requireTransitionTime(now);
        return new Analysis(
                id, owner, AnalysisStatus.FAILED, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance, effectiveRetryable,
                safeCode, safeUserMessage, version, null, expiresAt, createdAt, now);
    }

    /** Terminalizes a persisted Analysis/AnalysisJob mismatch discovered during startup recovery. */
    public Analysis failProcessingStateConflict(
            String safeCode,
            String safeUserMessage,
            Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requireSafeCode(safeCode, "safeCode");
        requireSafeMessage(safeUserMessage, "safeUserMessage");
        requireTransitionTime(now);
        return new Analysis(
                id, owner, AnalysisStatus.FAILED, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance, false,
                safeCode, safeUserMessage, version, null, expiresAt, createdAt, now);
    }

    /** Retries the failed machine stage selected by the persisted AnalysisJob. */
    public Analysis retryProcessing(AnalysisStatus failedStage, Instant now) {
        Objects.requireNonNull(failedStage, "failedStage must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.FAILED || !retryable) {
            throw new IllegalStateException("Analysis can be retried only after a processing failure");
        }
        requireMachineStage(failedStage);
        requireTransitionTime(now);
        return new Analysis(
                id, owner, failedStage, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance, false,
                null, null, version, null, expiresAt, createdAt, now);
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

    /** Completes reconciliation with its original result and signed invoice-minus-reference difference. */
    public Analysis completeReconciliation(
            AnalysisReconciliationStatus result, BigDecimal difference, Instant now) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (status != AnalysisStatus.RECONCILING) {
            throw new IllegalStateException("Analysis can complete reconciliation only while reconciling");
        }
        requireTransitionTime(now);
        return new Analysis(
                id, owner, AnalysisStatus.GENERATING_REPORT, reviewStatus, result,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance,
                retryable, failureCode, failureUserMessage, version, completedAt, expiresAt, createdAt, now);
    }

    /** Completes the synchronous report stage after its PDF has been persisted. */
    public Analysis completeReportGeneration(Instant generatedAt) {
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        if (status != AnalysisStatus.GENERATING_REPORT) {
            throw new IllegalStateException("Analysis can complete report generation only while generating a report");
        }
        requireTransitionTime(generatedAt);
        return new Analysis(
                id, owner, AnalysisStatus.COMPLETED, reviewStatus, reconciliationStatus,
                supplierName, supplierKey, referenceType, referenceNumber, invoiceNumber, currency,
                referenceTotal, invoicedTotal, difference, priceTolerance,
                false, failureCode, failureUserMessage, version, generatedAt, expiresAt, createdAt, generatedAt);
    }

    private void requireTransitionTime(Instant now) {
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
    }

    private static void requireMachineStage(AnalysisStatus stage) {
        if (stage != AnalysisStatus.CLASSIFYING
                && stage != AnalysisStatus.EXTRACTING
                && stage != AnalysisStatus.MATCHING
                && stage != AnalysisStatus.RECONCILING
                && stage != AnalysisStatus.GENERATING_REPORT) {
            throw new IllegalStateException("Analysis transition requires a machine stage");
        }
    }

    private static void requireSafeCode(String value, String fieldName) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw new IllegalArgumentException(fieldName + " must be a safe uppercase code");
        }
    }

    private static void requireSafeMessage(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > 500
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must be a safe non-empty message");
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
