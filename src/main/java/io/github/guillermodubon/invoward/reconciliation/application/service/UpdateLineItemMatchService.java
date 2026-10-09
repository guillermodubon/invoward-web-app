package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchSetView;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualLineItemMatchUpdate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/** Applies a manual decision and returns the refreshed persisted match-set view. */
@Service
public class UpdateLineItemMatchService {

    private final UpdateLineItemMatchTransaction transaction;
    private final GetLineItemMatchesService getLineItemMatchesService;

    public UpdateLineItemMatchService(
            UpdateLineItemMatchTransaction transaction,
            GetLineItemMatchesService getLineItemMatchesService) {
        this.transaction = Objects.requireNonNull(transaction);
        this.getLineItemMatchesService = Objects.requireNonNull(getLineItemMatchesService);
    }

    public LineItemMatchSetView update(
            UUID analysisId,
            UUID matchId,
            AnalysisOwner owner,
            ManualLineItemMatchUpdate command) {
        transaction.update(analysisId, matchId, owner, command);
        return getLineItemMatchesService.get(analysisId, owner);
    }
}
