package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.insights.application.model.InsightsPeriod;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InsightsPeriodFactoryTest {

    @Test
    void defaultPeriodUsesTheCurrentUtcCalendarMonth() {
        InsightsPeriodFactory factory = factory("2026-10-15T23:59:59Z", ZoneOffset.ofHours(14));

        assertEquals(new InsightsPeriod(
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-01")), factory.create(null, null));
    }

    @Test
    void defaultPeriodHandlesMonthYearBoundaryAndLeapFebruary() {
        assertEquals(new InsightsPeriod(
                        LocalDate.parse("2024-12-01"), LocalDate.parse("2025-01-01")),
                factory("2024-12-31T23:59:59Z", ZoneOffset.UTC).currentUtcMonth());
        assertEquals(new InsightsPeriod(
                        LocalDate.parse("2024-02-01"), LocalDate.parse("2024-03-01")),
                factory("2024-02-29T12:00:00Z", ZoneOffset.UTC).currentUtcMonth());
    }

    @Test
    void customInclusiveDatesBecomeHalfOpenAndAllowExactly366Days() {
        InsightsPeriod leapDay = factory("2026-01-01T00:00:00Z", ZoneOffset.UTC)
                .create(LocalDate.parse("2024-02-28"), LocalDate.parse("2024-02-29"));
        assertEquals(LocalDate.parse("2024-02-28"), leapDay.fromInclusive());
        assertEquals(LocalDate.parse("2024-03-01"), leapDay.toExclusive());

        InsightsPeriod maximum = factory("2026-01-01T00:00:00Z", ZoneOffset.UTC)
                .create(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"));
        assertEquals(366, java.time.temporal.ChronoUnit.DAYS.between(
                maximum.fromInclusive(), maximum.toExclusive()));
    }

    @Test
    void clockZoneDoesNotChangeTheUtcDateAtRollover() {
        InsightsPeriodFactory factory = factory("2026-11-01T00:30:00Z", ZoneId.of("America/Los_Angeles"));

        assertEquals(new InsightsPeriod(
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-01")), factory.currentUtcMonth());

        InsightsPeriodFactory beforeUtcRollover = factory("2026-10-31T10:30:00Z", ZoneOffset.ofHours(14));
        assertEquals(new InsightsPeriod(
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-01")),
                beforeUtcRollover.currentUtcMonth());
    }

    @Test
    void customWindowRequiresBothOrderedDatesAndRejectsMoreThan366Days() {
        InsightsPeriodFactory factory = factory("2026-01-01T00:00:00Z", ZoneOffset.UTC);
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(LocalDate.parse("2026-01-01"), null));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(null, LocalDate.parse("2026-01-01")));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-01")));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(LocalDate.parse("2024-01-01"), LocalDate.parse("2025-01-01")));
    }

    @Test
    void rejectsMaximumDateOverflowAndDefaultMonthOverflow() {
        InsightsPeriodFactory factory = factory("2026-01-01T00:00:00Z", ZoneOffset.UTC);
        assertThrows(IllegalArgumentException.class, () -> factory.create(LocalDate.MAX, LocalDate.MAX));

        InsightsPeriodFactory lastRepresentableYear = factory(
                LocalDate.of(999_999_999, 12, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
                ZoneOffset.UTC);
        assertThrows(IllegalArgumentException.class, lastRepresentableYear::currentUtcMonth);
    }

    private static InsightsPeriodFactory factory(String instant, ZoneId clockZone) {
        return new InsightsPeriodFactory(Clock.fixed(Instant.parse(instant), clockZone));
    }
}
