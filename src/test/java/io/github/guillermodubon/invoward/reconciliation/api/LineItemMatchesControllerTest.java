package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchTargetUnavailableException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchesNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualLineItemMatchUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualMatchAction;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchSetView;
import io.github.guillermodubon.invoward.reconciliation.application.service.ConfirmLineItemMatchesService;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetLineItemMatchesService;
import io.github.guillermodubon.invoward.reconciliation.application.service.UpdateLineItemMatchService;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LineItemMatchesControllerTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID GUEST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID MATCH_ID = UUID.randomUUID();
    private static final UUID REFERENCE_LINE_ID = UUID.randomUUID();
    private static final UUID INVOICE_LINE_ID = UUID.randomUUID();
    private static final UUID UNMATCHED_MATCH_ID = UUID.randomUUID();
    private static final UUID INVOICE_ONLY_LINE_ID = UUID.randomUUID();
    private static final Instant GUEST_EXPIRY = Instant.parse("2026-10-08T12:00:00Z");

    private final GetLineItemMatchesService getService = mock(GetLineItemMatchesService.class);
    private final UpdateLineItemMatchService updateService = mock(UpdateLineItemMatchService.class);
    private final ConfirmLineItemMatchesService confirmService = mock(ConfirmLineItemMatchesService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final AnalysisOwner guestOwner = new GuestSessionOwner(GUEST_ID, GUEST_EXPIRY);
    private final AuthenticatedIdentity identity = new CurrentAccount(
            USER_ID, "Test User", "test@example.com", true, UserStatus.ACTIVE);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new LineItemMatchesController(getService, updateService, confirmService, ownerResolver))
                .setControllerAdvice(new LineItemMatchesExceptionHandler())
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
    void getReturnsSafePersistedMatchSetAndTouchesGuestOnlyAfterSuccess() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner)).thenReturn(matchSet());

        String body = mockMvc.perform(get("/api/analyses/{analysisId}/matches", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.analysisStatus").value("AWAITING_MATCH_REVIEW"))
                .andExpect(jsonPath("$.reviewRequired").value(true))
                .andExpect(jsonPath("$.matches[0].id").value(MATCH_ID.toString()))
                .andExpect(jsonPath("$.matches[0].status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.matches[0].method").value("FUZZY"))
                .andExpect(jsonPath("$.matches[0].confidence").value(0.8765))
                .andExpect(jsonPath("$.matches[0].version").value(2))
                .andExpect(jsonPath("$.matches[0].reviewedAt").doesNotExist())
                .andExpect(jsonPath("$.matches[0].reference.lineItemId").value(REFERENCE_LINE_ID.toString()))
                .andExpect(jsonPath("$.matches[0].reference.position").value(0))
                .andExpect(jsonPath("$.matches[0].reference.description").value("Widget A"))
                .andExpect(jsonPath("$.matches[0].reference.quantity").value(2.0))
                .andExpect(jsonPath("$.matches[0].reference.unitPrice").value(12.5))
                .andExpect(jsonPath("$.matches[0].reference.lineTotal").value(25.0))
                .andExpect(jsonPath("$.matches[0].invoice.lineItemId").value(INVOICE_LINE_ID.toString()))
                .andExpect(jsonPath("$.matches[1].reference").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.matches[1].invoice.lineItemId").value(INVOICE_ONLY_LINE_ID.toString()))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(GUEST_ID.toString()));
        assertFalse(body.contains("sourceText"));
        assertFalse(body.contains("boundingBox"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains("modelId"));
        assertFalse(body.contains("prompt"));
        verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        verify(getService).get(ANALYSIS_ID, guestOwner);
        verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void authenticatedIdentityIsResolvedBeforeGuestCookie() throws Exception {
        RegisteredUserOwner registeredOwner = new RegisteredUserOwner(USER_ID);
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(getService.get(ANALYSIS_ID, registeredOwner)).thenReturn(matchSet());
        authenticate(identity);

        mockMvc.perform(get("/api/analyses/{analysisId}/matches", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk());

        verify(ownerResolver).resolveForRead(eq(identity), any(HttpServletRequest.class));
        verify(getService).get(ANALYSIS_ID, registeredOwner);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
    }

    @Test
    void matchesRemainReadableAfterAnalysisEntersReconciling() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner)).thenReturn(new LineItemMatchSetView(
                ANALYSIS_ID, AnalysisStatus.RECONCILING, false, matchSet().matches()));

        mockMvc.perform(get("/api/analyses/{analysisId}/matches", ANALYSIS_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisStatus").value("RECONCILING"))
                .andExpect(jsonPath("$.reviewRequired").value(false));

        verify(getService).get(ANALYSIS_ID, guestOwner);
        verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void unresolvedOwnerReturnsGenericAnalysisNotFoundWithoutCallingReadService() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{analysisId}/matches", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(getService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void analysisNotFoundFromOwnerScopedServiceUsesTheSameSafeResponse() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner)).thenThrow(new AnalysisNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/matches", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void missingPersistedMatchSetReturnsStableNotFoundWithoutTouchingGuest() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner)).thenThrow(new MatchesNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/matches", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"MATCHES_NOT_FOUND","message":"Matches were not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void inconsistentPersistedMatchSetReturnsSafeConflict() throws Exception {
        when(getService.get(ANALYSIS_ID, guestOwner)).thenThrow(new MatchSetConflictException());

        String body = mockMvc.perform(get("/api/analyses/{analysisId}/matches", ANALYSIS_ID))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code":"MATCH_SET_CONFLICT","message":"The persisted match set is inconsistent."}
                        """))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("database"));
        assertFalse(body.contains("stackTrace"));
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void patchReturnsRefreshedMatchSetAndTouchesGuestOnlyAfterSuccess() throws Exception {
        ManualLineItemMatchUpdate command = new ManualLineItemMatchUpdate(
                2, ManualMatchAction.CONFIRM, null, null);
        when(updateService.update(ANALYSIS_ID, MATCH_ID, guestOwner, command)).thenReturn(matchSet());

        String body = mockMvc.perform(patch("/api/analyses/{analysisId}/matches/{matchId}", ANALYSIS_ID, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 2,
                                  "action": "CONFIRM",
                                  "referenceLineItemId": null,
                                  "invoiceLineItemId": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.analysisId").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.matches[0].id").value(MATCH_ID.toString()))
                .andExpect(jsonPath("$.matches[0].version").value(2))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(GUEST_ID.toString()));
        assertFalse(body.contains("sourceText"));
        assertFalse(body.contains("boundingBox"));
        verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        verify(updateService).update(ANALYSIS_ID, MATCH_ID, guestOwner, command);
        verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void patchUsesAuthenticatedIdentityBeforeGuestCookie() throws Exception {
        RegisteredUserOwner registeredOwner = new RegisteredUserOwner(USER_ID);
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(updateService.update(
                eq(ANALYSIS_ID), eq(MATCH_ID), eq(registeredOwner), any(ManualLineItemMatchUpdate.class)))
                .thenReturn(matchSet());
        authenticate(identity);

        mockMvc.perform(patch("/api/analyses/{analysisId}/matches/{matchId}", ANALYSIS_ID, MATCH_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmRequest(2)))
                .andExpect(status().isOk());

        verify(ownerResolver).resolveForRead(eq(identity), any(HttpServletRequest.class));
        verify(updateService).update(
                eq(ANALYSIS_ID), eq(MATCH_ID), eq(registeredOwner), any(ManualLineItemMatchUpdate.class));
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void unresolvedOwnerReturnsGenericAnalysisNotFoundWithoutCallingUpdateService() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/analyses/{analysisId}/matches/{matchId}", ANALYSIS_ID, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmRequest(0)))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(updateService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void invalidActionFieldsReturnSafeBadRequest() throws Exception {
        mockMvc.perform(patch("/api/analyses/{analysisId}/matches/{matchId}", ANALYSIS_ID, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "action": "CONFIRM",
                                  "referenceLineItemId": "%s",
                                  "invoiceLineItemId": null
                                }
                                """.formatted(REFERENCE_LINE_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(updateService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void missingExpectedVersionFailsValidation() throws Exception {
        mockMvc.perform(patch("/api/analyses/{analysisId}/matches/{matchId}", ANALYSIS_ID, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(updateService);
    }

    @Test
    void staleVersionMapsToSafeConflict() throws Exception {
        when(updateService.update(
                eq(ANALYSIS_ID), eq(MATCH_ID), eq(guestOwner), any(ManualLineItemMatchUpdate.class)))
                .thenThrow(new MatchConflictException());

        mockMvc.perform(patch("/api/analyses/{analysisId}/matches/{matchId}", ANALYSIS_ID, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmRequest(0)))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code":"MATCH_CONFLICT","message":"The match changed before the update."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void unavailableTargetMapsToSafeConflict() throws Exception {
        when(updateService.update(
                eq(ANALYSIS_ID), eq(MATCH_ID), eq(guestOwner), any(ManualLineItemMatchUpdate.class)))
                .thenThrow(new MatchTargetUnavailableException());

        mockMvc.perform(patch("/api/analyses/{analysisId}/matches/{matchId}", ANALYSIS_ID, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "action": "MATCH_WITH",
                                  "referenceLineItemId": "%s",
                                  "invoiceLineItemId": "%s"
                                }
                                """.formatted(REFERENCE_LINE_ID, INVOICE_LINE_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATCH_TARGET_UNAVAILABLE"));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void matchNotFoundMapsToSafeNotFound() throws Exception {
        when(updateService.update(
                eq(ANALYSIS_ID), eq(MATCH_ID), eq(guestOwner), any(ManualLineItemMatchUpdate.class)))
                .thenThrow(new MatchNotFoundException());

        mockMvc.perform(patch("/api/analyses/{analysisId}/matches/{matchId}", ANALYSIS_ID, MATCH_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmRequest(0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MATCH_NOT_FOUND"));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void confirmReturnsNoContentAndRecordsGuestActivityOnlyAfterServiceSuccess() throws Exception {
        mockMvc.perform(post("/api/analyses/{analysisId}/matches/confirm", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        var inOrder = inOrder(ownerResolver, confirmService);
        inOrder.verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        inOrder.verify(confirmService).confirm(ANALYSIS_ID, guestOwner);
        inOrder.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(getService, updateService);
    }

    @Test
    void confirmUsesAuthenticatedIdentityBeforeGuestCookie() throws Exception {
        RegisteredUserOwner registeredOwner = new RegisteredUserOwner(USER_ID);
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        authenticate(identity);

        mockMvc.perform(post("/api/analyses/{analysisId}/matches/confirm", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isNoContent());

        verify(ownerResolver).resolveForRead(eq(identity), any(HttpServletRequest.class));
        verify(confirmService).confirm(ANALYSIS_ID, registeredOwner);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void unresolvedOwnerReturnsGenericAnalysisNotFoundWithoutCallingConfirmationService() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{analysisId}/matches/confirm", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(confirmService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void confirmationConflictMapsToSafeMatchSetConflict() throws Exception {
        doThrow(new MatchSetConflictException()).when(confirmService).confirm(ANALYSIS_ID, guestOwner);

        mockMvc.perform(post("/api/analyses/{analysisId}/matches/confirm", ANALYSIS_ID))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code":"MATCH_SET_CONFLICT","message":"The persisted match set is inconsistent."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    private static LineItemMatchSetView matchSet() {
        return new LineItemMatchSetView(
                ANALYSIS_ID,
                AnalysisStatus.AWAITING_MATCH_REVIEW,
                true,
                List.of(
                        new LineItemMatchSetView.MatchEntry(
                                MATCH_ID,
                                LineMatchStatus.NEEDS_REVIEW,
                                LineMatchMethod.FUZZY,
                                new BigDecimal("0.8765"),
                                2,
                                null,
                                line(REFERENCE_LINE_ID, 0, "Widget A"),
                                line(INVOICE_LINE_ID, 0, "Widget A")),
                        new LineItemMatchSetView.MatchEntry(
                                UNMATCHED_MATCH_ID,
                                LineMatchStatus.UNMATCHED_INVOICE,
                                LineMatchMethod.NONE,
                                null,
                                0,
                                null,
                                null,
                                line(INVOICE_ONLY_LINE_ID, 1, "Other"))));
    }

    private static LineItemMatchSetView.LineItem line(UUID id, int position, String description) {
        return new LineItemMatchSetView.LineItem(
                id, position, "SKU-1", description,
                new BigDecimal("2.0000"), "ea",
                new BigDecimal("12.5000"), new BigDecimal("25.0000"));
    }

    private static void authenticate(AuthenticatedIdentity authenticatedIdentity) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(authenticatedIdentity, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    private static String confirmRequest(long expectedVersion) {
        return """
                {"expectedVersion":%d,"action":"CONFIRM"}
                """.formatted(expectedVersion);
    }
}
