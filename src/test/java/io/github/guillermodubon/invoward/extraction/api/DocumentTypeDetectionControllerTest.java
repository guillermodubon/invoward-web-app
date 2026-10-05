package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.model.DetectedDocumentType;
import io.github.guillermodubon.invoward.extraction.application.service.DetectDocumentTypesService;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DocumentTypeDetectionControllerTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID REFERENCE_ID = UUID.randomUUID();
    private static final UUID INVOICE_ID = UUID.randomUUID();
    private static final UUID GUEST_ID = UUID.randomUUID();

    private final DetectDocumentTypesService detectService = mock(DetectDocumentTypesService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GuestSessionOwner guestOwner = new GuestSessionOwner(
            GUEST_ID, Instant.parse("2026-10-05T00:00:00Z"));
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new DocumentTypeDetectionController(detectService, ownerResolver))
                .setControllerAdvice(new ExtractionExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(guestOwner));
    }

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void returnsOnlyPersistedSafeDetectionFieldsInReferenceThenInvoiceOrder() throws Exception {
        List<DetectedDocumentType> detections = List.of(
                new DetectedDocumentType(REFERENCE_ID, DocumentRole.REFERENCE, DocumentType.PURCHASE_ORDER),
                new DetectedDocumentType(INVOICE_ID, DocumentRole.INVOICE, DocumentType.INVOICE));
        when(detectService.detect(ANALYSIS_ID, guestOwner)).thenReturn(detections);

        String body = mockMvc.perform(post("/api/analyses/{analysisId}/documents/detect-types", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].documentId").value(REFERENCE_ID.toString()))
                .andExpect(jsonPath("$[0].role").value("REFERENCE"))
                .andExpect(jsonPath("$[0].detectedType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$[1].documentId").value(INVOICE_ID.toString()))
                .andExpect(jsonPath("$[1].role").value("INVOICE"))
                .andExpect(jsonPath("$[1].detectedType").value("INVOICE"))
                .andReturn().getResponse().getContentAsString();

        JsonNode first = objectMapper.readTree(body).get(0);
        assertEquals(3, first.size());
        assertFalse(body.contains(GUEST_ID.toString()));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains("model"));

        org.mockito.InOrder order = inOrder(ownerResolver, detectService);
        order.verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        order.verify(detectService).detect(ANALYSIS_ID, guestOwner);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void unresolvedExistingOwnerReturnsSameNotFoundWithoutCreatingOrTouchingGuestSession() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{analysisId}/documents/detect-types", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(detectService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
    }

    @Test
    void mapsApplicationAndProviderFailuresToSafeStableResponses() throws Exception {
        when(detectService.detect(ANALYSIS_ID, guestOwner))
                .thenThrow(new DocumentsRequiredException())
                .thenThrow(new DocumentClassificationConflictException())
                .thenThrow(new DocumentClassificationInvalidException())
                .thenThrow(new DocumentIntelligenceException(DocumentIntelligenceException.Failure.INVALID_RESPONSE))
                .thenThrow(new DocumentIntelligenceException(DocumentIntelligenceException.Failure.RATE_LIMITED))
                .thenThrow(new DocumentStorageException(DocumentStorageException.Failure.NOT_FOUND));

        assertSafeError(409, "DOCUMENTS_REQUIRED", "A reference document and an invoice are required.");
        assertSafeError(409, "EXTRACTION_CONFLICT",
                "Document classification is not available in the current Analysis state.");
        assertSafeError(502, "AI_RESPONSE_INVALID", "Document intelligence returned an invalid response.");
        assertSafeError(502, "AI_RESPONSE_INVALID", "Document intelligence returned an invalid response.");
        assertSafeError(503, "AI_PROVIDER_UNAVAILABLE", "Document intelligence is temporarily unavailable.");
        assertSafeError(503, "DOCUMENT_STORAGE_UNAVAILABLE", "Document storage is unavailable.");

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void malformedAnalysisIdReturnsSafeBadRequest() throws Exception {
        mockMvc.perform(post("/api/analyses/not-a-uuid/documents/detect-types"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(ownerResolver, detectService);
    }

    private void assertSafeError(int statusCode, String code, String message) throws Exception {
        String body = mockMvc.perform(post("/api/analyses/{analysisId}/documents/detect-types", ANALYSIS_ID))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("provider stack"));
        assertFalse(body.contains("api-key"));
        assertFalse(body.contains("secret"));
    }
}
