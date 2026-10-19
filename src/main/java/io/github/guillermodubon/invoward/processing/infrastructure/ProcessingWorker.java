package io.github.guillermodubon.invoward.processing.infrastructure;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.extraction.application.service.DetectDocumentTypesService;
import io.github.guillermodubon.invoward.extraction.application.service.RunDocumentExtractionService;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingClaim;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingFailure;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingFailureClassifier;
import io.github.guillermodubon.invoward.processing.application.service.ClaimProcessingJobTransaction;
import io.github.guillermodubon.invoward.processing.application.service.FailProcessingJobTransaction;
import io.github.guillermodubon.invoward.reconciliation.application.service.ReconcileAnalysisService;
import io.github.guillermodubon.invoward.reconciliation.application.service.RunLineItemMatchingService;
import io.github.guillermodubon.invoward.reporting.application.service.GenerateReportService;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Claims and executes exactly one persisted machine stage for one Analysis. */
@Component
public final class ProcessingWorker {

    private final ClaimProcessingJobTransaction claimTransaction;
    private final FailProcessingJobTransaction failTransaction;
    private final ProcessingFailureClassifier failureClassifier;
    private final DetectDocumentTypesService detectDocumentTypesService;
    private final RunDocumentExtractionService runDocumentExtractionService;
    private final RunLineItemMatchingService runLineItemMatchingService;
    private final ReconcileAnalysisService reconcileAnalysisService;
    private final GenerateReportService generateReportService;

    public ProcessingWorker(
            ClaimProcessingJobTransaction claimTransaction,
            FailProcessingJobTransaction failTransaction,
            ProcessingFailureClassifier failureClassifier,
            DetectDocumentTypesService detectDocumentTypesService,
            RunDocumentExtractionService runDocumentExtractionService,
            RunLineItemMatchingService runLineItemMatchingService,
            ReconcileAnalysisService reconcileAnalysisService,
            GenerateReportService generateReportService) {
        this.claimTransaction = Objects.requireNonNull(claimTransaction);
        this.failTransaction = Objects.requireNonNull(failTransaction);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
        this.detectDocumentTypesService = Objects.requireNonNull(detectDocumentTypesService);
        this.runDocumentExtractionService = Objects.requireNonNull(runDocumentExtractionService);
        this.runLineItemMatchingService = Objects.requireNonNull(runLineItemMatchingService);
        this.reconcileAnalysisService = Objects.requireNonNull(reconcileAnalysisService);
        this.generateReportService = Objects.requireNonNull(generateReportService);
    }

    /** A claim is committed before the selected application use case is invoked. */
    public void process(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Optional<ProcessingClaim> claim = claimTransaction.claim(analysisId);
        if (claim.isEmpty()) {
            return;
        }

        ProcessingClaim acquired = claim.orElseThrow();
        try {
            executeOneStage(acquired);
        } catch (RuntimeException stageFailure) {
            Optional<ProcessingFailure> classified = failureClassifier.classify(stageFailure);
            classified.ifPresent(failure -> failTransaction.fail(acquired, failure));
        }
    }

    private void executeOneStage(ProcessingClaim claim) {
        UUID analysisId = claim.analysisId();
        AnalysisOwner owner = claim.owner();
        switch (claim.stage()) {
            case CLASSIFYING -> detectDocumentTypesService.detect(analysisId, owner);
            case EXTRACTING -> runDocumentExtractionService.run(analysisId, owner);
            case MATCHING -> runLineItemMatchingService.run(analysisId, owner);
            case RECONCILING -> reconcileAnalysisService.reconcile(analysisId, owner);
            case GENERATING_REPORT -> generateReportService.generate(analysisId, owner);
            default -> throw new IllegalStateException("Claimed stage is not executable by the worker");
        }
    }

}
