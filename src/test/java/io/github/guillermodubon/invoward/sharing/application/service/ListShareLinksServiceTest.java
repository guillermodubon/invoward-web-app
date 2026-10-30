package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.sharing.application.exception.InvalidShareLinkQueryException;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkListItem;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkListing;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkPage;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import io.github.guillermodubon.invoward.sharing.domain.ShareLinkStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListShareLinksServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final RegisteredUserOwner REGISTERED_OWNER = new RegisteredUserOwner(UUID.randomUUID());

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final ShareLinkRepository shareLinkRepository = mock(ShareLinkRepository.class);
    private final ListShareLinksService service = new ListShareLinksService(
            analysisRepository, shareLinkRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void returnsPagedSafeMetadataWithCurrentDerivedStatusAndDeterministicPageMetadata() {
        Analysis analysis = Analysis.create(ANALYSIS_ID, REGISTERED_OWNER, PriceTolerance.exactMatch(), NOW);
        UUID activeId = UUID.randomUUID();
        UUID expiredId = UUID.randomUUID();
        ShareLink active = link(activeId, ANALYSIS_ID, "a".repeat(64), NOW.minusSeconds(10), NOW.plusSeconds(10), null);
        ShareLink expiredAtBoundary = link(
                expiredId, ANALYSIS_ID, "b".repeat(64), NOW.minusSeconds(20), NOW, null);
        when(analysisRepository.findOwnedById(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.of(analysis));
        when(shareLinkRepository.findPageByAnalysisId(ANALYSIS_ID, 1, 2))
                .thenReturn(new ShareLinkPage(List.of(active, expiredAtBoundary), 1, 2, 5));

        ShareLinkListing result = service.list(ANALYSIS_ID, REGISTERED_OWNER, 1, 2);

        assertEquals(List.of(
                new ShareLinkListItem(activeId, active.createdAt(), active.expiresAt(), null, ShareLinkStatus.ACTIVE),
                new ShareLinkListItem(expiredId, expiredAtBoundary.createdAt(), expiredAtBoundary.expiresAt(), null,
                        ShareLinkStatus.EXPIRED)), result.links());
        assertEquals(1, result.page());
        assertEquals(2, result.size());
        assertEquals(5, result.totalElements());
        assertEquals(3, result.totalPages());
        assertTrue(result.hasNext());
        assertFalse(result.toString().contains("a".repeat(64)));
        assertFalse(result.toString().contains("b".repeat(64)));
        verify(analysisRepository).findOwnedById(ANALYSIS_ID, REGISTERED_OWNER, NOW);
        verify(shareLinkRepository).findPageByAnalysisId(ANALYSIS_ID, 1, 2);
        verify(shareLinkRepository, never()).create(org.mockito.ArgumentMatchers.any());
        verify(shareLinkRepository, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokedStatusTakesPrecedenceOverExpiry() {
        Instant createdAt = NOW.minusSeconds(100);
        ShareLink revokedAndExpired = link(UUID.randomUUID(), ANALYSIS_ID, "c".repeat(64), createdAt,
                NOW.minusSeconds(1), createdAt.plusSeconds(10));
        Analysis analysis = Analysis.create(ANALYSIS_ID, REGISTERED_OWNER, PriceTolerance.exactMatch(), NOW);
        when(analysisRepository.findOwnedById(ANALYSIS_ID, REGISTERED_OWNER, NOW))
                .thenReturn(Optional.of(analysis));
        when(shareLinkRepository.findPageByAnalysisId(ANALYSIS_ID, 0, 20))
                .thenReturn(new ShareLinkPage(List.of(revokedAndExpired), 0, 20, 1));

        assertEquals(ShareLinkStatus.REVOKED, service.list(ANALYSIS_ID, REGISTERED_OWNER, 0, 20)
                .links().getFirst().status());
    }

    @Test
    void authorizesParentBeforeAnyShareLinkRepositoryAccessForRegisteredOrGuestOwner() {
        GuestSessionOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), NOW.minusSeconds(1));
        when(analysisRepository.findOwnedById(ANALYSIS_ID, REGISTERED_OWNER, NOW)).thenReturn(Optional.empty());
        when(analysisRepository.findOwnedById(ANALYSIS_ID, guestOwner, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> service.list(ANALYSIS_ID, REGISTERED_OWNER, 0, 20));
        assertThrows(AnalysisNotFoundException.class,
                () -> service.list(ANALYSIS_ID, guestOwner, 0, 20));

        verify(analysisRepository).findOwnedById(ANALYSIS_ID, REGISTERED_OWNER, NOW);
        verify(analysisRepository).findOwnedById(ANALYSIS_ID, guestOwner, NOW);
        verifyNoInteractions(shareLinkRepository);
    }

    @Test
    void rejectsOutOfBoundsPagesBeforeRepositoryAccess() {
        assertThrows(InvalidShareLinkQueryException.class,
                () -> service.list(ANALYSIS_ID, REGISTERED_OWNER, -1, 20));
        assertThrows(InvalidShareLinkQueryException.class,
                () -> service.list(ANALYSIS_ID, REGISTERED_OWNER, ShareLinkPage.MAX_PAGE + 1, 20));
        assertThrows(InvalidShareLinkQueryException.class,
                () -> service.list(ANALYSIS_ID, REGISTERED_OWNER, 0, 0));
        assertThrows(InvalidShareLinkQueryException.class,
                () -> service.list(ANALYSIS_ID, REGISTERED_OWNER, 0, ShareLinkPage.MAX_SIZE + 1));

        verifyNoInteractions(analysisRepository, shareLinkRepository);
    }

    @Test
    void listingUseCaseIsMarkedReadOnly() throws NoSuchMethodException {
        assertTrue(ListShareLinksService.class
                .getMethod("list", UUID.class, AnalysisOwner.class, int.class, int.class)
                .getAnnotation(Transactional.class).readOnly());
    }

    private static ShareLink link(
            UUID id, UUID analysisId, String hash, Instant createdAt, Instant expiresAt, Instant revokedAt) {
        return new ShareLink(id, analysisId, hash, createdAt, expiresAt, revokedAt);
    }
}
