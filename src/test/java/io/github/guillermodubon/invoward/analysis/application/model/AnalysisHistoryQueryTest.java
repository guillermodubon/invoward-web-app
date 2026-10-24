package io.github.guillermodubon.invoward.analysis.application.model;

import io.github.guillermodubon.invoward.analysis.application.exception.InvalidAnalysisHistoryQueryException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisHistoryQueryTest {

    @Test
    void discrepancyFilterUsesExactlyTheExistingPublicIdentifiers() {
        String[] existing = Arrays.stream(DiscrepancyType.values()).map(Enum::name).toArray(String[]::new);
        String[] history = Arrays.stream(HistoryDiscrepancyType.values()).map(Enum::name).toArray(String[]::new);

        assertArrayEquals(existing, history);
        assertEquals(HistoryDiscrepancyType.UNIT_PRICE_MISMATCH,
                query(null, null, HistoryDiscrepancyType.UNIT_PRICE_MISMATCH, null, null, null).discrepancyType());
    }

    @Test
    void defaultsAndNormalizesOptionalFilters() {
        AnalysisHistoryQuery defaults = AnalysisHistoryQuery.defaults();

        assertEquals(0, defaults.page());
        assertEquals(20, defaults.size());
        assertEquals(AnalysisHistorySort.CREATED_AT_DESC, defaults.sort());
        assertEquals(null, defaults.search());
        assertEquals(null, defaults.supplier());

        AnalysisHistoryQuery query = AnalysisHistoryQuery.of(
                null, null, "  invoice search  ", AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, "  ACME  ",
                HistoryDiscrepancyType.TOTAL_MISMATCH, " usd ", LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-31"), new BigDecimal("0.0000"), new BigDecimal("12.34"),
                AnalysisHistorySort.SUPPLIER_NAME_ASC);

        assertEquals("invoice search", query.search());
        assertEquals("ACME", query.supplier());
        assertEquals("USD", query.currency());
        assertEquals(HistoryDiscrepancyType.TOTAL_MISMATCH, query.discrepancyType());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, query.status());
        assertEquals(AnalysisStatus.COMPLETED, query.processingStatus());
        assertEquals(AnalysisReviewStatus.PENDING, query.reviewStatus());
    }

    @Test
    void trimsBlankTextAndCountsUnicodeCodePoints() {
        assertEquals(null, query(" \t ", " \n ", null, null, null, null).search());
        assertEquals(120, query("😀".repeat(120), null, null, null, null, null)
                .search().codePointCount(0, query("😀".repeat(120), null, null, null, null, null)
                        .search().length()));
        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query("😀".repeat(121), null, null, null, null, null));
        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query(null, "x".repeat(241), null, null, null, null));
    }

    @Test
    void enforcesPageAndSizeBounds() {
        assertEquals(1000, AnalysisHistoryQuery.of(1000, 100, null, null, null, null,
                null, null, null, null, null, null, null, null).page());
        assertEquals(1, AnalysisHistoryQuery.of(0, 1, null, null, null, null,
                null, null, null, null, null, null, null, null).size());

        assertThrows(InvalidAnalysisHistoryQueryException.class, () -> AnalysisHistoryQuery.of(
                -1, 20, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(InvalidAnalysisHistoryQueryException.class, () -> AnalysisHistoryQuery.of(
                1001, 20, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(InvalidAnalysisHistoryQueryException.class, () -> AnalysisHistoryQuery.of(
                0, 0, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThrows(InvalidAnalysisHistoryQueryException.class, () -> AnalysisHistoryQuery.of(
                0, 101, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void validatesCurrencyDatesAndNumeric19Scale4Amounts() {
        assertEquals("EUR", query(null, null, null, "eur", null, null).currency());
        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query(null, null, null, "ＥＵＲ", null, null));
        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query(null, null, null, "EU", null, null));

        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query(null, null, null, null, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-10-31")));
        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query(null, null, null, null, null, LocalDate.MAX));
        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query(null, null, null, null, null, null,
                        new BigDecimal("10.00001"), null));
        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query(null, null, null, null, null, null,
                        new BigDecimal("1000000000000000"), null));
        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query(null, null, null, null, null, null, new BigDecimal("-0.01"), null));
        assertThrows(InvalidAnalysisHistoryQueryException.class,
                () -> query(null, null, null, null, null, null,
                        new BigDecimal("20"), new BigDecimal("19.99")));

        assertEquals(new BigDecimal("999999999999999.9999"), query(
                null, null, null, null, null, null,
                new BigDecimal("0"), new BigDecimal("999999999999999.9999")).maxDifference());
        assertEquals(new BigDecimal("10.00000"), query(
                null, null, null, null, null, null, new BigDecimal("10.00000"), null).minDifference());
    }

    @Test
    void permitsOnlyTheEightStableSortPairs() {
        List<String> expected = List.of(
                "createdAt,desc", "createdAt,asc", "difference,desc", "difference,asc",
                "invoicedTotal,desc", "invoicedTotal,asc", "supplierName,asc", "supplierName,desc");
        assertEquals(expected, Arrays.stream(AnalysisHistorySort.values())
                .map(AnalysisHistorySort::parameterValue).toList());
        for (String value : expected) {
            assertEquals(value, AnalysisHistorySort.parse(value).parameterValue());
        }
        assertEquals(AnalysisHistorySort.CREATED_AT_DESC, AnalysisHistorySort.parse(null));
        assertThrows(InvalidAnalysisHistoryQueryException.class, () -> AnalysisHistorySort.parse("id,asc"));
        assertThrows(InvalidAnalysisHistoryQueryException.class, () -> AnalysisHistorySort.parse("createdAt,asc;drop table"));
    }

    @Test
    void queryValidationErrorsDoNotEchoSubmittedValues() {
        InvalidAnalysisHistoryQueryException exception = assertThrows(
                InvalidAnalysisHistoryQueryException.class,
                () -> query("SECRET-INVOICE-" + "x".repeat(120), null, null, null, null, null));

        assertFalse(exception.getMessage().contains("SECRET-INVOICE"));
    }

    @Test
    void historyItemIsAnImmutableSafeProjectionWithConsistentTimestamps() {
        Instant created = Instant.parse("2026-10-01T10:00:00Z");
        Instant reviewed = created.plusSeconds(30);
        AnalysisHistoryItem item = new AnalysisHistoryItem(
                UUID.randomUUID(), AnalysisStatus.COMPLETED, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisReviewStatus.REVIEWED, reviewed, "ACME", "QUOTE", "Q-1", "I-1", "USD",
                new BigDecimal("10.00"), new BigDecimal("12.00"), new BigDecimal("2.00"),
                created, created.plusSeconds(60), created.plusSeconds(20));
        Set<String> allowed = Set.of("id", "status", "reconciliationStatus", "reviewStatus", "reviewedAt",
                "supplierName", "referenceType", "referenceNumber", "invoiceNumber", "currency",
                "referenceTotal", "invoicedTotal", "difference", "createdAt", "updatedAt", "completedAt");

        assertTrue(allowed.containsAll(Arrays.stream(AnalysisHistoryItem.class.getRecordComponents())
                .map(component -> component.getName()).toList()));
        assertEquals(allowed.size(), AnalysisHistoryItem.class.getRecordComponents().length);
        assertEquals(AnalysisReviewStatus.REVIEWED, item.reviewStatus());
        assertThrows(IllegalArgumentException.class, () -> new AnalysisHistoryItem(
                UUID.randomUUID(), AnalysisStatus.COMPLETED, null, AnalysisReviewStatus.PENDING,
                created, null, null, null, null, null, null, null, null,
                created, created.plusSeconds(60), null));
    }

    @Test
    void pageCopiesContentAndDerivesConsistentMetadataIncludingOutOfRangePage() {
        AnalysisHistoryItem item = item();
        java.util.ArrayList<AnalysisHistoryItem> mutable = new java.util.ArrayList<>(List.of(item));
        AnalysisHistoryPage first = new AnalysisHistoryPage(mutable, 0, 1, 2);
        mutable.clear();

        assertEquals(List.of(item), first.content());
        assertEquals(2, first.totalPages());
        assertTrue(first.hasNext());
        assertFalse(first.hasPrevious());
        assertThrows(UnsupportedOperationException.class, () -> first.content().clear());

        AnalysisHistoryPage beyond = new AnalysisHistoryPage(List.of(), 4, 20, 21);
        assertEquals(2, beyond.totalPages());
        assertFalse(beyond.hasNext());
        assertTrue(beyond.hasPrevious());
        AnalysisHistoryPage empty = new AnalysisHistoryPage(List.of(), 0, 20, 0);
        assertEquals(0, empty.totalPages());
        assertFalse(empty.hasNext());
    }

    private static AnalysisHistoryQuery query(
            String search, String supplier, HistoryDiscrepancyType discrepancyType,
            String currency, LocalDate from, LocalDate to) {
        return query(search, supplier, discrepancyType, currency, from, to, null, null);
    }

    private static AnalysisHistoryQuery query(
            String search, String supplier, HistoryDiscrepancyType discrepancyType,
            String currency, LocalDate from, LocalDate to, BigDecimal min, BigDecimal max) {
        return AnalysisHistoryQuery.of(0, 20, search, null, null, null, supplier, discrepancyType,
                currency, from, to, min, max, AnalysisHistorySort.CREATED_AT_DESC);
    }

    private static AnalysisHistoryItem item() {
        Instant created = Instant.parse("2026-10-01T10:00:00Z");
        return new AnalysisHistoryItem(UUID.randomUUID(), AnalysisStatus.COMPLETED, null,
                AnalysisReviewStatus.PENDING, null, null, null, null, null, null,
                null, null, null, created, created, created);
    }
}
