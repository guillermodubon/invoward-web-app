package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentSizeLimitExceededException;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentsLockedException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentRoleAlreadyExistsException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.model.DocumentFileFormat;
import io.github.guillermodubon.invoward.document.application.model.IncomingDocumentUpload;
import io.github.guillermodubon.invoward.document.application.model.ValidatedDocumentUpload;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.application.port.DocumentUploadValidator;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.support.storage.FakeDocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadDocumentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final byte[] PDF_BYTES = "%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final String SHA_256 = "a".repeat(64);
    private static final UUID ANALYSIS_ID = UUID.randomUUID();

    @TempDir
    private Path temporaryDirectory;

    private final AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
    private final Analysis analysis = Analysis.create(
            ANALYSIS_ID, owner, PriceTolerance.exactMatch(), NOW.minusSeconds(1));
    private final GetAnalysisService analysisService = mock(GetAnalysisService.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentUploadValidator uploadValidator = mock(DocumentUploadValidator.class);
    private final FakeDocumentStorage storage = new FakeDocumentStorage(Clock.fixed(NOW, ZoneOffset.UTC));
    private final UploadDocumentTransaction transaction = mock(UploadDocumentTransaction.class);
    private UploadDocumentService service;
    private Path stagedPath;

    @BeforeEach
    void setUp() {
        service = new UploadDocumentService(
                analysisService,
                documentRepository,
                uploadValidator,
                storage,
                transaction,
                new DocumentStorageKeyGenerator(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                1_024);
        when(analysisService.get(ANALYSIS_ID, owner)).thenReturn(analysis);
        when(documentRepository.existsByAnalysisIdAndRole(ANALYSIS_ID, DocumentRole.REFERENCE)).thenReturn(false);
        when(documentRepository.sumSizeBytesByAnalysisId(ANALYSIS_ID)).thenReturn(0L);
        when(transaction.persist(eq(owner), any(Document.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void validatesStoresThenPersistsMetadataAndCleansTemporaryFile() throws IOException {
        ValidatedDocumentUpload validated = stagedUpload(PDF_BYTES.length);
        when(uploadValidator.validate(any(IncomingDocumentUpload.class))).thenReturn(validated);
        when(transaction.persist(eq(owner), any(Document.class))).thenAnswer(invocation -> {
            assertEquals(1, storage.storedObjectCount(), "the object must be stored before DB persistence");
            assertTrue(Files.exists(stagedPath), "the staged source must remain available during persistence");
            return invocation.getArgument(1);
        });

        Document uploaded = service.upload(ANALYSIS_ID, owner, DocumentRole.REFERENCE, incoming());

        assertEquals(ANALYSIS_ID, uploaded.analysisId());
        assertEquals(DocumentRole.REFERENCE, uploaded.role());
        assertEquals("reference.pdf", uploaded.originalFilename());
        assertEquals("application/pdf", uploaded.contentType());
        assertEquals((long) PDF_BYTES.length, uploaded.sizeBytes());
        assertEquals(1, uploaded.pageCount());
        assertEquals(SHA_256, uploaded.sha256());
        assertTrue(uploaded.storageKey().matches(
                "users/" + ownerId() + "/analyses/" + ANALYSIS_ID + "/documents/[0-9a-f-]{36}\\.pdf"));
        assertFalse(uploaded.storageKey().contains(uploaded.originalFilename()));
        assertEquals(PDF_BYTES.length, storage.storedBytes(uploaded.storageKey()).orElseThrow().length);
        assertEquals("application/pdf", storage.storedContentType(uploaded.storageKey()).orElseThrow());
        assertFalse(Files.exists(stagedPath));
        verify(transaction).persist(owner, uploaded);
    }

    @Test
    void storageFailureDoesNotCallTransactionAndCleansTemporaryFile() throws IOException {
        ValidatedDocumentUpload validated = stagedUpload(PDF_BYTES.length);
        when(uploadValidator.validate(any(IncomingDocumentUpload.class))).thenReturn(validated);
        ((FakeDocumentStorage) storage).configureFailure(
                FakeDocumentStorage.Operation.STORE, DocumentStorageException.Failure.UNAVAILABLE);

        DocumentStorageException failure = assertThrows(DocumentStorageException.class,
                () -> service.upload(ANALYSIS_ID, owner, DocumentRole.REFERENCE, incoming()));

        assertEquals(DocumentStorageException.Failure.UNAVAILABLE, failure.failure());
        verify(transaction, never()).persist(any(), any());
        assertEquals(0, ((FakeDocumentStorage) storage).storedObjectCount());
        assertFalse(Files.exists(stagedPath));
    }

    @Test
    void databaseFailureCompensatesStoredObjectAndPreservesOriginalFailure() throws IOException {
        ValidatedDocumentUpload validated = stagedUpload(PDF_BYTES.length);
        when(uploadValidator.validate(any(IncomingDocumentUpload.class))).thenReturn(validated);
        RuntimeException databaseFailure = new IllegalStateException("database operation failed");
        when(transaction.persist(eq(owner), any(Document.class))).thenThrow(databaseFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.upload(ANALYSIS_ID, owner, DocumentRole.REFERENCE, incoming()));

        assertSame(databaseFailure, thrown);
        assertEquals(0, ((FakeDocumentStorage) storage).storedObjectCount());
        assertFalse(Files.exists(stagedPath));
    }

    @Test
    void roleRaceAfterStorageIsMappedByTransactionAndCompensated() throws IOException {
        ValidatedDocumentUpload validated = stagedUpload(PDF_BYTES.length);
        when(uploadValidator.validate(any(IncomingDocumentUpload.class))).thenReturn(validated);
        DocumentRoleAlreadyExistsException roleConflict = new DocumentRoleAlreadyExistsException();
        when(transaction.persist(eq(owner), any(Document.class))).thenThrow(roleConflict);

        DocumentRoleAlreadyExistsException thrown = assertThrows(DocumentRoleAlreadyExistsException.class,
                () -> service.upload(ANALYSIS_ID, owner, DocumentRole.REFERENCE, incoming()));

        assertSame(roleConflict, thrown);
        assertEquals(0, ((FakeDocumentStorage) storage).storedObjectCount());
        assertFalse(Files.exists(stagedPath));
    }

    @Test
    void failedCompensationDoesNotReplaceOriginalDatabaseFailure() throws IOException {
        ValidatedDocumentUpload validated = stagedUpload(PDF_BYTES.length);
        when(uploadValidator.validate(any(IncomingDocumentUpload.class))).thenReturn(validated);
        ((FakeDocumentStorage) storage).configureFailure(
                FakeDocumentStorage.Operation.DELETE, DocumentStorageException.Failure.UNAVAILABLE);
        DocumentRoleAlreadyExistsException roleConflict = new DocumentRoleAlreadyExistsException();
        when(transaction.persist(eq(owner), any(Document.class))).thenThrow(roleConflict);

        DocumentRoleAlreadyExistsException thrown = assertThrows(DocumentRoleAlreadyExistsException.class,
                () -> service.upload(ANALYSIS_ID, owner, DocumentRole.REFERENCE, incoming()));

        assertSame(roleConflict, thrown);
        assertEquals(1, ((FakeDocumentStorage) storage).storedObjectCount());
        assertFalse(Files.exists(stagedPath));
    }

    @Test
    void unauthorizedAnalysisIsRejectedBeforeValidationOrStorage() {
        when(analysisService.get(ANALYSIS_ID, owner)).thenThrow(new AnalysisNotFoundException());

        assertThrows(AnalysisNotFoundException.class,
                () -> service.upload(ANALYSIS_ID, owner, DocumentRole.REFERENCE, incoming()));

        verifyNoInteractions(uploadValidator, transaction);
        assertEquals(0, ((FakeDocumentStorage) storage).storedObjectCount());
    }

    @Test
    void occupiedRoleIsRejectedBeforeValidationOrStorage() {
        when(documentRepository.existsByAnalysisIdAndRole(ANALYSIS_ID, DocumentRole.REFERENCE)).thenReturn(true);

        assertThrows(DocumentRoleAlreadyExistsException.class,
                () -> service.upload(ANALYSIS_ID, owner, DocumentRole.REFERENCE, incoming()));

        verifyNoInteractions(uploadValidator, transaction);
        assertEquals(0, ((FakeDocumentStorage) storage).storedObjectCount());
    }

    @Test
    void lockedAnalysisIsRejectedBeforeValidationOrStorage() {
        when(analysisService.get(ANALYSIS_ID, owner)).thenReturn(analysisWithStatus(AnalysisStatus.CLASSIFYING));

        assertThrows(AnalysisDocumentsLockedException.class,
                () -> service.upload(ANALYSIS_ID, owner, DocumentRole.REFERENCE, incoming()));

        verifyNoInteractions(uploadValidator, transaction);
        assertEquals(0, ((FakeDocumentStorage) storage).storedObjectCount());
    }

    @Test
    void combinedLimitUsesValidatedSizeAndRejectsBeforeStorage() throws IOException {
        ValidatedDocumentUpload validated = stagedUpload(125);
        when(uploadValidator.validate(any(IncomingDocumentUpload.class))).thenReturn(validated);
        when(documentRepository.sumSizeBytesByAnalysisId(ANALYSIS_ID)).thenReturn(900L);

        assertThrows(AnalysisDocumentSizeLimitExceededException.class,
                () -> service.upload(ANALYSIS_ID, owner, DocumentRole.REFERENCE, incoming()));

        verifyNoInteractions(transaction);
        assertEquals(0, ((FakeDocumentStorage) storage).storedObjectCount());
        assertFalse(Files.exists(stagedPath));
    }

    private ValidatedDocumentUpload stagedUpload(long sizeBytes) throws IOException {
        stagedPath = temporaryDirectory.resolve("opaque-staged-upload.upload");
        Files.write(stagedPath, PDF_BYTES);
        return ValidatedDocumentUpload.staged(
                stagedPath,
                "reference.pdf",
                DocumentFileFormat.PDF,
                sizeBytes,
                1,
                SHA_256);
    }

    private static IncomingDocumentUpload incoming() {
        return new IncomingDocumentUpload(
                "reference.pdf",
                "application/pdf",
                1,
                new ByteArrayInputStream(PDF_BYTES));
    }

    private Analysis analysisWithStatus(AnalysisStatus status) {
        return new Analysis(
                analysis.id(),
                analysis.owner(),
                status,
                AnalysisReviewStatus.PENDING,
                analysis.reconciliationStatus(),
                analysis.supplierName(),
                analysis.supplierKey(),
                analysis.referenceType(),
                analysis.referenceNumber(),
                analysis.invoiceNumber(),
                analysis.currency(),
                analysis.referenceTotal(),
                analysis.invoicedTotal(),
                analysis.difference(),
                analysis.priceTolerance(),
                analysis.retryable(),
                analysis.failureCode(),
                analysis.failureUserMessage(),
                analysis.version(),
                analysis.completedAt(),
                analysis.expiresAt(),
                analysis.createdAt(),
                analysis.updatedAt());
    }

    private UUID ownerId() {
        return ((RegisteredUserOwner) owner).userId();
    }
}
