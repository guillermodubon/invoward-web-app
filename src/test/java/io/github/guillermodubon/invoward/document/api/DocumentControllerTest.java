package io.github.guillermodubon.invoward.document.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentSizeLimitExceededException;
import io.github.guillermodubon.invoward.document.application.exception.AnalysisDocumentsLockedException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentRoleAlreadyExistsException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException;
import io.github.guillermodubon.invoward.document.application.model.IncomingDocumentUpload;
import io.github.guillermodubon.invoward.document.application.service.UploadDocumentService;
import io.github.guillermodubon.invoward.document.application.service.GetDocumentService;
import io.github.guillermodubon.invoward.document.application.service.ListDocumentsService;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.test.web.servlet.MockMvc;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DocumentControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-01T12:00:00Z");

    private final UploadDocumentService uploadService = mock(UploadDocumentService.class);
    private final ListDocumentsService listDocumentsService = mock(ListDocumentsService.class);
    private final GetDocumentService getDocumentService = mock(GetDocumentService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final UUID analysisId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final RegisteredUserOwner registeredOwner = new RegisteredUserOwner(userId);
    private final AuthenticatedIdentity authenticatedIdentity = new CurrentAccount(
            userId, "Test User", "test@example.com", true, UserStatus.ACTIVE);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        mockMvc = standaloneSetup(new DocumentController(
                        uploadService, listDocumentsService, getDocumentService, ownerResolver))
                .setControllerAdvice(new DocumentExceptionHandler(), new DocumentMultipartExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadReturnsCreatedLocationAndOnlySafeDocumentMetadata() throws Exception {
        UUID documentId = UUID.randomUUID();
        Document document = uploadedDocument(documentId, registeredOwner, DocumentRole.REFERENCE);
        byte[] content = new byte[128];
        byte[] pdfHeader = "%PDF-1.7 test bytes".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(pdfHeader, 0, content, 0, pdfHeader.length);
        when(ownerResolver.resolveForRead(eq(authenticatedIdentity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(uploadService.upload(eq(analysisId), eq(registeredOwner), eq(DocumentRole.REFERENCE),
                any(IncomingDocumentUpload.class))).thenReturn(document);
        authenticate(authenticatedIdentity);

        var result = mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .file(new MockMultipartFile("file", "quote.pdf", "application/pdf", content))
                        .param("role", "REFERENCE")
                        .cookie(new Cookie("INVOWARD_GUEST", UUID.randomUUID().toString())))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        "/api/analyses/" + analysisId + "/documents/" + documentId))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").value(documentId.toString()))
                .andExpect(jsonPath("$.role").value("REFERENCE"))
                .andExpect(jsonPath("$.detectedType").isEmpty())
                .andExpect(jsonPath("$.confirmedType").isEmpty())
                .andExpect(jsonPath("$.originalFilename").value("quote.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(content.length))
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(document.sha256()));
        assertFalse(body.contains(document.storageKey()));
        assertFalse(body.contains(userId.toString()));
        assertFalse(body.contains("analysisId"));

        var incoming = org.mockito.ArgumentCaptor.forClass(IncomingDocumentUpload.class);
        verify(uploadService).upload(eq(analysisId), eq(registeredOwner), eq(DocumentRole.REFERENCE), incoming.capture());
        IncomingDocumentUpload received = incoming.getValue();
        assertEquals("quote.pdf", received.originalFilename());
        assertEquals("application/pdf", received.reportedContentType());
        assertEquals(content.length, received.reportedSizeBytes());
        try (InputStream stream = received.content()) {
            assertArrayEquals(content, stream.readAllBytes());
        }
        verify(ownerResolver).resolveForRead(eq(authenticatedIdentity), any(HttpServletRequest.class));
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
    }

    @Test
    void existingGuestOwnerCanUploadWithoutIssuingOrCreatingGuestCookie() throws Exception {
        UUID guestId = UUID.randomUUID();
        GuestSessionOwner guestOwner = new GuestSessionOwner(guestId, CREATED_AT.plusSeconds(3600));
        Document document = uploadedDocument(UUID.randomUUID(), guestOwner, DocumentRole.INVOICE);
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(guestOwner));
        when(uploadService.upload(eq(analysisId), eq(guestOwner), eq(DocumentRole.INVOICE),
                any(IncomingDocumentUpload.class))).thenReturn(document);

        mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf", new byte[]{1}))
                        .param("role", "INVOICE")
                        .cookie(new Cookie("INVOWARD_GUEST", guestId.toString())))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void unresolvedExistingOwnerReturnsAnalysisNotFoundBeforeCallingUploadService() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .param("role", "INVALID"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Analysis was not found."));

        verifyNoInteractions(uploadService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    @Test
    void missingInvalidOrRepeatedRoleAndMissingOrMultipleFilesReturnSafeBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .file(file("file", "quote.pdf"))
                        .param("role", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_REQUEST"));

        mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .file(file("file", "quote.pdf")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_REQUEST"));

        mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .file(file("file", "quote.pdf"))
                        .param("role", "REFERENCE", "INVOICE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_REQUEST"));

        mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .param("role", "REFERENCE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_REQUEST"));

        mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .file(file("file", "quote.pdf"))
                        .file(file("file", "second.pdf"))
                        .param("role", "REFERENCE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_REQUEST"));

        verifyNoInteractions(uploadService);
    }

    @Test
    void uploadMapsApplicationFailuresToSafeStableHttpResponses() throws Exception {
        when(uploadService.upload(eq(analysisId), eq(registeredOwner), eq(DocumentRole.REFERENCE),
                any(IncomingDocumentUpload.class)))
                .thenThrow(new DocumentRoleAlreadyExistsException())
                .thenThrow(new AnalysisDocumentSizeLimitExceededException())
                .thenThrow(new AnalysisDocumentsLockedException())
                .thenThrow(new DocumentStorageException(DocumentStorageException.Failure.UNAVAILABLE))
                .thenThrow(new DocumentUploadValidationException(
                        DocumentUploadValidationException.Failure.TYPE_MISMATCH))
                .thenThrow(new MaxUploadSizeExceededException(100))
                .thenThrow(new MultipartException(
                        "Multipart parser rejected the request", new MaxUploadSizeExceededException(100)));

        assertError("DOCUMENT_ROLE_ALREADY_EXISTS", 409);
        assertError("ANALYSIS_DOCUMENT_SIZE_LIMIT_EXCEEDED", 413);
        assertError("ANALYSIS_DOCUMENTS_LOCKED", 409);
        assertError("DOCUMENT_STORAGE_UNAVAILABLE", 503);
        assertError("DOCUMENT_TYPE_MISMATCH", 415);
        assertError("DOCUMENT_TOO_LARGE", 413);
        assertError("DOCUMENT_TOO_LARGE", 413);
    }

    @Test
    void multipartStreamClosesWhenUploadServiceRejectsBeforeValidation() throws Exception {
        TrackingMultipartFile file = new TrackingMultipartFile();
        when(uploadService.upload(eq(analysisId), eq(registeredOwner), eq(DocumentRole.REFERENCE),
                any(IncomingDocumentUpload.class))).thenThrow(new DocumentRoleAlreadyExistsException());

        mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .file(file)
                        .param("role", "REFERENCE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ROLE_ALREADY_EXISTS"));

        assertTrue(file.streamClosed.get());
    }

    private void assertError(String code, int status) throws Exception {
        mockMvc.perform(multipart("/api/analyses/{analysisId}/documents", analysisId)
                        .file(file("file", "quote.pdf"))
                        .param("role", "REFERENCE"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private/secret"))));
    }

    private static MockMultipartFile file(String partName, String filename) {
        return new MockMultipartFile(partName, filename, "application/pdf", new byte[]{1, 2, 3});
    }

    private Document uploadedDocument(UUID id, AnalysisOwner owner, DocumentRole role) {
        return Document.createUploaded(
                id,
                analysisId,
                role,
                role == DocumentRole.REFERENCE ? "quote.pdf" : "invoice.pdf",
                "application/pdf",
                128,
                2,
                "a".repeat(64),
                "private/secret-storage-key",
                owner instanceof GuestSessionOwner guestOwner ? guestOwner.expiresAt() : null,
                CREATED_AT);
    }

    private static void authenticate(AuthenticatedIdentity identity) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    private static final class TrackingMultipartFile extends MockMultipartFile {

        private final AtomicBoolean streamClosed = new AtomicBoolean();

        private TrackingMultipartFile() {
            super("file", "quote.pdf", "application/pdf", new byte[]{1, 2, 3});
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream delegate = super.getInputStream();
            return new FilterInputStream(delegate) {
                @Override
                public void close() throws IOException {
                    streamClosed.set(true);
                    super.close();
                }
            };
        }
    }
}
