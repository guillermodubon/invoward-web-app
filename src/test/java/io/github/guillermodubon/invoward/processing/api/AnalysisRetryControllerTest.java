package io.github.guillermodubon.invoward.processing.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.processing.application.exception.ProcessingRetryNotAllowedException;
import io.github.guillermodubon.invoward.processing.application.service.RetryAnalysisProcessingService;
import jakarta.servlet.http.HttpServletRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AnalysisRetryControllerTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("8f2c8a9a-680b-4c44-8d40-913ecb14df5c");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    private final RetryAnalysisProcessingService retryService = mock(RetryAnalysisProcessingService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final MockMvc mockMvc = standaloneSetup(new AnalysisRetryController(retryService, ownerResolver))
            .setControllerAdvice(new ProcessingApiExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

    @Test
    void retryableOwnerReceivesAcceptedStatusLocationAndNoStoreResponse() throws Exception {
        AnalysisOwner owner = new GuestSessionOwner(UUID.randomUUID(), NOW.plusSeconds(3600));
        AnalysisStatusSnapshot snapshot = new AnalysisStatusSnapshot(
                ANALYSIS_ID, AnalysisStatus.CLASSIFYING, AnalysisJobStatus.QUEUED,
                AnalysisStatus.CLASSIFYING, false, null, null, NOW);
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.of(owner));
        when(retryService.retry(ANALYSIS_ID, owner)).thenReturn(snapshot);

        mockMvc.perform(post("/api/analyses/{id}/retry", ANALYSIS_ID))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/analyses/" + ANALYSIS_ID + "/status"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.status").value("CLASSIFYING"))
                .andExpect(jsonPath("$.jobStatus").value("QUEUED"))
                .andExpect(jsonPath("$.currentStage").value("CLASSIFYING"))
                .andExpect(jsonPath("$.retryable").value(false));

        verify(retryService).retry(ANALYSIS_ID, owner);
        verify(ownerResolver).recordSuccessfulActivity(owner);
    }

    @Test
    void unresolvedGuestReturnsNotFoundWithoutCreatingGuestOrCallingRetry() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{id}/retry", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(retryService);
        verify(ownerResolver, never()).resolveForCreate(any(), any(HttpServletRequest.class));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void nonOwnedAnalysisReturnsSameSafeNotFoundResponse() throws Exception {
        AnalysisOwner owner = new GuestSessionOwner(UUID.randomUUID(), NOW.plusSeconds(3600));
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.of(owner));
        when(retryService.retry(ANALYSIS_ID, owner)).thenThrow(new AnalysisNotFoundException());

        mockMvc.perform(post("/api/analyses/{id}/retry", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void nonretryableAnalysisReturnsStableConflictWithoutInternalDetails() throws Exception {
        AnalysisOwner owner = new GuestSessionOwner(UUID.randomUUID(), NOW.plusSeconds(3600));
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.of(owner));
        when(retryService.retry(ANALYSIS_ID, owner)).thenThrow(new ProcessingRetryNotAllowedException());

        String body = mockMvc.perform(post("/api/analyses/{id}/retry", ANALYSIS_ID))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_RETRY_NOT_ALLOWED",
                         "message":"Analysis retry is not allowed in its current state."}
                        """))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("ProcessingRetryNotAllowedException"));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }
}
