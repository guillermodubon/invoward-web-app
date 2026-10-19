package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;

import java.util.Objects;
import java.util.UUID;

/** Runs the extraction worker stage and atomically persists both human-review drafts. */
public final class RunDocumentExtractionService {

    private final PrepareExtractionService preparationService;
    private final PersistExtractionTransaction persistenceTransaction;

    public RunDocumentExtractionService(
            PrepareExtractionService preparationService,
            PersistExtractionTransaction persistenceTransaction) {
        this.preparationService = Objects.requireNonNull(preparationService);
        this.persistenceTransaction = Objects.requireNonNull(persistenceTransaction);
    }

    public ExtractionReview run(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        return persistenceTransaction.persist(
                analysisId, owner, preparationService.prepareForWorker(analysisId, owner));
    }
}
