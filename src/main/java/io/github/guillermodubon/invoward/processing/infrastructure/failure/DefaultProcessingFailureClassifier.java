package io.github.guillermodubon.invoward.processing.infrastructure.failure;

import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.extraction.application.exception.AnalysisExtractionNotAllowedException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentMaterializationException;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionOutputException;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingFailure;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingFailureClassifier;
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
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Maps typed stage failures to stable categories without retaining exception text or causes. */
@Component
public final class DefaultProcessingFailureClassifier implements ProcessingFailureClassifier {

    private static final String AI_UNAVAILABLE_USER = "Document processing is temporarily unavailable.";
    private static final String STORAGE_USER = "A required file could not be accessed.";
    private static final String REPORT_STORAGE_USER = "The report could not be stored.";
    private static final String PROCESSING_STATE_USER = "The analysis is no longer ready for this processing stage.";
    private static final String PROCESSING_INPUT_USER = "The analysis contains invalid processing input.";

    @Override
    public Optional<ProcessingFailure> classify(RuntimeException exception) {
        Objects.requireNonNull(exception, "exception must not be null");

        if (hasCause(exception, TaskRejectedException.class)) {
            return Optional.empty();
        }

        if (exception instanceof DocumentIntelligenceException intelligenceException) {
            return Optional.of(classify(intelligenceException.failure()));
        }
        if (exception instanceof DocumentStorageException storageException) {
            return Optional.of(classifyDocumentStorage(storageException.failure()));
        }
        if (exception instanceof DocumentMaterializationException) {
            return Optional.of(failure(
                    "DOCUMENT_STORAGE_UNAVAILABLE",
                    "Private document materialization failed.",
                    STORAGE_USER,
                    true));
        }
        if (exception instanceof ReportStorageException storageException) {
            return Optional.of(classifyReportStorage(storageException.failure()));
        }
        if (exception instanceof ReportGenerationException) {
            return Optional.of(failure(
                    "REPORT_GENERATION_FAILED",
                    "Report generation failed.",
                    "The reconciliation report could not be generated.",
                    false));
        }
        if (exception instanceof DocumentClassificationInvalidException) {
            return Optional.of(failure(
                    "AI_RESPONSE_INVALID",
                    "Document classification response failed validation.",
                    "The document could not be classified safely.",
                    false));
        }
        if (exception instanceof InvalidExtractionOutputException) {
            return Optional.of(failure(
                    "PROCESSING_INPUT_INVALID",
                    "Extraction output failed validation.",
                    PROCESSING_INPUT_USER,
                    false));
        }
        if (isDeterministicStateConflict(exception)) {
            return Optional.of(failure(
                    "PROCESSING_STATE_CONFLICT",
                    "Processing preconditions were not satisfied.",
                    PROCESSING_STATE_USER,
                    false));
        }
        if (hasCause(exception, TransientDataAccessException.class)) {
            return Optional.of(failure(
                    "DATABASE_TEMPORARILY_UNAVAILABLE",
                    "A transient database operation failed.",
                    "Processing is temporarily unavailable.",
                    true));
        }

        return Optional.of(failure(
                "PROCESSING_FAILED",
                "An unexpected processing failure occurred.",
                "Processing could not be completed.",
                false));
    }

    private static ProcessingFailure classify(DocumentIntelligenceException.Failure failure) {
        return switch (failure) {
            case UNAVAILABLE, UNKNOWN -> failure(
                    "AI_PROVIDER_UNAVAILABLE",
                    "Document intelligence is unavailable.",
                    AI_UNAVAILABLE_USER,
                    true);
            case RATE_LIMITED -> failure(
                    "AI_RATE_LIMITED",
                    "Document intelligence request was rate limited.",
                    AI_UNAVAILABLE_USER,
                    true);
            case AUTHENTICATION -> failure(
                    "AI_CONFIGURATION_INVALID",
                    "Document intelligence configuration is invalid.",
                    AI_UNAVAILABLE_USER,
                    false);
            case INVALID_RESPONSE -> failure(
                    "AI_RESPONSE_INVALID",
                    "Document intelligence response failed validation.",
                    "The document could not be processed safely.",
                    false);
        };
    }

    private static ProcessingFailure classifyDocumentStorage(DocumentStorageException.Failure failure) {
        boolean retryable = failure == DocumentStorageException.Failure.UNAVAILABLE
                || failure == DocumentStorageException.Failure.UNKNOWN;
        return failure(
                "DOCUMENT_STORAGE_UNAVAILABLE",
                "Document storage operation failed.",
                STORAGE_USER,
                retryable);
    }

    private static ProcessingFailure classifyReportStorage(ReportStorageException.Failure failure) {
        boolean retryable = failure == ReportStorageException.Failure.UNAVAILABLE
                || failure == ReportStorageException.Failure.UNKNOWN;
        return failure(
                "REPORT_STORAGE_UNAVAILABLE",
                "Report storage operation failed.",
                REPORT_STORAGE_USER,
                retryable);
    }

    private static boolean isDeterministicStateConflict(RuntimeException exception) {
        return exception instanceof AnalysisExtractionNotAllowedException
                || exception instanceof DocumentClassificationConflictException
                || exception instanceof AnalysisMatchingNotAllowedException
                || exception instanceof MatchingInputNotReadyException
                || exception instanceof MatchSetConflictException
                || exception instanceof AnalysisReconciliationNotAllowedException
                || exception instanceof ReconciliationInputNotReadyException
                || exception instanceof ReconciliationInputChangedException
                || exception instanceof ReconciliationConflictException
                || exception instanceof AnalysisReportNotAllowedException
                || exception instanceof ReportInputNotReadyException
                || exception instanceof ReportConflictException;
    }

    private static ProcessingFailure failure(
            String code,
            String internalMessage,
            String userMessage,
            boolean retryableCandidate) {
        return new ProcessingFailure(code, internalMessage, userMessage, retryableCandidate);
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> causeType) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = failure;
        while (cause != null && visited.add(cause)) {
            if (causeType.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
