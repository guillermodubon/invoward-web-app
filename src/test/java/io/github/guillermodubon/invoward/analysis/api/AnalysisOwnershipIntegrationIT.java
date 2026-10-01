package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.application.service.CreateAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "invoward.email.provider=disabled")
class AnalysisOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String RAW_PASSWORD = "analysis integration passphrase";
    private static final String ANALYSIS_NOT_FOUND =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CreateAnalysisService createAnalysisService;

    @Test
    void registeredOwnerCanCreateReadStatusWhileOtherIdentitiesCannotAccessAndGuestCookieIsIgnored()
            throws Exception {
        UserFixture userA = insertActiveVerifiedUser("analysis-user-a");
        UserFixture userB = insertActiveVerifiedUser("analysis-user-b");

        UUID guestSessionId = jdbcTemplate.execute(
                (ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
        Instant guestExpiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.guest_sessions WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp("expires_at").toInstant(),
                guestSessionId);
        UUID guestAnalysisId = createAnalysisService.create(
                new GuestSessionOwner(guestSessionId, guestExpiresAt), PriceTolerance.exactMatch()).id();
        long guestSessionCountBeforeRegisteredCreate = countGuestSessions();

        URI baseUri = applicationUri();
        AuthenticatedSession sessionA = login(baseUri, userA);

        HttpResponse<String> guestAnalysisWithAuthenticatedIdentity = get(
                baseUri, analysisPath(guestAnalysisId), sessionA.cookie(), guestSessionId);
        assertAnalysisNotFound(guestAnalysisWithAuthenticatedIdentity, userA, guestSessionId);

        CsrfTicket csrf = csrfTicket(baseUri, sessionA.cookie());
        HttpResponse<String> createdResponse = postJson(
                baseUri,
                "/api/analyses",
                csrf.sessionCookie(),
                csrf.headerName(),
                csrf.token(),
                objectMapper.writeValueAsString(Map.of()),
                guestSessionId);

        assertEquals(202, createdResponse.statusCode());
        assertEquals("application/json", createdResponse.headers()
                .firstValue("Content-Type").orElseThrow().split(";", 2)[0]);
        JsonNode created = objectMapper.readTree(createdResponse.body());
        UUID analysisId = UUID.fromString(created.get("id").asString());
        assertEquals("CREATED", created.get("status").asString());
        assertEquals("PENDING", created.get("reviewStatus").asString());
        assertTrue(created.get("reconciliationStatus").isNull());
        assertEquals(0, created.get("priceTolerancePercent").decimalValue().compareTo(java.math.BigDecimal.ZERO));
        assertTrue(created.get("priceToleranceAbsolute").isNull());
        assertTrue(created.get("expiresAt").isNull());
        assertEquals("/api/analyses/" + analysisId,
                createdResponse.headers().firstValue("Location").orElseThrow());
        assertFalse(createdResponse.headers().allValues("Set-Cookie").stream()
                .anyMatch(value -> value.startsWith(GUEST_COOKIE + "=")));

        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.analyses
                WHERE id = ? AND user_id = ? AND guest_session_id IS NULL AND expires_at IS NULL
                """, Integer.class, analysisId, userA.id()));
        assertEquals(guestSessionCountBeforeRegisteredCreate, countGuestSessions());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.analysis_jobs WHERE analysis_id = ?",
                Integer.class,
                analysisId));

        HttpResponse<String> userADetail = get(
                baseUri, analysisPath(analysisId), csrf.sessionCookie(), guestSessionId);
        assertEquals(200, userADetail.statusCode());
        assertEquals("CREATED", objectMapper.readTree(userADetail.body()).get("status").asString());
        assertFalse(userADetail.body().contains(userA.id().toString()));
        assertFalse(userADetail.body().contains(guestSessionId.toString()));

        HttpResponse<String> userAStatus = get(
                baseUri, analysisPath(analysisId) + "/status", csrf.sessionCookie(), guestSessionId);
        assertEquals(200, userAStatus.statusCode());
        JsonNode status = objectMapper.readTree(userAStatus.body());
        assertEquals(analysisId.toString(), status.get("analysisId").asString());
        assertEquals("CREATED", status.get("status").asString());
        assertEquals("WAITING_FOR_USER", status.get("jobStatus").asString());
        assertEquals("CREATED", status.get("currentStage").asString());
        assertFalse(status.get("retryable").asBoolean());
        assertFalse(userAStatus.body().contains(userA.id().toString()));
        assertFalse(userAStatus.body().contains(guestSessionId.toString()));

        AuthenticatedSession sessionB = login(baseUri, userB);
        String userBDetailError = assertAnalysisNotFound(
                get(baseUri, analysisPath(analysisId), sessionB.cookie(), null), userA, null);
        String userBStatusError = assertAnalysisNotFound(
                get(baseUri, analysisPath(analysisId) + "/status", sessionB.cookie(), null), userA, null);
        assertEquals(userBDetailError, userBStatusError);

        String anonymousDetailError = assertAnalysisNotFound(
                get(baseUri, analysisPath(analysisId), null, null), userA, null);
        String anonymousStatusError = assertAnalysisNotFound(
                get(baseUri, analysisPath(analysisId) + "/status", null, null), userA, null);
        String guestDetailError = assertAnalysisNotFound(
                get(baseUri, analysisPath(analysisId), null, guestSessionId), userA, guestSessionId);
        String guestStatusError = assertAnalysisNotFound(
                get(baseUri, analysisPath(analysisId) + "/status", null, guestSessionId),
                userA, guestSessionId);
        assertEquals(anonymousDetailError, anonymousStatusError);
        assertEquals(anonymousDetailError, guestDetailError);
        assertEquals(anonymousDetailError, guestStatusError);
        assertEquals(userBDetailError, anonymousDetailError);

        String missingAnalysisError = assertAnalysisNotFound(
                get(baseUri, analysisPath(UUID.randomUUID()), null, null), userA, null);
        assertEquals(anonymousDetailError, missingAnalysisError);
    }

    private String assertAnalysisNotFound(
            HttpResponse<String> response, UserFixture protectedOwner, UUID guestSessionId) {
        assertEquals(404, response.statusCode());
        assertEquals("application/json", response.headers()
                .firstValue("Content-Type").orElseThrow().split(";", 2)[0]);
        assertEquals(ANALYSIS_NOT_FOUND, response.body());
        assertFalse(response.body().contains(protectedOwner.id().toString()));
        if (guestSessionId != null) {
            assertFalse(response.body().contains(guestSessionId.toString()));
        }
        return response.body();
    }

    private AuthenticatedSession login(URI baseUri, UserFixture user) throws Exception {
        CsrfTicket preAuthentication = csrfTicket(baseUri, null);
        HttpResponse<String> response = postJson(
                baseUri,
                "/api/auth/login",
                preAuthentication.sessionCookie(),
                preAuthentication.headerName(),
                preAuthentication.token(),
                objectMapper.writeValueAsString(Map.of("email", user.email(), "password", RAW_PASSWORD)),
                null);
        assertEquals(200, response.statusCode());
        String authenticatedCookie = cookieValue(response, SESSION_COOKIE);
        assertNotEquals(preAuthentication.sessionCookie(), authenticatedCookie,
                "Successful login must rotate the pre-authentication session ID.");
        CsrfTicket authenticatedCsrf = csrfTicket(baseUri, authenticatedCookie);
        return new AuthenticatedSession(authenticatedCsrf.sessionCookie(), authenticatedCsrf);
    }

    private CsrfTicket csrfTicket(URI baseUri, String sessionCookie) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", sessionCookie, null);
        assertEquals(200, response.statusCode());
        JsonNode csrf = objectMapper.readTree(response.body());
        String updatedCookie = updatedCookieValue(response, SESSION_COOKIE, sessionCookie);
        assertNotNull(updatedCookie);
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        assertFalse(csrf.get("token").asString().isBlank());
        return new CsrfTicket(updatedCookie, csrf.get("headerName").asString(), csrf.get("token").asString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, String sessionCookie, UUID guestSessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        List<String> cookies = new ArrayList<>();
        if (sessionCookie != null) {
            cookies.add(SESSION_COOKIE + "=" + sessionCookie);
        }
        if (guestSessionId != null) {
            cookies.add(GUEST_COOKIE + "=" + guestSessionId);
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", String.join("; ", cookies));
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(
            URI baseUri,
            String path,
            String sessionCookie,
            String csrfHeader,
            String csrfToken,
            String body,
            UUID guestSessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        List<String> cookies = new ArrayList<>();
        if (sessionCookie != null) {
            cookies.add(SESSION_COOKIE + "=" + sessionCookie);
        }
        if (guestSessionId != null) {
            cookies.add(GUEST_COOKIE + "=" + guestSessionId);
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", String.join("; ", cookies));
        }
        if (csrfHeader != null && csrfToken != null) {
            request.header(csrfHeader, csrfToken);
        }
        return httpClient.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private UserFixture insertActiveVerifiedUser(String emailPrefix) {
        UUID id = UUID.randomUUID();
        String email = emailPrefix + "-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Analysis Integration User', ?, ?, true, ?)
                """, id, email, passwordEncoder.encode(RAW_PASSWORD), UserStatus.ACTIVE.name());
        return new UserFixture(id, email);
    }

    private long countGuestSessions() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoward.guest_sessions", Long.class);
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private static String analysisPath(UUID analysisId) {
        return "/api/analyses/" + analysisId;
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

    private static String updatedCookieValue(
            HttpResponse<?> response, String cookieName, String fallback) {
        String prefix = cookieName + "=";
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .findFirst()
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .orElse(fallback);
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    private record UserFixture(UUID id, String email) {
    }

    private record CsrfTicket(String sessionCookie, String headerName, String token) {
    }

    private record AuthenticatedSession(String cookie, CsrfTicket csrf) {
    }
}
