package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkNotFoundException;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RevokeShareLinkTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant CREATED_AT = NOW.minusSeconds(60);
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID SHARE_LINK_ID = UUID.randomUUID();
    private static final RegisteredUserOwner REGISTERED_OWNER = new RegisteredUserOwner(UUID.randomUUID());

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final ShareLinkRepository shareLinkRepository = mock(ShareLinkRepository.class);
    private final RevokeShareLinkTransaction transaction = new RevokeShareLinkTransaction(
            analysisRepository, shareLinkRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void locksAndAuthorizesParentBeforeFindingAndUpdatingNestedLink() {
        Analysis analysis = Analysis.create(ANALYSIS_ID, REGISTERED_OWNER, PriceTolerance.exactMatch(), CREATED_AT);
        ShareLink link = activeLink();
        ShareLink revoked = link.revoke(NOW);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.of(analysis));
        when(shareLinkRepository.findByAnalysisIdAndIdForUpdate(ANALYSIS_ID, SHARE_LINK_ID))
                .thenReturn(Optional.of(link));
        when(shareLinkRepository.update(revoked)).thenReturn(Optional.of(revoked));

        transaction.revoke(ANALYSIS_ID, REGISTERED_OWNER, SHARE_LINK_ID);

        var order = inOrder(analysisRepository, shareLinkRepository);
        order.verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW);
        order.verify(shareLinkRepository).findByAnalysisIdAndIdForUpdate(ANALYSIS_ID, SHARE_LINK_ID);
        order.verify(shareLinkRepository).update(revoked);
    }

    @Test
    void foreignOrExpiredParentStopsBeforeNestedShareLinkLookup() {
        GuestSessionOwner expiredGuest = new GuestSessionOwner(UUID.randomUUID(), NOW);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.empty());
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, expiredGuest, NOW))
                .thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> transaction.revoke(ANALYSIS_ID, REGISTERED_OWNER, SHARE_LINK_ID));
        assertThrows(AnalysisNotFoundException.class,
                () -> transaction.revoke(ANALYSIS_ID, expiredGuest, SHARE_LINK_ID));

        verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW);
        verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, expiredGuest, NOW);
        verifyNoInteractions(shareLinkRepository);
    }

    @Test
    void unknownOrOtherAnalysisShareLinkReturnsSafeNotFoundAfterParentAuthorization() {
        Analysis analysis = Analysis.create(ANALYSIS_ID, REGISTERED_OWNER, PriceTolerance.exactMatch(), CREATED_AT);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.of(analysis));
        when(shareLinkRepository.findByAnalysisIdAndIdForUpdate(ANALYSIS_ID, SHARE_LINK_ID))
                .thenReturn(Optional.empty());

        assertThrows(ShareLinkNotFoundException.class,
                () -> transaction.revoke(ANALYSIS_ID, REGISTERED_OWNER, SHARE_LINK_ID));

        var order = inOrder(analysisRepository, shareLinkRepository);
        order.verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW);
        order.verify(shareLinkRepository).findByAnalysisIdAndIdForUpdate(ANALYSIS_ID, SHARE_LINK_ID);
        verify(shareLinkRepository, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repeatedRevocationDoesNotRewriteOrChangeOriginalRevokedAt() {
        Instant originalRevokedAt = NOW.minusSeconds(20);
        Analysis analysis = Analysis.create(ANALYSIS_ID, REGISTERED_OWNER, PriceTolerance.exactMatch(), CREATED_AT);
        ShareLink alreadyRevoked = new ShareLink(
                SHARE_LINK_ID, ANALYSIS_ID, "a".repeat(64), CREATED_AT, NOW.plusSeconds(3600), originalRevokedAt);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.of(analysis));
        when(shareLinkRepository.findByAnalysisIdAndIdForUpdate(ANALYSIS_ID, SHARE_LINK_ID))
                .thenReturn(Optional.of(alreadyRevoked));

        transaction.revoke(ANALYSIS_ID, REGISTERED_OWNER, SHARE_LINK_ID);

        assertEquals(originalRevokedAt, alreadyRevoked.revokedAt());
        verify(shareLinkRepository, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void locksTheAnalysisForTheWholeRevocationTransaction() throws NoSuchMethodException {
        assertTrue(RevokeShareLinkTransaction.class
                .getMethod("revoke", UUID.class, AnalysisOwner.class, UUID.class)
                .isAnnotationPresent(Transactional.class));
    }

    private static ShareLink activeLink() {
        return new ShareLink(SHARE_LINK_ID, ANALYSIS_ID, "a".repeat(64), CREATED_AT, NOW.plusSeconds(3600), null);
    }
}
