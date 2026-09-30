package io.github.guillermodubon.invoward.identity.api;

import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@AutoConfigureMockMvc
class LoginSessionIntegrationIT {

    private static final String RAW_PASSWORD = "correct horse battery staple";
    private static final String AUTHENTICATION_FAILED = """
            {"code":"AUTHENTICATION_FAILED","message":"Invalid email or password."}
            """;
    private static final String AUTHENTICATION_REQUIRED = """
            {"code":"AUTHENTICATION_REQUIRED","message":"Authentication is required."}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void activeVerifiedUserCanLoginAndReadSafeCurrentUserFromSession() throws Exception {
        UserFixture user = insertUser(UserStatus.ACTIVE, true);

        LoginResult login = login(user.email(), RAW_PASSWORD);

        assertEquals(200, login.status());
        assertNotNull(login.session());
        assertNotNull(login.session().getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
        assertSafeCurrentUser(login.body(), user);

        MvcResult currentUser = mockMvc.perform(get("/api/auth/me").session(login.session()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andReturn();
        assertSafeCurrentUser(currentUser.getResponse().getContentAsString(), user);
        assertEquals(objectMapper.readTree(login.body()),
                objectMapper.readTree(currentUser.getResponse().getContentAsByteArray()));
    }

    @Test
    void loginEmailLookupIgnoresCaseAndStripsSurroundingWhitespace() throws Exception {
        UserFixture user = insertUser(UserStatus.ACTIVE, true);

        assertEquals(200, login(user.email().toUpperCase(Locale.ROOT), RAW_PASSWORD).status());
        assertEquals(200, login("  " + user.email() + "  ", RAW_PASSWORD).status());
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnTheSameGenericAuthenticationFailure() throws Exception {
        UserFixture user = insertUser(UserStatus.ACTIVE, true);

        LoginResult wrongPassword = login(user.email(), "a different valid passphrase");
        LoginResult unknownEmail = login(uniqueEmail(), RAW_PASSWORD);

        assertGenericAuthenticationFailure(wrongPassword);
        assertGenericAuthenticationFailure(unknownEmail);
        assertEquals(wrongPassword.body(), unknownEmail.body());
    }

    @Test
    void pendingAndDisabledAccountsReturnTheSameGenericAuthenticationFailure() throws Exception {
        UserFixture pending = insertUser(UserStatus.PENDING_VERIFICATION, false);
        UserFixture disabled = insertUser(UserStatus.DISABLED, true);

        LoginResult pendingResult = login(pending.email(), RAW_PASSWORD);
        LoginResult disabledResult = login(disabled.email(), RAW_PASSWORD);

        assertGenericAuthenticationFailure(pendingResult);
        assertGenericAuthenticationFailure(disabledResult);
        assertEquals(pendingResult.body(), disabledResult.body());
    }

    @Test
    void basicAuthenticationAndDefaultFormLoginAreNotAvailable() throws Exception {
        UserFixture user = insertUser(UserStatus.ACTIVE, true);
        String credentials = Base64.getEncoder().encodeToString(
                (user.email() + ":" + RAW_PASSWORD).getBytes(StandardCharsets.UTF_8));

        MvcResult basicAttempt = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Basic " + credentials))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andReturn();
        assertEquals(AUTHENTICATION_REQUIRED.trim(), basicAttempt.getResponse().getContentAsString());

        MvcResult formLoginPage = mockMvc.perform(get("/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andReturn();
        assertEquals(AUTHENTICATION_REQUIRED.trim(), formLoginPage.getResponse().getContentAsString());
        assertNull(formLoginPage.getResponse().getHeader("Location"));
        assertFalse(formLoginPage.getResponse().getContentAsString().toLowerCase().contains("<html"));
    }

    @Test
    void malformedUnsupportedAndOversizedLoginRequestsAreRejectedSafely() throws Exception {
        UserFixture user = insertUser(UserStatus.ACTIVE, true);

        assertInvalidLoginRequest(APPLICATION_JSON.toString(), "{\"email\":");
        assertInvalidLoginRequest("application/x-www-form-urlencoded",
                "email=" + user.email() + "&password=" + RAW_PASSWORD);

        String validPayloadWithOversizedWhitespace = objectMapper.writeValueAsString(Map.of(
                "email", user.email(),
                "password", RAW_PASSWORD)) + " ".repeat(8 * 1024);
        assertTrue(validPayloadWithOversizedWhitespace.getBytes(StandardCharsets.UTF_8).length > 8 * 1024);
        assertInvalidLoginRequest(APPLICATION_JSON.toString(), validPayloadWithOversizedWhitespace);
    }

    private LoginResult login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
        return submitLogin(APPLICATION_JSON.toString(), body);
    }

    private LoginResult submitLogin(String contentType, String body) throws Exception {
        CsrfSession csrf = csrfSession();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(contentType)
                        .content(body))
                .andReturn();
        HttpSession requestSession = result.getRequest().getSession(false);
        MockHttpSession session = requestSession instanceof MockHttpSession mockSession ? mockSession : null;
        return new LoginResult(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString(),
                session);
    }

    private CsrfSession csrfSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tokenResponse = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertEquals("X-CSRF-TOKEN", tokenResponse.get("headerName").asString());
        assertFalse(tokenResponse.get("token").asString().isBlank());
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return new CsrfSession(tokenResponse.get("token").asString(),
                tokenResponse.get("headerName").asString(), session);
    }

    private void assertInvalidLoginRequest(String contentType, String body) throws Exception {
        LoginResult result = submitLogin(contentType, body);

        assertEquals(400, result.status());
        assertEquals("{\"code\":\"INVALID_REQUEST\",\"message\":\"The request could not be processed.\"}",
                result.body());
        assertFalse(result.body().contains(RAW_PASSWORD));
    }

    private void assertGenericAuthenticationFailure(LoginResult result) {
        assertEquals(401, result.status());
        assertEquals(AUTHENTICATION_FAILED.trim(), result.body());
        assertFalse(result.body().contains(RAW_PASSWORD));
    }

    private void assertSafeCurrentUser(String responseBody, UserFixture user) throws Exception {
        JsonNode response = objectMapper.readTree(responseBody);
        assertEquals(5, response.size());
        assertEquals(user.id().toString(), response.get("id").asString());
        assertEquals("Test Login User", response.get("displayName").asString());
        assertEquals(user.email(), response.get("email").asString());
        assertTrue(response.get("emailVerified").asBoolean());
        assertEquals("ACTIVE", response.get("status").asString());
        assertFalse(responseBody.contains(RAW_PASSWORD));
        assertFalse(responseBody.contains(user.passwordHash()));
    }

    private UserFixture insertUser(UserStatus status, boolean emailVerified) {
        UUID id = UUID.randomUUID();
        String email = uniqueEmail();
        String passwordHash = passwordEncoder.encode(RAW_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Test Login User', ?, ?, ?, ?)
                """, id, email, passwordHash, emailVerified, status.name());
        return new UserFixture(id, email, passwordHash);
    }

    private static String uniqueEmail() {
        return "login-" + UUID.randomUUID() + "@example.com";
    }

    private record UserFixture(UUID id, String email, String passwordHash) {
    }

    private record CsrfSession(String token, String headerName, MockHttpSession session) {
    }

    private record LoginResult(int status, String body, MockHttpSession session) {
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }
}
