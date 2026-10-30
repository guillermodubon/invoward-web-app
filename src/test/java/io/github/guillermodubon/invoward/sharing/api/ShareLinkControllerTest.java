package io.github.guillermodubon.invoward.sharing.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.sharing.application.exception.InvalidShareLinkQueryException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareExpiryUnavailableException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkCreationException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkLimitReachedException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkNotFoundException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkNotShareableException;
import io.github.guillermodubon.invoward.sharing.application.model.CreatedShareLink;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkListing;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkListItem;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkExpiryChoice;
import io.github.guillermodubon.invoward.sharing.application.service.CreateShareLinkService;
import io.github.guillermodubon.invoward.sharing.application.service.ListShareLinksService;
import io.github.guillermodubon.invoward.sharing.application.service.RevokeShareLinkService;
import io.github.guillermodubon.invoward.sharing.domain.ShareLinkStatus;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ShareLinkControllerTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID GUEST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plusSeconds(7 * 24 * 60 * 60L);
    private static final String RAW_TOKEN = "sensitive-one-time-bearer-token";

    private final CreateShareLinkService createService = mock(CreateShareLinkService.class);
    private final ListShareLinksService listService = mock(ListShareLinksService.class);
    private final RevokeShareLinkService revokeService = mock(RevokeShareLinkService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final AnalysisOwner guestOwner = new GuestSessionOwner(GUEST_ID, EXPIRES_AT.plusSeconds(3600));
    private final AnalysisOwner registeredOwner = new RegisteredUserOwner(USER_ID);
    private final AuthenticatedIdentity identity = new CurrentAccount(
            USER_ID, "Test User", "test@example.test", true, UserStatus.ACTIVE);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new ShareLinkController(createService, listService, revokeService, ownerResolver))
                .setControllerAdvice(new ShareLinkExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        when(ownerResolver.resolveForReadPrincipal(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(guestOwner));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsOneTimeUrlWithNoStoreAndNoReferrerHeadersAndOnlyWhitelistedFields() throws Exception {
        CreatedShareLink result = created();
        when(createService.create(ANALYSIS_ID, guestOwner, ShareLinkExpiryChoice.DAYS_7)).thenReturn(result);

        String response = mockMvc.perform(post("/api/analyses/{analysisId}/share-links", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString()))
                        .contentType("application/json")
                        .content("{\"expiresIn\":\"7_DAYS\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").value(result.id().toString()))
                .andExpect(jsonPath("$.publicUrl").value(result.publicUrl()))
                .andExpect(jsonPath("$.expiresIn").value("7_DAYS"))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT.toString()))
                .andExpect(jsonPath("$.revokedAt").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains("tokenHash"));
        assertFalse(response.contains("userId"));
        assertFalse(response.contains("guestSessionId"));
        assertEquals(Set.of("id", "publicUrl", "expiresIn", "createdAt", "expiresAt", "revokedAt"),
                fieldNames(response));

        var order = inOrder(ownerResolver, createService);
        order.verify(ownerResolver).resolveForReadPrincipal(isNull(), any(HttpServletRequest.class));
        order.verify(createService).create(ANALYSIS_ID, guestOwner, ShareLinkExpiryChoice.DAYS_7);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void authenticatedIdentityIsPassedToResolverAheadOfGuestCookie() throws Exception {
        when(ownerResolver.resolveForReadPrincipal(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        when(createService.create(ANALYSIS_ID, registeredOwner, ShareLinkExpiryChoice.HOURS_24))
                .thenReturn(created());
        authenticate(identity);

        mockMvc.perform(post("/api/analyses/{analysisId}/share-links", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString()))
                        .contentType("application/json")
                        .content("{\"expiresIn\":\"24_HOURS\"}"))
                .andExpect(status().isCreated());

        verify(ownerResolver).resolveForReadPrincipal(eq(identity), any(HttpServletRequest.class));
        verify(createService).create(ANALYSIS_ID, registeredOwner, ShareLinkExpiryChoice.HOURS_24);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void acceptsThirtyDayPublicExpiryValue() throws Exception {
        Instant expiry = CREATED_AT.plusSeconds(30L * 24 * 60 * 60);
        CreatedShareLink result = new CreatedShareLink(
                UUID.randomUUID(), "https://app.example.test/api/public/reports/one-time-token",
                ShareLinkExpiryChoice.DAYS_30, CREATED_AT, expiry, null);
        when(createService.create(ANALYSIS_ID, guestOwner, ShareLinkExpiryChoice.DAYS_30)).thenReturn(result);

        mockMvc.perform(post("/api/analyses/{analysisId}/share-links", ANALYSIS_ID)
                        .contentType("application/json")
                        .content("{\"expiresIn\":\"30_DAYS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresIn").value("30_DAYS"));

        verify(createService).create(ANALYSIS_ID, guestOwner, ShareLinkExpiryChoice.DAYS_30);
    }

    @Test
    void missingOwnerReturnsAnalysisNotFoundWithoutCallingCreationService() throws Exception {
        when(ownerResolver.resolveForReadPrincipal(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{analysisId}/share-links", ANALYSIS_ID)
                        .contentType("application/json")
                        .content("{\"expiresIn\":\"24_HOURS\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));

        verifyNoInteractions(createService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    @Test
    void missingNullMalformedUnknownAndUnsupportedValuesReturnSafeBadRequest() throws Exception {
        for (String requestBody : new String[] {
                "{}",
                "{\"expiresIn\":null}",
                "{\"expiresIn\":\"1_DAY\"}",
                "{\"expiresIn\":\"24_HOURS\",\"expiresIn\":\"7_DAYS\"}",
                "{\"expiresIn\":\"7_DAYS\",\"publicUrl\":\"https://attacker.invalid\"}",
                "{\"expiresIn\":\"7_DAYS\""
        }) {
            String response = mockMvc.perform(post("/api/analyses/{analysisId}/share-links", ANALYSIS_ID)
                            .contentType("application/json")
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SHARE_LINK_REQUEST"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains(RAW_TOKEN));
            assertFalse(response.contains("attacker.invalid"));
        }
        verifyNoInteractions(createService);
    }

    @Test
    void mapsCreationFailuresToSafeStableResponses() throws Exception {
        assertServiceFailure(new ShareLinkLimitReachedException(), 409, "SHARE_LINK_LIMIT_REACHED");
        assertServiceFailure(new ShareLinkNotShareableException(), 409, "ANALYSIS_NOT_SHAREABLE");
        assertServiceFailure(new ShareExpiryUnavailableException(), 409, "SHARE_EXPIRY_UNAVAILABLE");
        assertServiceFailure(new ShareLinkCreationException(), 500, "SHARE_LINK_CREATION_FAILED");
    }

    @Test
    void mapsPersistenceFailuresToSafeStableErrorsWithoutLeakingDatabaseDetails() throws Exception {
        String sensitiveDetails = "insert into share_links token_hash=private-hash jdbc-secret";
        doThrow(new org.springframework.dao.DataIntegrityViolationException(sensitiveDetails))
                .when(createService).create(ANALYSIS_ID, guestOwner, ShareLinkExpiryChoice.DAYS_7);

        String createBody = mockMvc.perform(post("/api/analyses/{analysisId}/share-links", ANALYSIS_ID)
                        .contentType("application/json")
                        .content("{\"expiresIn\":\"7_DAYS\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SHARE_LINK_CREATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(createBody.contains(sensitiveDetails));
        assertFalse(createBody.contains("private-hash"));
        assertFalse(createBody.contains("jdbc-secret"));

        doThrow(new org.springframework.dao.DataAccessResourceFailureException(sensitiveDetails))
                .when(listService).list(ANALYSIS_ID, guestOwner, 0, 20);
        String listBody = mockMvc.perform(get("/api/analyses/{analysisId}/share-links", ANALYSIS_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SHARE_LINK_OPERATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(listBody.contains(sensitiveDetails));

        UUID shareId = UUID.randomUUID();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException(sensitiveDetails))
                .when(revokeService).revoke(ANALYSIS_ID, guestOwner, shareId);
        String revokeBody = mockMvc.perform(delete(
                        "/api/analyses/{analysisId}/share-links/{shareId}", ANALYSIS_ID, shareId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SHARE_LINK_OPERATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(revokeBody.contains(sensitiveDetails));
    }

    @Test
    void listsOwnerMetadataWithDefaultPaginationNoStoreAndNoTokenFields() throws Exception {
        UUID linkId = UUID.randomUUID();
        ShareLinkListing listing = new ShareLinkListing(List.of(
                new ShareLinkListItem(linkId, CREATED_AT, EXPIRES_AT, null, ShareLinkStatus.ACTIVE)),
                0, 20, 1);
        when(listService.list(ANALYSIS_ID, guestOwner, 0, 20)).thenReturn(listing);

        String body = mockMvc.perform(get("/api/analyses/{analysisId}/share-links", ANALYSIS_ID)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.links[0].id").value(linkId.toString()))
                .andExpect(jsonPath("$.links[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andReturn().getResponse().getContentAsString();

        assertEquals(Set.of("links", "page", "size", "totalElements", "totalPages", "hasNext"),
                fieldNames(body));
        JsonNode link = objectMapper.readTree(body).get("links").get(0);
        assertEquals(Set.of("id", "createdAt", "expiresAt", "revokedAt", "status"),
                new java.util.HashSet<>(link.propertyNames()));
        assertFalse(body.contains("publicUrl"));
        assertFalse(body.contains("tokenHash"));
        assertFalse(body.contains("rawToken"));

        var order = inOrder(ownerResolver, listService);
        order.verify(ownerResolver).resolveForReadPrincipal(isNull(), any(HttpServletRequest.class));
        order.verify(listService).list(ANALYSIS_ID, guestOwner, 0, 20);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(createService);
    }

    @Test
    void listsWithExplicitPageAndSize() throws Exception {
        when(listService.list(ANALYSIS_ID, guestOwner, 3, 5))
                .thenReturn(new ShareLinkListing(List.of(), 3, 5, 15));

        mockMvc.perform(get("/api/analyses/{analysisId}/share-links?page=3&size=5", ANALYSIS_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(15))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(false));

        verify(listService).list(ANALYSIS_ID, guestOwner, 3, 5);
    }

    @Test
    void invalidPageAndSizeReturnSpecificSafeQueryError() throws Exception {
        doThrow(new InvalidShareLinkQueryException()).when(listService).list(ANALYSIS_ID, guestOwner, -1, 20);
        doThrow(new InvalidShareLinkQueryException()).when(listService).list(ANALYSIS_ID, guestOwner, 1001, 20);
        doThrow(new InvalidShareLinkQueryException()).when(listService).list(ANALYSIS_ID, guestOwner, 0, 0);
        doThrow(new InvalidShareLinkQueryException()).when(listService).list(ANALYSIS_ID, guestOwner, 0, 101);

        for (String query : new String[] {
                "?page=-1", "?page=1001", "?page=invalid", "?size=0", "?size=101", "?size=invalid"
        }) {
            mockMvc.perform(get("/api/analyses/{analysisId}/share-links" + query, ANALYSIS_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SHARE_LINK_QUERY"));
        }

        verify(listService).list(ANALYSIS_ID, guestOwner, -1, 20);
        verify(listService).list(ANALYSIS_ID, guestOwner, 1001, 20);
        verify(listService).list(ANALYSIS_ID, guestOwner, 0, 0);
        verify(listService).list(ANALYSIS_ID, guestOwner, 0, 101);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void missingOwnerDoesNotCallListService() throws Exception {
        when(ownerResolver.resolveForReadPrincipal(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{analysisId}/share-links", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));

        verifyNoInteractions(listService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    @Test
    void revokesGuestOwnedLinkWithNoContentAndNoStore() throws Exception {
        UUID shareId = UUID.randomUUID();

        mockMvc.perform(delete("/api/analyses/{analysisId}/share-links/{shareId}", ANALYSIS_ID, shareId)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        var order = inOrder(ownerResolver, revokeService);
        order.verify(ownerResolver).resolveForReadPrincipal(isNull(), any(HttpServletRequest.class));
        order.verify(revokeService).revoke(ANALYSIS_ID, guestOwner, shareId);
        order.verify(ownerResolver).recordSuccessfulActivity(guestOwner);
        verifyNoInteractions(createService, listService);
    }

    @Test
    void authenticatedIdentityTakesPrecedenceOverGuestCookieWhenRevoking() throws Exception {
        UUID shareId = UUID.randomUUID();
        when(ownerResolver.resolveForReadPrincipal(eq(identity), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(registeredOwner));
        authenticate(identity);

        mockMvc.perform(delete("/api/analyses/{analysisId}/share-links/{shareId}", ANALYSIS_ID, shareId)
                        .cookie(new Cookie("INVOWARD_GUEST", GUEST_ID.toString())))
                .andExpect(status().isNoContent());

        verify(ownerResolver).resolveForReadPrincipal(eq(identity), any(HttpServletRequest.class));
        verify(revokeService).revoke(ANALYSIS_ID, registeredOwner, shareId);
        verify(ownerResolver).recordSuccessfulActivity(registeredOwner);
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    @Test
    void repeatedDeleteRemainsNoContent() throws Exception {
        UUID shareId = UUID.randomUUID();
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(delete("/api/analyses/{analysisId}/share-links/{shareId}", ANALYSIS_ID, shareId))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
        }

        verify(revokeService, times(2)).revoke(ANALYSIS_ID, guestOwner, shareId);
    }

    @Test
    void unknownShareIdReturnsSafeScopedNotFoundAfterOwnerResolution() throws Exception {
        UUID shareId = UUID.randomUUID();
        doThrow(new ShareLinkNotFoundException()).when(revokeService).revoke(ANALYSIS_ID, guestOwner, shareId);

        mockMvc.perform(delete("/api/analyses/{analysisId}/share-links/{shareId}", ANALYSIS_ID, shareId))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {"code":"SHARE_LINK_NOT_FOUND","message":"Share link was not found."}
                        """));

        var order = inOrder(ownerResolver, revokeService);
        order.verify(ownerResolver).resolveForReadPrincipal(isNull(), any(HttpServletRequest.class));
        order.verify(revokeService).revoke(ANALYSIS_ID, guestOwner, shareId);
        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    @Test
    void foreignParentReturnsAnalysisNotFoundWithoutSuccessfulActivity() throws Exception {
        UUID shareId = UUID.randomUUID();
        doThrow(new AnalysisNotFoundException()).when(revokeService).revoke(ANALYSIS_ID, guestOwner, shareId);

        mockMvc.perform(delete("/api/analyses/{analysisId}/share-links/{shareId}", ANALYSIS_ID, shareId))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));

        verify(revokeService).revoke(ANALYSIS_ID, guestOwner, shareId);
        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    @Test
    void missingOwnerDoesNotCallRevokeService() throws Exception {
        when(ownerResolver.resolveForReadPrincipal(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/analyses/{analysisId}/share-links/{shareId}", ANALYSIS_ID, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));

        verifyNoInteractions(revokeService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any());
    }

    private void assertServiceFailure(RuntimeException failure, int statusCode, String code) throws Exception {
        doThrow(failure).when(createService).create(ANALYSIS_ID, guestOwner, ShareLinkExpiryChoice.DAYS_7);

        String response = mockMvc.perform(post("/api/analyses/{analysisId}/share-links", ANALYSIS_ID)
                        .contentType("application/json")
                        .content("{\"expiresIn\":\"7_DAYS\"}"))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(RAW_TOKEN));
        verify(ownerResolver, never()).recordSuccessfulActivity(guestOwner);
    }

    private static CreatedShareLink created() {
        return new CreatedShareLink(UUID.randomUUID(),
                "https://app.example.test/api/public/reports/" + RAW_TOKEN,
                ShareLinkExpiryChoice.DAYS_7, CREATED_AT, EXPIRES_AT, null);
    }

    private Set<String> fieldNames(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        java.util.HashSet<String> names = new java.util.HashSet<>();
        names.addAll(root.propertyNames());
        return names;
    }

    private static void authenticate(AuthenticatedIdentity principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "N/A"));
        SecurityContextHolder.setContext(context);
    }
}
