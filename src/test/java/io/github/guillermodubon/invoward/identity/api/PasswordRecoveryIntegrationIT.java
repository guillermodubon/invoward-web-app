package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.email.FakeEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "invoward.email.provider=disabled")
@Import(PasswordRecoveryIntegrationIT.TestEmailConfiguration.class)
class PasswordRecoveryIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String OLD_PASSWORD = "the original account passphrase";
    private static final String NEW_PASSWORD = "the replacement account passphrase";
    private static final String FORGOT_RESPONSE = """
            {"message":"If an eligible account exists, password reset instructions will be sent."}
            """;
    private static final String CSRF_INVALID =
            "{\"code\":\"CSRF_INVALID\",\"message\":\"The request could not be validated.\"}";
    private static final String RESET_TOKEN_INVALID =
            "{\"code\":\"RESET_TOKEN_INVALID\",\"message\":\"The password reset link is invalid or expired.\"}";
    private static final String AUTHENTICATION_FAILED =
            "{\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"Invalid email or password.\"}";
    private static final String SESSION_INVALIDATED =
            "{\"code\":\"SESSION_INVALIDATED\",\"message\":"
                    + "\"Your session is no longer valid. Please sign in again.\"}";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]{43})");

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
    private FakeEmailSender fakeEmailSender;

    @BeforeEach
    void clearEmailCapture() {
        fakeEmailSender.clear();
    }

    @Test
    void forgotPasswordResetInvalidatesOldSessionAndCredentialThenAllowsNewLogin() throws Exception {
        UUID userId = UUID.randomUUID();
        String email = "password-recovery-" + userId + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Password Recovery User', ?, ?, true, ?)
                """, userId, email, passwordEncoder.encode(OLD_PASSWORD), UserStatus.ACTIVE.name());

        URI baseUri = URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        CsrfTicket loginCsrf = csrfTicket(baseUri, null);
        HttpResponse<String> initialLogin = postJson(
                baseUri,
                "/api/auth/login",
                loginCsrf.sessionCookie(),
                loginCsrf.headerName(),
                loginCsrf.token(),
                json(Map.of("email", email, "password", OLD_PASSWORD)));
        assertEquals(200, initialLogin.statusCode());
        String authenticatedCookie = sessionCookieValue(initialLogin);
        assertNotEquals(loginCsrf.sessionCookie(), authenticatedCookie);
        assertEquals(200, get(baseUri, "/api/auth/me", authenticatedCookie).statusCode());

        CsrfTicket recoveryCsrf = csrfTicket(baseUri, authenticatedCookie);
        HttpResponse<String> knownAccountRequest = requestPasswordReset(
                baseUri, recoveryCsrf, authenticatedCookie, email);
        assertAcceptedForgotResponse(knownAccountRequest);
        assertEquals(1, fakeEmailSender.sentEmails().size());
        String firstToken = extractToken(fakeEmailSender.sentEmails().getFirst().textBody());
        assertTokenHashOnly(firstToken);

        HttpResponse<String> unknownAccountRequest = requestPasswordReset(
                baseUri, recoveryCsrf, authenticatedCookie, "not-registered@example.com");
        assertAcceptedForgotResponse(unknownAccountRequest);
        assertEquals(knownAccountRequest.body(), unknownAccountRequest.body());

        String pendingEmail = "password-recovery-pending-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Pending Recovery User', ?, ?, false, ?)
                """, UUID.randomUUID(), pendingEmail, passwordEncoder.encode(OLD_PASSWORD),
                UserStatus.PENDING_VERIFICATION.name());
        HttpResponse<String> pendingAccountRequest = requestPasswordReset(
                baseUri, recoveryCsrf, authenticatedCookie, pendingEmail);
        assertAcceptedForgotResponse(pendingAccountRequest);
        assertEquals(knownAccountRequest.body(), pendingAccountRequest.body());

        String disabledEmail = "password-recovery-disabled-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Disabled Recovery User', ?, ?, false, ?)
                """, UUID.randomUUID(), disabledEmail, passwordEncoder.encode(OLD_PASSWORD),
                UserStatus.DISABLED.name());
        HttpResponse<String> disabledAccountRequest = requestPasswordReset(
                baseUri, recoveryCsrf, authenticatedCookie, disabledEmail);
        assertAcceptedForgotResponse(disabledAccountRequest);
        assertEquals(knownAccountRequest.body(), disabledAccountRequest.body());

        HttpResponse<String> cooldownRequest = requestPasswordReset(
                baseUri, recoveryCsrf, authenticatedCookie, email);
        assertAcceptedForgotResponse(cooldownRequest);
        assertEquals(knownAccountRequest.body(), cooldownRequest.body());
        assertEquals(1, fakeEmailSender.sentEmails().size());
        assertEquals(1, countResetTokens(email));

        assertEquals(1, jdbcTemplate.update("""
                UPDATE invoward.password_reset_tokens
                SET created_at = created_at - INTERVAL '61 seconds'
                WHERE token_hash = ?
                """, sha256(firstToken)));
        HttpResponse<String> rotatedRequest = requestPasswordReset(
                baseUri, recoveryCsrf, authenticatedCookie, email);
        assertAcceptedForgotResponse(rotatedRequest);
        assertEquals(knownAccountRequest.body(), rotatedRequest.body());
        assertEquals(2, fakeEmailSender.sentEmails().size());
        assertEquals(2, countResetTokens(email));
        String replacementToken = extractToken(fakeEmailSender.sentEmails().getLast().textBody());
        assertNotEquals(firstToken, replacementToken);
        assertTokenHashOnly(replacementToken);

        String firstResetBody = json(Map.of("token", firstToken, "newPassword", NEW_PASSWORD));
        HttpResponse<String> resetWithoutCsrf = postJson(
                baseUri, "/api/auth/reset-password", authenticatedCookie, null, null, firstResetBody);
        assertEquals(403, resetWithoutCsrf.statusCode());
        assertEquals(CSRF_INVALID, resetWithoutCsrf.body());
        assertEquals(200, get(baseUri, "/api/auth/me", authenticatedCookie).statusCode());

        HttpResponse<String> resetWithRotatedOutToken = postJson(
                baseUri,
                "/api/auth/reset-password",
                authenticatedCookie,
                recoveryCsrf.headerName(),
                recoveryCsrf.token(),
                firstResetBody);
        assertEquals(400, resetWithRotatedOutToken.statusCode());
        assertEquals(RESET_TOKEN_INVALID, resetWithRotatedOutToken.body());

        HttpResponse<String> reset = postJson(
                baseUri,
                "/api/auth/reset-password",
                authenticatedCookie,
                recoveryCsrf.headerName(),
                recoveryCsrf.token(),
                json(Map.of("token", replacementToken, "newPassword", NEW_PASSWORD)));
        assertEquals(204, reset.statusCode());

        String newPasswordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM invoward.users WHERE id = ?", String.class, userId);
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, newPasswordHash));
        assertFalse(passwordEncoder.matches(OLD_PASSWORD, newPasswordHash));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.password_reset_tokens WHERE token_hash IN (?, ?)
                """, Integer.class, firstToken, replacementToken));
        assertEquals(2, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.password_reset_tokens
                WHERE token_hash IN (?, ?) AND used_at IS NOT NULL
                """, Integer.class, sha256(firstToken), sha256(replacementToken)));

        HttpResponse<String> rejectedOldSession = get(baseUri, "/api/auth/me", authenticatedCookie);
        assertEquals(401, rejectedOldSession.statusCode());
        assertEquals(SESSION_INVALIDATED, rejectedOldSession.body());

        CsrfTicket oldPasswordCsrf = csrfTicket(baseUri, null);
        HttpResponse<String> rejectedOldPassword = postJson(
                baseUri,
                "/api/auth/login",
                oldPasswordCsrf.sessionCookie(),
                oldPasswordCsrf.headerName(),
                oldPasswordCsrf.token(),
                json(Map.of("email", email, "password", OLD_PASSWORD)));
        assertEquals(401, rejectedOldPassword.statusCode());
        assertEquals(AUTHENTICATION_FAILED, rejectedOldPassword.body());

        CsrfTicket newPasswordCsrf = csrfTicket(baseUri, null);
        HttpResponse<String> newPasswordLogin = postJson(
                baseUri,
                "/api/auth/login",
                newPasswordCsrf.sessionCookie(),
                newPasswordCsrf.headerName(),
                newPasswordCsrf.token(),
                json(Map.of("email", email, "password", NEW_PASSWORD)));
        assertEquals(200, newPasswordLogin.statusCode());
        assertFalse(newPasswordLogin.body().contains(NEW_PASSWORD));

        String replacementSession = sessionCookieValue(newPasswordLogin);
        HttpResponse<String> currentUser = get(baseUri, "/api/auth/me", replacementSession);
        assertEquals(200, currentUser.statusCode());
        JsonNode currentUserResponse = objectMapper.readTree(currentUser.body());
        assertEquals(userId.toString(), currentUserResponse.get("id").asString());
        assertEquals(email, currentUserResponse.get("email").asString());
        assertEquals("ACTIVE", currentUserResponse.get("status").asString());

        List<TransactionalEmail> sentEmails = fakeEmailSender.sentEmails();
        assertEquals(3, sentEmails.size());
        assertEquals("Reset your InvoWard password", sentEmails.get(0).subject());
        assertEquals("Reset your InvoWard password", sentEmails.get(1).subject());
        assertEquals("Your InvoWard password was changed", sentEmails.get(2).subject());
        assertTrue(sentEmails.stream().allMatch(message -> email.equals(message.recipient())));
        for (String credential : List.of(OLD_PASSWORD, NEW_PASSWORD, sha256(firstToken), sha256(replacementToken))) {
            assertFalse(sentEmails.get(2).textBody().contains(credential));
            assertFalse(sentEmails.get(2).htmlBody().contains(credential));
        }
    }

    private HttpResponse<String> requestPasswordReset(
            URI baseUri, CsrfTicket csrf, String sessionCookie, String email) throws Exception {
        return postJson(
                baseUri,
                "/api/auth/forgot-password",
                sessionCookie,
                csrf.headerName(),
                csrf.token(),
                json(Map.of("email", email)));
    }

    private CsrfTicket csrfTicket(URI baseUri, String sessionCookie) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", sessionCookie);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("X-CSRF-TOKEN", body.get("headerName").asString());
        String refreshedCookie = updatedSessionCookie(response, sessionCookie);
        assertNotNull(refreshedCookie);
        return new CsrfTicket(body.get("token").asString(), body.get("headerName").asString(), refreshedCookie);
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

    private String json(Map<String, String> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private void assertAcceptedForgotResponse(HttpResponse<String> response) {
        assertEquals(202, response.statusCode());
        assertEquals(FORGOT_RESPONSE.trim(), response.body());
    }

    private void assertTokenHashOnly(String rawToken) throws Exception {
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.password_reset_tokens WHERE token_hash = ?
                """, Integer.class, sha256(rawToken)));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.password_reset_tokens WHERE token_hash = ?
                """, Integer.class, rawToken));
    }

    private int countResetTokens(String email) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM invoward.password_reset_tokens token
                JOIN invoward.users user_account ON user_account.id = token.user_id
                WHERE user_account.email = ?
                """, Integer.class, email);
    }

    private static String extractToken(String message) {
        Matcher matcher = TOKEN_PATTERN.matcher(message);
        assertTrue(matcher.find(), "reset email should contain the raw token URL");
        return matcher.group(1);
    }

    private static String sha256(String rawToken) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private static String sessionCookieValue(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .map(PasswordRecoveryIntegrationIT::cookieValue)
                .orElseThrow(() -> new AssertionError("Expected an " + SESSION_COOKIE + " cookie."));
    }

    private static String updatedSessionCookie(HttpResponse<?> response, String fallback) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .map(PasswordRecoveryIntegrationIT::cookieValue)
                .orElse(fallback);
    }

    private static String cookieValue(String setCookieHeader) {
        return setCookieHeader.substring((SESSION_COOKIE + "=").length()).split(";", 2)[0];
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEmailConfiguration {

        @Bean
        @Primary
        FakeEmailSender fakeEmailSender() {
            return new FakeEmailSender();
        }
    }

    private record CsrfTicket(String token, String headerName, String sessionCookie) {
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }
}
