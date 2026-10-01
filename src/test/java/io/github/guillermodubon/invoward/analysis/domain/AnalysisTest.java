package io.github.guillermodubon.invoward.analysis.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final PriceTolerance EXACT = PriceTolerance.exactMatch();

    @Test
    void registeredAnalysisStartsInTheApprovedEmptyState() {
        Analysis analysis = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW);

        assertEquals(AnalysisStatus.CREATED, analysis.status());
        assertEquals(AnalysisReviewStatus.PENDING, analysis.reviewStatus());
        assertNull(analysis.reconciliationStatus());
        assertNull(analysis.supplierName());
        assertNull(analysis.supplierKey());
        assertNull(analysis.referenceType());
        assertNull(analysis.referenceNumber());
        assertNull(analysis.invoiceNumber());
        assertNull(analysis.currency());
        assertNull(analysis.referenceTotal());
        assertNull(analysis.invoicedTotal());
        assertNull(analysis.difference());
        assertEquals(EXACT, analysis.priceTolerance());
        assertFalse(analysis.retryable());
        assertNull(analysis.failureCode());
        assertNull(analysis.failureUserMessage());
        assertEquals(0, analysis.version());
        assertNull(analysis.completedAt());
        assertNull(analysis.expiresAt());
        assertEquals(NOW, analysis.createdAt());
        assertEquals(NOW, analysis.updatedAt());
    }

    @Test
    void guestAnalysisUsesItsOwnersFixedExpiry() {
        Instant expiry = NOW.plusSeconds(86_400);
        GuestSessionOwner owner = GuestSessionOwner.create(UUID.randomUUID(), expiry, NOW);

        Analysis analysis = Analysis.create(UUID.randomUUID(), owner, EXACT, NOW);

        assertEquals(expiry, analysis.expiresAt());
        assertEquals(owner, analysis.owner());
    }

    @Test
    void rejectsInvalidRequiredValuesOwnershipExpiryAndTimestamps() {
        RegisteredUserOwner registered = new RegisteredUserOwner(UUID.randomUUID());
        GuestSessionOwner guest = GuestSessionOwner.create(UUID.randomUUID(), NOW.plusSeconds(10), NOW);

        assertThrows(NullPointerException.class, () -> Analysis.create(null, registered, EXACT, NOW));
        assertThrows(NullPointerException.class, () -> Analysis.create(UUID.randomUUID(), null, EXACT, NOW));
        assertThrows(NullPointerException.class, () -> Analysis.create(UUID.randomUUID(), registered, null, NOW));
        assertThrows(NullPointerException.class, () -> Analysis.create(UUID.randomUUID(), registered, EXACT, null));
        assertThrows(NullPointerException.class,
                () -> validAnalysis(guest, null, NOW, NOW, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(guest, NOW.plusSeconds(11), NOW, NOW, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(registered, NOW.plusSeconds(10), NOW, NOW, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(registered, null, NOW, NOW.minusSeconds(1), null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(registered, null, NOW, NOW, NOW.plusSeconds(1), null, null, null, 0));
    }

    @Test
    void rejectsNegativeTotalsAndNegativeVersion() {
        RegisteredUserOwner owner = new RegisteredUserOwner(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(owner, null, NOW, NOW, null, new BigDecimal("-0.01"), null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(owner, null, NOW, NOW, null, null, new BigDecimal("-0.01"), null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(owner, null, NOW, NOW, null, null, null, new BigDecimal("-0.01"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(owner, null, NOW, NOW, null, null, null, null, -1));
    }

    @Test
    void ownerCannotLeakGuestCredentialThroughAnalysisString() {
        UUID guestId = UUID.randomUUID();
        GuestSessionOwner owner = GuestSessionOwner.create(guestId, NOW.plusSeconds(10), NOW);

        assertFalse(Analysis.create(UUID.randomUUID(), owner, EXACT, NOW).toString().contains(guestId.toString()));
        assertTrue(Analysis.create(UUID.randomUUID(), owner, EXACT, NOW).toString().contains("status=CREATED"));
    }

    private static Analysis validAnalysis(
            AnalysisOwner owner,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            BigDecimal referenceTotal,
            BigDecimal invoicedTotal,
            BigDecimal difference,
            long version) {
        return new Analysis(
                UUID.randomUUID(), owner, AnalysisStatus.CREATED, AnalysisReviewStatus.PENDING, null,
                null, null, null, null, null, null,
                referenceTotal, invoicedTotal, difference, EXACT,
                false, null, null, version, completedAt, expiresAt, createdAt, updatedAt);
    }
}
