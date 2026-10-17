package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reporting.application.exception.CsvExportTooLargeException;
import io.github.guillermodubon.invoward.reporting.application.model.CsvExport;
import io.github.guillermodubon.invoward.reporting.application.service.ExportReconciliationCsvService;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CsvExportControllerTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("65000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("65000000-0000-0000-0000-000000000002");
    private static final UUID GUEST_ID = UUID.fromString("65000000-0000-0000-0000-000000000003");
    private static final byte[] CSV_BYTES = "Scope,Discrepancy Type\r\nDOCUMENT,TOTAL_MISMATCH\r\n"
            .getBytes(StandardCharsets.UTF_8);

    private final ExportReconciliationCsvService exportService = mock(ExportReconciliationCsvService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final AnalysisOwner guestOwner = new GuestSessionOwner(
            GUEST_ID, Instant.parse("2026-10-18T12:00:00Z"));
    private final RegisteredUserOwner registeredOwner = new RegisteredUserOwner(USER_ID);
    private final AuthenticatedIdentity identity = new CurrentAccount(
            USER_ID, "Test User", "test@example.com", true, UserStatus.ACTIVE);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new CsvExportController(exportService, ownerResolver))
                .setControllerAdvice(new ReportingExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(guestOwner));
        when(exportService.export(ANALYSIS_ID, guestOwner)).thenReturn(new CsvExport(CSV_BYTES));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void guestOwnerReceivesUtf8AttachmentNoStoreAndRecordsActivityAfterExport() throws Exception {
        byte[] body = mockMvc.perform(get("/api/analyses/{analysisId}/export.csv", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"invoward-reconciliation-" + ANALYSIS_ID + ".csv\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsByteArray();

        assertArrayEquals(CSV_BYTES, body);
        var order = inOrder(ownerResolver, exportService);
        order.verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        order.verify(exportService).export(ANALYSIS_ID, guestOwner);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void authenticatedIdentityTakesPrecedenceOverGuestCookie() throws Exception {
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(exportService.export(ANALYSIS_ID, registeredOwner)).thenReturn(new CsvExport(CSV_BYTES));
        authenticate(identity);

        mockMvc.perform(get("/api/analyses/{analysisId}/export.csv", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk());

        verify(exportService).export(ANALYSIS_ID, registeredOwner);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void unresolvedOwnerReturnsAnalysisNotFoundBeforeCsvExport() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{analysisId}/export.csv", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));

        verifyNoInteractions(exportService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void crossOwnerAnalysisNotFoundDoesNotRecordGuestActivity() throws Exception {
        when(exportService.export(ANALYSIS_ID, guestOwner)).thenThrow(new AnalysisNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/export.csv", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void missingReconciliationReturnsStableNotFound() throws Exception {
        when(exportService.export(ANALYSIS_ID, guestOwner)).thenThrow(new ReconciliationNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/export.csv", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"RECONCILIATION_NOT_FOUND","message":"Reconciliation was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void oversizedExportReturnsSafe413WithoutCsvPayload() throws Exception {
        when(exportService.export(ANALYSIS_ID, guestOwner)).thenThrow(new CsvExportTooLargeException());

        mockMvc.perform(get("/api/analyses/{analysisId}/export.csv", ANALYSIS_ID))
                .andExpect(status().is(413))
                .andExpect(content().json("""
                        {"code":"EXPORT_TOO_LARGE","message":"The CSV export exceeds the configured size limit."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void invalidAnalysisIdReturnsSafeBadRequestBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/analyses/not-a-uuid/export.csv"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(exportService);
    }

    private static void authenticate(AuthenticatedIdentity authenticatedIdentity) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(authenticatedIdentity, null, List.of()));
        SecurityContextHolder.setContext(context);
    }
}
