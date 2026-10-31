package io.github.guillermodubon.invoward.sharing.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.sharing.application.model.GeneratedShareToken;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(
        properties = {
                "invoward.email.provider=disabled",
                "spring.ai.model.chat=none"
        })
@AutoConfigureMockMvc
class PublicSharedReportHttpIntegrationIT {

    private static final String PUBLIC_NOT_FOUND =
            "{\"code\":\"SHARE_LINK_NOT_FOUND\",\"message\":\"Shared report was not found.\"}";
    private static final String LOGIN_PASSWORD = "correct horse battery staple";
    private static final String PRIVATE_SUPPLIER_KEY = "private-supplier-key-sentinel";
    private static final String PRIVATE_STORAGE_KEY = "private-report-storage-key-sentinel";
    private static final String PRIVATE_HASH = "e".repeat(64);

    private final List<UUID> analysisIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> guestSessionIds = new ArrayList<>();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private GeneratedReportRepository generatedReportRepository;

    @Autowired
    private ShareLinkRepository shareLinkRepository;

    @Autowired
    private ShareTokenGenerator tokenGenerator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SessionRegistry sessionRegistry;

    @MockitoBean
    private GetReconciliationService reconciliationService;

    @AfterEach
    void cleanFixtures() {
        analysisIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", id));
        guestSessionIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.guest_sessions WHERE id = ?", id));
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.users WHERE id = ?", id));
        analysisIds.clear();
        guestSessionIds.clear();
        userIds.clear();
    }

    @Test
    void anonymousAndUnrelatedAuthenticatedRequestsReceiveTheSameWhitelistedReportWithoutCookies()
            throws Exception {
        Analysis analysis = createRegisteredAnalysis();
        addGeneratedReport(analysis, null);
        GeneratedShareToken token = createShareLink(analysis, Instant.now().plus(Duration.ofHours(12)), null);
        when(reconciliationService.get(eq(analysis.id()), eq(analysis.owner())))
                .thenReturn(reconciliation(analysis));
        int registeredSessionsBeforeAnonymousRead = registeredSessionCount();

        MvcResult anonymous = getPublicReport(token.rawToken(), null);

        assertEquals(200, anonymous.getResponse().getStatus(), anonymous.getResponse().getContentAsString());
        assertPublicResponseHeaders(anonymous);
        assertNoSetCookie(anonymous);
        assertNull(anonymous.getRequest().getSession(false),
                "An anonymous public read must not create an HttpSession.");
        assertEquals(registeredSessionsBeforeAnonymousRead, registeredSessionCount(),
                "The anonymous public request must not register or create an authenticated session.");
        assertPublicWhitelist(anonymous.getResponse().getContentAsString(), analysis, token.rawToken());

        MockHttpSession unrelatedSession = loginUnrelatedUser();
        try {
            MvcResult authenticated = getPublicReport(token.rawToken(), unrelatedSession);
            assertEquals(200, authenticated.getResponse().getStatus(),
                    authenticated.getResponse().getContentAsString());
            assertPublicResponseHeaders(authenticated);
            assertNoSetCookie(authenticated);
            assertEquals(anonymous.getResponse().getContentAsString(),
                    authenticated.getResponse().getContentAsString(),
                    "An unrelated authenticated session must not alter the public share projection.");
        } finally {
            logout(unrelatedSession);
        }
    }

    @Test
    void malformedUnknownRevokedExpiredGuestExpiredMissingParentAndMissingReportShareLinksAreUniform404()
            throws Exception {
        List<String> unavailableTokens = new ArrayList<>();
        unavailableTokens.add("not-a-canonical-share-token");
        unavailableTokens.add(tokenGenerator.generate().rawToken());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Analysis revokedAnalysis = createRegisteredAnalysis();
        addGeneratedReport(revokedAnalysis, null);
        unavailableTokens.add(createShareLink(
                revokedAnalysis, now.plus(Duration.ofHours(1)), now.minusSeconds(30)).rawToken());

        Analysis expiredLinkAnalysis = createRegisteredAnalysis();
        addGeneratedReport(expiredLinkAnalysis, null);
        unavailableTokens.add(createShareLink(
                expiredLinkAnalysis, now.minusSeconds(30), null).rawToken());

        Analysis expiredReportAnalysis = createRegisteredAnalysis();
        addGeneratedReport(expiredReportAnalysis, now.minusSeconds(10));
        unavailableTokens.add(createShareLink(
                expiredReportAnalysis, now.plus(Duration.ofHours(1)), null).rawToken());

        Analysis expiredGuestAnalysis = createExpiredGuestAnalysis();
        addGeneratedReport(expiredGuestAnalysis, expiredGuestAnalysis.expiresAt());
        unavailableTokens.add(createShareLink(
                expiredGuestAnalysis, now.plus(Duration.ofHours(1)), null).rawToken());

        Analysis deletedParent = createRegisteredAnalysis();
        addGeneratedReport(deletedParent, null);
        GeneratedShareToken deletedParentToken = createShareLink(
                deletedParent, now.plus(Duration.ofHours(1)), null);
        jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", deletedParent.id());
        unavailableTokens.add(deletedParentToken.rawToken());

        Analysis missingReportAnalysis = createRegisteredAnalysis();
        unavailableTokens.add(createShareLink(
                missingReportAnalysis, now.plus(Duration.ofHours(1)), null).rawToken());

        String expectedBody = null;
        for (String rawToken : unavailableTokens) {
            MvcResult response = getPublicReport(rawToken, null);
            assertEquals(404, response.getResponse().getStatus(), response.getResponse().getContentAsString());
            assertPublicResponseHeaders(response);
            assertNoSetCookie(response);
            if (expectedBody == null) {
                expectedBody = response.getResponse().getContentAsString();
            } else {
                assertEquals(expectedBody, response.getResponse().getContentAsString(),
                        "Unavailable capabilities must have a uniform response.");
            }
            assertEquals(PUBLIC_NOT_FOUND, response.getResponse().getContentAsString());
            assertNull(response.getRequest().getSession(false),
                    "An invalid public token must not create an HttpSession.");
        }
    }

    private Analysis createRegisteredAnalysis() {
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        userIds.add(userId);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(300);
        Instant completedAt = createdAt.plusSeconds(60);
        Analysis analysis = new Analysis(
                UUID.randomUUID(), new RegisteredUserOwner(userId), AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, null, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Public integration supplier", PRIVATE_SUPPLIER_KEY, "QUOTE", "REF-PUBLIC", "INV-PUBLIC", "USD",
                new BigDecimal("100.0000"), new BigDecimal("110.0000"), new BigDecimal("10.0000"),
                PriceTolerance.exactMatch(), false, null, null, 0,
                completedAt, null, createdAt, completedAt);
        Analysis persisted = analysisRepository.create(analysis);
        analysisIds.add(persisted.id());
        return persisted;
    }

    private Analysis createExpiredGuestAnalysis() {
        UUID guestSessionId = UUID.randomUUID();
        Instant expiresAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(30);
        Instant createdAt = expiresAt.minus(Duration.ofHours(24));
        Instant lastSeenAt = expiresAt.minusSeconds(30);
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, guestSessionId, Timestamp.from(expiresAt), Timestamp.from(lastSeenAt), Timestamp.from(createdAt));
        guestSessionIds.add(guestSessionId);

        Instant completedAt = createdAt.plusSeconds(60);
        Analysis analysis = new Analysis(
                UUID.randomUUID(), new GuestSessionOwner(guestSessionId, expiresAt), AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING, null, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Expired guest supplier", PRIVATE_SUPPLIER_KEY, "QUOTE", "REF-EXPIRED", "INV-EXPIRED", "USD",
                new BigDecimal("100.0000"), new BigDecimal("110.0000"), new BigDecimal("10.0000"),
                PriceTolerance.exactMatch(), false, null, null, 0,
                completedAt, expiresAt, createdAt, completedAt);
        Analysis persisted = analysisRepository.create(analysis);
        analysisIds.add(persisted.id());
        return persisted;
    }

    private void addGeneratedReport(Analysis analysis, Instant expiresAt) {
        generatedReportRepository.create(new GeneratedReport(
                UUID.randomUUID(), analysis.id(), PRIVATE_STORAGE_KEY + "/" + analysis.id(),
                "application/pdf", 128, PRIVATE_HASH, expiresAt, analysis.completedAt()));
    }

    private GeneratedShareToken createShareLink(Analysis analysis, Instant expiresAt, Instant revokedAt) {
        GeneratedShareToken token = tokenGenerator.generate();
        Instant createdAt = expiresAt.isBefore(Instant.now())
                ? expiresAt.minus(Duration.ofHours(1))
                : Instant.now().minus(Duration.ofMinutes(1));
        shareLinkRepository.create(new ShareLink(
                UUID.randomUUID(), analysis.id(), token.tokenHash(), createdAt, expiresAt, revokedAt));
        return token;
    }

    private MockHttpSession loginUnrelatedUser() throws Exception {
        String email = "public-http-" + UUID.randomUUID() + "@example.test";
        UUID userId = UUID.randomUUID();
        userIds.add(userId);
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Unrelated Public Reader', ?, ?, true, ?)
                """, userId, email, passwordEncoder.encode(LOGIN_PASSWORD), UserStatus.ACTIVE.name());

        CsrfTicket csrf = csrfTicket(null);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", LOGIN_PASSWORD))))
                .andReturn();
        assertEquals(200, login.getResponse().getStatus(), login.getResponse().getContentAsString());
        MockHttpSession authenticatedSession = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(authenticatedSession, "Login should establish a server-side session.");
        return authenticatedSession;
    }

    private void logout(MockHttpSession session) throws Exception {
        CsrfTicket csrf = csrfTicket(session);
        MvcResult response = mockMvc.perform(post("/api/auth/logout")
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertEquals(204, response.getResponse().getStatus(), response.getResponse().getContentAsString());
    }

    private CsrfTicket csrfTicket(MockHttpSession existingSession) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = get("/api/auth/csrf");
        if (existingSession != null) {
            request.session(existingSession);
        }
        MvcResult response = mockMvc.perform(request).andReturn();
        assertEquals(200, response.getResponse().getStatus(), response.getResponse().getContentAsString());
        JsonNode body = objectMapper.readTree(response.getResponse().getContentAsByteArray());
        MockHttpSession session = (MockHttpSession) response.getRequest().getSession(false);
        assertNotNull(session, "CSRF bootstrap should create a session for the unrelated login flow.");
        return new CsrfTicket(session, body.get("headerName").asString(), body.get("token").asString());
    }

    private MvcResult getPublicReport(String rawToken, MockHttpSession session) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                get("/api/public/reports/{token}", rawToken).accept(APPLICATION_JSON);
        if (session != null) {
            request.session(session);
        }
        return mockMvc.perform(request).andReturn();
    }

    private void assertPublicWhitelist(String body, Analysis analysis, String rawToken) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        assertEquals(Set.of(
                "reportGeneratedAt", "supplierName", "referenceType", "referenceNumber", "invoiceNumber",
                "currency", "reconciliationStatus", "referenceTotal", "invoiceTotal", "difference",
                "tolerance", "summary", "lines", "discrepancies"), propertyNames(root));
        assertEquals("Public integration supplier", root.get("supplierName").asString());
        assertEquals("INV-PUBLIC", root.get("invoiceNumber").asString());
        assertEquals("REVIEW_REQUIRED", root.get("reconciliationStatus").asString());
        for (String privateValue : List.of(
                analysis.id().toString(), analysis.owner().toString(), PRIVATE_SUPPLIER_KEY,
                PRIVATE_STORAGE_KEY, PRIVATE_HASH, rawToken)) {
            assertFalse(body.contains(privateValue), "The public response must not expose " + privateValue);
        }
        for (String privateField : List.of(
                "userId", "guestSessionId", "analysisId", "storageKey", "sha256", "resolutionNote",
                "resolutionReason", "evidence", "publicUrl", "reportUrl")) {
            assertFalse(body.contains("\"" + privateField + "\""),
                    "The public response must not contain field " + privateField);
        }
    }

    private static void assertPublicResponseHeaders(MvcResult response) {
        assertEquals("no-store", response.getResponse().getHeader("Cache-Control"));
        assertEquals("no-referrer", response.getResponse().getHeader("Referrer-Policy"));
        assertEquals("nosniff", response.getResponse().getHeader("X-Content-Type-Options"));
        assertEquals("application/json", response.getResponse().getContentType().split(";", 2)[0]);
    }

    private static void assertNoSetCookie(MvcResult response) {
        assertTrue(response.getResponse().getHeaders("Set-Cookie").isEmpty(),
                "Public report reads must not create or refresh session/guest cookies.");
    }

    private int registeredSessionCount() {
        return sessionRegistry.getAllPrincipals().stream()
                .mapToInt(principal -> sessionRegistry.getAllSessions(principal, false).size())
                .sum();
    }

    private static Set<String> propertyNames(JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static ReconciliationResult reconciliation(Analysis analysis) {
        return new ReconciliationResult(
                analysis.id(), AnalysisStatus.COMPLETED, analysis.reconciliationStatus(), analysis.priceTolerance(),
                analysis.referenceTotal(), analysis.invoicedTotal(), analysis.difference(),
                DuplicateInvoiceWarning.none(), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of());
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    private record CsrfTicket(MockHttpSession session, String headerName, String token) {
    }
}
