package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisProcessingNotAllowedException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.service.RequestAnalysisProcessingService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportNotFoundException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDownload;
import io.github.guillermodubon.invoward.reporting.application.service.GetGeneratedReportService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ReportControllerTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID GUEST_ID = UUID.randomUUID();
    private static final Instant GUEST_EXPIRY = Instant.parse("2026-10-16T12:00:00Z");

    private final RequestAnalysisProcessingService requestService = mock(RequestAnalysisProcessingService.class);
    private final GetGeneratedReportService getService = mock(GetGeneratedReportService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final AnalysisOwner guestOwner = new GuestSessionOwner(GUEST_ID, GUEST_EXPIRY);
    private final AnalysisOwner registeredOwner = new RegisteredUserOwner(USER_ID);
    private final AuthenticatedIdentity identity = new CurrentAccount(
            USER_ID, "Test User", "test@example.com", true, UserStatus.ACTIVE);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new ReportController(requestService, getService, ownerResolver))
                .setControllerAdvice(new ReportingExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(guestOwner));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pendingReportRequestReturns202AndStatusLocationWithoutRunningReportWorkInline() throws Exception {
        when(requestService.request(ANALYSIS_ID, guestOwner, AnalysisStatus.GENERATING_REPORT))
                .thenReturn(snapshot(AnalysisStatus.GENERATING_REPORT, AnalysisJobStatus.QUEUED));

        String body = mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/analyses/" + ANALYSIS_ID + "/status"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.status").value("GENERATING_REPORT"))
                .andExpect(jsonPath("$.jobStatus").value("QUEUED"))
                .andExpect(jsonPath("$.currentStage").value("GENERATING_REPORT"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("downloadUrl"));
        assertFalse(body.contains("storageKey"));
        var order = inOrder(ownerResolver, requestService);
        order.verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        order.verify(requestService).request(ANALYSIS_ID, guestOwner, AnalysisStatus.GENERATING_REPORT);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(getService);
    }

    @Test
    void completedReportRequestReturns200StatusWithoutLocation() throws Exception {
        when(requestService.request(ANALYSIS_ID, guestOwner, AnalysisStatus.GENERATING_REPORT))
                .thenReturn(snapshot(AnalysisStatus.COMPLETED, AnalysisJobStatus.COMPLETED));

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.jobStatus").value("COMPLETED"));

        verify(requestService).request(ANALYSIS_ID, guestOwner, AnalysisStatus.GENERATING_REPORT);
        verify(ownerResolver).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(getService);
    }

    @Test
    void postUsesAuthenticatedOwnerBeforeGuestCookie() throws Exception {
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(requestService.request(ANALYSIS_ID, registeredOwner, AnalysisStatus.GENERATING_REPORT))
                .thenReturn(snapshot(AnalysisStatus.GENERATING_REPORT, AnalysisJobStatus.RUNNING));
        authenticate(identity);

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("GENERATING_REPORT"));

        verify(ownerResolver).resolveForRead(eq(identity), any(HttpServletRequest.class));
        verify(requestService).request(ANALYSIS_ID, registeredOwner, AnalysisStatus.GENERATING_REPORT);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(getService);
    }

    @Test
    void unresolvedOwnerReturns404BeforeRequestingWork() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(requestService, getService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void ownerScopedAnalysisNotFoundUsesUniform404() throws Exception {
        when(requestService.request(ANALYSIS_ID, guestOwner, AnalysisStatus.GENERATING_REPORT))
                .thenThrow(new AnalysisNotFoundException());

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verifyNoInteractions(getService);
    }

    @Test
    void invalidStageIsSafeConflict() throws Exception {
        when(requestService.request(ANALYSIS_ID, guestOwner, AnalysisStatus.GENERATING_REPORT))
                .thenThrow(new AnalysisProcessingNotAllowedException());

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_PROCESSING_NOT_ALLOWED","message":"Analysis processing is not allowed in its current state."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verifyNoInteractions(getService);
    }

    @Test
    void invalidAnalysisIdReturnsSafeBadRequestBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/analyses/not-a-uuid/report"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(requestService, getService);
    }

    @Test
    void getReturnsPersistedMetadataAndFreshSignedUrlWithoutCaching() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner)).thenReturn(report());

        String body = mockMvc.perform(get("/api/analyses/{analysisId}/report", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(report().id().toString()))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(321))
                .andExpect(jsonPath("$.generatedAt").value("2026-10-15T12:00:00Z"))
                .andExpect(jsonPath("$.downloadUrl").value("https://objects.example.test/signed-report"))
                .andExpect(jsonPath("$.downloadUrlExpiresAt").value("2026-10-15T12:05:00Z"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("userId"));
        var order = inOrder(ownerResolver, getService);
        order.verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        order.verify(getService).get(ANALYSIS_ID, guestOwner);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(requestService);
    }

    @Test
    void getUsesAuthenticatedOwnerBeforeGuestCookie() throws Exception {
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(getService.get(ANALYSIS_ID, registeredOwner)).thenReturn(report());
        authenticate(identity);

        mockMvc.perform(get("/api/analyses/{analysisId}/report", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk());

        verify(getService).get(ANALYSIS_ID, registeredOwner);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(requestService);
    }

    @Test
    void getUnresolvedOwnerReturnsAnalysisNotFoundBeforeReadingReport() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(getService, requestService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void getMissingReportReturnsStableNotFound() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner)).thenThrow(new ReportNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"REPORT_NOT_FOUND","message":"Report was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verifyNoInteractions(requestService);
    }

    @Test
    void getStorageFailureIsSafeAndDoesNotExposeSignedUrlOrProviderDetails() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner))
                .thenThrow(new ReportStorageException(ReportStorageException.Failure.UNAVAILABLE));

        String body = mockMvc.perform(get("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_STORAGE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("signed"));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verifyNoInteractions(requestService);
    }

    @Test
    void invalidGetAnalysisIdReturnsSafeBadRequestBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/analyses/not-a-uuid/report"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(getService, requestService);
    }

    private static AnalysisStatusSnapshot snapshot(AnalysisStatus status, AnalysisJobStatus jobStatus) {
        return new AnalysisStatusSnapshot(
                ANALYSIS_ID, status, jobStatus, status, false, null, null,
                Instant.parse("2026-10-16T12:00:00Z"));
    }

    private static ReportDownload report() {
        return new ReportDownload(
                UUID.fromString("d70341d2-2e45-47c0-a571-5ae115fb9165"),
                "application/pdf",
                321,
                Instant.parse("2026-10-15T12:00:00Z"),
                null,
                URI.create("https://objects.example.test/signed-report"),
                Instant.parse("2026-10-15T12:05:00Z"));
    }

    private static void authenticate(AuthenticatedIdentity authenticatedIdentity) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(authenticatedIdentity, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
