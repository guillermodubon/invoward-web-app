package io.github.guillermodubon.invoward.processing.infrastructure;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
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
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessingWorkerTest {

    @Mock private ClaimProcessingJobTransaction claimTransaction;
    @Mock private FailProcessingJobTransaction failTransaction;
    @Mock private ProcessingFailureClassifier failureClassifier;
    @Mock private DetectDocumentTypesService detectDocumentTypesService;
    @Mock private RunDocumentExtractionService runDocumentExtractionService;
    @Mock private RunLineItemMatchingService runLineItemMatchingService;
    @Mock private ReconcileAnalysisService reconcileAnalysisService;
    @Mock private GenerateReportService generateReportService;

    private ProcessingWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ProcessingWorker(
                claimTransaction, failTransaction, failureClassifier,
                detectDocumentTypesService, runDocumentExtractionService, runLineItemMatchingService,
                reconcileAnalysisService, generateReportService);
    }

    @Test
    void claimDispatchesExactlyOneExistingStageUseCase() {
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        for (AnalysisStatus stage : List.of(
                AnalysisStatus.CLASSIFYING,
                AnalysisStatus.EXTRACTING,
                AnalysisStatus.MATCHING,
                AnalysisStatus.RECONCILING,
                AnalysisStatus.GENERATING_REPORT)) {
            UUID analysisId = UUID.randomUUID();
            when(claimTransaction.claim(analysisId))
                    .thenReturn(Optional.of(new ProcessingClaim(analysisId, owner, stage, 1)));

            worker.process(analysisId);

            verifyStageOnly(stage, analysisId, owner);
            clearInvocations(detectDocumentTypesService, runDocumentExtractionService,
                    runLineItemMatchingService, reconcileAnalysisService, generateReportService);
        }
    }

    @Test
    void missingClaimDoesNotInvokeAnyStageOrFailureCollaborator() {
        UUID analysisId = UUID.randomUUID();
        when(claimTransaction.claim(analysisId)).thenReturn(Optional.empty());

        worker.process(analysisId);

        assertNoStageOrFailureCollaboratorInvoked();
    }

    @Test
    void nonMachineStageCannotBecomeAClaimOrInvokeExternalWork() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProcessingClaim(
                        UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()),
                        AnalysisStatus.AWAITING_CONFIRMATION, 1))
                .isInstanceOf(IllegalArgumentException.class);
        UUID analysisId = UUID.randomUUID();
        when(claimTransaction.claim(analysisId)).thenReturn(Optional.empty());

        worker.process(analysisId);

        assertNoStageOrFailureCollaboratorInvoked();
    }

    @Test
    void classifiedStageFailureIsPersistedAgainstItsExactClaim() {
        UUID analysisId = UUID.randomUUID();
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        ProcessingClaim claim = new ProcessingClaim(analysisId, owner, AnalysisStatus.CLASSIFYING, 2);
        ProcessingFailure safeFailure = new ProcessingFailure(
                "AI_PROVIDER_UNAVAILABLE", "provider unavailable", "Processing is temporarily unavailable.", true);
        when(claimTransaction.claim(analysisId)).thenReturn(Optional.of(claim));
        when(failureClassifier.classify(any(IllegalStateException.class))).thenReturn(Optional.of(safeFailure));
        org.mockito.Mockito.doThrow(new IllegalStateException("raw provider detail"))
                .when(detectDocumentTypesService).detect(analysisId, owner);

        worker.process(analysisId);

        verify(failureClassifier).classify(any(IllegalStateException.class));
        verify(failTransaction).fail(claim, safeFailure);
        verify(runDocumentExtractionService, never()).run(any(), any());
        verify(runLineItemMatchingService, never()).run(any(), any());
        verify(reconcileAnalysisService, never()).reconcile(any(), any());
        verify(generateReportService, never()).generate(any(), any());
    }

    @Test
    void reportStorageFailureIsClassifiedAndPersistedAgainstTheClaimedReportStage() {
        UUID analysisId = UUID.randomUUID();
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        ProcessingClaim claim = new ProcessingClaim(analysisId, owner, AnalysisStatus.GENERATING_REPORT, 1);
        ReportStorageException storageFailure =
                new ReportStorageException(ReportStorageException.Failure.UNAVAILABLE);
        ProcessingFailure safeFailure = new ProcessingFailure(
                "REPORT_STORAGE_UNAVAILABLE", "Report storage operation failed.",
                "The report could not be stored.", true);
        when(claimTransaction.claim(analysisId)).thenReturn(Optional.of(claim));
        when(failureClassifier.classify(storageFailure)).thenReturn(Optional.of(safeFailure));
        org.mockito.Mockito.doThrow(storageFailure).when(generateReportService).generate(analysisId, owner);

        worker.process(analysisId);

        verify(generateReportService).generate(analysisId, owner);
        verify(failureClassifier).classify(storageFailure);
        verify(failTransaction).fail(claim, safeFailure);
        verify(detectDocumentTypesService, never()).detect(any(), any());
        verify(runDocumentExtractionService, never()).run(any(), any());
        verify(runLineItemMatchingService, never()).run(any(), any());
        verify(reconcileAnalysisService, never()).reconcile(any(), any());
    }

    private void assertNoStageOrFailureCollaboratorInvoked() {
        verify(detectDocumentTypesService, never()).detect(any(), any());
        verify(runDocumentExtractionService, never()).run(any(), any());
        verify(runLineItemMatchingService, never()).run(any(), any());
        verify(reconcileAnalysisService, never()).reconcile(any(), any());
        verify(generateReportService, never()).generate(any(), any());
        verify(failureClassifier, never()).classify(any());
        verify(failTransaction, never()).fail(any(), any());
    }

    private void verifyStageOnly(AnalysisStatus stage, UUID analysisId, AnalysisOwner owner) {
        switch (stage) {
            case CLASSIFYING -> {
                verify(detectDocumentTypesService).detect(analysisId, owner);
                verify(runDocumentExtractionService, never()).run(any(), any());
                verify(runLineItemMatchingService, never()).run(any(), any());
                verify(reconcileAnalysisService, never()).reconcile(any(), any());
                verify(generateReportService, never()).generate(any(), any());
            }
            case EXTRACTING -> {
                verify(runDocumentExtractionService).run(analysisId, owner);
                verify(detectDocumentTypesService, never()).detect(any(), any());
                verify(runLineItemMatchingService, never()).run(any(), any());
                verify(reconcileAnalysisService, never()).reconcile(any(), any());
                verify(generateReportService, never()).generate(any(), any());
            }
            case MATCHING -> {
                verify(runLineItemMatchingService).run(analysisId, owner);
                verify(detectDocumentTypesService, never()).detect(any(), any());
                verify(runDocumentExtractionService, never()).run(any(), any());
                verify(reconcileAnalysisService, never()).reconcile(any(), any());
                verify(generateReportService, never()).generate(any(), any());
            }
            case RECONCILING -> {
                verify(reconcileAnalysisService).reconcile(analysisId, owner);
                verify(detectDocumentTypesService, never()).detect(any(), any());
                verify(runDocumentExtractionService, never()).run(any(), any());
                verify(runLineItemMatchingService, never()).run(any(), any());
                verify(generateReportService, never()).generate(any(), any());
            }
            case GENERATING_REPORT -> {
                verify(generateReportService).generate(analysisId, owner);
                verify(detectDocumentTypesService, never()).detect(any(), any());
                verify(runDocumentExtractionService, never()).run(any(), any());
                verify(runLineItemMatchingService, never()).run(any(), any());
                verify(reconcileAnalysisService, never()).reconcile(any(), any());
            }
            default -> throw new AssertionError("Unexpected test stage " + stage);
        }
    }
}
