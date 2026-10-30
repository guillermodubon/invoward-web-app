package io.github.guillermodubon.invoward.sharing.application.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShareLinkExpiryChoiceTest {

    private static final Instant NOW = Instant.parse("2026-03-07T23:30:00Z");

    @Test
    void exposesOnlyTheThreeFixedUtcDurations() {
        assertEquals(List.of("24_HOURS", "7_DAYS", "30_DAYS"),
                java.util.Arrays.stream(ShareLinkExpiryChoice.values())
                        .map(ShareLinkExpiryChoice::requestValue)
                        .toList());
        assertEquals(Duration.ofHours(24), ShareLinkExpiryChoice.HOURS_24.duration());
        assertEquals(Duration.ofDays(7), ShareLinkExpiryChoice.DAYS_7.duration());
        assertEquals(Duration.ofDays(30), ShareLinkExpiryChoice.DAYS_30.duration());
    }

    @Test
    void calculatesExpiryOnTheInstantTimelineWithoutTimezoneOrDstEffects() {
        assertEquals(NOW.plus(Duration.ofHours(24)),
                ShareLinkExpiryChoice.HOURS_24.calculateExpiresAt(NOW, null, null));
        assertEquals(NOW.plus(Duration.ofDays(7)),
                ShareLinkExpiryChoice.DAYS_7.calculateExpiresAt(NOW, null, null));
        assertEquals(NOW.plus(Duration.ofDays(30)),
                ShareLinkExpiryChoice.DAYS_30.calculateExpiresAt(NOW, null, null));
    }

    @Test
    void allExpiryChoicesRemainElapsedDurationsAcrossSpringAndFallDstTransitions() {
        ZoneId newYork = ZoneId.of("America/New_York");
        List<ZonedDateTime> daylightSavingBoundaries = List.of(
                ZonedDateTime.ofLocal(
                        LocalDateTime.of(2026, 3, 8, 1, 30), newYork, ZoneOffset.ofHours(-5)),
                ZonedDateTime.ofLocal(
                        LocalDateTime.of(2026, 11, 1, 1, 30), newYork, ZoneOffset.ofHours(-4)));

        for (ZonedDateTime localStart : daylightSavingBoundaries) {
            Instant now = localStart.toInstant();
            for (ShareLinkExpiryChoice choice : ShareLinkExpiryChoice.values()) {
                Instant actual = choice.calculateExpiresAt(now, null, null);

                assertEquals(now.plus(choice.duration()), actual,
                        () -> choice + " must use elapsed UTC duration at " + localStart);
                assertEquals(choice.duration(), Duration.between(now, actual));
                assertNotEquals(localStart.plusDays(choice.duration().toDays()).toInstant(), actual,
                        () -> choice + " must not use local calendar-day arithmetic at " + localStart);
            }
        }
    }

    @Test
    void capsExpiryAtTheEarlierAnalysisOrReportExpiry() {
        Instant requestedExpiry = NOW.plus(Duration.ofDays(30));
        Instant analysisExpiry = NOW.plus(Duration.ofDays(10));
        Instant reportExpiry = NOW.plus(Duration.ofDays(5));

        assertEquals(analysisExpiry,
                ShareLinkExpiryChoice.DAYS_30.calculateExpiresAt(NOW, analysisExpiry, null));
        assertEquals(reportExpiry,
                ShareLinkExpiryChoice.DAYS_30.calculateExpiresAt(NOW, analysisExpiry, reportExpiry));
        assertEquals(requestedExpiry,
                ShareLinkExpiryChoice.DAYS_30.calculateExpiresAt(NOW, null, null));
    }

    @Test
    void eachAllowedDurationIsCappedByTheEarliestGuestOrReportExpiry() {
        for (ShareLinkExpiryChoice choice : ShareLinkExpiryChoice.values()) {
            Instant requested = NOW.plus(choice.duration());
            Instant analysisExpiry = requested.minusSeconds(2);
            Instant reportExpiry = requested.minusSeconds(1);

            assertEquals(requested, choice.calculateExpiresAt(NOW, null, null));
            assertEquals(analysisExpiry, choice.calculateExpiresAt(NOW, analysisExpiry, null));
            assertEquals(reportExpiry, choice.calculateExpiresAt(NOW, null, reportExpiry));
            assertEquals(analysisExpiry,
                    choice.calculateExpiresAt(NOW, analysisExpiry, reportExpiry));
            assertEquals(requested, choice.calculateExpiresAt(NOW, requested, requested));
        }
    }

    @Test
    void rejectsAnalysisOrReportExpiryWithNoRemainingLifetime() {
        for (Instant unusableExpiry : List.of(NOW, NOW.minusNanos(1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> ShareLinkExpiryChoice.DAYS_7.calculateExpiresAt(NOW, unusableExpiry, null));
            assertThrows(IllegalArgumentException.class,
                    () -> ShareLinkExpiryChoice.DAYS_7.calculateExpiresAt(NOW, null, unusableExpiry));
        }
        Instant oneNanosecondRemaining = NOW.plusNanos(1);
        assertEquals(oneNanosecondRemaining,
                ShareLinkExpiryChoice.HOURS_24.calculateExpiresAt(NOW, oneNanosecondRemaining, null));
        assertEquals(oneNanosecondRemaining,
                ShareLinkExpiryChoice.HOURS_24.calculateExpiresAt(NOW, null, oneNanosecondRemaining));
    }

    @Test
    void requiresCalculationInstant() {
        assertThrows(NullPointerException.class,
                () -> ShareLinkExpiryChoice.DAYS_7.calculateExpiresAt(null, null, null));
    }
}
