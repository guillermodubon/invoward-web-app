package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionPreparation;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionRequestTypes;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;

import java.util.Objects;
import java.util.UUID;

/** Coordinates provider preparation and the short atomic persistence transaction. */
public final class StartExtractionService {

    private final PrepareExtractionService preparationService;
    private final PersistExtractionTransaction persistenceTransaction;

    public StartExtractionService(
            PrepareExtractionService preparationService,
            PersistExtractionTransaction persistenceTransaction) {
        this.preparationService = Objects.requireNonNull(preparationService);
        this.persistenceTransaction = Objects.requireNonNull(persistenceTransaction);
    }

    public ExtractionReview start(
            UUID analysisId,
            AnalysisOwner owner,
            ExtractionRequestTypes requestedTypes) {
        ExtractionPreparation preparation = preparationService.prepare(analysisId, owner, requestedTypes);
        if (preparation instanceof ExtractionPreparation.ExistingDraft existingDraft) {
            return existingDraft.review();
        }
        ExtractionPreparation.Ready ready = (ExtractionPreparation.Ready) preparation;
        return persistenceTransaction.persist(analysisId, owner, ready);
    }
}
