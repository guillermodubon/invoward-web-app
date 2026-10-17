package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportStorageKeyGeneratorTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-14T12:00:00Z");
    private final ReportStorageKeyGenerator generator = new ReportStorageKeyGenerator();

    @Test
    void registeredKeyUsesRequiredOwnerScopeAndOpaquePdfName() {
        UUID userId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysis(analysisId, new RegisteredUserOwner(userId), null);

        String first = generator.generate(analysis);
        String second = generator.generate(analysis);
        String prefix = "users/" + userId + "/analyses/" + analysisId + "/reports/";

        assertTrue(first.matches(prefix + "[0-9a-f-]{36}\\.pdf"));
        assertTrue(second.matches(prefix + "[0-9a-f-]{36}\\.pdf"));
        assertNotEquals(first, second);
        assertFalse(first.contains("supplier"));
        assertFalse(first.contains("invoice"));
        assertFalse(first.contains("original-file"));
    }

    @Test
    void guestKeyDoesNotExposeGuestSessionIdentifier() {
        UUID guestSessionId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        Instant expiry = CREATED_AT.plusSeconds(86_400);
        Analysis analysis = analysis(analysisId, new GuestSessionOwner(guestSessionId, expiry), expiry);

        String key = generator.generate(analysis);

        assertTrue(key.matches("guest/" + analysisId + "/reports/[0-9a-f-]{36}\\.pdf"));
        assertFalse(key.contains(guestSessionId.toString()));
    }

    private static Analysis analysis(UUID id, AnalysisOwner owner, Instant expiresAt) {
        return new Analysis(
                id,
                owner,
                AnalysisStatus.GENERATING_REPORT,
                AnalysisReviewStatus.REVIEWED,
                AnalysisReconciliationStatus.MATCHED,
                null, null, null, null, null, null, null, null, null,
                new PriceTolerance(java.math.BigDecimal.ZERO, null), false, null, null,
                0, null, expiresAt, CREATED_AT, CREATED_AT);
    }
}
