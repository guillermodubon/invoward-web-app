package io.github.guillermodubon.invoward.analysis.application.model;

import io.github.guillermodubon.invoward.analysis.application.exception.InvalidAnalysisHistoryQueryException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/** Immutable, framework-independent filter contract for a registered user's Analysis history. */
public record AnalysisHistoryQuery(
        int page,
        int size,
        String search,
        AnalysisReconciliationStatus status,
        AnalysisStatus processingStatus,
        AnalysisReviewStatus reviewStatus,
        String supplier,
        HistoryDiscrepancyType discrepancyType,
        String currency,
        LocalDate from,
        LocalDate to,
        BigDecimal minDifference,
        BigDecimal maxDifference,
        AnalysisHistorySort sort) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_PAGE = 1000;
    public static final int MAX_SIZE = 100;
    public static final int MAX_SEARCH_CODE_POINTS = 120;
    public static final int MAX_SUPPLIER_CODE_POINTS = 240;
    private static final BigDecimal MAX_NUMERIC_19_4 = new BigDecimal("999999999999999.9999");

    public AnalysisHistoryQuery {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new InvalidAnalysisHistoryQueryException();
        }
        search = normalizeOptionalText(search, MAX_SEARCH_CODE_POINTS);
        supplier = normalizeOptionalText(supplier, MAX_SUPPLIER_CODE_POINTS);
        currency = normalizeCurrency(currency);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidAnalysisHistoryQueryException();
        }
        if (to != null && to.equals(LocalDate.MAX)) {
            // The repository converts inclusive `to` to an exclusive next-day boundary.
            throw new InvalidAnalysisHistoryQueryException();
        }
        minDifference = validateDifference(minDifference);
        maxDifference = validateDifference(maxDifference);
        if (minDifference != null && maxDifference != null && minDifference.compareTo(maxDifference) > 0) {
            throw new InvalidAnalysisHistoryQueryException();
        }
        sort = sort == null ? AnalysisHistorySort.CREATED_AT_DESC : sort;
    }

    /** Creates a default empty history query. */
    public static AnalysisHistoryQuery defaults() {
        return of(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Applies documented defaults before validating the otherwise typed query values. */
    public static AnalysisHistoryQuery of(
            Integer page,
            Integer size,
            String search,
            AnalysisReconciliationStatus status,
            AnalysisStatus processingStatus,
            AnalysisReviewStatus reviewStatus,
            String supplier,
            HistoryDiscrepancyType discrepancyType,
            String currency,
            LocalDate from,
            LocalDate to,
            BigDecimal minDifference,
            BigDecimal maxDifference,
            AnalysisHistorySort sort) {
        return new AnalysisHistoryQuery(
                page == null ? DEFAULT_PAGE : page,
                size == null ? DEFAULT_SIZE : size,
                search,
                status,
                processingStatus,
                reviewStatus,
                supplier,
                discrepancyType,
                currency,
                from,
                to,
                minDifference,
                maxDifference,
                sort);
    }

    private static String normalizeOptionalText(String value, int maximumCodePoints) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new InvalidAnalysisHistoryQueryException();
        }
        return normalized;
    }

    private static String normalizeCurrency(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (!normalized.matches("[A-Za-z]{3}")) {
            throw new InvalidAnalysisHistoryQueryException();
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal validateDifference(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0 || value.compareTo(MAX_NUMERIC_19_4) > 0) {
            throw new InvalidAnalysisHistoryQueryException();
        }
        BigDecimal normalized = value.stripTrailingZeros();
        long integerDigits = (long) normalized.precision() - normalized.scale();
        if (normalized.scale() > 4 || integerDigits > 15) {
            throw new InvalidAnalysisHistoryQueryException();
        }
        return value;
    }
}
