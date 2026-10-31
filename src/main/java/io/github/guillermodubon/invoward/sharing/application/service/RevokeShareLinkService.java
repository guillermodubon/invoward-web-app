package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;

import java.util.Objects;
import java.util.UUID;

/** Application entry point for owner-authorized share-link revocation. */
public class RevokeShareLinkService {

    private final RevokeShareLinkTransaction transaction;

    public RevokeShareLinkService(RevokeShareLinkTransaction transaction) {
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    }

    public void revoke(UUID analysisId, AnalysisOwner owner, UUID shareLinkId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(shareLinkId, "shareLinkId must not be null");
        transaction.revoke(analysisId, owner, shareLinkId);
    }
}
