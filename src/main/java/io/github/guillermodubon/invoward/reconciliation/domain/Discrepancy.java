package io.github.guillermodubon.invoward.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Financial finding and its human-managed resolution lifecycle. */
public record Discrepancy(
        UUID id,
        UUID analysisId,
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

    private static final int MAX_VALUE_CODE_POINTS = 255;
    private static final int MAX_NOTE_CODE_POINTS = 2000;

    public Discrepancy {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(resolutionStatus, "resolutionStatus must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        referenceValue = optionalBoundedText(referenceValue, MAX_VALUE_CODE_POINTS, "referenceValue");
        invoiceValue = optionalBoundedText(invoiceValue, MAX_VALUE_CODE_POINTS, "invoiceValue");
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("explanation must not be blank");
        }
        validateDifference(absoluteDifference, 4, "absoluteDifference");
        validateDifference(percentageDifference, 6, "percentageDifference");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        resolutionNote = normalizeNote(resolutionNote);
        validateResolution(resolutionStatus, resolutionReason, resolutionNote, resolvedAt, createdAt, updatedAt);
    }

    /** Returns a new discrepancy after an allowed resolution-state transition. */
    public Discrepancy transitionResolution(
            DiscrepancyResolutionStatus nextStatus,
            DiscrepancyResolutionReason nextReason,
            String nextNote,
            Instant now) {
        Objects.requireNonNull(nextStatus, "nextStatus must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("now must not be before updatedAt");
        }
        if (!isAllowedTransition(resolutionStatus, nextStatus)) {
            throw new IllegalStateException("discrepancy resolution transition is not allowed");
        }

        if (nextStatus == DiscrepancyResolutionStatus.OPEN) {
            return new Discrepancy(
                    id, analysisId, reconciliationLineId, type, referenceValue, invoiceValue,
                    absoluteDifference, percentageDifference, explanation, nextStatus,
                    null, null, version, null, createdAt, now);
        }
        return new Discrepancy(
                id, analysisId, reconciliationLineId, type, referenceValue, invoiceValue,
                absoluteDifference, percentageDifference, explanation, nextStatus,
                nextReason, nextNote, version, now, createdAt, now);
    }

    private static void validateResolution(
            DiscrepancyResolutionStatus status,
            DiscrepancyResolutionReason reason,
            String note,
            Instant resolvedAt,
            Instant createdAt,
            Instant updatedAt) {
        if (status == DiscrepancyResolutionStatus.OPEN) {
            if (reason != null || note != null || resolvedAt != null) {
                throw new IllegalArgumentException("open discrepancies must not contain resolution details");
            }
            return;
        }
        if (reason == null || resolvedAt == null) {
            throw new IllegalArgumentException("resolved discrepancies require a reason and resolvedAt");
        }
        if (resolvedAt.isBefore(createdAt) || resolvedAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("resolvedAt must be between createdAt and updatedAt");
        }
        if (reason == DiscrepancyResolutionReason.OTHER && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("OTHER resolution requires a nonblank note");
        }
    }

    private static boolean isAllowedTransition(
            DiscrepancyResolutionStatus current,
            DiscrepancyResolutionStatus next) {
        return switch (current) {
            case OPEN -> next == DiscrepancyResolutionStatus.ACCEPTED
                    || next == DiscrepancyResolutionStatus.RESOLVED;
            case ACCEPTED -> next == DiscrepancyResolutionStatus.RESOLVED
                    || next == DiscrepancyResolutionStatus.OPEN;
            case RESOLVED -> next == DiscrepancyResolutionStatus.ACCEPTED
                    || next == DiscrepancyResolutionStatus.OPEN;
        };
    }

    private static String optionalBoundedText(String value, int maxCodePoints, String field) {
        if (value == null) {
            return null;
        }
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException(field + " must not exceed " + maxCodePoints + " code points");
        }
        return value;
    }

    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        for (int offset = 0; offset < note.length();) {
            int codePoint = note.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("resolutionNote must not contain control characters");
            }
            offset += Character.charCount(codePoint);
        }
        String normalized = note.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_NOTE_CODE_POINTS) {
            throw new IllegalArgumentException("resolutionNote must not exceed 2000 code points");
        }
        return normalized;
    }

    private static void validateDifference(BigDecimal value, int scale, String field) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() > scale
                || Math.max(0L, (long) normalized.precision() - normalized.scale()) > 19 - scale) {
            throw new IllegalArgumentException(field + " exceeds its database numeric precision");
        }
    }

    @Override
    public String toString() {
        return "Discrepancy[id=" + id + ", analysisId=" + analysisId + ", type=" + type
                + ", resolutionStatus=" + resolutionStatus + ", version=" + version + "]";
    }
}
