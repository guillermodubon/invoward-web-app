package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "invoward.email.provider=disabled",
                "invoward.guest.session-ttl=24h",
                "invoward.guest.cookie.secure=false",
                "invoward.guest.cookie.same-site=lax"
        })
class AnalysisGuestOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String NOT_FOUND_BODY =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";
    private static final Duration GUEST_TTL = Duration.ofHours(24);
    private static final Pattern MAX_AGE_ATTRIBUTE = Pattern.compile("(?:^|;\\s*)Max-Age=(\\d+)(?:;|$)");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsReusesAndTouchesGuestSessionWithoutSlidingItsExpiry() throws Exception {
        URI baseUri = applicationUri();
        CsrfTicket csrf = csrfTicket(baseUri);
        HttpResponse<String> firstCreate = createAnalysis(baseUri, csrf, null);

        assertEquals(202, firstCreate.statusCode());
        UUID firstAnalysisId = analysisId(firstCreate);
        String firstGuestCookie = cookieValue(firstCreate, GUEST_COOKIE);
        UUID guestSessionId = UUID.fromString(firstGuestCookie);
        assertGuestCookieAttributes(firstCreate, guestSessionId);
        assertFalse(firstCreate.body().contains(guestSessionId.toString()));
        assertFalse(firstCreate.body().contains("guestSessionId"));

        GuestSessionRow initialSession = guestSession(guestSessionId);
        assertEquals(GUEST_TTL, Duration.between(initialSession.createdAt(), initialSession.expiresAt()));
        assertEquals(initialSession.expiresAt(), analysisExpiry(firstAnalysisId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.analyses WHERE id = ? AND user_id IS NULL AND guest_session_id = ?",
                Integer.class,
                firstAnalysisId,
                guestSessionId));
        assertEquals(1, jobCount(firstAnalysisId));

        long sessionCountBeforeReuse = guestSessionCount();
        Instant oldLastSeen = initialSession.createdAt();
        setLastSeenAt(guestSessionId, oldLastSeen);
        HttpResponse<String> secondCreate = createAnalysis(baseUri, csrf, firstGuestCookie);

        assertEquals(202, secondCreate.statusCode());
        UUID secondAnalysisId = analysisId(secondCreate);
        assertNotEquals(firstAnalysisId, secondAnalysisId);
        assertEquals(guestSessionId, UUID.fromString(cookieValue(secondCreate, GUEST_COOKIE)));
        assertEquals(sessionCountBeforeReuse, guestSessionCount(), "An active cookie must reuse its row.");
        assertEquals(initialSession.expiresAt(), guestSession(guestSessionId).expiresAt());
        assertTrue(guestSession(guestSessionId).lastSeenAt().isAfter(oldLastSeen));
        assertEquals(initialSession.expiresAt(), analysisExpiry(secondAnalysisId));
        assertEquals(1, jobCount(secondAnalysisId));
        assertFalse(secondCreate.body().contains(guestSessionId.toString()));

        oldLastSeen = initialSession.createdAt();
        setLastSeenAt(guestSessionId, oldLastSeen);
        HttpResponse<String> detail = get(baseUri, analysisPath(firstAnalysisId), firstGuestCookie);
        assertEquals(200, detail.statusCode());
        assertFalse(detail.body().contains(guestSessionId.toString()));
        assertTrue(guestSession(guestSessionId).lastSeenAt().isAfter(oldLastSeen));
        assertEquals(initialSession.expiresAt(), guestSession(guestSessionId).expiresAt());

        oldLastSeen = initialSession.createdAt();
        setLastSeenAt(guestSessionId, oldLastSeen);
        HttpResponse<String> status = get(baseUri, analysisPath(firstAnalysisId) + "/status", firstGuestCookie);
        assertEquals(200, status.statusCode());
        assertFalse(status.body().contains(guestSessionId.toString()));
        assertTrue(guestSession(guestSessionId).lastSeenAt().isAfter(oldLastSeen));
        assertEquals(initialSession.expiresAt(), guestSession(guestSessionId).expiresAt());
    }

    @Test
    void foreignMissingMalformedUnknownAndExpiredGuestOwnershipReturnTheSameNotFound() throws Exception {
        URI baseUri = applicationUri();
        CreatedGuestAnalysis guestA = createGuestAnalysis(baseUri);
        UUID guestB = insertActiveGuestSession();
        GuestSessionRow guestAInitial = guestSession(guestA.guestSessionId());
        GuestSessionRow guestBInitial = guestSession(guestB);
        ExpiredGuestAnalysis expired = insertExpiredGuestAnalysis();

        Instant guestAUnchanged = guestAInitial.createdAt();
        Instant guestBUnchanged = guestBInitial.createdAt();
        Instant expiredLastSeen = guestSession(expired.guestSessionId()).lastSeenAt();
        setLastSeenAt(guestA.guestSessionId(), guestAUnchanged);
        setLastSeenAt(guestB, guestBUnchanged);
        long sessionCountBeforeReads = guestSessionCount();

        List<HttpResponse<String>> failures = new ArrayList<>();
        failures.add(get(baseUri, analysisPath(guestA.analysisId()), guestB.toString()));
        failures.add(get(baseUri, analysisPath(guestA.analysisId()) + "/status", guestB.toString()));
        failures.add(get(baseUri, analysisPath(guestA.analysisId()), null));
        failures.add(get(baseUri, analysisPath(guestA.analysisId()) + "/status", null));
        failures.add(get(baseUri, analysisPath(guestA.analysisId()), "not-a-uuid"));
        failures.add(get(baseUri, analysisPath(guestA.analysisId()) + "/status", "not-a-uuid"));
        failures.add(get(baseUri, analysisPath(guestA.analysisId()), UUID.randomUUID().toString()));
        failures.add(get(baseUri, analysisPath(guestA.analysisId()) + "/status", UUID.randomUUID().toString()));
        failures.add(get(baseUri, analysisPath(expired.analysisId()), expired.guestSessionId().toString()));
        failures.add(get(baseUri, analysisPath(expired.analysisId()) + "/status",
                expired.guestSessionId().toString()));

        for (HttpResponse<String> response : failures) {
            assertNotFound(response);
            assertEquals(NOT_FOUND_BODY, response.body());
            assertFalse(response.body().contains(guestA.guestSessionId().toString()));
            assertFalse(response.body().contains(guestB.toString()));
        }

        assertEquals(sessionCountBeforeReads, guestSessionCount(), "GET must never create a guest session.");
        assertEquals(guestAUnchanged, guestSession(guestA.guestSessionId()).lastSeenAt());
        assertEquals(guestBUnchanged, guestSession(guestB).lastSeenAt());
        assertEquals(expiredLastSeen, guestSession(expired.guestSessionId()).lastSeenAt());
        assertEquals(guestAInitial.expiresAt(), guestSession(guestA.guestSessionId()).expiresAt());
        assertEquals(guestBInitial.expiresAt(), guestSession(guestB).expiresAt());
        assertEquals(expired.expiresAt(), guestSession(expired.guestSessionId()).expiresAt());
    }

    @Test
    void expiredGuestCookieIsReplacedInsteadOfReusedOnCreate() throws Exception {
        URI baseUri = applicationUri();
        ExpiredGuestAnalysis expired = insertExpiredGuestAnalysis();
        GuestSessionRow oldSession = guestSession(expired.guestSessionId());
        CsrfTicket csrf = csrfTicket(baseUri);

        HttpResponse<String> response = createAnalysis(baseUri, csrf, expired.guestSessionId().toString());

        assertEquals(202, response.statusCode());
        UUID replacementId = UUID.fromString(cookieValue(response, GUEST_COOKIE));
        UUID replacementAnalysisId = analysisId(response);
        assertNotEquals(expired.guestSessionId(), replacementId);
        assertGuestCookieAttributes(response, replacementId);
        assertFalse(response.body().contains(replacementId.toString()));
        assertEquals(oldSession.expiresAt(), guestSession(expired.guestSessionId()).expiresAt());
        assertEquals(oldSession.lastSeenAt(), guestSession(expired.guestSessionId()).lastSeenAt());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.analyses WHERE id = ? AND guest_session_id = ? AND user_id IS NULL",
                Integer.class,
                replacementAnalysisId,
                replacementId));
        assertEquals(guestSession(replacementId).expiresAt(), analysisExpiry(replacementAnalysisId));
        assertEquals(1, jobCount(replacementAnalysisId));
    }

    private CreatedGuestAnalysis createGuestAnalysis(URI baseUri) throws Exception {
        CsrfTicket csrf = csrfTicket(baseUri);
        HttpResponse<String> response = createAnalysis(baseUri, csrf, null);
        assertEquals(202, response.statusCode());
        return new CreatedGuestAnalysis(
                analysisId(response), UUID.fromString(cookieValue(response, GUEST_COOKIE)));
    }

    private HttpResponse<String> createAnalysis(URI baseUri, CsrfTicket csrf, String guestCookie)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve("/api/analyses"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(csrf.headerName(), csrf.token());
        List<String> cookies = new ArrayList<>();
        cookies.add(SESSION_COOKIE + "=" + csrf.sessionCookie());
        if (guestCookie != null) {
            cookies.add(GUEST_COOKIE + "=" + guestCookie);
        }
        request.header("Cookie", String.join("; ", cookies));
        return httpClient.send(
                request.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private CsrfTicket csrfTicket(URI baseUri) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", null);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("X-CSRF-TOKEN", body.get("headerName").asString());
        String token = body.get("token").asString();
        assertFalse(token.isBlank());
        return new CsrfTicket(cookieValue(response, SESSION_COOKIE), "X-CSRF-TOKEN", token);
    }

    private HttpResponse<String> get(URI baseUri, String path, String guestCookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (guestCookie != null) {
            request.header("Cookie", GUEST_COOKIE + "=" + guestCookie);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertGuestCookieAttributes(HttpResponse<String> response, UUID guestSessionId) {
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(GUEST_COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected the guest cookie."));
        assertTrue(cookie.contains(GUEST_COOKIE + "=" + guestSessionId));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Path=/api/analyses"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertFalse(cookie.contains("Secure"), "The local test profile uses HTTP and Secure=false.");
        assertTrue(maxAgeSeconds(cookie) > 86_000);
        assertTrue(maxAgeSeconds(cookie) <= GUEST_TTL.toSeconds());
    }

    private long maxAgeSeconds(String setCookie) {
        Matcher matcher = MAX_AGE_ATTRIBUTE.matcher(setCookie);
        assertTrue(matcher.find(), "The guest cookie must have a fixed-lifetime Max-Age.");
        return Long.parseLong(matcher.group(1));
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

    private void assertNotFound(HttpResponse<String> response) {
        assertEquals(404, response.statusCode());
        assertEquals("application/json", response.headers()
                .firstValue("Content-Type").orElseThrow().split(";", 2)[0]);
    }

    private GuestSessionRow guestSession(UUID id) {
        return jdbcTemplate.queryForObject("""
                SELECT expires_at, last_seen_at, created_at
                FROM invoward.guest_sessions WHERE id = ?
                """, (resultSet, rowNumber) -> new GuestSessionRow(
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("last_seen_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant()), id);
    }

    private Instant analysisExpiry(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.analyses WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
                analysisId);
    }

    private int jobCount(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.analysis_jobs WHERE analysis_id = ?",
                Integer.class,
                analysisId);
    }

    private long guestSessionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoward.guest_sessions", Long.class);
    }

    private void setLastSeenAt(UUID id, Instant value) {
        jdbcTemplate.update("UPDATE invoward.guest_sessions SET last_seen_at = ? WHERE id = ?",
                Timestamp.from(value), id);
    }

    private UUID insertActiveGuestSession() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(300);
        Instant expiresAt = createdAt.plus(GUEST_TTL);
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, id, Timestamp.from(expiresAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private ExpiredGuestAnalysis insertExpiredGuestAnalysis() {
        UUID guestSessionId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(Duration.ofHours(48));
        Instant expiresAt = createdAt.plus(GUEST_TTL);
        Instant lastSeenAt = createdAt.plus(Duration.ofHours(1));

        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, guestSessionId, Timestamp.from(expiresAt), Timestamp.from(lastSeenAt),
                Timestamp.from(createdAt));
        jdbcTemplate.update("""
                INSERT INTO invoward.analyses
                    (id, user_id, guest_session_id, status, review_status, expires_at, created_at, updated_at)
                VALUES (?, NULL, ?, 'CREATED', 'PENDING', ?, ?, ?)
                """, analysisId, guestSessionId, Timestamp.from(expiresAt),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        jdbcTemplate.update("""
                INSERT INTO invoward.analysis_jobs
                    (id, analysis_id, status, current_stage, created_at, updated_at)
                VALUES (?, ?, 'WAITING_FOR_USER', 'CREATED', ?, ?)
                """, UUID.randomUUID(), analysisId, Timestamp.from(createdAt), Timestamp.from(createdAt));
        return new ExpiredGuestAnalysis(
                guestSessionId, analysisId, guestSession(guestSessionId).expiresAt());
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private UUID analysisId(HttpResponse<String> response) throws Exception {
        return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
    }

    private static String analysisPath(UUID analysisId) {
        return "/api/analyses/" + analysisId;
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    private record CsrfTicket(String sessionCookie, String headerName, String token) {
    }

    private record CreatedGuestAnalysis(UUID analysisId, UUID guestSessionId) {
    }

    private record GuestSessionRow(Instant expiresAt, Instant lastSeenAt, Instant createdAt) {
    }

    private record ExpiredGuestAnalysis(UUID guestSessionId, UUID analysisId, Instant expiresAt) {
    }
}
