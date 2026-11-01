package io.github.guillermodubon.invoward.insights.application.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InsightsEnumContractsTest {

    @Test
    void dashboardRecentViewHasOnlySupportedValuesAndAStableDefault() {
        assertEquals(List.of("RECENT", "NEEDS_ATTENTION"),
                Arrays.stream(DashboardRecentView.values()).map(Enum::name).toList());
        assertEquals(DashboardRecentView.RECENT, DashboardRecentView.parse(null));
        assertEquals(DashboardRecentView.NEEDS_ATTENTION, DashboardRecentView.parse("NEEDS_ATTENTION"));
        assertEquals(DashboardRecentView.RECENT, DashboardRecentView.parse(" RECENT "));
        assertInvalid(() -> DashboardRecentView.parse("FAILED"));
        assertInvalid(() -> DashboardRecentView.parse("needs_attention"));
    }

    @Test
    void supplierListSortParsesOnlyTheApprovedWhitelist() {
        List<String> expected = List.of("analysisCount,desc", "analysisCount,asc",
                "supplierName,asc", "supplierName,desc");
        assertEquals(expected, Arrays.stream(SupplierListSort.values())
                .map(SupplierListSort::parameterValue).toList());
        assertEquals(SupplierListSort.ANALYSIS_COUNT_DESC, SupplierListSort.parse(null));
        for (String value : expected) {
            assertEquals(value, SupplierListSort.parse(value).parameterValue());
        }
        assertInvalid(() -> SupplierListSort.parse("id,asc"));
        assertInvalid(() -> SupplierListSort.parse("analysisCount,asc;drop table"));
    }

    @Test
    void supplierHistorySortParsesOnlyTheApprovedWhitelist() {
        List<String> expected = List.of("createdAt,desc", "createdAt,asc",
                "completedAt,desc", "completedAt,asc");
        assertEquals(expected, Arrays.stream(SupplierHistorySort.values())
                .map(SupplierHistorySort::parameterValue).toList());
        assertEquals(SupplierHistorySort.CREATED_AT_DESC, SupplierHistorySort.parse(null));
        for (String value : expected) {
            assertEquals(value, SupplierHistorySort.parse(value).parameterValue());
        }
        assertInvalid(() -> SupplierHistorySort.parse("ownerId,asc"));
        assertInvalid(() -> SupplierHistorySort.parse("completedAt,asc nulls first"));
    }

    private static void assertInvalid(Runnable action) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action::run);
        assertFalse(exception.getMessage().contains("drop table"));
        assertFalse(exception.getMessage().contains("ownerId"));
    }
}
