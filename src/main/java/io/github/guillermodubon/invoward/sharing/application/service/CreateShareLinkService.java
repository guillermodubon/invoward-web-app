package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.sharing.application.exception.DuplicateShareTokenHashException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkCreationException;
import io.github.guillermodubon.invoward.sharing.application.model.CreatedShareLink;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkExpiryChoice;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;

import java.util.Objects;
import java.util.UUID;

/** Creates a link through a separate transaction for each bounded token-collision attempt. */
public class CreateShareLinkService {

    private final CreateShareLinkTransaction transaction;

    public CreateShareLinkService(CreateShareLinkTransaction transaction) {
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    }

    /** Returns only after the transaction commits; token collisions are retried at most twice. */
    public CreatedShareLink create(UUID analysisId, AnalysisOwner owner, ShareLinkExpiryChoice expiresIn) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(expiresIn, "expiresIn must not be null");

        for (int attempt = 0; attempt < ShareTokenGenerator.MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return transaction.create(analysisId, owner, expiresIn);
            } catch (DuplicateShareTokenHashException collision) {
                if (attempt + 1 == ShareTokenGenerator.MAX_GENERATION_ATTEMPTS) {
                    throw new ShareLinkCreationException();
                }
            }
        }
        throw new ShareLinkCreationException();
    }
}
