package io.github.guillermodubon.invoward.identity.infrastructure.security;

import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.application.service.CurrentAccountService;
import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.api.GuestSessionCookieSupport;
import io.github.guillermodubon.invoward.analysis.application.service.CreateAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataEmailVerificationTokenJpaRepository;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository.SpringDataAnalysisJobJpaRepository;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository.SpringDataAnalysisJpaRepository;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.repository.SpringDataDocumentJpaRepository;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataPasswordResetTokenJpaRepository;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataUserJpaRepository;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataGuestSessionJpaRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
class SecurityConfigurationTest {

    @MockitoBean
    private SpringDataUserJpaRepository userJpaRepository;

    @MockitoBean
    private SpringDataEmailVerificationTokenJpaRepository emailVerificationTokenJpaRepository;

    @MockitoBean
    private SpringDataPasswordResetTokenJpaRepository passwordResetTokenJpaRepository;

    @MockitoBean
    private SpringDataAnalysisJpaRepository analysisJpaRepository;

    @MockitoBean
    private SpringDataAnalysisJobJpaRepository analysisJobJpaRepository;

    @MockitoBean
    private SpringDataGuestSessionJpaRepository guestSessionJpaRepository;

    @MockitoBean
    private SpringDataDocumentJpaRepository documentJpaRepository;

    @MockitoBean
    private CurrentAccountService currentAccountService;

    @MockitoBean
    private AnalysisRequestOwnerResolver analysisRequestOwnerResolver;

    @MockitoBean
    private CreateAnalysisService createAnalysisService;

    @MockitoBean
    private GuestSessionCookieSupport guestSessionCookieSupport;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Autowired
    private SecurityContextRepository securityContextRepository;

    @Autowired
    private CsrfTokenRepository csrfTokenRepository;

    @Autowired
    private org.springframework.security.web.authentication.session.SessionAuthenticationStrategy sessionAuthenticationStrategy;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private Environment environment;

    @Test
    void securityChainUsesJsonLoginAndDisablesBrowserAuthenticationMechanisms() {
        List<Filter> filters = filterChainProxy.getFilters("/api/auth/login");

        assertEquals(1, filters.stream().filter(JsonUsernamePasswordAuthenticationFilter.class::isInstance).count());
        assertTrue(filters.stream().anyMatch(CsrfFilter.class::isInstance));
        assertTrue(filters.stream().anyMatch(LogoutFilter.class::isInstance));
        assertTrue(filters.stream().anyMatch(ConcurrentSessionFilter.class::isInstance));
        assertTrue(filters.stream().anyMatch(AuthorizationFilter.class::isInstance));
        assertFalse(filters.stream().anyMatch(UsernamePasswordAuthenticationFilter.class::isInstance));
        assertFalse(filters.stream().anyMatch(BasicAuthenticationFilter.class::isInstance));
        assertFalse(filters.stream().anyMatch(filter -> filter.getClass().getSimpleName().equals("RememberMeAuthenticationFilter")));
        assertFalse(filters.stream().anyMatch(filter -> filter.getClass().getSimpleName()
                .equals("DefaultLoginPageGeneratingFilter")));
    }

    @Test
    void securityUsesSessionBackedContextCsrfAndRequiredOnlySessionPolicy() throws Exception {
        assertInstanceOf(HttpSessionSecurityContextRepository.class, securityContextRepository);
        assertInstanceOf(CompositeSessionAuthenticationStrategy.class, sessionAuthenticationStrategy);
        assertInstanceOf(SessionRegistryImpl.class, sessionRegistry);
        assertInstanceOf(org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.class,
                csrfTokenRepository);
        assertEquals("X-CSRF-TOKEN", csrfTokenRepository.generateToken(new MockHttpServletRequest()).getHeaderName());
        assertEquals("INVOWARD_SESSION", environment.getProperty("server.servlet.session.cookie.name"));
        assertEquals("true", environment.getProperty("server.servlet.session.cookie.http-only"));
        assertEquals("false", environment.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("lax", environment.getProperty("server.servlet.session.cookie.same-site"));
        assertEquals("cookie", environment.getProperty("server.servlet.session.tracking-modes"));
        assertEquals("30m", environment.getProperty("server.servlet.session.timeout"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("""
                        {"code":"AUTHENTICATION_REQUIRED","message":"Authentication is required."}
                        """))
                .andExpect(result -> assertNull(result.getRequest().getSession(false)));
        mockMvc.perform(get("/api/private/unlisted"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analysisAndDocumentGuestRoutesAreNarrowlyPublicAndKeepCsrfEnabled() throws Exception {
        UUID analysisId = UUID.randomUUID();
        when(analysisRequestOwnerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{id}", analysisId))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));
        mockMvc.perform(get("/api/analyses/{id}/status", analysisId))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        assertCsrfFailure(post("/api/analyses")
                .contentType(APPLICATION_JSON)
                .content("{}"));
        mockMvc.perform(post("/api/analyses")
                        .header("X-CSRF-TOKEN", "invalid-token")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("""
                        {"code":"CSRF_INVALID","message":"The request could not be validated."}
                        """));

        GuestSessionOwner owner = new GuestSessionOwner(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(24)));
        Analysis analysis = Analysis.create(UUID.randomUUID(), owner, PriceTolerance.exactMatch(), Instant.now());
        when(analysisRequestOwnerResolver.resolveForCreate(isNull(), any(HttpServletRequest.class)))
                .thenReturn(owner);
        when(createAnalysisService.create(eq(owner), eq(PriceTolerance.exactMatch()))).thenReturn(analysis);
        when(guestSessionCookieSupport.createGuestSessionCookie(owner)).thenReturn(ResponseCookie
                .from(GuestSessionCookieSupport.COOKIE_NAME, owner.guestSessionId().toString())
                .httpOnly(true)
                .sameSite("Lax")
                .path(GuestSessionCookieSupport.COOKIE_PATH)
                .build());

        mockMvc.perform(post("/api/analyses")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf().asHeader())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/analyses/{id}/documents", analysisId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/analyses/{id}/documents/{documentId}", analysisId, UUID.randomUUID()))
                .andExpect(status().isNotFound());
        assertCsrfFailure(post("/api/analyses/{id}/documents", analysisId)
                .contentType("multipart/form-data; boundary=InvoWardBoundary")
                .content("--InvoWardBoundary--\r\n"));
        mockMvc.perform(get("/api/analyses/{id}/documents/{documentId}/extra",
                        analysisId, UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/analyses/{id}/status/extra", analysisId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/analyses/{id}/status", analysisId)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf().asHeader()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void csrfIsRequiredForRegistrationLoginAndLogout() throws Exception {
        assertCsrfFailure(post("/api/auth/register").contentType(APPLICATION_JSON).content("{}"));
        assertCsrfFailure(post("/api/auth/login").contentType(APPLICATION_JSON).content("{}"));
        assertCsrfFailure(post("/api/auth/logout"));
        assertLifecycleCsrfFailure("/api/auth/verify-email", "{\"token\":\"token\"}");
        assertLifecycleCsrfFailure("/api/auth/resend-verification", "{\"email\":\"user@example.com\"}");
        assertLifecycleCsrfFailure("/api/auth/forgot-password", "{\"email\":\"user@example.com\"}");
        assertLifecycleCsrfFailure("/api/auth/reset-password",
                "{\"token\":\"token\",\"newPassword\":\"a long enough password\"}");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .header("X-CSRF-TOKEN", "invalid-token")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"CSRF_INVALID","message":"The request could not be validated."}
                        """));
    }

    @Test
    void newLifecycleRoutesArePublicForAuthenticationButStillRequireValidCsrf() throws Exception {
        assertPublicLifecycleRoute("/api/auth/verify-email");
        assertPublicLifecycleRoute("/api/auth/resend-verification");
        assertPublicLifecycleRoute("/api/auth/forgot-password");
        assertPublicLifecycleRoute("/api/auth/reset-password");
    }

    @Test
    void accountMutationRoutesRequireAuthenticationAndCsrf() throws Exception {
        assertAccountMutationSecurity("PATCH", "/api/account/profile", "{}");
        assertAccountMutationSecurity("POST", "/api/account/change-password", "{}");
        assertAccountMutationSecurity("POST", "/api/account/change-email", "{}");
        assertAccountMutationSecurity("POST", "/api/account/confirm-email-change", "{}");
    }

    @Test
    void validCsrfAllowsPublicRegistrationAndLoginRoutesToReachTheirHandlers() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()
                                .asHeader())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/login")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()
                                .asHeader())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedSecurityContextLoadsFromAndPersistsInHttpSession() throws Exception {
        MockHttpSession session = authenticatedSession();
        UUID userId = UUID.fromString("a3d9151b-50d3-42c4-9c08-9637795af0c1");
        when(currentAccountService.get(userId)).thenReturn(new CurrentAccount(
                userId, "Fresh Test User", "fresh@example.com", true, UserStatus.ACTIVE));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("""
                        {"id":"a3d9151b-50d3-42c4-9c08-9637795af0c1",
                        "displayName":"Fresh Test User",
                         "email":"fresh@example.com",
                         "emailVerified":true,
                         "status":"ACTIVE"}
                        """))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertEquals(5, objectMapper.readTree(body).size());
                    assertFalse(body.contains("test-password-hash"));
                    assertFalse(body.toLowerCase().contains("session"));
                    assertFalse(body.toLowerCase().contains("csrf"));
                });
        assertNotNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    @Test
    void csrfBootstrapReturnsSessionBackedTokenAndHeaderName() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertTrue(body.has("token"));
        assertFalse(body.get("token").asString().isBlank());
        assertEquals("X-CSRF-TOKEN", body.get("headerName").asString());
        assertEquals(2, body.size());
        assertNotNull(result.getRequest().getSession(false));

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(post("/api/auth/register")
                        .session(session)
                        .header(body.get("headerName").asString(), body.get("token").asString())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void successfulAuthenticationStrategyChangesSessionIdAndInvalidatesPreviousCsrfToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setSession(session);
        CsrfToken previousToken = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(previousToken, request, response);
        String previousSessionId = session.getId();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                "session-user", "credentials-not-used", List.of());

        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

        assertNotEquals(previousSessionId, session.getId());
        assertNull(csrfTokenRepository.loadToken(request));
        SessionInformation registeredSession = sessionRegistry.getSessionInformation(session.getId());
        assertNotNull(registeredSession);
        assertEquals(authentication.getPrincipal(), registeredSession.getPrincipal());
        assertNull(sessionRegistry.getSessionInformation(previousSessionId));
        sessionRegistry.removeSessionInformation(session.getId());
    }

    @Test
    void authenticatedLogoutRequiresCsrfThenInvalidatesSessionAndDeletesCookie() throws Exception {
        MockHttpSession session = authenticatedSessionWithCsrf();
        CsrfToken token = (CsrfToken) session.getAttribute(
                org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.class.getName()
                        + ".CSRF_TOKEN");
        assertNotNull(token);

        mockMvc.perform(post("/api/auth/logout")
                        .session(session)
                        .header("X-CSRF-TOKEN", token.getToken()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("INVOWARD_SESSION=")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        assertTrue(session.isInvalid());
    }

    @Test
    void authenticatedLogoutWithoutSessionAuthenticationIsRejectedAsJson() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()
                                .asHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {"code":"AUTHENTICATION_REQUIRED","message":"Authentication is required."}
                        """));
    }

    @Test
    void authenticatedAuthorizationDenialUsesGenericJsonResponse() throws Exception {
        JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(
                new tools.jackson.databind.ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("sensitive detail"));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ACCESS_DENIED"));
        assertTrue(response.getContentAsString().contains("Access is denied."));
        assertFalse(response.getContentAsString().contains("sensitive detail"));
    }

    private void assertCsrfFailure(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("""
                        {"code":"CSRF_INVALID","message":"The request could not be validated."}
                """));
    }

    private void assertLifecycleCsrfFailure(String path, String body) throws Exception {
        assertCsrfFailure(post(path).contentType(APPLICATION_JSON).content(body));
        mockMvc.perform(post(path)
                        .header("X-CSRF-TOKEN", "invalid-token")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"CSRF_INVALID","message":"The request could not be validated."}
                        """));
    }

    private void assertPublicLifecycleRoute(String path) throws Exception {
        mockMvc.perform(post(path)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf().asHeader())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("""
                        {"code":"VALIDATION_FAILED","message":"The request contains invalid data."}
                        """));
    }

    private void assertAccountMutationSecurity(String method, String path, String invalidBody) throws Exception {
        MockHttpServletRequestBuilder anonymousWithCsrf = accountRequest(method, path, invalidBody)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .csrf().asHeader());
        mockMvc.perform(anonymousWithCsrf)
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {"code":"AUTHENTICATION_REQUIRED","message":"Authentication is required."}
                        """));

        mockMvc.perform(accountRequest(method, path, invalidBody).session(authenticatedSession()))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"CSRF_INVALID","message":"The request could not be validated."}
                        """));

        mockMvc.perform(accountRequest(method, path, invalidBody)
                        .session(authenticatedSession())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json("""
                        {"code":"VALIDATION_FAILED","message":"The request contains invalid data."}
                        """));
    }

    private static MockHttpServletRequestBuilder accountRequest(String method, String path, String body) {
        return request(org.springframework.http.HttpMethod.valueOf(method), path)
                .contentType(APPLICATION_JSON)
                .content(body);
    }

    private MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUserPrincipal(new UserAccount(
                        UUID.fromString("a3d9151b-50d3-42c4-9c08-9637795af0c1"),
                        "Test User",
                        "test@example.com",
                        "test-password-hash",
                        true,
                        UserStatus.ACTIVE,
                        0,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z"))),
                "credentials-not-used",
                List.of());
        context.setAuthentication(authentication);
        securityContextRepository.saveContext(context, request, new MockHttpServletResponse());
        return session;
    }

    private MockHttpSession authenticatedSessionWithCsrf() {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, new MockHttpServletResponse());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "session-user", "credentials-not-used", List.of()));
        securityContextRepository.saveContext(context, request, new MockHttpServletResponse());
        return session;
    }
}
