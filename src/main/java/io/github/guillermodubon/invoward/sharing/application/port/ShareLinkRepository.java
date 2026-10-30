package io.github.guillermodubon.invoward.sharing.application.port;

import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkPage;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations for share links, scoped by their parent Analysis where applicable. */
public interface ShareLinkRepository {

    ShareLink create(ShareLink shareLink);

    Optional<ShareLink> findByTokenHash(String tokenHash);

    Optional<ShareLink> findByAnalysisIdAndId(UUID analysisId, UUID shareLinkId);

    Optional<ShareLink> findByAnalysisIdAndIdForUpdate(UUID analysisId, UUID shareLinkId);

    long countActiveByAnalysisId(UUID analysisId, Instant now);

    ShareLinkPage findPageByAnalysisId(UUID analysisId, int page, int size);

    Optional<ShareLink> update(ShareLink shareLink);
}
