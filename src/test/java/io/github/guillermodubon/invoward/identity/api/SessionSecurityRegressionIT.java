package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "invoward.email.provider=disabled",
                "server.servlet.session.cookie.secure=true"
        })
class SessionSecurityRegressionIT {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.instance();
    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String RAW_PASSWORD = "correct horse battery staple";
    private static final String CSRF_INVALID =
            "{\"code\":\"CSRF_INVALID\",\"message\":\"The request could not be validated.\"}";
    private static final String AUTHENTICATION_REQUIRED =
            "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication is required.\"}";

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

    @Test
    void loginRotatesSessionAndCsrfThenLogoutInvalidatesTheAuthenticatedSession() throws Exception {
        String email = "session-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Session Regression User', ?, ?, true, ?)
                """, UUID.randomUUID(), email, passwordEncoder.encode(RAW_PASSWORD), UserStatus.ACTIVE.name());

        URI baseUri = URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        HttpResponse<String> preAuthenticationCsrfResponse = get(baseUri, "/api/auth/csrf", null);
        assertEquals(200, preAuthenticationCsrfResponse.statusCode());
        JsonNode preAuthenticationCsrf = objectMapper.readTree(preAuthenticationCsrfResponse.body());
        String preAuthenticationToken = preAuthenticationCsrf.get("token").asString();
        assertEquals("X-CSRF-TOKEN", preAuthenticationCsrf.get("headerName").asString());

        String preAuthenticationCookie = sessionCookieValue(preAuthenticationCsrfResponse);
        String preAuthenticationCookieHeader = sessionCookieHeader(preAuthenticationCsrfResponse);
        assertSecureHttpOnlySessionCookie(preAuthenticationCookieHeader);

        String loginBody = objectMapper.writeValueAsString(Map.of("email", email, "password", RAW_PASSWORD));
        HttpResponse<String> loginResponse = postJson(baseUri, "/api/auth/login", preAuthenticationCookie,
                "X-CSRF-TOKEN", preAuthenticationToken, loginBody);
        assertEquals(200, loginResponse.statusCode());
        JsonNode loginUser = objectMapper.readTree(loginResponse.body());
        assertEquals(email, loginUser.get("email").asString());
        assertFalse(loginResponse.body().contains(RAW_PASSWORD));

        String authenticatedCookie = sessionCookieValue(loginResponse);
        String authenticatedCookieHeader = sessionCookieHeader(loginResponse);
        assertNotEquals(preAuthenticationCookie, authenticatedCookie,
                "Successful authentication must rotate the pre-authentication session ID.");
        assertSecureHttpOnlySessionCookie(authenticatedCookieHeader);

        HttpResponse<String> authenticatedMe = get(baseUri, "/api/auth/me", authenticatedCookie);
        assertEquals(200, authenticatedMe.statusCode());
        assertEquals(email, objectMapper.readTree(authenticatedMe.body()).get("email").asString());

        HttpResponse<String> logoutWithoutCsrf = postJson(baseUri, "/api/auth/logout", authenticatedCookie,
                null, null, "{}");
        assertEquals(403, logoutWithoutCsrf.statusCode());
        assertEquals(CSRF_INVALID, logoutWithoutCsrf.body());

        HttpResponse<String> logoutWithOldCsrf = postJson(baseUri, "/api/auth/logout", authenticatedCookie,
                "X-CSRF-TOKEN", preAuthenticationToken, "{}");
        assertEquals(403, logoutWithOldCsrf.statusCode());
        assertEquals(CSRF_INVALID, logoutWithOldCsrf.body());
        assertEquals(200, get(baseUri, "/api/auth/me", authenticatedCookie).statusCode(),
                "Rejected logout attempts must not invalidate a valid session.");

        HttpResponse<String> refreshedCsrfResponse = get(baseUri, "/api/auth/csrf", authenticatedCookie);
        assertEquals(200, refreshedCsrfResponse.statusCode());
        JsonNode refreshedCsrf = objectMapper.readTree(refreshedCsrfResponse.body());
        String refreshedToken = refreshedCsrf.get("token").asString();
        assertNotEquals(preAuthenticationToken, refreshedToken,
                "Login must invalidate the pre-authentication CSRF token.");

        HttpResponse<String> logoutResponse = postJson(baseUri, "/api/auth/logout", authenticatedCookie,
                refreshedCsrf.get("headerName").asString(), refreshedToken, "{}");
        assertEquals(204, logoutResponse.statusCode());
        assertSessionCookieDeleted(logoutResponse);

        HttpResponse<String> meAfterLogout = get(baseUri, "/api/auth/me", authenticatedCookie);
        assertEquals(401, meAfterLogout.statusCode());
        assertEquals(AUTHENTICATION_REQUIRED, meAfterLogout.body());
    }

    private HttpResponse<String> get(URI baseUri, String path, String sessionCookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (sessionCookie != null) {
            request.header("Cookie", SESSION_COOKIE + "=" + sessionCookie);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(
            URI baseUri,
            String path,
            String sessionCookie,
            String csrfHeader,
            String csrfToken,
            String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (sessionCookie != null) {
            request.header("Cookie", SESSION_COOKIE + "=" + sessionCookie);
        }
        if (csrfHeader != null && csrfToken != null) {
            request.header(csrfHeader, csrfToken);
        }
        return httpClient.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String sessionCookieValue(HttpResponse<?> response) {
        String cookieHeader = sessionCookieHeader(response);
        String cookieValue = cookieHeader.substring((SESSION_COOKIE + "=").length()).split(";", 2)[0];
        assertFalse(cookieValue.isBlank(), "The session cookie must contain a session ID.");
        return cookieValue;
    }

    private static String sessionCookieHeader(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an " + SESSION_COOKIE + " Set-Cookie header."));
    }

    private static void assertSecureHttpOnlySessionCookie(String cookieHeader) {
        String normalizedHeader = cookieHeader.toLowerCase(Locale.ROOT);
        assertTrue(normalizedHeader.contains("; secure"), "Session cookie must be Secure in this test profile.");
        assertTrue(normalizedHeader.contains("; httponly"), "Session cookie must be HttpOnly.");
        assertTrue(normalizedHeader.contains("; path=/"), "Session cookie must use the application root path.");
    }

    private static void assertSessionCookieDeleted(HttpResponse<?> response) {
        String deletionHeader = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Logout must delete the session cookie."));
        assertTrue(deletionHeader.matches("(?i)^INVOWARD_SESSION=;.*(?:max-age=0|expires=).*$"),
                "Logout must expire the session cookie.");
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
