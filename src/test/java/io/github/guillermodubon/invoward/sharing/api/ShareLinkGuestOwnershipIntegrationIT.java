package io.github.guillermodubon.invoward.sharing.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "invoward.email.provider=disabled",
                "spring.ai.model.chat=none",
                "invoward.guest.session-ttl=24h",
                "invoward.guest.cookie.secure=false",
                "invoward.guest.cookie.same-site=lax"
        })
class ShareLinkGuestOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String NOT_FOUND_BODY =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";
    private static final Duration GUEST_TTL = Duration.ofHours(24);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final List<UUID> analysisIds = new ArrayList<>();
    private final List<UUID> guestSessionIds = new ArrayList<>();

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private GeneratedReportRepository generatedReportRepository;

    @MockitoBean
    private GetReconciliationService reconciliationService;

    @AfterEach
    void cleanFixtures() {
        analysisIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", id));
        guestSessionIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.guest_sessions WHERE id = ?", id));
        analysisIds.clear();
        guestSessionIds.clear();
    }

    @Test
    void guestOwnerCanCreateListAndRevokeLinksWithExpiryCappedAndSessionExpiryFixed() throws Exception {
        URI baseUri = applicationUri();
        CsrfTicket csrf = csrfTicket(baseUri);
        GuestAnalysis fixture = createCompletedGuestAnalysis();
        Instant initialLastSeen = fixture.createdAt();
        setLastSeenAt(fixture.guestSessionId(), initialLastSeen);
        long guestCountBefore = guestSessionCount();

        HttpResponse<String> created = createLink(
                baseUri, fixture.analysisId(), csrf, fixture.guestSessionId().toString(), true, "30_DAYS");

        assertEquals(201, created.statusCode(), created.body());
        UUID shareLinkId = UUID.fromString(objectMapper.readTree(created.body()).get("id").asString());
        assertEquals(fixture.expiresAt().toString(),
                objectMapper.readTree(created.body()).get("expiresAt").asString(),
                "A guest share link must not outlive its parent Analysis or its generated report.");
        assertEquals(fixture.expiresAt(), analysisExpiry(fixture.analysisId()));
        assertEquals(fixture.expiresAt(), generatedReportExpiry(fixture.analysisId()));
        assertEquals(guestCountBefore, guestSessionCount(), "Managing a link must reuse the existing guest session.");
        assertTrue(guestSession(fixture.guestSessionId()).lastSeenAt().isAfter(initialLastSeen));
        assertEquals(fixture.expiresAt(), guestSession(fixture.guestSessionId()).expiresAt(),
                "Successful activity must not slide the fixed guest-session expiry.");
        assertNoGuestCookieSet(created);

        setLastSeenAt(fixture.guestSessionId(), initialLastSeen);
        HttpResponse<String> listing = listLinks(
                baseUri, fixture.analysisId(), csrf, fixture.guestSessionId().toString());
        assertEquals(200, listing.statusCode(), listing.body());
        assertTrue(listing.body().contains(shareLinkId.toString()));
        assertFalse(listing.body().contains("publicUrl"));
        assertFalse(listing.body().contains("tokenHash"));
        assertFalse(listing.body().contains("rawToken"));
        assertTrue(guestSession(fixture.guestSessionId()).lastSeenAt().isAfter(initialLastSeen));
        assertEquals(fixture.expiresAt(), guestSession(fixture.guestSessionId()).expiresAt());
        assertNoGuestCookieSet(listing);

        setLastSeenAt(fixture.guestSessionId(), initialLastSeen);
        HttpResponse<String> revoked = revokeLink(
                baseUri, fixture.analysisId(), csrf, fixture.guestSessionId().toString(), shareLinkId, true);
        assertEquals(204, revoked.statusCode(), revoked.body());
        assertEquals(fixture.expiresAt(), guestSession(fixture.guestSessionId()).expiresAt());
        assertTrue(guestSession(fixture.guestSessionId()).lastSeenAt().isAfter(initialLastSeen));
        assertTrue(shareLinkRevokedAt(fixture.analysisId(), shareLinkId) != null);
        assertNoGuestCookieSet(revoked);
    }

    @Test
    void guestBMissingMalformedUnknownAndExpiredCookiesReturnUniformNotFoundWithoutCreatingGuests()
            throws Exception {
        URI baseUri = applicationUri();
        CsrfTicket csrf = csrfTicket(baseUri);
        GuestAnalysis guestA = createCompletedGuestAnalysis();
        GuestSessionFixture guestB = insertGuestSession(false);
        GuestSessionFixture expired = insertGuestSession(true);
        Instant guestALastSeen = guestSession(guestA.guestSessionId()).lastSeenAt();
        Instant guestBLastSeen = guestSession(guestB.id()).lastSeenAt();
        long guestCountBefore = guestSessionCount();

        List<String> invalidCookies = List.of(
                guestB.id().toString(),
                "not-a-uuid",
                UUID.randomUUID().toString(),
                expired.id().toString());
        List<HttpResponse<String>> failures = new ArrayList<>();
        failures.add(listLinks(baseUri, guestA.analysisId(), csrf, null));
        failures.add(createLink(baseUri, guestA.analysisId(), csrf, null, true, "7_DAYS"));
        failures.add(revokeLink(baseUri, guestA.analysisId(), csrf, null, UUID.randomUUID(), true));
        for (String invalidCookie : invalidCookies) {
            failures.add(listLinks(baseUri, guestA.analysisId(), csrf, invalidCookie));
            failures.add(createLink(baseUri, guestA.analysisId(), csrf, invalidCookie, true, "7_DAYS"));
            failures.add(revokeLink(baseUri, guestA.analysisId(), csrf, invalidCookie, UUID.randomUUID(), true));
        }

        for (HttpResponse<String> response : failures) {
            assertNotFound(response);
            assertNoGuestCookieSet(response);
        }
        assertEquals(guestCountBefore, guestSessionCount(),
                "Nested share-link routes must not create GuestSessions for missing or invalid cookies.");
        assertEquals(guestALastSeen, guestSession(guestA.guestSessionId()).lastSeenAt());
        assertEquals(guestBLastSeen, guestSession(guestB.id()).lastSeenAt());
        assertEquals(expired.expiresAt(), guestSession(expired.id()).expiresAt());
    }

    @Test
    void postAndDeleteRequireCsrfWhileGetDoesNot() throws Exception {
        URI baseUri = applicationUri();
        CsrfTicket csrf = csrfTicket(baseUri);
        GuestAnalysis fixture = createCompletedGuestAnalysis();
        Instant initialLastSeen = fixture.createdAt();
        setLastSeenAt(fixture.guestSessionId(), initialLastSeen);

        HttpResponse<String> missingPostCsrf = createLink(
                baseUri, fixture.analysisId(), csrf, fixture.guestSessionId().toString(), false, "24_HOURS");
        assertEquals(403, missingPostCsrf.statusCode());
        assertEquals(0, shareLinkCount(fixture.analysisId()));
        assertEquals(initialLastSeen, guestSession(fixture.guestSessionId()).lastSeenAt());

        HttpResponse<String> created = createLink(
                baseUri, fixture.analysisId(), csrf, fixture.guestSessionId().toString(), true, "24_HOURS");
        assertEquals(201, created.statusCode(), created.body());
        UUID shareLinkId = UUID.fromString(objectMapper.readTree(created.body()).get("id").asString());

        HttpResponse<String> listWithoutCsrf = listLinks(
                baseUri, fixture.analysisId(), csrf, fixture.guestSessionId().toString());
        assertEquals(200, listWithoutCsrf.statusCode(), listWithoutCsrf.body());

        setLastSeenAt(fixture.guestSessionId(), initialLastSeen);
        HttpResponse<String> missingDeleteCsrf = revokeLink(
                baseUri, fixture.analysisId(), csrf, fixture.guestSessionId().toString(), shareLinkId, false);
        assertEquals(403, missingDeleteCsrf.statusCode());
        assertNull(shareLinkRevokedAt(fixture.analysisId(), shareLinkId));
        assertEquals(initialLastSeen, guestSession(fixture.guestSessionId()).lastSeenAt());

        HttpResponse<String> revoked = revokeLink(
                baseUri, fixture.analysisId(), csrf, fixture.guestSessionId().toString(), shareLinkId, true);
        assertEquals(204, revoked.statusCode(), revoked.body());
        assertTrue(shareLinkRevokedAt(fixture.analysisId(), shareLinkId) != null);
        assertEquals(fixture.expiresAt(), guestSession(fixture.guestSessionId()).expiresAt());
    }

    private GuestAnalysis createCompletedGuestAnalysis() {
        GuestSessionFixture guest = insertGuestSession(false);
        GuestSessionOwner owner = new GuestSessionOwner(guest.id(), guest.expiresAt());
        Instant completedAt = guest.createdAt().plusSeconds(60);
        Analysis analysis = new Analysis(
                UUID.randomUUID(), owner, AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, null,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Guest integration supplier", "guest integration supplier", "QUOTE", "REF-GUEST", "INV-GUEST",
                "USD", new BigDecimal("100.0000"), new BigDecimal("110.0000"), new BigDecimal("10.0000"),
                PriceTolerance.exactMatch(), false, null, null, 0,
                completedAt, guest.expiresAt(), guest.createdAt(), completedAt);
        Analysis persisted = analysisRepository.create(analysis);
        analysisIds.add(persisted.id());
        generatedReportRepository.create(new GeneratedReport(
                UUID.randomUUID(), persisted.id(),
                "guest/" + persisted.id() + "/reports/" + UUID.randomUUID() + ".pdf",
                "application/pdf", 128, "d".repeat(64), guest.expiresAt(), completedAt));
        when(reconciliationService.get(eq(persisted.id()), eq(owner)))
                .thenReturn(reconciliation(persisted));
        return new GuestAnalysis(persisted.id(), guest.id(), guest.createdAt(), guest.expiresAt());
    }

    private GuestSessionFixture insertGuestSession(boolean expired) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(120);
        Instant expiresAt = createdAt.plus(GUEST_TTL);
        Instant lastSeenAt = createdAt;
        if (expired) {
            expiresAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(30);
            lastSeenAt = expiresAt.minusSeconds(30);
        }
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, id, Timestamp.from(expiresAt), Timestamp.from(lastSeenAt), Timestamp.from(createdAt));
        guestSessionIds.add(id);
        return new GuestSessionFixture(id, createdAt, expiresAt, lastSeenAt);
    }

    private HttpResponse<String> createLink(
            URI baseUri, UUID analysisId, CsrfTicket csrf, String guestCookie,
            boolean includeCsrf, String expiryChoice)
            throws Exception {
        HttpRequest.Builder request = ownerRequest(
                baseUri, "/api/analyses/" + analysisId + "/share-links", csrf, guestCookie);
        request.header("Content-Type", "application/json").header("Accept", "application/json");
        if (includeCsrf) {
            request.header(csrf.headerName(), csrf.token());
        }
        return httpClient.send(
                request.POST(HttpRequest.BodyPublishers.ofString("{\"expiresIn\":\"" + expiryChoice + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> listLinks(
            URI baseUri, UUID analysisId, CsrfTicket csrf, String guestCookie) throws Exception {
        HttpRequest request = ownerRequest(
                        baseUri, "/api/analyses/" + analysisId + "/share-links", csrf, guestCookie)
                .GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> revokeLink(
            URI baseUri, UUID analysisId, CsrfTicket csrf, String guestCookie,
            UUID shareLinkId, boolean includeCsrf)
            throws Exception {
        HttpRequest.Builder request = ownerRequest(
                baseUri, "/api/analyses/" + analysisId + "/share-links/" + shareLinkId,
                csrf, guestCookie);
        if (includeCsrf) {
            request.header(csrf.headerName(), csrf.token());
        }
        return httpClient.send(request.DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder ownerRequest(URI baseUri, String path, CsrfTicket csrf, String guestCookie) {
        List<String> cookies = new ArrayList<>();
        cookies.add(SESSION_COOKIE + "=" + csrf.sessionCookie());
        if (guestCookie != null) {
            cookies.add(GUEST_COOKIE + "=" + guestCookie);
        }
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", String.join("; ", cookies));
    }

    private CsrfTicket csrfTicket(URI baseUri) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/auth/csrf"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("X-CSRF-TOKEN", body.get("headerName").asString());
        return new CsrfTicket(cookieValue(response, SESSION_COOKIE), "X-CSRF-TOKEN", body.get("token").asString());
    }

    private void assertNotFound(HttpResponse<String> response) {
        assertEquals(404, response.statusCode(), response.body());
        assertEquals(NOT_FOUND_BODY, response.body());
    }

    private static void assertNoGuestCookieSet(HttpResponse<?> response) {
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                .noneMatch(value -> value.startsWith(GUEST_COOKIE + "=")));
    }

    private static String cookieValue(HttpResponse<?> response, String cookieName) {
        String prefix = cookieName + "=";
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .findFirst()
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new AssertionError("Expected a " + cookieName + " cookie."));
    }

    private GuestSessionFixture guestSession(UUID id) {
        return jdbcTemplate.queryForObject("""
                SELECT created_at, expires_at, last_seen_at FROM invoward.guest_sessions WHERE id = ?
                """, (resultSet, rowNumber) -> new GuestSessionFixture(
                id,
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("last_seen_at").toInstant()), id);
    }

    private Instant analysisExpiry(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.analyses WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(), analysisId);
    }

    private Instant generatedReportExpiry(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.generated_reports WHERE analysis_id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(), analysisId);
    }

    private Instant shareLinkRevokedAt(UUID analysisId, UUID shareLinkId) {
        return jdbcTemplate.queryForObject("""
                SELECT revoked_at FROM invoward.share_links WHERE analysis_id = ? AND id = ?
                """, (resultSet, rowNumber) -> {
                    Timestamp value = resultSet.getTimestamp(1);
                    return value == null ? null : value.toInstant();
                }, analysisId, shareLinkId);
    }

    private int shareLinkCount(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.share_links WHERE analysis_id = ?", Integer.class, analysisId);
    }

    private long guestSessionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoward.guest_sessions", Long.class);
    }

    private void setLastSeenAt(UUID guestSessionId, Instant lastSeenAt) {
        jdbcTemplate.update("UPDATE invoward.guest_sessions SET last_seen_at = ? WHERE id = ?",
                Timestamp.from(lastSeenAt), guestSessionId);
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
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

    private record CsrfTicket(String sessionCookie, String headerName, String token) {
    }

    private record GuestSessionFixture(UUID id, Instant createdAt, Instant expiresAt, Instant lastSeenAt) {
    }

    private record GuestAnalysis(UUID analysisId, UUID guestSessionId, Instant createdAt, Instant expiresAt) {
    }
}
