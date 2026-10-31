package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkNotFoundException;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Performs parent-first authorization and idempotent revocation in one short transaction. */
public class RevokeShareLinkTransaction {

    private final AnalysisRepository analysisRepository;
    private final ShareLinkRepository shareLinkRepository;
    private final Clock clock;

    public RevokeShareLinkTransaction(
            AnalysisRepository analysisRepository,
            ShareLinkRepository shareLinkRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository, "analysisRepository must not be null");
        this.shareLinkRepository = Objects.requireNonNull(shareLinkRepository, "shareLinkRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public void revoke(UUID analysisId, AnalysisOwner owner, UUID shareLinkId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(shareLinkId, "shareLinkId must not be null");

        Instant ownerLookupTime = clock.instant();
        Analysis analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, ownerLookupTime)
                .orElseThrow(AnalysisNotFoundException::new);
        Instant now = clock.instant();
        if (owner instanceof GuestSessionOwner guestOwner
                && (!guestOwner.expiresAt().isAfter(now)
                || analysis.expiresAt() == null
                || !analysis.expiresAt().isAfter(now))) {
            throw new AnalysisNotFoundException();
        }

        ShareLink shareLink = shareLinkRepository.findByAnalysisIdAndIdForUpdate(analysis.id(), shareLinkId)
                .orElseThrow(ShareLinkNotFoundException::new);
        if (shareLink.revokedAt() != null) {
            return;
        }

        ShareLink revoked = shareLink.revoke(now);
        shareLinkRepository.update(revoked).orElseThrow(ShareLinkNotFoundException::new);
    }
}
