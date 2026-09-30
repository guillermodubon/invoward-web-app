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
import java.util.Locale;
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
@Import(AccountLifecycleIntegrationIT.TestEmailConfiguration.class)
class AccountLifecycleIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String ORIGINAL_PASSWORD = "the original account passphrase";
    private static final String REPLACEMENT_PASSWORD = "the replacement account passphrase";
    private static final String AUTHENTICATION_FAILED =
            "{\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"Invalid email or password.\"}";
    private static final String CSRF_INVALID =
            "{\"code\":\"CSRF_INVALID\",\"message\":\"The request could not be validated.\"}";
    private static final String SESSION_INVALIDATED =
            "{\"code\":\"SESSION_INVALIDATED\",\"message\":"
                    + "\"Your session is no longer valid. Please sign in again.\"}";
    private static final String EMAIL_CHANGE_ACCEPTED =
            "{\"message\":\"If the new address can be used, a confirmation email will be sent.\"}";
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
    void profileAndPasswordChangeKeepFreshMeThenRequireAuthenticationAgain() throws Exception {
        AccountFixture account = createVerifiedAccount("lifecycle-profile");
        URI baseUri = applicationUri();
        AuthenticatedSession primarySession = login(baseUri, account.email(), ORIGINAL_PASSWORD);
        AuthenticatedSession additionalSession = login(baseUri, account.email(), ORIGINAL_PASSWORD);

        assertCsrfRequiredForProfileAndPasswordChange(baseUri, primarySession);
        assertTrue(fakeEmailSender.sentEmails().isEmpty(), "Rejected CSRF requests must have no email side effects.");

        HttpResponse<String> profileResponse = patchJson(
                baseUri,
                "/api/account/profile",
                primarySession,
                Map.of("displayName", "Updated Lifecycle Name"));
        assertEquals(200, profileResponse.statusCode());
        assertEquals("Updated Lifecycle Name", objectMapper.readTree(profileResponse.body()).get("displayName").asString());

        HttpResponse<String> freshMe = get(baseUri, "/api/auth/me", primarySession.cookie());
        assertEquals(200, freshMe.statusCode());
        JsonNode currentUser = objectMapper.readTree(freshMe.body());
        assertEquals(account.id().toString(), currentUser.get("id").asString());
        assertEquals("Updated Lifecycle Name", currentUser.get("displayName").asString());
        assertEquals(account.email(), currentUser.get("email").asString());

        HttpResponse<String> passwordChange = postJson(
                baseUri,
                "/api/account/change-password",
                primarySession,
                Map.of("currentPassword", ORIGINAL_PASSWORD, "newPassword", REPLACEMENT_PASSWORD));
        assertEquals(204, passwordChange.statusCode());
        assertSessionCookieDeleted(passwordChange);
        assertTrue(passwordEncoder.matches(REPLACEMENT_PASSWORD, persistedPasswordHash(account.id())));
        assertFalse(passwordEncoder.matches(ORIGINAL_PASSWORD, persistedPasswordHash(account.id())));

        HttpResponse<String> primaryAfterChange = get(baseUri, "/api/auth/me", primarySession.cookie());
        assertEquals(401, primaryAfterChange.statusCode());
        HttpResponse<String> additionalAfterChange = get(baseUri, "/api/auth/me", additionalSession.cookie());
        assertEquals(401, additionalAfterChange.statusCode());
        assertEquals(SESSION_INVALIDATED, additionalAfterChange.body());

        List<TransactionalEmail> notices = fakeEmailSender.sentEmails();
        assertEquals(1, notices.size());
        TransactionalEmail passwordNotice = notices.getFirst();
        assertEquals(account.email(), passwordNotice.recipient());
        assertEquals("Your InvoWard password was changed", passwordNotice.subject());
        assertContainsNone(passwordNotice, ORIGINAL_PASSWORD, REPLACEMENT_PASSWORD);

        assertAuthenticationFailure(baseUri, account.email(), ORIGINAL_PASSWORD);
        AuthenticatedSession replacementLogin = login(baseUri, account.email(), REPLACEMENT_PASSWORD);
        HttpResponse<String> meAfterRelogin = get(baseUri, "/api/auth/me", replacementLogin.cookie());
        assertEquals(200, meAfterRelogin.statusCode());
        assertEquals("Updated Lifecycle Name", objectMapper.readTree(meAfterRelogin.body()).get("displayName").asString());
    }

    @Test
    void emailChangeRequiresCsrfAndAuthenticatedConfirmationThenReplacesLoginIdentity() throws Exception {
        AccountFixture account = createVerifiedAccount("lifecycle-email");
        String targetEmail = uniqueEmail("new-address");
        URI baseUri = applicationUri();
        AuthenticatedSession confirmingSession = login(baseUri, account.email(), ORIGINAL_PASSWORD);
        AuthenticatedSession additionalSession = login(baseUri, account.email(), ORIGINAL_PASSWORD);
        String csrfHeader = confirmingSession.csrf().headerName();
        String csrfToken = confirmingSession.csrf().token();

        HttpResponse<String> missingCsrfRequest = postJson(
                baseUri,
                "/api/account/change-email",
                confirmingSession.cookie(),
                null,
                null,
                Map.of("currentPassword", ORIGINAL_PASSWORD, "newEmail", targetEmail));
        assertEquals(403, missingCsrfRequest.statusCode());
        assertEquals(CSRF_INVALID, missingCsrfRequest.body());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());

        String mixedCaseTarget = targetEmail.toUpperCase(Locale.ROOT);
        HttpResponse<String> requestChange = postJson(
                baseUri,
                "/api/account/change-email",
                confirmingSession.cookie(),
                csrfHeader,
                csrfToken,
                Map.of("currentPassword", ORIGINAL_PASSWORD, "newEmail", mixedCaseTarget));
        assertEquals(202, requestChange.statusCode());
        assertEquals(EMAIL_CHANGE_ACCEPTED, requestChange.body());
        assertEquals(account.email(), persistedEmail(account.id()), "Login email must remain unchanged until confirmation.");

        AccountFixture existingTargetOwner = createVerifiedAccount("occupied-target");
        HttpResponse<String> sameAddressRequest = postJson(
                baseUri,
                "/api/account/change-email",
                confirmingSession,
                Map.of("currentPassword", ORIGINAL_PASSWORD, "newEmail", account.email()));
        HttpResponse<String> occupiedAddressRequest = postJson(
                baseUri,
                "/api/account/change-email",
                confirmingSession,
                Map.of("currentPassword", ORIGINAL_PASSWORD, "newEmail", existingTargetOwner.email()));
        HttpResponse<String> cooldownRequest = postJson(
                baseUri,
                "/api/account/change-email",
                confirmingSession,
                Map.of("currentPassword", ORIGINAL_PASSWORD, "newEmail", targetEmail));
        for (HttpResponse<String> parityCase : List.of(
                sameAddressRequest, occupiedAddressRequest, cooldownRequest)) {
            assertEquals(202, parityCase.statusCode());
            assertEquals(EMAIL_CHANGE_ACCEPTED, parityCase.body());
        }

        List<TransactionalEmail> afterRequest = fakeEmailSender.sentEmails();
        assertEquals(1, afterRequest.size());
        TransactionalEmail confirmationEmail = afterRequest.getFirst();
        assertEquals(targetEmail, confirmationEmail.recipient());
        assertEquals("Confirm your new InvoWard email", confirmationEmail.subject());
        String rawToken = extractToken(confirmationEmail.textBody());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.email_verification_tokens
                WHERE token_hash = ? AND purpose = 'EMAIL_CHANGE' AND target_email = ?
                """, Integer.class, sha256(rawToken), targetEmail));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.email_verification_tokens WHERE token_hash = ?
                """, Integer.class, rawToken));

        HttpResponse<String> missingCsrfConfirmation = postJson(
                baseUri,
                "/api/account/confirm-email-change",
                confirmingSession.cookie(),
                null,
                null,
                Map.of("token", rawToken));
        assertEquals(403, missingCsrfConfirmation.statusCode());
        assertEquals(CSRF_INVALID, missingCsrfConfirmation.body());
        assertEquals(account.email(), persistedEmail(account.id()));
        assertEquals(1, fakeEmailSender.sentEmails().size(), "Rejected CSRF must not consume or notify the token.");

        CsrfTicket refreshedCsrf = csrfTicket(baseUri, confirmingSession.cookie());
        AuthenticatedSession sessionForConfirmation = new AuthenticatedSession(
                refreshedCsrf.sessionCookie(),
                new CsrfTicket(refreshedCsrf.token(), refreshedCsrf.headerName(), refreshedCsrf.sessionCookie()));
        HttpResponse<String> confirmation = postJson(
                baseUri,
                "/api/account/confirm-email-change",
                sessionForConfirmation.cookie(),
                sessionForConfirmation.csrf().headerName(),
                sessionForConfirmation.csrf().token(),
                Map.of("token", rawToken));
        assertEquals(204, confirmation.statusCode());
        assertSessionCookieDeleted(confirmation);
        assertEquals(targetEmail, persistedEmail(account.id()));
        assertTrue(persistedEmailVerified(account.id()));

        assertEquals(401, get(baseUri, "/api/auth/me", confirmingSession.cookie()).statusCode());
        HttpResponse<String> additionalAfterChange = get(baseUri, "/api/auth/me", additionalSession.cookie());
        assertEquals(401, additionalAfterChange.statusCode());
        assertEquals(SESSION_INVALIDATED, additionalAfterChange.body());

        List<TransactionalEmail> notifications = fakeEmailSender.sentEmails();
        assertEquals(2, notifications.size());
        TransactionalEmail oldAddressNotice = notifications.get(1);
        assertEquals(account.email(), oldAddressNotice.recipient());
        assertEquals("Your InvoWard email address was changed", oldAddressNotice.subject());
        assertContainsNone(oldAddressNotice, ORIGINAL_PASSWORD, rawToken, targetEmail);

        assertAuthenticationFailure(baseUri, account.email(), ORIGINAL_PASSWORD);
        AuthenticatedSession newIdentityLogin = login(baseUri, targetEmail, ORIGINAL_PASSWORD);
        HttpResponse<String> meAfterEmailChange = get(baseUri, "/api/auth/me", newIdentityLogin.cookie());
        assertEquals(200, meAfterEmailChange.statusCode());
        JsonNode changedIdentity = objectMapper.readTree(meAfterEmailChange.body());
        assertEquals(targetEmail, changedIdentity.get("email").asString());
        assertEquals("ACTIVE", changedIdentity.get("status").asString());
    }

    private void assertCsrfRequiredForProfileAndPasswordChange(URI baseUri, AuthenticatedSession session)
            throws Exception {
        HttpResponse<String> profileWithoutCsrf = patchJson(
                baseUri,
                "/api/account/profile",
                new AuthenticatedSession(session.cookie(), null),
                Map.of("displayName", "Must Not Persist"));
        assertEquals(403, profileWithoutCsrf.statusCode());
        assertEquals(CSRF_INVALID, profileWithoutCsrf.body());

        HttpResponse<String> passwordWithoutCsrf = postJson(
                baseUri,
                "/api/account/change-password",
                new AuthenticatedSession(session.cookie(), null),
                Map.of("currentPassword", ORIGINAL_PASSWORD, "newPassword", REPLACEMENT_PASSWORD));
        assertEquals(403, passwordWithoutCsrf.statusCode());
        assertEquals(CSRF_INVALID, passwordWithoutCsrf.body());
        assertEquals(200, get(baseUri, "/api/auth/me", session.cookie()).statusCode());
    }

    private AuthenticatedSession login(URI baseUri, String email, String password) throws Exception {
        CsrfTicket preAuthentication = csrfTicket(baseUri, null);
        HttpResponse<String> response = postJson(
                baseUri,
                "/api/auth/login",
                preAuthentication.sessionCookie(),
                preAuthentication.headerName(),
                preAuthentication.token(),
                Map.of("email", email, "password", password));
        assertEquals(200, response.statusCode());

        String authenticatedCookie = sessionCookieValue(response);
        assertNotEquals(preAuthentication.sessionCookie(), authenticatedCookie,
                "Login must rotate the pre-authentication session ID.");
        CsrfTicket authenticatedCsrf = csrfTicket(baseUri, authenticatedCookie);
        return new AuthenticatedSession(authenticatedCsrf.sessionCookie(), authenticatedCsrf);
    }

    private void assertAuthenticationFailure(URI baseUri, String email, String password) throws Exception {
        CsrfTicket preAuthentication = csrfTicket(baseUri, null);
        HttpResponse<String> response = postJson(
                baseUri,
                "/api/auth/login",
                preAuthentication.sessionCookie(),
                preAuthentication.headerName(),
                preAuthentication.token(),
                Map.of("email", email, "password", password));
        assertEquals(401, response.statusCode());
        assertEquals(AUTHENTICATION_FAILED, response.body());
        assertFalse(response.body().contains(email));
        assertFalse(response.body().contains(password));
    }

    private CsrfTicket csrfTicket(URI baseUri, String sessionCookie) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", sessionCookie);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        String refreshedCookie = updatedSessionCookie(response, sessionCookie);
        assertNotNull(refreshedCookie);
        return new CsrfTicket(body.get("token").asString(), body.get("headerName").asString(), refreshedCookie);
    }

    private HttpResponse<String> get(URI baseUri, String path, String sessionCookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        addSessionCookie(request, sessionCookie);
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(
            URI baseUri,
            String path,
            AuthenticatedSession session,
            Map<String, String> body) throws Exception {
        String headerName = session.csrf() == null ? null : session.csrf().headerName();
        String token = session.csrf() == null ? null : session.csrf().token();
        return sendJson("POST", baseUri, path, session.cookie(), headerName, token, body);
    }

    private HttpResponse<String> postJson(
            URI baseUri,
            String path,
            String sessionCookie,
            String csrfHeader,
            String csrfToken,
            Map<String, String> body) throws Exception {
        return sendJson("POST", baseUri, path, sessionCookie, csrfHeader, csrfToken, body);
    }

    private HttpResponse<String> patchJson(
            URI baseUri,
            String path,
            AuthenticatedSession session,
            Map<String, String> body) throws Exception {
        String headerName = session.csrf() == null ? null : session.csrf().headerName();
        String token = session.csrf() == null ? null : session.csrf().token();
        return sendJson("PATCH", baseUri, path, session.cookie(), headerName, token, body);
    }

    private HttpResponse<String> sendJson(
            String method,
            URI baseUri,
            String path,
            String sessionCookie,
            String csrfHeader,
            String csrfToken,
            Map<String, String> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        addSessionCookie(request, sessionCookie);
        if (csrfHeader != null && csrfToken != null) {
            request.header(csrfHeader, csrfToken);
        }
        return httpClient.send(
                request.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void addSessionCookie(HttpRequest.Builder request, String sessionCookie) {
        if (sessionCookie != null) {
            request.header("Cookie", SESSION_COOKIE + "=" + sessionCookie);
        }
    }

    private AccountFixture createVerifiedAccount(String emailPrefix) {
        UUID userId = UUID.randomUUID();
        String email = uniqueEmail(emailPrefix);
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Lifecycle User', ?, ?, true, ?)
                """, userId, email, passwordEncoder.encode(ORIGINAL_PASSWORD), UserStatus.ACTIVE.name());
        return new AccountFixture(userId, email);
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private String persistedEmail(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM invoward.users WHERE id = ?", String.class, userId);
    }

    private boolean persistedEmailVerified(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT email_verified FROM invoward.users WHERE id = ?", Boolean.class, userId);
    }

    private String persistedPasswordHash(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM invoward.users WHERE id = ?", String.class, userId);
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private static String extractToken(String content) {
        Matcher matcher = TOKEN_PATTERN.matcher(content);
        assertTrue(matcher.find(), "The confirmation email should contain its raw token URL.");
        return matcher.group(1);
    }

    private static String sha256(String rawToken) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static void assertContainsNone(TransactionalEmail email, String... sensitiveValues) {
        for (String sensitiveValue : sensitiveValues) {
            assertFalse(email.textBody().contains(sensitiveValue));
            assertFalse(email.htmlBody().contains(sensitiveValue));
        }
    }

    private static String sessionCookieValue(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .map(AccountLifecycleIntegrationIT::cookieValue)
                .orElseThrow(() -> new AssertionError("Expected an " + SESSION_COOKIE + " cookie."));
    }

    private static String updatedSessionCookie(HttpResponse<?> response, String fallback) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .map(AccountLifecycleIntegrationIT::cookieValue)
                .orElse(fallback);
    }

    private static String cookieValue(String setCookieHeader) {
        return setCookieHeader.substring((SESSION_COOKIE + "=").length()).split(";", 2)[0];
    }

    private static void assertSessionCookieDeleted(HttpResponse<?> response) {
        String cookieHeader = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Sensitive account changes must expire the session cookie."));
        assertTrue(cookieHeader.matches("(?i)^INVOWARD_SESSION=;.*(?:max-age=0|expires=).*$"));
    }

    private record AccountFixture(UUID id, String email) {
    }

    private record CsrfTicket(String token, String headerName, String sessionCookie) {
    }

    private record AuthenticatedSession(String cookie, CsrfTicket csrf) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEmailConfiguration {

        @Bean
        @Primary
        FakeEmailSender fakeEmailSender() {
            return new FakeEmailSender();
        }
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }
}
