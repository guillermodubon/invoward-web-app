package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentSizeLimitExceededException;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentsLockedException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentRoleAlreadyExistsException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.model.IncomingDocumentUpload;
import io.github.guillermodubon.invoward.document.application.model.StorageObjectUpload;
import io.github.guillermodubon.invoward.document.application.model.ValidatedDocumentUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.application.port.DocumentUploadValidator;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Coordinates authorized validation, private storage, and the short metadata transaction. */
public final class UploadDocumentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadDocumentService.class);

    private final GetAnalysisService analysisService;
    private final DocumentRepository documentRepository;
    private final DocumentUploadValidator uploadValidator;
    private final DocumentStorage documentStorage;
    private final UploadDocumentTransaction transaction;
    private final DocumentStorageKeyGenerator storageKeyGenerator;
    private final Clock clock;
    private final long maxCombinedSizeBytes;

    public UploadDocumentService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository,
            DocumentUploadValidator uploadValidator,
            DocumentStorage documentStorage,
            UploadDocumentTransaction transaction,
            DocumentStorageKeyGenerator storageKeyGenerator,
            Clock clock,
            long maxCombinedSizeBytes) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService must not be null");
        this.documentRepository = Objects.requireNonNull(documentRepository, "documentRepository must not be null");
        this.uploadValidator = Objects.requireNonNull(uploadValidator, "uploadValidator must not be null");
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.storageKeyGenerator = Objects.requireNonNull(
                storageKeyGenerator, "storageKeyGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maxCombinedSizeBytes <= 0) {
            throw new IllegalArgumentException("maxCombinedSizeBytes must be positive");
        }
        this.maxCombinedSizeBytes = maxCombinedSizeBytes;
    }

    public Document upload(
            UUID analysisId,
            AnalysisOwner owner,
            DocumentRole role,
            IncomingDocumentUpload incoming) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");

        Analysis analysis = analysisService.get(analysisId, owner);
        validateUploadWindow(analysis);
        if (documentRepository.existsByAnalysisIdAndRole(analysisId, role)) {
            throw new DocumentRoleAlreadyExistsException();
        }

        ValidatedDocumentUpload validated = uploadValidator.validate(incoming);
        try {
            validateCombinedSize(analysisId, validated.sizeBytes());
            String storageKey = storageKeyGenerator.generate(owner, analysisId, validated.fileFormat());
            Document document = Document.createUploaded(
                    UUID.randomUUID(),
                    analysisId,
                    role,
                    validated.originalFilename(),
                    validated.contentType(),
                    validated.sizeBytes(),
                    validated.pageCount(),
                    validated.sha256(),
                    storageKey,
                    analysis.expiresAt(),
                    clock.instant());

            documentStorage.store(new StorageObjectUpload(
                    storageKey,
                    validated.temporaryFile(),
                    validated.contentType(),
                    validated.sizeBytes()));
            try {
                return transaction.persist(owner, document);
            } catch (RuntimeException persistenceFailure) {
                compensateStoredObject(storageKey);
                throw persistenceFailure;
            }
        } finally {
            cleanupStagedUpload(validated);
        }
    }

    private static void validateUploadWindow(Analysis analysis) {
        if (analysis.status() != AnalysisStatus.CREATED
                && analysis.status() != AnalysisStatus.UPLOADING) {
            throw new AnalysisDocumentsLockedException();
        }
    }

    private void validateCombinedSize(UUID analysisId, long incomingBytes) {
        long existingBytes = documentRepository.sumSizeBytesByAnalysisId(analysisId);
        if (existingBytes > maxCombinedSizeBytes
                || incomingBytes > maxCombinedSizeBytes - existingBytes) {
            throw new AnalysisDocumentSizeLimitExceededException();
        }
    }

    private void compensateStoredObject(String storageKey) {
        try {
            documentStorage.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            DocumentStorageException.Failure failureType = cleanupFailure instanceof DocumentStorageException storageFailure
                    ? storageFailure.failure()
                    : DocumentStorageException.Failure.UNKNOWN;
            LOGGER.warn("operation=document_upload_compensation result=failure failureType={}", failureType);
        }
    }

    private static void cleanupStagedUpload(ValidatedDocumentUpload validated) {
        try {
            validated.close();
        } catch (IOException cleanupFailure) {
            LOGGER.warn("operation=document_upload_temp_cleanup result=failure failureType=IO");
        }
    }
}
