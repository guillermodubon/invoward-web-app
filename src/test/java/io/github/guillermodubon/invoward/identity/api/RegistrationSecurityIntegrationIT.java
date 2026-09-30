package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.email.FakeEmailSender;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@AutoConfigureMockMvc
@Import(RegistrationSecurityIntegrationIT.TestBeans.class)
class RegistrationSecurityIntegrationIT {

    private static final String RAW_PASSWORD = "correct horse battery staple";
    private static final String ACCEPTED_RESPONSE = """
            {"message":"If the account can be created, check the email address for the next step."}
            """;
    private static final String VERIFICATION_RESEND_RESPONSE = """
            {"message":"If the account is eligible, a verification email will be sent."}
            """;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private FakeEmailSender fakeEmailSender;

    @BeforeEach
    void clearFakeEmailSender() {
        fakeEmailSender.clear();
    }

    @Test
    void csrfProtectedRegistrationPersistsPendingAccountAndSendsEmailAfterCommit() throws Exception {
        String normalizedEmail = uniqueEmail();
        String submittedEmail = normalizedEmail.toUpperCase(Locale.ROOT);
        CsrfSession csrf = csrfSession();

        MvcResult result = mockMvc.perform(register(csrf, "  Guillermo Hernández  ", submittedEmail, RAW_PASSWORD))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json(ACCEPTED_RESPONSE))
                .andReturn();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(ACCEPTED_RESPONSE.trim(), responseBody);
        assertFalse(responseBody.contains(RAW_PASSWORD));

        assertEquals(1, countUsers(normalizedEmail));
        Map<String, Object> persistedUser = jdbcTemplate.queryForMap("""
                SELECT id, display_name, email, password_hash, email_verified, status
                FROM invoward.users WHERE email = ?
                """, normalizedEmail);
        assertEquals("Guillermo Hernández", persistedUser.get("display_name"));
        assertEquals(normalizedEmail, persistedUser.get("email"));
        assertEquals(Boolean.FALSE, persistedUser.get("email_verified"));
        assertEquals("PENDING_VERIFICATION", persistedUser.get("status"));
        String passwordHash = (String) persistedUser.get("password_hash");
        assertNotEquals(RAW_PASSWORD, passwordHash);
        assertTrue(passwordEncoder.matches(RAW_PASSWORD, passwordHash));

        assertEquals(1, fakeEmailSender.sentEmails().size());
        TransactionalEmail email = fakeEmailSender.sentEmails().getFirst();
        assertEquals(normalizedEmail, email.recipient());
        assertEquals("Verify your InvoWard email", email.subject());
        assertTrue(email.textBody().contains("expires at"));
        assertTrue(email.textBody().contains("If you did not create an InvoWard account"));
        String rawToken = extractToken(email.textBody());
        assertEquals(43, rawToken.length());

        UUID userId = (UUID) persistedUser.get("id");
        Map<String, Object> persistedToken = jdbcTemplate.queryForMap("""
                SELECT token_hash, purpose, target_email
                FROM invoward.email_verification_tokens WHERE user_id = ?
                """, userId);
        String tokenHash = (String) persistedToken.get("token_hash");
        assertEquals(sha256(rawToken), tokenHash);
        assertEquals("REGISTRATION", persistedToken.get("purpose"));
        assertEquals(normalizedEmail, persistedToken.get("target_email"));
        assertFalse(tokenHash.contains(rawToken));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.email_verification_tokens WHERE token_hash = ?
                """, Integer.class, rawToken));

        assertNoAuthenticatedSecurityContext(result.getRequest().getSession(false));
    }

    @Test
    void registrationVerificationAllowsJsonLoginAndCurrentUserSession() throws Exception {
        String email = uniqueEmail();
        CsrfSession registrationCsrf = csrfSession();

        mockMvc.perform(register(registrationCsrf, "Lifecycle User", email, RAW_PASSWORD))
                .andExpect(status().isAccepted())
                .andExpect(content().json(ACCEPTED_RESPONSE));

        assertEquals(1, fakeEmailSender.sentEmails().size());
        TransactionalEmail verificationEmail = fakeEmailSender.sentEmails().getFirst();
        String rawToken = extractToken(verificationEmail.textBody());

        MvcResult verification = mockMvc.perform(post("/api/auth/verify-email")
                        .session(registrationCsrf.session())
                        .header(registrationCsrf.headerName(), registrationCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", rawToken))))
                .andExpect(status().isNoContent())
                .andReturn();
        assertNoAuthenticatedSecurityContext(verification.getRequest().getSession(false));

        Map<String, Object> activatedUser = jdbcTemplate.queryForMap("""
                SELECT id, display_name, email, email_verified, status
                FROM invoward.users WHERE email = ?
                """, email);
        assertEquals(Boolean.TRUE, activatedUser.get("email_verified"));
        assertEquals("ACTIVE", activatedUser.get("status"));

        CsrfSession loginCsrf = csrfSession();
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(loginCsrf.session())
                        .header(loginCsrf.headerName(), loginCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andReturn();

        String loginBody = login.getResponse().getContentAsString();
        JsonNode loginResponse = objectMapper.readTree(loginBody);
        assertEquals(5, loginResponse.size());
        assertEquals(activatedUser.get("id").toString(), loginResponse.get("id").asString());
        assertEquals("Lifecycle User", loginResponse.get("displayName").asString());
        assertEquals(email, loginResponse.get("email").asString());
        assertTrue(loginResponse.get("emailVerified").asBoolean());
        assertEquals("ACTIVE", loginResponse.get("status").asString());
        assertFalse(loginBody.contains(RAW_PASSWORD));

        HttpSession authenticatedSession = login.getRequest().getSession(false);
        assertNotNull(authenticatedSession);
        assertNotNull(authenticatedSession.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));

        MvcResult currentUser = mockMvc.perform(get("/api/auth/me").session((MockHttpSession) authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andReturn();
        assertEquals(loginResponse, objectMapper.readTree(currentUser.getResponse().getContentAsByteArray()));
        assertFalse(currentUser.getResponse().getContentAsString().contains(RAW_PASSWORD));
    }

    @Test
    void resendDuringCooldownIsGenericAndAfterCooldownRotatesVerificationToken() throws Exception {
        String email = uniqueEmail();
        CsrfSession csrf = csrfSession();
        mockMvc.perform(register(csrf, "Resend User", email, RAW_PASSWORD))
                .andExpect(status().isAccepted());

        String firstToken = extractToken(fakeEmailSender.sentEmails().getFirst().textBody());
        String immediateResendBody = objectMapper.writeValueAsString(Map.of("email", email));
        mockMvc.perform(post("/api/auth/resend-verification")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(immediateResendBody))
                .andExpect(status().isAccepted())
                .andExpect(content().json(VERIFICATION_RESEND_RESPONSE));
        assertEquals(1, fakeEmailSender.sentEmails().size());
        assertEquals(1, countTokens(email));

        assertEquals(1, jdbcTemplate.update("""
                UPDATE invoward.email_verification_tokens
                SET created_at = created_at - INTERVAL '61 seconds'
                WHERE token_hash = ?
                """, sha256(firstToken)));

        mockMvc.perform(post("/api/auth/resend-verification")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(immediateResendBody))
                .andExpect(status().isAccepted())
                .andExpect(content().json(VERIFICATION_RESEND_RESPONSE));

        assertEquals(2, fakeEmailSender.sentEmails().size());
        assertEquals(2, countTokens(email));
        String replacementToken = extractToken(fakeEmailSender.sentEmails().getLast().textBody());
        assertNotEquals(firstToken, replacementToken);

        mockMvc.perform(post("/api/auth/verify-email")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", firstToken))))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"VERIFICATION_TOKEN_INVALID","message":"The verification link is invalid or expired."}
                        """));

        mockMvc.perform(post("/api/auth/verify-email")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", replacementToken))))
                .andExpect(status().isNoContent());
    }

    @Test
    void resendVerificationResponseIsIdenticalForUnknownActiveDisabledCooldownAndEligiblePendingAccounts()
            throws Exception {
        String email = uniqueEmail();
        CsrfSession csrf = csrfSession();
        mockMvc.perform(register(csrf, "Resend Parity User", email, RAW_PASSWORD))
                .andExpect(status().isAccepted());
        String firstToken = extractToken(fakeEmailSender.sentEmails().getFirst().textBody());

        String cooldownResponse = requestResend(csrf, email)
                .andExpect(status().isAccepted())
                .andExpect(content().json(VERIFICATION_RESEND_RESPONSE))
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, jdbcTemplate.update("""
                UPDATE invoward.email_verification_tokens
                SET created_at = created_at - INTERVAL '61 seconds'
                WHERE token_hash = ?
                """, sha256(firstToken)));

        String eligiblePendingResponse = requestResend(csrf, email)
                .andExpect(status().isAccepted())
                .andExpect(content().json(VERIFICATION_RESEND_RESPONSE))
                .andReturn().getResponse().getContentAsString();
        assertEquals(cooldownResponse, eligiblePendingResponse);
        String replacementToken = extractToken(fakeEmailSender.sentEmails().getLast().textBody());
        mockMvc.perform(post("/api/auth/verify-email")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", replacementToken))))
                .andExpect(status().isNoContent());

        String activeResponse = requestResend(csrf, email)
                .andExpect(status().isAccepted())
                .andExpect(content().json(VERIFICATION_RESEND_RESPONSE))
                .andReturn().getResponse().getContentAsString();
        assertEquals(cooldownResponse, activeResponse);
        assertEquals(1, jdbcTemplate.update(
                "UPDATE invoward.users SET status = 'DISABLED' WHERE email = ?", email));

        String disabledResponse = requestResend(csrf, email)
                .andExpect(status().isAccepted())
                .andExpect(content().json(VERIFICATION_RESEND_RESPONSE))
                .andReturn().getResponse().getContentAsString();
        String unknownResponse = requestResend(csrf, uniqueEmail())
                .andExpect(status().isAccepted())
                .andExpect(content().json(VERIFICATION_RESEND_RESPONSE))
                .andReturn().getResponse().getContentAsString();

        assertEquals(cooldownResponse, disabledResponse);
        assertEquals(cooldownResponse, unknownResponse);
        assertEquals(2, fakeEmailSender.sentEmails().size());
    }

    @Test
    void duplicateRegistrationReturnsIdenticalGenericResponseAndDoesNotSendAnotherEmail() throws Exception {
        String email = uniqueEmail();
        CsrfSession csrf = csrfSession();

        String firstResponse = mockMvc.perform(register(csrf, "First Name", email, RAW_PASSWORD))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String duplicateResponse = mockMvc.perform(register(
                        csrf, "Different Name", email.toUpperCase(Locale.ROOT), RAW_PASSWORD))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertEquals(ACCEPTED_RESPONSE.trim(), firstResponse);
        assertEquals(firstResponse, duplicateResponse);
        assertEquals(1, countUsers(email));
        assertEquals(1, countTokens(email));
        assertEquals(1, fakeEmailSender.sentEmails().size());
    }

    @Test
    void missingAndInvalidCsrfAreRejectedBeforeRegistration() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registrationJson("New User", email, RAW_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"CSRF_INVALID","message":"The request could not be validated."}
                        """));

        CsrfSession csrf = csrfSession();
        mockMvc.perform(post("/api/auth/register")
                        .session(csrf.session())
                        .header(csrf.headerName(), "not-the-session-token")
                        .contentType(APPLICATION_JSON)
                        .content(registrationJson("New User", email, RAW_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"code":"CSRF_INVALID","message":"The request could not be validated."}
                        """));

        assertEquals(0, countUsers(email));
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void invalidRegistrationFieldsReturnSafeValidationErrorsWithValidCsrf() throws Exception {
        CsrfSession csrf = csrfSession();
        String longPassword = "a".repeat(129);

        assertInvalidRegistration(csrf, "Valid User", "invalid-email", RAW_PASSWORD, "email");
        assertInvalidRegistration(csrf, "Valid User", uniqueEmail(), "short", "password");
        assertInvalidRegistration(csrf, "Valid User", uniqueEmail(), longPassword, "password");
        assertInvalidRegistration(csrf, "   ", uniqueEmail(), RAW_PASSWORD, "displayName");

        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    private void assertInvalidRegistration(
            CsrfSession csrf,
            String displayName,
            String email,
            String password,
            String expectedInvalidField) throws Exception {
        MvcResult result = mockMvc.perform(register(csrf, displayName, email, password))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields." + expectedInvalidField).exists())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertFalse(response.contains(password));
        assertFalse(response.contains(email));
    }

    private CsrfSession csrfSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertEquals("X-CSRF-TOKEN", response.get("headerName").asString());
        assertFalse(response.get("token").asString().isBlank());
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return new CsrfSession(response.get("token").asString(), response.get("headerName").asString(), session);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(
            CsrfSession csrf,
            String displayName,
            String email,
            String password) throws Exception {
        return post("/api/auth/register")
                .session(csrf.session())
                .header(csrf.headerName(), csrf.token())
                .contentType(APPLICATION_JSON)
                .content(registrationJson(displayName, email, password));
    }

    private ResultActions requestResend(CsrfSession csrf, String email) throws Exception {
        return mockMvc.perform(post("/api/auth/resend-verification")
                .session(csrf.session())
                .header(csrf.headerName(), csrf.token())
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))));
    }

    private String registrationJson(String displayName, String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "displayName", displayName,
                "email", email,
                "password", password));
    }

    private void assertNoAuthenticatedSecurityContext(HttpSession session) {
        assertNotNull(session);
        assertNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    private int countUsers(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.users WHERE email = ?", Integer.class, email);
    }

    private int countTokens(String email) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.email_verification_tokens WHERE target_email = ?
                """, Integer.class, email);
    }

    private static String uniqueEmail() {
        return "registration-security-" + UUID.randomUUID() + "@example.com";
    }

    private static String extractToken(String message) {
        Matcher matcher = TOKEN_PATTERN.matcher(message);
        assertTrue(matcher.find(), "verification email should contain the raw token URL");
        return matcher.group(1);
    }

    private static String sha256(String rawToken) throws NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        FakeEmailSender fakeEmailSender() {
            return new FakeEmailSender();
        }
    }

    private record CsrfSession(String token, String headerName, MockHttpSession session) {
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }
}
