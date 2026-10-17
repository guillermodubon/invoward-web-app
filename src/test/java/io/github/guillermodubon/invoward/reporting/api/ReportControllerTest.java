package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.reporting.application.exception.AnalysisReportNotAllowedException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportConflictException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportGenerationException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportInputNotReadyException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportNotFoundException;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
import io.github.guillermodubon.invoward.reporting.application.model.GenerateReportResult;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDownload;
import io.github.guillermodubon.invoward.reporting.application.service.GenerateReportService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private final GenerateReportService generateService = mock(GenerateReportService.class);
    private final GetGeneratedReportService getService = mock(GetGeneratedReportService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final AnalysisOwner guestOwner = new GuestSessionOwner(GUEST_ID, GUEST_EXPIRY);
    private final RegisteredUserOwner registeredOwner = new RegisteredUserOwner(USER_ID);
    private final AuthenticatedIdentity identity = new CurrentAccount(
            USER_ID, "Test User", "test@example.com", true, UserStatus.ACTIVE);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new ReportController(generateService, getService, ownerResolver))
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
    void firstReportCreationReturns201LocationNoStoreAndSafeMetadata() throws Exception {
        when(generateService.generate(ANALYSIS_ID, guestOwner))
                .thenReturn(new GenerateReportResult(report(), true));

        String body = mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/analyses/" + ANALYSIS_ID + "/report"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(report().id().toString()))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(321))
                .andExpect(jsonPath("$.generatedAt").value("2026-10-15T12:00:00Z"))
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.downloadUrl").value("https://objects.example.test/signed-report"))
                .andExpect(jsonPath("$.downloadUrlExpiresAt").value("2026-10-15T12:05:00Z"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("createdByThisCall"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        var order = inOrder(ownerResolver, generateService);
        order.verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        order.verify(generateService).generate(ANALYSIS_ID, guestOwner);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void getReturnsPersistedMetadataAndFreshSignedUrlWithoutNoStoreCaching() throws Exception {
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
        verifyNoInteractions(generateService);
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
        verifyNoInteractions(generateService);
    }

    @Test
    void getUnresolvedOwnerReturnsAnalysisNotFoundBeforeReadingReport() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(getService, generateService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void getOwnerScopedAnalysisNotFoundDoesNotTouchGuestOrGenerateReport() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner)).thenThrow(new AnalysisNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verifyNoInteractions(generateService);
    }

    @Test
    void getMissingReportReturnsStableNotFoundAndDoesNotTouchGuest() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner)).thenThrow(new ReportNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"REPORT_NOT_FOUND","message":"Report was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verifyNoInteractions(generateService);
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
        verifyNoInteractions(generateService);
    }

    @Test
    void getInvalidAnalysisIdReturnsSafeBadRequestBeforeAnyServiceCall() throws Exception {
        mockMvc.perform(get("/api/analyses/not-a-uuid/report"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(getService, generateService);
    }

    @Test
    void existingImmutableReportReturns200WithoutLocation() throws Exception {
        when(generateService.generate(ANALYSIS_ID, guestOwner))
                .thenReturn(new GenerateReportResult(report(), false));

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value(report().id().toString()));

        verify(generateService).generate(ANALYSIS_ID, guestOwner);
        verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void authenticatedOwnerTakesPrecedenceOverGuestCookie() throws Exception {
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(generateService.generate(ANALYSIS_ID, registeredOwner))
                .thenReturn(new GenerateReportResult(report(), false));
        authenticate(identity);

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk());

        verify(ownerResolver).resolveForRead(eq(identity), any(HttpServletRequest.class));
        verify(generateService).generate(ANALYSIS_ID, registeredOwner);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void unresolvedOwnerReturns404BeforeGenerationAndDoesNotTouchGuest() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(generateService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void ownerScopedAnalysisNotFoundUsesSameSafeResponse() throws Exception {
        when(generateService.generate(ANALYSIS_ID, guestOwner)).thenThrow(new AnalysisNotFoundException());

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void reportInputNotReadyIsSafeConflict() throws Exception {
        when(generateService.generate(ANALYSIS_ID, guestOwner)).thenThrow(new ReportInputNotReadyException());

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code":"REPORT_INPUT_NOT_READY","message":"The persisted reconciliation is not ready for report generation."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void workflowConflictsUseSafeStableCodes() throws Exception {
        doThrow(new ReportConflictException()).when(generateService).generate(ANALYSIS_ID, guestOwner);
        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_CONFLICT"));

        doThrow(new AnalysisReportNotAllowedException()).when(generateService).generate(ANALYSIS_ID, guestOwner);
        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_REPORT_NOT_ALLOWED"));
    }

    @Test
    void rendererFailureDoesNotLeakProviderDetails() throws Exception {
        when(generateService.generate(ANALYSIS_ID, guestOwner))
                .thenThrow(new ReportGenerationException(new IllegalStateException("private renderer detail")));

        String body = mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("REPORT_GENERATION_FAILED"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("private renderer detail"));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void unavailableStorageMapsTo503WithoutLeakingDetails() throws Exception {
        when(generateService.generate(ANALYSIS_ID, guestOwner))
                .thenThrow(new ReportStorageException(ReportStorageException.Failure.UNAVAILABLE));

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_STORAGE_UNAVAILABLE"));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void nonAvailabilityStorageFailureMapsToGenericGenerationFailure() throws Exception {
        when(generateService.generate(ANALYSIS_ID, guestOwner))
                .thenThrow(new ReportStorageException(ReportStorageException.Failure.PERMISSION_DENIED));

        mockMvc.perform(post("/api/analyses/{analysisId}/report", ANALYSIS_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("REPORT_GENERATION_FAILED"));
    }

    @Test
    void invalidAnalysisIdReturnsSafeBadRequest() throws Exception {
        mockMvc.perform(post("/api/analyses/not-a-uuid/report"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(generateService);
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
