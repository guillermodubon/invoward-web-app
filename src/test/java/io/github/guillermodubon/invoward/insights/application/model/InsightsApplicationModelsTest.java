package io.github.guillermodubon.invoward.insights.application.model;

import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsApplicationModelsTest {

    @Test
    void insightsPeriodUsesHalfOpenIntervalsAndEnforcesThe366DayLimit() {
        InsightsPeriod period = new InsightsPeriod(
                LocalDate.parse("2024-02-01"), LocalDate.parse("2024-03-01"));

        assertEquals(29, java.time.temporal.ChronoUnit.DAYS.between(
                period.fromInclusive(), period.toExclusive()));
        assertThrows(NullPointerException.class, () -> new InsightsPeriod(null, LocalDate.MAX));
        assertThrows(NullPointerException.class, () -> new InsightsPeriod(LocalDate.MIN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InsightsPeriod(LocalDate.parse("2026-02-02"), LocalDate.parse("2026-02-02")));
        assertThrows(IllegalArgumentException.class,
                () -> new InsightsPeriod(LocalDate.parse("2026-02-03"), LocalDate.parse("2026-02-02")));
        assertThrows(IllegalArgumentException.class,
                () -> new InsightsPeriod(LocalDate.parse("2024-01-01"), LocalDate.parse("2025-01-02")));
        assertEquals(366, new InsightsPeriod(
                LocalDate.parse("2024-01-01"), LocalDate.parse("2025-01-01")).toExclusive()
                .toEpochDay() - LocalDate.parse("2024-01-01").toEpochDay());
    }

    @Test
    void currencyAmountsKeepExactBigDecimalAndRejectInvalidCurrencyOrNegativeCounts() {
        BigDecimal precise = new BigDecimal("-12.340000");
        CurrencyAmount amount = new CurrencyAmount("USD", precise, 2);

        assertEquals(precise, amount.amount());
        assertEquals("-12.340000", amount.amount().toPlainString());
        assertEquals(0, new CurrencyAmount("EUR", BigDecimal.ZERO, 0).contributingAnalyses());
        assertThrows(NullPointerException.class, () -> new CurrencyAmount(null, BigDecimal.ONE, 1));
        assertThrows(NullPointerException.class, () -> new CurrencyAmount("USD", null, 1));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyAmount("usd", BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyAmount("US", BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyAmount("US1", BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyAmount("USD", BigDecimal.ONE, -1));
        assertEquals(BigDecimal.class, CurrencyAmount.class.getRecordComponents()[1].getType());
    }

    @Test
    void insightsPageIsImmutableAndCalculatesMetadataWithoutOverflow() {
        ArrayList<String> mutable = new ArrayList<>(List.of("first"));
        InsightsPage<String> page = new InsightsPage<>(mutable, 0, 1, 2);
        mutable.clear();

        assertEquals(List.of("first"), page.content());
        assertEquals(2, page.totalPages());
        assertTrue(page.hasNext());
        assertFalse(page.hasPrevious());
        assertThrows(UnsupportedOperationException.class, () -> page.content().clear());
        assertEquals(Long.MAX_VALUE, new InsightsPage<>(List.of(), 1_000, 1,
                Long.MAX_VALUE).totalPages());

        InsightsPage<String> outOfRange = new InsightsPage<>(List.of(), 4, 20, 21);
        assertEquals(2, outOfRange.totalPages());
        assertFalse(outOfRange.hasNext());
        assertTrue(outOfRange.hasPrevious());
        assertEquals(0, new InsightsPage<>(List.of(), 0, 20, 0).totalPages());
    }

    @Test
    void insightsPageRejectsInvalidBoundsAndInconsistentContent() {
        assertThrows(NullPointerException.class, () -> new InsightsPage<>(null, 0, 20, 0));
        assertThrows(NullPointerException.class, () -> new InsightsPage<>(Arrays.asList("x", null), 0, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new InsightsPage<>(List.of(), -1, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new InsightsPage<>(List.of(), 1_001, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new InsightsPage<>(List.of(), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new InsightsPage<>(List.of(), 0, 101, 0));
        assertThrows(IllegalArgumentException.class, () -> new InsightsPage<>(List.of(), 0, 20, -1));
        assertThrows(IllegalArgumentException.class, () -> new InsightsPage<>(List.of("a", "b"), 0, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new InsightsPage<>(List.of("a"), 0, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new InsightsPage<>(List.of("a"), 1, 20, 1));
    }

    @Test
    void discrepancyFrequencyUsesExistingTypeAndNonnegativeCount() {
        assertEquals(DiscrepancyType.class,
                DiscrepancyTypeFrequency.class.getRecordComponents()[0].getType());
        DiscrepancyTypeFrequency frequency = new DiscrepancyTypeFrequency(DiscrepancyType.TOTAL_MISMATCH, 3);
        assertEquals(DiscrepancyType.TOTAL_MISMATCH, frequency.type());
        assertEquals(3, frequency.count());
        assertThrows(NullPointerException.class, () -> new DiscrepancyTypeFrequency(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscrepancyTypeFrequency(DiscrepancyType.TOTAL_MISMATCH, -1));
    }

    @Test
    void supplierKeyPreservesExactPersistedValueAndEnforcesColumnBounds() {
        String canonicalKey = "ACME & Sons (東京)";
        assertEquals(canonicalKey, new SupplierKey(canonicalKey).value());
        String maxCodePoints = "😀".repeat(SupplierKey.MAX_CODE_POINTS);
        assertEquals(maxCodePoints, new SupplierKey(maxCodePoints).value());
        assertEquals("supplier/child", new SupplierKey("supplier/child").value());
        assertEquals("supplier\\child", new SupplierKey("supplier\\child").value());
        assertEquals(".", new SupplierKey(".").value());
        assertEquals("..", new SupplierKey("..").value());
        assertThrows(NullPointerException.class, () -> new SupplierKey(null));
        assertThrows(IllegalArgumentException.class, () -> new SupplierKey(""));
        assertThrows(IllegalArgumentException.class, () -> new SupplierKey("  \t "));
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierKey("😀".repeat(SupplierKey.MAX_CODE_POINTS + 1)));
        assertThrows(IllegalArgumentException.class, () -> new SupplierKey("\uD800"));
    }

    @Test
    void allNumericReadModelComponentsAvoidBinaryFloatingPoint() {
        List<Class<?>> models = List.of(CurrencyAmount.class, InsightsPage.class,
                DiscrepancyTypeFrequency.class);

        for (Class<?> model : models) {
            for (RecordComponent component : model.getRecordComponents()) {
                assertFalse(component.getType() == double.class || component.getType() == Double.class
                                || component.getType() == float.class || component.getType() == Float.class,
                        model.getSimpleName() + " must not use binary floating point fields");
            }
        }
        assertInstanceOf(BigDecimal.class, new CurrencyAmount("USD", BigDecimal.ONE, 1).amount());
    }
}
