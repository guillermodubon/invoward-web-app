package io.github.guillermodubon.invoward.processing.infrastructure.failure;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.extraction.application.exception.AnalysisExtractionNotAllowedException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentMaterializationException;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionOutputException;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingFailure;
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisMatchingNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisReconciliationNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchingInputNotReadyException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputChangedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputNotReadyException;
import io.github.guillermodubon.invoward.reporting.application.exception.AnalysisReportNotAllowedException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportConflictException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportGenerationException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportInputNotReadyException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultProcessingFailureClassifierTest {

    private static final String INJECTED_PROVIDER_DETAIL = "provider-secret-sentinel-7e91";

    private final DefaultProcessingFailureClassifier classifier = new DefaultProcessingFailureClassifier();

    @Test
    void classifiesEveryDocumentIntelligenceFailureAsSpecified() {
        for (DocumentIntelligenceException.Failure kind : DocumentIntelligenceException.Failure.values()) {
            ProcessingFailure failure = classify(new DocumentIntelligenceException(kind));
            switch (kind) {
                case UNAVAILABLE, UNKNOWN -> assertClassification(failure, "AI_PROVIDER_UNAVAILABLE", true);
                case RATE_LIMITED -> assertClassification(failure, "AI_RATE_LIMITED", true);
                case AUTHENTICATION -> assertClassification(failure, "AI_CONFIGURATION_INVALID", false);
                case INVALID_RESPONSE -> assertClassification(failure, "AI_RESPONSE_INVALID", false);
            }
        }
    }

    @Test
    void classifiesEveryDocumentAndReportStorageFailure() {
        for (DocumentStorageException.Failure kind : DocumentStorageException.Failure.values()) {
            ProcessingFailure failure = classify(new DocumentStorageException(kind));
            assertClassification(failure, "DOCUMENT_STORAGE_UNAVAILABLE",
                    kind == DocumentStorageException.Failure.UNAVAILABLE
                            || kind == DocumentStorageException.Failure.UNKNOWN);
        }
        for (ReportStorageException.Failure kind : ReportStorageException.Failure.values()) {
            ProcessingFailure failure = classify(new ReportStorageException(kind));
            assertClassification(failure, "REPORT_STORAGE_UNAVAILABLE",
                    kind == ReportStorageException.Failure.UNAVAILABLE
                            || kind == ReportStorageException.Failure.UNKNOWN);
        }
    }

    @Test
    void classifiesMaterializationReportGenerationAndTransientDatabaseFailures() {
        assertClassification(classify(new DocumentMaterializationException()),
                "DOCUMENT_STORAGE_UNAVAILABLE", true);
        assertClassification(classify(new ReportGenerationException(
                new IllegalStateException(INJECTED_PROVIDER_DETAIL))), "REPORT_GENERATION_FAILED", false);
        assertClassification(classify(new CannotAcquireLockException(INJECTED_PROVIDER_DETAIL)),
                "DATABASE_TEMPORARILY_UNAVAILABLE", true);
        assertClassification(classify(new IllegalStateException(
                "wrapper", new TransientDataAccessResourceException(INJECTED_PROVIDER_DETAIL))),
                "DATABASE_TEMPORARILY_UNAVAILABLE", true);
    }

    @Test
    void nontransientBusinessConflictsAndInvalidInputsAreNotRetryable() {
        List<RuntimeException> conflicts = List.of(
                new AnalysisExtractionNotAllowedException(),
                new DocumentClassificationConflictException(),
                new AnalysisMatchingNotAllowedException(),
                new MatchingInputNotReadyException(),
                new MatchSetConflictException(),
                new AnalysisReconciliationNotAllowedException(),
                new ReconciliationInputNotReadyException(),
                new ReconciliationInputChangedException(),
                new ReconciliationConflictException(),
                new AnalysisReportNotAllowedException(),
                new ReportInputNotReadyException(),
                new ReportConflictException());

        conflicts.forEach(exception -> assertClassification(
                classify(exception), "PROCESSING_STATE_CONFLICT", false));
        assertClassification(classify(new InvalidExtractionOutputException()), "PROCESSING_INPUT_INVALID", false);
        assertClassification(classify(new DocumentClassificationInvalidException()), "AI_RESPONSE_INVALID", false);
    }

    @Test
    void unknownRuntimeFailuresAreSafeAndTaskRejectionIsNotAStageFailure() {
        ProcessingFailure failure = classify(new IllegalStateException(INJECTED_PROVIDER_DETAIL));
        assertClassification(failure, "PROCESSING_FAILED", false);
        assertSafe(failure);

        Optional<ProcessingFailure> rejected = classifier.classify(
                new TaskRejectedException(INJECTED_PROVIDER_DETAIL));
        assertTrue(rejected.isEmpty());

        Optional<ProcessingFailure> wrappedRejection = classifier.classify(
                new IllegalStateException("dispatch wrapper", new TaskRejectedException(INJECTED_PROVIDER_DETAIL)));
        assertTrue(wrappedRejection.isEmpty());
    }

    private ProcessingFailure classify(RuntimeException exception) {
        return classifier.classify(exception).orElseThrow();
    }

    private static void assertClassification(ProcessingFailure failure, String code, boolean retryable) {
        assertEquals(code, failure.code());
        assertEquals(retryable, failure.retryableCandidate());
        assertSafe(failure);
    }

    private static void assertSafe(ProcessingFailure failure) {
        assertTrue(failure.internalMessage().length() <= 500);
        assertTrue(failure.userMessage().length() <= 500);
        assertFalse(failure.internalMessage().isBlank());
        assertFalse(failure.userMessage().isBlank());
        assertFalse(failure.internalMessage().chars().anyMatch(Character::isISOControl));
        assertFalse(failure.userMessage().chars().anyMatch(Character::isISOControl));
        assertFalse(failure.internalMessage().contains(INJECTED_PROVIDER_DETAIL));
        assertFalse(failure.userMessage().contains(INJECTED_PROVIDER_DETAIL));
        assertFalse(failure.toString().contains(INJECTED_PROVIDER_DETAIL));
    }
}
