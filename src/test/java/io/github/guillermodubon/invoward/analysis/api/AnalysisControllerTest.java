package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.service.CreateAnalysisService;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisStatusService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

class AnalysisControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    private final CreateAnalysisService createAnalysisService = mock(CreateAnalysisService.class);
    private final GetAnalysisService getAnalysisService = mock(GetAnalysisService.class);
    private final GetAnalysisStatusService getAnalysisStatusService = mock(GetAnalysisStatusService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final GuestSessionCookieSupport cookieSupport = mock(GuestSessionCookieSupport.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new AnalysisController(
                        createAnalysisService, getAnalysisService, getAnalysisStatusService,
                        ownerResolver, cookieSupport))
                .setControllerAdvice(new AnalysisExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void guestCreationReturnsAcceptedLocationSafeBodyAndCookieOnlyAfterCreate() throws Exception {
        UUID guestId = UUID.randomUUID();
        GuestSessionOwner owner = new GuestSessionOwner(guestId, NOW.plus(Duration.ofHours(24)));
        PriceTolerance tolerance = new PriceTolerance(new BigDecimal("3"), new BigDecimal("12.3400"));
        Analysis analysis = Analysis.create(UUID.randomUUID(), owner, tolerance, NOW);
        ResponseCookie cookie = ResponseCookie.from(GuestSessionCookieSupport.COOKIE_NAME, guestId.toString())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(GuestSessionCookieSupport.COOKIE_PATH)
                .maxAge(Duration.ofHours(24))
                .build();
        when(ownerResolver.resolveForCreate(isNull(), any(HttpServletRequest.class))).thenReturn(owner);
        when(createAnalysisService.create(owner, tolerance)).thenReturn(analysis);
        when(cookieSupport.createGuestSessionCookie(owner)).thenReturn(cookie);

        String body = mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content("{\"priceTolerancePercent\":3,\"priceToleranceAbsolute\":12.3400}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/analyses/" + analysis.id()))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("INVOWARD_GUEST=" + guestId),
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("Secure"),
                                org.hamcrest.Matchers.containsString("SameSite=Lax"),
                                org.hamcrest.Matchers.containsString("Path=/api/analyses"))))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").value(analysis.id().toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.reconciliationStatus").doesNotExist())
                .andExpect(jsonPath("$.priceTolerancePercent").value(3))
                .andExpect(jsonPath("$.priceToleranceAbsolute").value(12.34))
                .andExpect(jsonPath("$.expiresAt").value(owner.expiresAt().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains(guestId.toString()));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("guestSessionId"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("userId"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("version"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("supplierKey"));
        assertEquals(Set.of("id", "status", "reviewStatus", "reconciliationStatus",
                        "priceTolerancePercent", "priceToleranceAbsolute", "createdAt", "updatedAt", "expiresAt"),
                jsonFieldNames(body));
        verify(createAnalysisService).create(owner, tolerance);
        verify(ownerResolver).recordSuccessfulActivity(owner);
        verify(cookieSupport).createGuestSessionCookie(owner);
    }

    @Test
    void omittedToleranceDefaultsToExactMatchAndAllowedPercentagesAreAccepted() throws Exception {
        GuestSessionOwner owner = new GuestSessionOwner(UUID.randomUUID(), NOW.plus(Duration.ofHours(24)));
        when(ownerResolver.resolveForCreate(isNull(), any(HttpServletRequest.class))).thenReturn(owner);
        when(createAnalysisService.create(eq(owner), any(PriceTolerance.class)))
                .thenAnswer(invocation -> Analysis.create(
                        UUID.randomUUID(), owner, invocation.getArgument(1), NOW));
        when(cookieSupport.createGuestSessionCookie(owner)).thenAnswer(invocation -> ResponseCookie
                .from(GuestSessionCookieSupport.COOKIE_NAME, owner.guestSessionId().toString())
                .path(GuestSessionCookieSupport.COOKIE_PATH)
                .build());

        mockMvc.perform(post("/api/analyses").contentType("application/json").content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.priceTolerancePercent").value(0))
                .andExpect(jsonPath("$.priceToleranceAbsolute").doesNotExist());

        verify(createAnalysisService).create(owner, PriceTolerance.exactMatch());
        verify(ownerResolver).recordSuccessfulActivity(owner);

        for (String percent : List.of("0", "1", "3", "5")) {
            mockMvc.perform(post("/api/analyses")
                            .contentType("application/json")
                            .content("{\"priceTolerancePercent\":" + percent + "}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.priceTolerancePercent").value(Integer.parseInt(percent)));
        }
    }

    @Test
    void authenticatedCreationIgnoresGuestCookieAndDoesNotEmitIt() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthenticatedIdentity identity = new CurrentAccount(
                userId, "Account", "account@example.com", true, UserStatus.ACTIVE);
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        Analysis analysis = Analysis.create(
                UUID.randomUUID(), owner, PriceTolerance.exactMatch(), NOW);
        when(ownerResolver.resolveForCreate(eq(identity), any(HttpServletRequest.class))).thenReturn(owner);
        when(createAnalysisService.create(owner, PriceTolerance.exactMatch())).thenReturn(analysis);

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
        SecurityContextHolder.setContext(securityContext);

        mockMvc.perform(post("/api/analyses")
                        .cookie(new Cookie(GuestSessionCookieSupport.COOKIE_NAME, UUID.randomUUID().toString()))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/analyses/" + analysis.id()))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());

        verify(ownerResolver).resolveForCreate(eq(identity), any(HttpServletRequest.class));
        verify(cookieSupport, never()).createGuestSessionCookie(any());
        verify(createAnalysisService).create(owner, PriceTolerance.exactMatch());
    }

    @Test
    void invalidToleranceValuesReturnSafeValidationErrorsBeforeOwnerResolution() throws Exception {
        List<String> invalidRequests = List.of(
                "{\"priceTolerancePercent\":-1}",
                "{\"priceTolerancePercent\":2}",
                "{\"priceTolerancePercent\":100}",
                "{\"priceToleranceAbsolute\":-0.01}",
                "{\"priceToleranceAbsolute\":12.12345}",
                "{\"priceToleranceAbsolute\":1000000000000000}");

        for (String request : invalidRequests) {
            mockMvc.perform(post("/api/analyses")
                            .contentType("application/json")
                            .content(request))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("The request contains invalid data."))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(request))));
        }

        verifyNoInteractions(ownerResolver, createAnalysisService, getAnalysisService, cookieSupport);
    }

    @Test
    void malformedJsonReturnsSafeBadRequestWithoutCreatingAnAnalysis() throws Exception {
        String malformedBody = "{\"priceTolerancePercent\":";

        mockMvc.perform(post("/api/analyses")
                        .contentType("application/json")
                        .content(malformedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("The request is invalid."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("priceTolerancePercent"))));

        verifyNoInteractions(ownerResolver, createAnalysisService, getAnalysisService, cookieSupport);
    }

    @Test
    void registeredUserDetailUsesResolvedOwnerAndReturnsOnlySafeDetailFields() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        AuthenticatedIdentity identity = new CurrentAccount(
                userId, "Account", "account@example.com", true, UserStatus.ACTIVE);
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        Analysis analysis = detailedAnalysis(analysisId, owner);
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(owner));
        when(getAnalysisService.get(analysisId, owner)).thenReturn(analysis);
        setAuthenticatedIdentity(identity);

        String body = mockMvc.perform(get("/api/analyses/{id}", analysisId)
                        .cookie(new Cookie(GuestSessionCookieSupport.COOKIE_NAME, UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").value(analysisId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.supplierName").value("Northwind Supplies"))
                .andExpect(jsonPath("$.referenceType").value("QUOTE"))
                .andExpect(jsonPath("$.referenceNumber").value("Q-1042"))
                .andExpect(jsonPath("$.invoiceNumber").value("I-908"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.referenceTotal").value(110.25))
                .andExpect(jsonPath("$.invoicedTotal").value(111.25))
                .andExpect(jsonPath("$.difference").value(1.00))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.failureCode").value("SAFE_CODE"))
                .andExpect(jsonPath("$.failureMessage").value("A safe explanation for the user."))
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains(userId.toString()));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("private-supplier-key"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("version"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("guestSessionId"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("lastErrorMessage"));
        assertEquals(Set.of("id", "status", "reviewStatus", "reconciliationStatus", "supplierName",
                        "referenceType", "referenceNumber", "invoiceNumber", "currency", "referenceTotal",
                        "invoicedTotal", "difference", "priceTolerancePercent", "priceToleranceAbsolute",
                        "retryable", "failureCode", "failureMessage", "createdAt", "updatedAt", "completedAt",
                        "expiresAt"), jsonFieldNames(body));
        verify(ownerResolver).resolveForRead(eq(identity), any(HttpServletRequest.class));
        verify(getAnalysisService).get(analysisId, owner);
        verify(ownerResolver).recordSuccessfulActivity(owner);
        verify(cookieSupport, never()).createGuestSessionCookie(any());
    }

    @Test
    void guestDetailUsesResolvedCookieOwnerAndDoesNotIssueOrExposeGuestCredential() throws Exception {
        UUID guestId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        GuestSessionOwner owner = new GuestSessionOwner(guestId, NOW.plus(Duration.ofHours(23)));
        Analysis analysis = detailedAnalysis(analysisId, owner);
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(owner));
        when(getAnalysisService.get(analysisId, owner)).thenReturn(analysis);

        String body = mockMvc.perform(get("/api/analyses/{id}", analysisId)
                        .cookie(new Cookie(GuestSessionCookieSupport.COOKIE_NAME, guestId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(analysisId.toString()))
                .andExpect(jsonPath("$.supplierName").value("Northwind Supplies"))
                .andExpect(jsonPath("$.expiresAt").value(owner.expiresAt().toString()))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains(guestId.toString()));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("guestSessionId"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("supplierKey"));
        verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        verify(getAnalysisService).get(analysisId, owner);
        verify(ownerResolver).recordSuccessfulActivity(owner);
        verify(cookieSupport, never()).createGuestSessionCookie(any());
    }

    @Test
    void statusReturnsOnlySafeProjectionForResolvedOwner() throws Exception {
        UUID guestId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        GuestSessionOwner owner = new GuestSessionOwner(guestId, NOW.plus(Duration.ofHours(23)));
        AnalysisStatusSnapshot snapshot = new AnalysisStatusSnapshot(
                analysisId,
                AnalysisStatus.FAILED,
                AnalysisJobStatus.FAILED,
                AnalysisStatus.FAILED,
                false,
                "ANALYSIS_FAILED",
                "A safe explanation for the user.",
                NOW);
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(owner));
        when(getAnalysisStatusService.getStatus(analysisId, owner)).thenReturn(snapshot);

        String body = mockMvc.perform(get("/api/analyses/{id}/status", analysisId)
                        .cookie(new Cookie(GuestSessionCookieSupport.COOKIE_NAME, guestId.toString())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.analysisId").value(analysisId.toString()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.jobStatus").value("FAILED"))
                .andExpect(jsonPath("$.currentStage").value("FAILED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.failureCode").value("ANALYSIS_FAILED"))
                .andExpect(jsonPath("$.failureMessage").value("A safe explanation for the user."))
                .andExpect(jsonPath("$.updatedAt").value(NOW.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains(guestId.toString()));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("guestSessionId"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("userId"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("attemptCount"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("lastErrorCode"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("lastErrorMessage"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("provider response must stay private"));
        assertEquals(Set.of("analysisId", "status", "jobStatus", "currentStage", "retryable",
                        "failureCode", "failureMessage", "updatedAt"), jsonFieldNames(body));
        verify(ownerResolver).resolveForRead(isNull(), any(HttpServletRequest.class));
        verify(getAnalysisStatusService).getStatus(analysisId, owner);
        verify(ownerResolver).recordSuccessfulActivity(owner);
    }

    @Test
    void statusWithoutResolvedOwnerReturnsGenericNotFoundWithoutCallingService() throws Exception {
        UUID analysisId = UUID.randomUUID();
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{id}/status", analysisId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Analysis was not found."));

        verifyNoInteractions(getAnalysisStatusService);
    }

    @Test
    void absentOwnerAndNonOwnedAnalysisHaveIdenticalNotFoundResponses() throws Exception {
        UUID analysisId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedIdentity identity = new CurrentAccount(
                userId, "Account", "account@example.com", true, UserStatus.ACTIVE);
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.empty());
        when(ownerResolver.resolveForRead(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(owner));
        when(getAnalysisService.get(analysisId, owner)).thenThrow(new AnalysisNotFoundException());

        String noOwnerBody = mockMvc.perform(get("/api/analyses/{id}", analysisId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Analysis was not found."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        setAuthenticatedIdentity(identity);
        String nonOwnedBody = mockMvc.perform(get("/api/analyses/{id}", analysisId))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals(noOwnerBody, nonOwnedBody);
        verify(getAnalysisService).get(analysisId, owner);
        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    @Test
    void malformedAnalysisIdReturnsSafeBadRequest() throws Exception {
        mockMvc.perform(get("/api/analyses/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("The request is invalid."));

        verifyNoInteractions(ownerResolver, createAnalysisService, getAnalysisService, cookieSupport);
    }

    private static void setAuthenticatedIdentity(AuthenticatedIdentity identity) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
        SecurityContextHolder.setContext(securityContext);
    }

    private Set<String> jsonFieldNames(String body) throws Exception {
        Set<String> names = new HashSet<>();
        JsonNode json = objectMapper.readTree(body);
        names.addAll(json.propertyNames());
        return names;
    }

    private static Analysis detailedAnalysis(UUID id, AnalysisOwner owner) {
        return new Analysis(
                id,
                owner,
                AnalysisStatus.CREATED,
                AnalysisReviewStatus.PENDING,
                null,
                "Northwind Supplies",
                "private-supplier-key",
                "QUOTE",
                "Q-1042",
                "I-908",
                "USD",
                new BigDecimal("110.25"),
                new BigDecimal("111.25"),
                new BigDecimal("1.00"),
                PriceTolerance.exactMatch(),
                false,
                "SAFE_CODE",
                "A safe explanation for the user.",
                42,
                null,
                owner instanceof GuestSessionOwner guestOwner ? guestOwner.expiresAt() : null,
                NOW,
                NOW.plusSeconds(1));
    }
}
