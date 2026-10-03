package io.github.guillermodubon.invoward.document.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.exception.DocumentNotFoundException;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.application.model.DocumentDownload;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.service.GetDocumentService;
import io.github.guillermodubon.invoward.document.application.service.ListDocumentsService;
import io.github.guillermodubon.invoward.document.application.service.UploadDocumentService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DocumentReadControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant URL_EXPIRES_AT = Instant.parse("2026-10-01T12:05:00Z");

    private final UploadDocumentService uploadService = mock(UploadDocumentService.class);
    private final ListDocumentsService listDocumentsService = mock(ListDocumentsService.class);
    private final GetDocumentService getDocumentService = mock(GetDocumentService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final UUID analysisId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
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
    void listReturnsOnlySafeMetadataAndRecordsActivityAfterSuccessfulRead() throws Exception {
        Document reference = document(UUID.randomUUID(), registeredOwner, DocumentRole.REFERENCE);
        Document invoice = document(UUID.randomUUID(), registeredOwner, DocumentRole.INVOICE);
        when(listDocumentsService.list(analysisId, registeredOwner)).thenReturn(List.of(reference, invoice));

        String body = mockMvc.perform(get("/api/analyses/{analysisId}/documents", analysisId)
                        .cookie(new Cookie("INVOWARD_GUEST", UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$[0].role").value("REFERENCE"))
                .andExpect(jsonPath("$[1].role").value("INVOICE"))
                .andExpect(jsonPath("$[0].downloadUrl").doesNotExist())
                .andExpect(jsonPath("$[0].sha256").doesNotExist())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(reference.sha256()));
        assertFalse(body.contains(reference.storageKey()));
        assertFalse(body.contains(userId.toString()));
        assertFalse(body.contains(analysisId.toString()));
        verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        verify(listDocumentsService).list(analysisId, registeredOwner);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verifyNoInteractions(getDocumentService);
    }

    @Test
    void detailReturnsSafeMetadataAndShortLivedUrlWithNoStore() throws Exception {
        GuestSessionOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), URL_EXPIRES_AT.plusSeconds(60));
        Document document = document(documentId, guestOwner, DocumentRole.REFERENCE);
        URI signedUrl = URI.create("https://storage.example.test/object?signature=temporary-secret");
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(guestOwner));
        when(getDocumentService.get(analysisId, documentId, guestOwner))
                .thenReturn(new DocumentDownload(document, new PresignedDownload(signedUrl, URL_EXPIRES_AT)));

        String body = mockMvc.perform(get(
                        "/api/analyses/{analysisId}/documents/{documentId}", analysisId, documentId)
                        .cookie(new Cookie("INVOWARD_GUEST", guestOwner.guestSessionId().toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.id").value(documentId.toString()))
                .andExpect(jsonPath("$.role").value("REFERENCE"))
                .andExpect(jsonPath("$.originalFilename").value("quote.pdf"))
                .andExpect(jsonPath("$.downloadUrl").value(signedUrl.toString()))
                .andExpect(jsonPath("$.downloadUrlExpiresAt").value(URL_EXPIRES_AT.toString()))
                .andExpect(jsonPath("$.sha256").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.guestSessionId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(document.sha256()));
        assertFalse(body.contains(document.storageKey()));
        assertFalse(body.contains(guestOwner.guestSessionId().toString()));
        verify(getDocumentService).get(analysisId, documentId, guestOwner);
        verify(ownerResolver).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(listDocumentsService);
    }

    @Test
    void parentAnalysisNotOwnedReturnsAnalysisNotFoundWithoutCallingReadServices() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{analysisId}/documents", analysisId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Analysis was not found."));
        mockMvc.perform(get(
                        "/api/analyses/{analysisId}/documents/{documentId}", analysisId, documentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Analysis was not found."));

        verifyNoInteractions(listDocumentsService, getDocumentService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    @Test
    void authenticatedIdentityTakesPrecedenceOverGuestCookie() throws Exception {
        when(ownerResolver.resolveForRead(eq(authenticatedIdentity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(listDocumentsService.list(analysisId, registeredOwner)).thenReturn(List.of());
        authenticate(authenticatedIdentity);

        mockMvc.perform(get("/api/analyses/{analysisId}/documents", analysisId)
                        .cookie(new Cookie("INVOWARD_GUEST", UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(ownerResolver).resolveForRead(eq(authenticatedIdentity), any(HttpServletRequest.class));
        verify(listDocumentsService).list(analysisId, registeredOwner);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
    }

    @Test
    void documentOutsideAnalysisMapsToGenericDocumentNotFound() throws Exception {
        when(getDocumentService.get(analysisId, documentId, registeredOwner))
                .thenThrow(new DocumentNotFoundException());

        mockMvc.perform(get(
                        "/api/analyses/{analysisId}/documents/{documentId}", analysisId, documentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Document was not found."));

        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    @Test
    void readFailureDoesNotTouchGuestSessionAndDoesNotExposeStorageDetails() throws Exception {
        GuestSessionOwner guestOwner = new GuestSessionOwner(UUID.randomUUID(), URL_EXPIRES_AT.plusSeconds(60));
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(guestOwner));
        when(getDocumentService.get(analysisId, documentId, guestOwner))
                .thenThrow(new DocumentStorageException(DocumentStorageException.Failure.UNAVAILABLE));

        mockMvc.perform(get(
                        "/api/analyses/{analysisId}/documents/{documentId}", analysisId, documentId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOCUMENT_STORAGE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("temporary-secret"))));

        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    private Document document(UUID id, AnalysisOwner owner, DocumentRole role) {
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
}
