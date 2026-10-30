package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.sharing.application.exception.InvalidShareLinkQueryException;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkListItem;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkListing;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkPage;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Reads the owner's persisted link metadata using database pagination and derives current status. */
public class ListShareLinksService {

    private final AnalysisRepository analysisRepository;
    private final ShareLinkRepository shareLinkRepository;
    private final Clock clock;

    public ListShareLinksService(
            AnalysisRepository analysisRepository,
            ShareLinkRepository shareLinkRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository, "analysisRepository must not be null");
        this.shareLinkRepository = Objects.requireNonNull(shareLinkRepository, "shareLinkRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional(readOnly = true)
    public ShareLinkListing list(UUID analysisId, AnalysisOwner owner, int page, int size) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        validateBounds(page, size);

        Instant now = clock.instant();
        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);

        ShareLinkPage persistedPage = shareLinkRepository.findPageByAnalysisId(analysis.id(), page, size);
        boolean parentIsActive = analysis.expiresAt() == null || now.isBefore(analysis.expiresAt());
        return new ShareLinkListing(
                persistedPage.content().stream()
                        .map(link -> toListItem(link, now, parentIsActive))
                        .toList(),
                persistedPage.page(),
                persistedPage.size(),
                persistedPage.totalElements());
    }

    private static ShareLinkListItem toListItem(ShareLink link, Instant now, boolean parentIsActive) {
        return new ShareLinkListItem(
                link.id(), link.createdAt(), link.expiresAt(), link.revokedAt(), link.statusAt(now, parentIsActive));
    }

    private static void validateBounds(int page, int size) {
        if (page < 0 || page > ShareLinkPage.MAX_PAGE || size < 1 || size > ShareLinkPage.MAX_SIZE) {
            throw new InvalidShareLinkQueryException();
        }
    }
}
