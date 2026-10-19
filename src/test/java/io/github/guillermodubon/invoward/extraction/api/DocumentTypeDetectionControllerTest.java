package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentClassificationConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentsRequiredException;
import io.github.guillermodubon.invoward.extraction.application.service.RequestDocumentTypeDetectionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
    private static final UUID GUEST_ID = UUID.randomUUID();
    private static final Instant UPDATED_AT = Instant.parse("2026-10-05T12:00:00Z");

    private final RequestDocumentTypeDetectionService requestService =
            mock(RequestDocumentTypeDetectionService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final GuestSessionOwner guestOwner = new GuestSessionOwner(
            GUEST_ID, Instant.parse("2026-10-06T00:00:00Z"));
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new DocumentTypeDetectionController(requestService, ownerResolver))
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
    void queuedClassificationReturnsAcceptedStatusAndPollingLocation() throws Exception {
        when(requestService.request(ANALYSIS_ID, guestOwner)).thenReturn(snapshot(
                AnalysisStatus.CLASSIFYING, AnalysisJobStatus.QUEUED, AnalysisStatus.CLASSIFYING));

        mockMvc.perform(post("/api/analyses/{analysisId}/documents/detect-types", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Location", "/api/analyses/" + ANALYSIS_ID + "/status"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.status").value("CLASSIFYING"))
                .andExpect(jsonPath("$.jobStatus").value("QUEUED"))
                .andExpect(jsonPath("$.currentStage").value("CLASSIFYING"))
                .andExpect(jsonPath("$.guestSessionId").doesNotExist());

        var order = inOrder(ownerResolver, requestService);
        order.verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        order.verify(requestService).request(ANALYSIS_ID, guestOwner);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void runningClassificationReturnsAcceptedWithoutChangingTheCommandContract() throws Exception {
        when(requestService.request(ANALYSIS_ID, guestOwner)).thenReturn(snapshot(
                AnalysisStatus.CLASSIFYING, AnalysisJobStatus.RUNNING, AnalysisStatus.CLASSIFYING));

        mockMvc.perform(post("/api/analyses/{analysisId}/documents/detect-types", ANALYSIS_ID))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/analyses/" + ANALYSIS_ID + "/status"))
                .andExpect(jsonPath("$.jobStatus").value("RUNNING"));
    }

    @Test
    void completedClassificationWaitingForUserReturnsOkWithoutLocation() throws Exception {
        when(requestService.request(ANALYSIS_ID, guestOwner)).thenReturn(snapshot(
                AnalysisStatus.CLASSIFYING, AnalysisJobStatus.WAITING_FOR_USER, AnalysisStatus.CLASSIFYING));

        mockMvc.perform(post("/api/analyses/{analysisId}/documents/detect-types", ANALYSIS_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("CLASSIFYING"))
                .andExpect(jsonPath("$.jobStatus").value("WAITING_FOR_USER"));
    }

    @Test
    void commandAfterClassificationHasPassedReturnsCurrentStatus() throws Exception {
        when(requestService.request(ANALYSIS_ID, guestOwner)).thenReturn(snapshot(
                AnalysisStatus.EXTRACTING, AnalysisJobStatus.QUEUED, AnalysisStatus.EXTRACTING));

        mockMvc.perform(post("/api/analyses/{analysisId}/documents/detect-types", ANALYSIS_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.status").value("EXTRACTING"));
    }

    @Test
    void unresolvedOwnerReturnsUniformNotFoundWithoutCallingCommandService() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{analysisId}/documents/detect-types", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(requestService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void invalidPrerequisitesMapToSafeConflictResponses() throws Exception {
        when(requestService.request(ANALYSIS_ID, guestOwner))
                .thenThrow(new DocumentsRequiredException())
                .thenThrow(new DocumentClassificationConflictException());

        assertSafeError("DOCUMENTS_REQUIRED", "A reference document and an invoice are required.");
        assertSafeError("EXTRACTION_CONFLICT",
                "Document classification is not available in the current Analysis state.");

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void malformedAnalysisIdReturnsSafeBadRequestBeforeOwnerResolution() throws Exception {
        mockMvc.perform(post("/api/analyses/not-a-uuid/documents/detect-types"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(ownerResolver, requestService);
    }

    private void assertSafeError(String code, String message) throws Exception {
        String body = mockMvc.perform(post("/api/analyses/{analysisId}/documents/detect-types", ANALYSIS_ID))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("provider stack"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("secret"));
    }

    private static AnalysisStatusSnapshot snapshot(
            AnalysisStatus status,
            AnalysisJobStatus jobStatus,
            AnalysisStatus currentStage) {
        return new AnalysisStatusSnapshot(
                ANALYSIS_ID, status, jobStatus, currentStage, false, null, null, UPDATED_AT);
    }
}
