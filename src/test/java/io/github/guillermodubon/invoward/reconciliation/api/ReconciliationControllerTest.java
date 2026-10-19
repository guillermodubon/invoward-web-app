package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisProcessingNotAllowedException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.service.RequestAnalysisProcessingService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceCandidate;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceSignal;
import io.github.guillermodubon.invoward.reconciliation.application.model.DiscrepancyResolutionUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetDiscrepancyEvidenceService;
import io.github.guillermodubon.invoward.reconciliation.application.service.UpdateDiscrepancyService;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.EvidenceSide;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ReconciliationControllerTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID GUEST_ID = UUID.randomUUID();
    private static final UUID OTHER_ANALYSIS_ID = UUID.randomUUID();
    private static final UUID LINE_ID = UUID.randomUUID();
    private static final UUID MATCH_ID = UUID.randomUUID();
    private static final UUID DISCREPANCY_ID = UUID.randomUUID();
    private static final UUID EVIDENCE_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private final RequestAnalysisProcessingService requestProcessingService =
            mock(RequestAnalysisProcessingService.class);
    private final GetReconciliationService getReconciliationService = mock(GetReconciliationService.class);
    private final GetDiscrepancyEvidenceService getDiscrepancyEvidenceService =
            mock(GetDiscrepancyEvidenceService.class);
    private final UpdateDiscrepancyService updateDiscrepancyService = mock(UpdateDiscrepancyService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final AnalysisOwner guestOwner = new GuestSessionOwner(GUEST_ID, NOW.plusSeconds(86_400));
    private final AuthenticatedIdentity identity = new CurrentAccount(
            USER_ID, "Test User", "test@example.com", true, UserStatus.ACTIVE);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new ReconciliationController(
                        getReconciliationService, getDiscrepancyEvidenceService,
                        requestProcessingService, updateDiscrepancyService, ownerResolver))
                .setControllerAdvice(new ReconciliationExceptionHandler(), new LineItemMatchesExceptionHandler())
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
    void postReturnsAcceptedProcessingStatusAndPollingLocationForPendingReconciliation() throws Exception {
        when(requestProcessingService.request(ANALYSIS_ID, guestOwner, AnalysisStatus.RECONCILING))
                .thenReturn(statusSnapshot(AnalysisStatus.RECONCILING,
                        AnalysisJobStatus.QUEUED, AnalysisStatus.RECONCILING));

        String body = mockMvc.perform(post("/api/analyses/{analysisId}/reconcile", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Location", "/api/analyses/" + ANALYSIS_ID + "/status"))
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.status").value("RECONCILING"))
                .andExpect(jsonPath("$.jobStatus").value("QUEUED"))
                .andExpect(jsonPath("$.currentStage").value("RECONCILING"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(GUEST_ID.toString()));
        assertFalse(body.contains("userId"));
        assertFalse(body.contains("guestSessionId"));

        var order = inOrder(ownerResolver, requestProcessingService);
        order.verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        order.verify(requestProcessingService).request(ANALYSIS_ID, guestOwner, AnalysisStatus.RECONCILING);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
        verify(ownerResolver, never()).resolveForCreate(any(), any(HttpServletRequest.class));
    }

    @Test
    void postReturnsCurrentStatusWhenReconciliationHasAlreadyAdvanced() throws Exception {
        when(requestProcessingService.request(ANALYSIS_ID, guestOwner, AnalysisStatus.RECONCILING))
                .thenReturn(statusSnapshot(AnalysisStatus.GENERATING_REPORT,
                        AnalysisJobStatus.QUEUED, AnalysisStatus.GENERATING_REPORT));

        mockMvc.perform(post("/api/analyses/{analysisId}/reconcile", ANALYSIS_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("GENERATING_REPORT"))
                .andExpect(jsonPath("$.jobStatus").value("QUEUED"));
    }

    @Test
    void getReturnsSameSafeStableRepresentationWithoutReconciliationOrMatchingWrites() throws Exception {
        AnalysisOwner registeredOwner = new RegisteredUserOwner(USER_ID);
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(getReconciliationService.get(ANALYSIS_ID, registeredOwner)).thenReturn(result());
        authenticate(identity);

        String body = mockMvc.perform(get("/api/analyses/{analysisId}/reconciliation", ANALYSIS_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.analysisStatus").value("GENERATING_REPORT"))
                .andExpect(jsonPath("$.reconciliationStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.summary.lineCount").value(1))
                .andExpect(jsonPath("$.lines[0].lineMatchId").value(MATCH_ID.toString()))
                .andExpect(jsonPath("$.discrepancies[0].resolutionStatus").value("OPEN"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(GUEST_ID.toString()));
        assertFalse(body.contains("userId"));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("supplierKey"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains("sourceText"));
        assertFalse(body.contains("boundingBox"));
        verify(ownerResolver).resolveForRead(eq(identity), any(HttpServletRequest.class));
        verify(getReconciliationService).get(ANALYSIS_ID, registeredOwner);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verifyNoInteractions(requestProcessingService);
    }

    @Test
    void getWithoutPersistedResultReturnsStableReconciliationNotFound() throws Exception {
        when(getReconciliationService.get(ANALYSIS_ID, guestOwner))
                .thenThrow(new ReconciliationNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/reconciliation", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"RECONCILIATION_NOT_FOUND","message":"Reconciliation was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verifyNoInteractions(requestProcessingService);
    }

    @Test
    void getEvidenceReturnsOnlySafeProjectionAndRecordsActivityAfterSuccess() throws Exception {
        DiscrepancyEvidence evidence = new DiscrepancyEvidence(
                EVIDENCE_ID, DISCREPANCY_ID, DOCUMENT_ID, EvidenceSide.REFERENCE, 2,
                "Printed invoice line", new BoundingBox(0.1, 0.2, 0.8, 0.9), NOW);
        when(getDiscrepancyEvidenceService.get(ANALYSIS_ID, guestOwner, DISCREPANCY_ID))
                .thenReturn(List.of(evidence));

        String body = mockMvc.perform(get(
                                "/api/analyses/{analysisId}/discrepancies/{discrepancyId}/evidence",
                                ANALYSIS_ID, DISCREPANCY_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$[0].id").value(EVIDENCE_ID.toString()))
                .andExpect(jsonPath("$[0].documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$[0].side").value("REFERENCE"))
                .andExpect(jsonPath("$[0].pageNumber").value(2))
                .andExpect(jsonPath("$[0].sourceText").value("Printed invoice line"))
                .andExpect(jsonPath("$[0].boundingBox.xMin").value(0.1))
                .andExpect(jsonPath("$[0].boundingBox.yMax").value(0.9))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("discrepancyId"));
        assertFalse(body.contains("createdAt"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains("supplierKey"));
        assertFalse(body.contains("userId"));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("url"));
        verify(getDiscrepancyEvidenceService).get(ANALYSIS_ID, guestOwner, DISCREPANCY_ID);
        verify(ownerResolver).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(requestProcessingService, getReconciliationService, updateDiscrepancyService);
    }

    @Test
    void getEvidenceWithNoEvidenceReturnsEmptyArray() throws Exception {
        when(getDiscrepancyEvidenceService.get(ANALYSIS_ID, guestOwner, DISCREPANCY_ID)).thenReturn(List.of());

        mockMvc.perform(get(
                                "/api/analyses/{analysisId}/discrepancies/{discrepancyId}/evidence",
                                ANALYSIS_ID, DISCREPANCY_ID))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void getEvidenceForWrongAnalysisDiscrepancyReturnsSafeNotFound() throws Exception {
        when(getDiscrepancyEvidenceService.get(ANALYSIS_ID, guestOwner, DISCREPANCY_ID))
                .thenThrow(new DiscrepancyNotFoundException());

        mockMvc.perform(get(
                                "/api/analyses/{analysisId}/discrepancies/{discrepancyId}/evidence",
                                ANALYSIS_ID, DISCREPANCY_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"DISCREPANCY_NOT_FOUND","message":"Discrepancy was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verifyNoInteractions(requestProcessingService, getReconciliationService, updateDiscrepancyService);
    }

    @Test
    void getEvidenceWithInvalidOwnerDoesNotCallEvidenceServiceOrCreateGuest() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(get(
                                "/api/analyses/{analysisId}/discrepancies/{discrepancyId}/evidence",
                                ANALYSIS_ID, DISCREPANCY_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", "malformed")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(getDiscrepancyEvidenceService, requestProcessingService, getReconciliationService,
                updateDiscrepancyService);
        verify(ownerResolver, never()).resolveForCreate(any(), any(HttpServletRequest.class));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void getCrossOwnerAnalysisReturnsSameNotFoundWithoutTouchingOwner() throws Exception {
        when(getReconciliationService.get(ANALYSIS_ID, guestOwner)).thenThrow(new AnalysisNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/reconciliation", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
        verifyNoInteractions(requestProcessingService);
    }

    @Test
    void getWithMissingOrExpiredGuestDoesNotCreateSessionOrQueryReconciliation() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{analysisId}/reconciliation", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", "malformed-or-expired")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(getReconciliationService, requestProcessingService);
        verify(ownerResolver, never()).resolveForCreate(any(), any(HttpServletRequest.class));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void authenticatedIdentityTakesPrecedenceOverGuestCookie() throws Exception {
        AnalysisOwner registeredOwner = new RegisteredUserOwner(USER_ID);
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(requestProcessingService.request(ANALYSIS_ID, registeredOwner, AnalysisStatus.RECONCILING))
                .thenReturn(statusSnapshot(AnalysisStatus.RECONCILING,
                        AnalysisJobStatus.RUNNING, AnalysisStatus.RECONCILING));
        authenticate(identity);

        mockMvc.perform(post("/api/analyses/{analysisId}/reconcile", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECONCILING"));

        verify(ownerResolver).resolveForRead(eq(identity), any(HttpServletRequest.class));
        verify(requestProcessingService).request(ANALYSIS_ID, registeredOwner, AnalysisStatus.RECONCILING);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void missingOrInvalidGuestOwnerReturnsGenericNotFoundWithoutCreatingGuestOrCallingService() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{analysisId}/reconcile", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", "malformed")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(requestProcessingService);
        verify(ownerResolver, never()).resolveForCreate(any(), any(HttpServletRequest.class));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void repeatedPostReturnsTheSameSuccessfulRepresentationFromIdempotentApplicationService() throws Exception {
        AnalysisStatusSnapshot queued = statusSnapshot(AnalysisStatus.RECONCILING,
                AnalysisJobStatus.QUEUED, AnalysisStatus.RECONCILING);
        when(requestProcessingService.request(ANALYSIS_ID, guestOwner, AnalysisStatus.RECONCILING))
                .thenReturn(queued);

        String first = mockMvc.perform(post("/api/analyses/{analysisId}/reconcile", ANALYSIS_ID))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/analyses/{analysisId}/reconcile", ANALYSIS_ID))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        verify(requestProcessingService, times(2)).request(ANALYSIS_ID, guestOwner, AnalysisStatus.RECONCILING);
        verify(ownerResolver, times(2)).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void invalidProcessingStateReturnsSafeConflictAndDoesNotTouchGuest() throws Exception {
        when(requestProcessingService.request(ANALYSIS_ID, guestOwner, AnalysisStatus.RECONCILING))
                .thenThrow(new AnalysisProcessingNotAllowedException());

        String body = mockMvc.perform(post("/api/analyses/{analysisId}/reconcile", ANALYSIS_ID))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_PROCESSING_NOT_ALLOWED","message":"Analysis processing is not allowed in its current state."}
                        """))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("provider"));
        assertFalse(body.contains("database"));
        assertFalse(body.contains("stackTrace"));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void patchReturnsUpdatedSafeDiscrepancyAndRecordsOwnerOnlyAfterSuccess() throws Exception {
        DiscrepancyResolutionUpdate command = new DiscrepancyResolutionUpdate(
                0, DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionReason.APPROVED_CHANGE, null);
        when(updateDiscrepancyService.update(ANALYSIS_ID, guestOwner, DISCREPANCY_ID, command))
                .thenReturn(acceptedDiscrepancy());

        String body = mockMvc.perform(patch(
                                "/api/analyses/{analysisId}/discrepancies/{discrepancyId}",
                                ANALYSIS_ID, DISCREPANCY_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "resolutionStatus": "ACCEPTED",
                                  "resolutionReason": "APPROVED_CHANGE",
                                  "resolutionNote": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value(DISCREPANCY_ID.toString()))
                .andExpect(jsonPath("$.type").value("UNIT_PRICE_MISMATCH"))
                .andExpect(jsonPath("$.resolutionStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.resolutionReason").value("APPROVED_CHANGE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.resolvedAt").value(NOW.plusSeconds(1).toString()))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(GUEST_ID.toString()));
        assertFalse(body.contains(USER_ID.toString()));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("supplierKey"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains("password"));

        var order = inOrder(ownerResolver, updateDiscrepancyService);
        order.verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        order.verify(updateDiscrepancyService).update(ANALYSIS_ID, guestOwner, DISCREPANCY_ID, command);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void patchUsesAuthenticatedOwnerWhenGuestCookieIsAlsoPresent() throws Exception {
        RegisteredUserOwner registeredOwner = new RegisteredUserOwner(USER_ID);
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(updateDiscrepancyService.update(
                eq(ANALYSIS_ID), eq(registeredOwner), eq(DISCREPANCY_ID), any(DiscrepancyResolutionUpdate.class)))
                .thenReturn(acceptedDiscrepancy());
        authenticate(identity);

        mockMvc.perform(patch(
                                "/api/analyses/{analysisId}/discrepancies/{discrepancyId}",
                                ANALYSIS_ID, DISCREPANCY_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptedRequest()))
                .andExpect(status().isOk());

        verify(ownerResolver).resolveForRead(eq(identity), any(HttpServletRequest.class));
        verify(updateDiscrepancyService).update(
                eq(ANALYSIS_ID), eq(registeredOwner), eq(DISCREPANCY_ID), any(DiscrepancyResolutionUpdate.class));
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void unresolvedOwnerReturnsAnalysisNotFoundWithoutCallingUpdateService() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(patch(
                                "/api/analyses/{analysisId}/discrepancies/{discrepancyId}",
                                ANALYSIS_ID, DISCREPANCY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptedRequest()))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(updateDiscrepancyService);
        verify(ownerResolver, never()).resolveForCreate(any(), any(HttpServletRequest.class));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void discrepancyOutsideAnalysisReturnsSafeNotFound() throws Exception {
        when(updateDiscrepancyService.update(
                eq(ANALYSIS_ID), eq(guestOwner), eq(DISCREPANCY_ID), any(DiscrepancyResolutionUpdate.class)))
                .thenThrow(new DiscrepancyNotFoundException());

        String body = mockMvc.perform(patch(
                                "/api/analyses/{analysisId}/discrepancies/{discrepancyId}",
                                ANALYSIS_ID, DISCREPANCY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptedRequest()))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"DISCREPANCY_NOT_FOUND","message":"Discrepancy was not found."}
                        """))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(OTHER_ANALYSIS_ID.toString()));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void staleVersionReturnsSafeDiscrepancyConflict() throws Exception {
        when(updateDiscrepancyService.update(
                eq(ANALYSIS_ID), eq(guestOwner), eq(DISCREPANCY_ID), any(DiscrepancyResolutionUpdate.class)))
                .thenThrow(new DiscrepancyConflictException());

        String body = mockMvc.perform(patch(
                                "/api/analyses/{analysisId}/discrepancies/{discrepancyId}",
                                ANALYSIS_ID, DISCREPANCY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptedRequest()))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code":"DISCREPANCY_CONFLICT","message":"The discrepancy changed while it was being reviewed. Reload and try again."}
                        """))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("OptimisticLock"));
        assertFalse(body.contains("database"));
        assertFalse(body.contains("stackTrace"));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void invalidPatchFieldsReturnSafeValidationErrorsWithoutCallingService() throws Exception {
        String path = "/api/analyses/{analysisId}/discrepancies/{discrepancyId}";
        mockMvc.perform(patch(path, ANALYSIS_ID, DISCREPANCY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"VALIDATION_FAILED","message":"The request contains invalid data."}
                        """));
        mockMvc.perform(patch(path, ANALYSIS_ID, DISCREPANCY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":-1,\"resolutionStatus\":\"OPEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch(path, ANALYSIS_ID, DISCREPANCY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"resolutionStatus\":\"NOT_A_STATUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(updateDiscrepancyService);
    }

    private static String acceptedRequest() {
        return """
                {"expectedVersion":0,"resolutionStatus":"ACCEPTED","resolutionReason":"APPROVED_CHANGE"}
                """;
    }

    private static Discrepancy acceptedDiscrepancy() {
        return new Discrepancy(
                DISCREPANCY_ID, ANALYSIS_ID, LINE_ID, DiscrepancyType.UNIT_PRICE_MISMATCH,
                "10.0000", "11.0000", BigDecimal.ONE, new BigDecimal("10.000000"),
                "The invoiced unit price differs from the reference price beyond the configured tolerance.",
                DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionReason.APPROVED_CHANGE,
                null, 1, NOW.plusSeconds(1), NOW, NOW.plusSeconds(1));
    }

    private static ReconciliationResult result() {
        ReconciliationLine line = new ReconciliationLine(
                LINE_ID, ANALYSIS_ID, MATCH_ID, ReconciliationLineStatus.DIFFERENT,
                "SKU-1", "SKU-1", "Widget", "Widget",
                new BigDecimal("2.0000"), new BigDecimal("2.0000"),
                new BigDecimal("10.0000"), new BigDecimal("11.0000"),
                new BigDecimal("20.0000"), new BigDecimal("22.0000"),
                BigDecimal.ZERO, new BigDecimal("1.0000"), new BigDecimal("2.0000"), NOW);
        Discrepancy discrepancy = new Discrepancy(
                DISCREPANCY_ID, ANALYSIS_ID, LINE_ID, DiscrepancyType.UNIT_PRICE_MISMATCH,
                "10.0000", "11.0000", BigDecimal.ONE, new BigDecimal("10.000000"),
                "The invoiced unit price differs from the reference price beyond the configured tolerance.",
                DiscrepancyResolutionStatus.OPEN, null, null, 0, null, NOW, NOW);
        DuplicateInvoiceWarning duplicate = new DuplicateInvoiceWarning(true, List.of(
                new DuplicateInvoiceCandidate(OTHER_ANALYSIS_ID, NOW.minusSeconds(60), "INV-42",
                        List.of(DuplicateInvoiceSignal.SAME_INVOICE_NUMBER, DuplicateInvoiceSignal.SAME_SUPPLIER))));
        return new ReconciliationResult(
                ANALYSIS_ID, AnalysisStatus.GENERATING_REPORT, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                new PriceTolerance(new BigDecimal("3"), new BigDecimal("5.0000")),
                new BigDecimal("20.0000"), new BigDecimal("22.0000"), new BigDecimal("2.0000"),
                duplicate, new ReconciliationSummary(1, 0, 1, 0, 0, 1, 0, 0),
                List.of(line), List.of(discrepancy));
    }

    private static AnalysisStatusSnapshot statusSnapshot(
            AnalysisStatus status,
            AnalysisJobStatus jobStatus,
            AnalysisStatus currentStage) {
        return new AnalysisStatusSnapshot(
                ANALYSIS_ID, status, jobStatus, currentStage, false, null, null, NOW);
    }

    private static void authenticate(AuthenticatedIdentity authenticatedIdentity) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(authenticatedIdentity, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
